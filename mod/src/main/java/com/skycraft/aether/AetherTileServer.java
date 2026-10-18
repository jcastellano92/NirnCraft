package com.skycraft.aether;

import com.skycraft.network.SkyNetwork;
import com.skycraft.tamriel.terrain.TamrielHeight;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server side of the multiplayer far field (dedicated, integrated and LAN-hosted alike): exact tiles of the
 * overworld's own tamriel_height, from the world's tile cache or computed (AetherExactSource.tileBytes) on one
 * low-priority worker spread over a quarter of the cores, and sent to the player who asked.
 * <ul>
 *   <li>Requests are honoured only for tiles inside that player's clipmap window, OUTSTANDING per player in flight;
 *   the queue serves coarse LODs (the horizon) and near tiles first.</li>
 *   <li>The worker yields while the server's average tick is over TICK_BUSY_MS: the far field never costs ticks.</li>
 *   <li>While the server has headroom and nobody is waiting, it computes tiles around players ahead of their
 *   requests (cached, so a LAN guest or a returning player gets them at once).</li>
 *   <li>Sends are paced per player (BYTES_PER_SECOND), so a joining player's 4 MB window never floods a link.</li>
 * </ul>
 */
final class AetherTileServer {
    static final int OUTSTANDING = 24;
    static final int BYTES_PER_SECOND = Integer.getInteger("skycraft.aether.tileBytesPerSecond", 2_000_000);
    static final double TICK_BUSY_MS = Double.parseDouble(System.getProperty("skycraft.aether.tickBusyMs", "45"));
    static final double TICK_IDLE_MS = 25;
    private static final long SALT = 0x5EEDAE7E40000001L;
    private static volatile AetherTileServer current;

    private final MinecraftServer server;
    private final AetherExactSource columns;
    private final long worldId;
    private final PriorityBlockingQueue<Job> queue = new PriorityBlockingQueue<>();
    private final Map<UUID, Peer> peers = new ConcurrentHashMap<>();
    private final AtomicLong order = new AtomicLong();
    private final Thread worker;
    private volatile boolean running = true;
    private final java.util.concurrent.atomic.AtomicInteger pregenPending = new java.util.concurrent.atomic.AtomicInteger();

    /** One tile to make; player == null for a pregeneration job. */
    private record Job(int lod, int tx, int tz, ServerPlayer player, double priority, long seq) implements Comparable<Job> {
        @Override
        public int compareTo(Job o) {
            int c = Double.compare(priority, o.priority);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
    }

    /** Per player: tiles in flight and the paced send queue. Server thread, except the counter. */
    private static final class Peer {
        final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        final ArrayDeque<AetherNet.TileData> outbox = new ArrayDeque<>();
        double tokens = BYTES_PER_SECOND / 4.0;
    }

    private AetherTileServer(MinecraftServer server, TamrielHeight height) {
        this.server = server;
        this.columns = new AetherExactSource(height, AetherBiomes.forServer(server), AetherTileCache.forServer(server),
                () -> null, AetherExactSource.defaultThreads());
        this.worldId = worldId(server.overworld().getSeed());
        worker = new Thread(this::run, "skycraft-aether-tiles");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    /** ServerStartedEvent: serve tiles when the overworld is a Tamriel world (else clients keep the stand-in). */
    static void start(MinecraftServer server) {
        stop();
        try {
            TamrielHeight h = AetherExactSource.heightOf(server);
            if (h != null) current = new AetherTileServer(server, h);
            com.skycraft.Skycraft.LOGGER.info(h != null
                    ? "aether tile service on: exact far tiles for clients ({} column threads, {})"
                    : "aether tile service off: the overworld is not a Tamriel world (clients keep the stand-in)",
                    AetherExactSource.defaultThreads(), server.isDedicatedServer() ? "dedicated, idle pregeneration" : "integrated");
        } catch (RuntimeException e) {
            // the far field is a visual service: it must never take a server down (clients keep the stand-in)
            com.skycraft.Skycraft.LOGGER.warn("aether tile service not started", e);
        }
    }

    static void stop() {
        AetherTileServer t = current;
        current = null;
        if (t != null) {
            t.running = false;
            t.worker.interrupt();
            t.columns.close();
        }
    }

    /** The Hello for a player, or null when this server does not stream tiles. */
    static AetherNet.Hello hello() {
        AetherTileServer t = current;
        return t == null ? null : new AetherNet.Hello(t.worldId, AetherTileCodec.VERSION);
    }

    static long worldId(long seed) {
        long z = seed ^ SALT ^ (long) AetherTileCodec.VERSION << 48;  // splitmix64 finaliser
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static void request(ServerPlayer player, AetherNet.TileRequest r) {
        AetherTileServer t = current;
        if (t == null) {
            for (int i = 0; i < r.lods().length; i++)  // nothing to serve: stop the client asking
                SkyNetwork.sendToPlayer(player, new AetherNet.TileData(r.lods()[i], r.txs()[i], r.tzs()[i], new byte[0]));
            return;
        }
        if (player.level().dimension() != Level.OVERWORLD) return;
        Peer peer = t.peers.computeIfAbsent(player.getUUID(), u -> new Peer());
        for (int i = 0; i < r.lods().length; i++) {
            int lod = r.lods()[i], tx = r.txs()[i], tz = r.tzs()[i];
            if (lod < 0 || lod >= AetherFarField.LODS) continue;
            double d = windowDistance(player, lod, tx, tz);
            if (d > AetherFarField.WINDOW + 1.5) continue;  // not in its window (moved on)
            if (peer.inFlight.incrementAndGet() > OUTSTANDING) {
                peer.inFlight.decrementAndGet();
                continue;  // the client re-asks after its timeout
            }
            t.queue.add(new Job(lod, tx, tz, player, priority(lod, d), t.order.incrementAndGet()));
        }
    }

    /** Distance in tiles from the player's tile at that LOD (Chebyshev, fractional). */
    private static double windowDistance(ServerPlayer p, int lod, int tx, int tz) {
        double span = AetherNative.TILE << lod;
        return Math.max(Math.abs(tx + 0.5 - p.getX() / span), Math.abs(tz + 0.5 - p.getZ() / span)) - 0.5;
    }

    /** Coarse LODs first (one tile covers the most horizon), then near tiles. */
    private static double priority(int lod, double dist) {
        return (AetherFarField.LODS - 1 - lod) * 0.75 + dist;
    }

    static void forget(ServerPlayer player) {
        AetherTileServer t = current;
        if (t != null) t.peers.remove(player.getUUID());
    }

    /** ServerTickEvent.Post: paced sends, and pregeneration while the server has headroom. */
    static void tick() {
        AetherTileServer t = current;
        if (t == null) return;
        for (Map.Entry<UUID, Peer> e : t.peers.entrySet()) {
            Peer p = e.getValue();
            synchronized (p) {
                p.tokens = Math.min(BYTES_PER_SECOND / 4.0, p.tokens + BYTES_PER_SECOND / 20.0);
                while (!p.outbox.isEmpty() && p.tokens > 0) {
                    AetherNet.TileData d = p.outbox.poll();
                    p.tokens -= d.data().length + 16;
                    p.inFlight.decrementAndGet();
                    ServerPlayer sp = t.server.getPlayerList().getPlayer(e.getKey());
                    if (sp != null && !sp.hasDisconnected()) SkyNetwork.sendToPlayer(sp, d);
                }
            }
        }
        // host load (render/quality HostLoad): the column pool shrinks while the server needs its cores, and
        // pregeneration slows (then stops) as ticks lengthen
        double mspt = t.tickMs();
        if (t.server.getTickCount() % 100 == 0) {
            boolean guests = !t.server.isDedicatedServer() && t.server.isPublished() && t.server.getPlayerCount() > 1;
            t.columns.setThreads(com.skycraft.render.quality.HostLoad.serverColumnThreads(
                    Runtime.getRuntime().availableProcessors(), t.server.isDedicatedServer(), guests, mspt));
        }
        int every = com.skycraft.render.quality.HostLoad.pregenIntervalTicks(mspt);
        if (every > 0 && t.server.getTickCount() % every == 0) t.pregenerate();
    }

    private double tickMs() {
        return server.getAverageTickTimeNanos() / 1e6;
    }

    /** Queue the most useful uncached tile around the players, one at a time, when nothing else is waiting. */
    private void pregenerate() {
        // (single player: the host's own client computes its tiles; pregenerate for guests: dedicated or opened to LAN)
        if (!server.isDedicatedServer() && !server.isPublished()) return;
        if (!queue.isEmpty() || pregenPending.get() > 0 || tickMs() > TICK_IDLE_MS) return;
        Job best = null;
        for (ServerPlayer p : server.overworld().players()) {
            for (int lod = AetherFarField.LODS - 1; lod >= 0; lod--) {
                int span = AetherNative.TILE << lod;
                int ptx = Math.floorDiv((int) Math.floor(p.getX()), span), ptz = Math.floorDiv((int) Math.floor(p.getZ()), span);
                for (int dz = -AetherFarField.WINDOW; dz <= AetherFarField.WINDOW; dz++)
                    for (int dx = -AetherFarField.WINDOW; dx <= AetherFarField.WINDOW; dx++) {
                        int tx = ptx + dx, tz = ptz + dz;
                        double pr = priority(lod, windowDistance(p, lod, tx, tz));
                        if (best != null && pr >= best.priority) continue;
                        if (columns.cached(lod, tx, tz)) continue;
                        best = new Job(lod, tx, tz, null, pr, order.incrementAndGet());
                    }
            }
        }
        if (best != null) {
            pregenPending.incrementAndGet();
            queue.add(best);
        }
    }

    private void run() {
        while (running) {
            Job j;
            try {
                j = queue.take();
                // never compete with a struggling server: wait for its ticks to come back under budget
                while (running && tickMs() > TICK_BUSY_MS) Thread.sleep(25);
            } catch (InterruptedException e) {
                return;
            }
            byte[] data;
            try {
                data = columns.tileBytes(j.lod, j.tx, j.tz);
            } catch (Throwable e) {
                com.skycraft.Skycraft.LOGGER.warn("aether tile {} {} {}: {}", j.lod, j.tx, j.tz, e.toString());
                data = new byte[0];
            }
            if (j.player == null) {
                pregenPending.decrementAndGet();
                continue;
            }
            Peer p = peers.get(j.player.getUUID());
            if (p == null) continue;
            synchronized (p) {
                p.outbox.add(new AetherNet.TileData(j.lod, j.tx, j.tz, data));
            }
        }
    }
}
