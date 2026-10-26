package com.nirncraft.atlas;

import com.nirncraft.network.SkyNetwork;
import com.nirncraft.tamriel.terrain.TamrielHeight;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server side of the multiplayer far field (dedicated, integrated and LAN-hosted alike): exact tiles of the
 * overworld's own tamriel_height, from the world's tile cache or computed (AtlasExactSource.tileBytes) on one
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
public final class AtlasTileServer {
    static final int OUTSTANDING = 24;
    static final int BYTES_PER_SECOND = Integer.getInteger("nirncraft.atlas.tileBytesPerSecond", 2_000_000);
    static final double TICK_BUSY_MS = Double.parseDouble(System.getProperty("nirncraft.atlas.tickBusyMs", "45"));
    static final double TICK_IDLE_MS = 25;
    private static final long SALT = 0x5EEDAE7E40000001L;
    private static volatile AtlasTileServer current;

    private final MinecraftServer server;
    private final AtlasExactSource columns;
    private final long worldId;
    private final PriorityBlockingQueue<Job> queue = new PriorityBlockingQueue<>();
    private final Map<UUID, Peer> peers = new ConcurrentHashMap<>();
    private final AtomicLong order = new AtomicLong();
    private final Thread worker;
    private volatile boolean running = true;
    private final java.util.concurrent.atomic.AtomicInteger pregenPending = new java.util.concurrent.atomic.AtomicInteger();
    /** Server-thread counters for the stream log. */
    private long sentTiles, sentBytes, loggedTiles;

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
        final ArrayDeque<AtlasNet.TileData> outbox = new ArrayDeque<>();
        double tokens = BYTES_PER_SECOND / 4.0;
    }

    private AtlasTileServer(MinecraftServer server, TamrielHeight height) {
        this.server = server;
        this.columns = new AtlasExactSource(height, AtlasBiomes.forServer(server), AtlasTileCache.forServer(server),
                () -> null, AtlasExactSource.defaultThreads());
        this.columns.surface(AtlasSurface.forServer(server));
        this.worldId = worldId(server.overworld().getSeed());
        worker = new Thread(this::run, "nirncraft-atlas-tiles");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    /** ServerStartedEvent: serve tiles when the overworld is a Tamriel world (else clients keep the stand-in). */
    static void start(MinecraftServer server) {
        stop();
        try {
            TamrielHeight h = AtlasExactSource.heightOf(server);
            if (h != null) current = new AtlasTileServer(server, h);
            com.nirncraft.NirnCraft.LOGGER.info(h != null
                    ? "atlas tile service on: exact far tiles for clients ({} column threads, {})"
                    : "atlas tile service off: the overworld is not a Tamriel world (clients keep the stand-in)",
                    AtlasExactSource.defaultThreads(), server.isDedicatedServer() ? "dedicated, idle pregeneration" : "integrated");
        } catch (RuntimeException e) {
            // the far field is a visual service: it must never take a server down (clients keep the stand-in)
            com.nirncraft.NirnCraft.LOGGER.warn("atlas tile service not started", e);
        }
    }

    static void stop() {
        AtlasTileServer t = current;
        current = null;
        if (t != null) {
            t.running = false;
            t.worker.interrupt();
            t.columns.close();
        }
    }

    /** The Hello for a player, or null when this server does not stream tiles. */
    static AtlasNet.Hello hello() {
        AtlasTileServer t = current;
        return t == null ? null : new AtlasNet.Hello(t.worldId, AtlasTileCodec.VERSION);
    }

    static long worldId(long seed) {
        // the seed, the codec and the far identity (world generation + painting code): a client's cache of another
        // generation is never reused
        long z = seed ^ SALT ^ (long) AtlasTileCodec.VERSION << 48 ^ AtlasTileCodec.identity().hashCode() * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static void request(ServerPlayer player, AtlasNet.TileRequest r) {
        AtlasTileServer t = current;
        if (t != null && !t.peers.containsKey(player.getUUID()))
            com.nirncraft.NirnCraft.LOGGER.info("atlas tile service: first request from {} ({} tiles, in {})",
                    player.getName().getString(), r.lods().length, player.level().dimension().identifier());
        if (t == null) {
            for (int i = 0; i < r.lods().length; i++)  // nothing to serve: stop the client asking
                SkyNetwork.sendToPlayer(player, new AtlasNet.TileData(r.lods()[i], r.txs()[i], r.tzs()[i], new byte[0]));
            return;
        }
        if (player.level().dimension() != Level.OVERWORLD) return;
        Peer peer = t.peers.computeIfAbsent(player.getUUID(), u -> new Peer());
        for (int i = 0; i < r.lods().length; i++) {
            int lod = r.lods()[i], tx = r.txs()[i], tz = r.tzs()[i];
            if (lod < 0 || lod >= AtlasFarField.LODS) continue;
            double d = windowDistance(player, lod, tx, tz);
            if (d > AtlasFarField.WINDOW + 1.5) continue;  // not in its window (moved on)
            if (peer.inFlight.incrementAndGet() > OUTSTANDING) {
                peer.inFlight.decrementAndGet();
                continue;  // the client re-asks after its timeout
            }
            t.queue.add(new Job(lod, tx, tz, player, priority(lod, d), t.order.incrementAndGet()));
        }
    }

    /** Distance in tiles from the player's tile at that LOD (Chebyshev, fractional). */
    private static double windowDistance(ServerPlayer p, int lod, int tx, int tz) {
        double span = AtlasNative.TILE << lod;
        return Math.max(Math.abs(tx + 0.5 - p.getX() / span), Math.abs(tz + 0.5 - p.getZ() / span)) - 0.5;
    }

    /** Coarse LODs first (one tile covers the most horizon), then near tiles. */
    private static double priority(int lod, double dist) {
        return (AtlasFarField.LODS - 1 - lod) * 0.75 + dist;
    }

    static void forget(ServerPlayer player) {
        AtlasTileServer t = current;
        if (t != null) t.peers.remove(player.getUUID());
    }

    /** ServerTickEvent.Post: paced sends, and pregeneration while the server has headroom. */
    static void tick() {
        AtlasTileServer t = current;
        if (t == null) return;
        // a line a minute while tiles flow: what the stream costs a host (LAN / dedicated) and that clients get it
        if (t.server.getTickCount() % 1200 == 0 && t.sentTiles != t.loggedTiles) {
            t.loggedTiles = t.sentTiles;
            com.nirncraft.NirnCraft.LOGGER.info("atlas tile service: {} tiles ({} MB) sent to {} players so far, {} pregenerating",
                    t.sentTiles, String.format("%.1f", t.sentBytes / 1e6), t.peers.size(), t.pregenPending.get());
        }
        for (Map.Entry<UUID, Peer> e : t.peers.entrySet()) {
            Peer p = e.getValue();
            synchronized (p) {
                p.tokens = Math.min(BYTES_PER_SECOND / 4.0, p.tokens + BYTES_PER_SECOND / 20.0);
                while (!p.outbox.isEmpty() && p.tokens > 0) {
                    AtlasNet.TileData d = p.outbox.poll();
                    p.tokens -= d.data().length + 16;
                    p.inFlight.decrementAndGet();
                    ServerPlayer sp = t.server.getPlayerList().getPlayer(e.getKey());
                    if (sp != null && !sp.hasDisconnected()) {
                        SkyNetwork.sendToPlayer(sp, d);
                        t.sentTiles++;
                        t.sentBytes += d.data().length;
                    }
                }
            }
        }
        // host load (render/quality HostLoad): the column pool shrinks while the server needs its cores, and
        // pregeneration slows (then stops) as ticks lengthen
        double mspt = t.tickMs();
        if (t.server.getTickCount() % 100 == 0) {
            boolean guests = !t.server.isDedicatedServer() && t.server.isPublished() && t.server.getPlayerCount() > 1;
            t.columns.setThreads(com.nirncraft.render.quality.HostLoad.serverColumnThreads(
                    Runtime.getRuntime().availableProcessors(), t.server.isDedicatedServer(), guests, mspt));
        }
        // the far surface blocks against what chunk generation really painted around the players: one log line
        // two minutes in and every ten minutes after (the in-game check of the far field's material rules)
        int tc = t.server.getTickCount();
        if (tc == 2400 || tc % 12000 == 0 && tc > 0) {
            // (a thread of its own: 400 columns of the exact generator on the server thread were 2 s of "Can't keep up";
            // it only reads, and a read that races a chunk's change is a lost sample)
            Thread check = new Thread(() -> {
                try {
                    AtlasSurface.selfCheck(t.server, t.columns, 400);
                } catch (RuntimeException ex) {
                    com.nirncraft.NirnCraft.LOGGER.warn("atlas surface check failed: {}", ex.toString());
                }
            }, "nirncraft-atlas-surface-check");
            check.setDaemon(true);
            check.setPriority(Thread.NORM_PRIORITY - 1);
            check.start();
        }
        int every = com.nirncraft.render.quality.HostLoad.pregenIntervalTicks(mspt);
        if (every > 0 && t.server.getTickCount() % every == 0) t.pregenerate();
    }

    private double tickMs() {
        return server.getAverageTickTimeNanos() / 1e6;
    }

    /**
     * Pre-builds far tiles into the world's tile cache while the server idles (single player too: the host's client
     * reads the same cache, so a revisit or a teleport puts exact tiles up at once instead of the raster stand-in):
     * first what is missing in the players' windows (most useful first), then the whole map's coarse levels (LOD 6
     * down to 2, nearest the players first: about 800 tiles, ten-odd seconds once per world, so the horizon is never
     * the stand-in's). The cache is keyed by the tiles' content revision: a generation change rebuilds, never stale.
     */
    private void pregenerate() {
        if (!queue.isEmpty() || pregenPending.get() >= PREGEN_INFLIGHT || tickMs() > TICK_IDLE_MS) return;
        Job best = null;
        for (ServerPlayer p : server.overworld().players()) {
            for (int lod = AtlasFarField.LODS - 1; lod >= 0; lod--) {
                int span = AtlasNative.TILE << lod;
                int ptx = Math.floorDiv((int) Math.floor(p.getX()), span), ptz = Math.floorDiv((int) Math.floor(p.getZ()), span);
                for (int dz = -AtlasFarField.WINDOW; dz <= AtlasFarField.WINDOW; dz++)
                    for (int dx = -AtlasFarField.WINDOW; dx <= AtlasFarField.WINDOW; dx++) {
                        int tx = ptx + dx, tz = ptz + dz;
                        double pr = priority(lod, windowDistance(p, lod, tx, tz));
                        if (best != null && pr >= best.priority) continue;
                        if (columns.cached(lod, tx, tz)) continue;
                        best = new Job(lod, tx, tz, null, pr, order.incrementAndGet());
                    }
            }
        }
        if (best == null) best = nextSweep();
        if (best != null) {
            pregenPending.incrementAndGet();
            queue.add(best);
        }
    }

    /** Jobs in flight at once for pre-building (each tile already spreads over the column threads). */
    private static final int PREGEN_INFLIGHT = 2;
    /** The coarsest level the whole-map sweep goes down to (finer ones follow the players). */
    static final int SWEEP_FINEST_LOD = 2;
    private List<long[]> sweep;
    private int sweepAt;
    private boolean sweepLogged;

    /**
     * The coarse pre-build's progress {tiles checked or built, tiles in all}, {0, 0} before it started, null without a
     * tile server (a remote client). Visual tests wait for it before far-field scenes (no stand-ins in a capture).
     */
    public static int[] prebuildProgress() {
        AtlasTileServer t = current;
        if (t == null) return null;
        List<long[]> sw = t.sweep;
        return sw == null ? new int[] {0, 0} : new int[] {Math.min(t.sweepAt, sw.size()), sw.size()};
    }

    /** The next uncached tile of the whole-map sweep (coarse levels over Tamriel), or null when done. */
    private Job nextSweep() {
        if (sweep == null) {
            sweep = new ArrayList<>();
            double px = 0, pz = 0;
            List<ServerPlayer> ps = server.overworld().players();
            if (!ps.isEmpty()) {
                px = ps.get(0).getX();
                pz = ps.get(0).getZ();
            }
            int margin = 2048;  // the endless lands just past the map's edge show too
            for (int lod = AtlasFarField.LODS - 1; lod >= SWEEP_FINEST_LOD; lod--) {
                int span = AtlasNative.TILE << lod;
                List<long[]> level = new ArrayList<>();
                for (int tz = Math.floorDiv(com.nirncraft.tamriel.TamrielMap.MIN_Z - margin, span);
                     tz <= Math.floorDiv(com.nirncraft.tamriel.TamrielMap.MAX_Z + margin, span); tz++)
                    for (int tx = Math.floorDiv(com.nirncraft.tamriel.TamrielMap.MIN_X - margin, span);
                         tx <= Math.floorDiv(com.nirncraft.tamriel.TamrielMap.MAX_X + margin, span); tx++) {
                        double d = Math.hypot((tx + 0.5) * span - px, (tz + 0.5) * span - pz);
                        level.add(new long[] {lod, tx, tz, (long) d});
                    }
                level.sort((a, b) -> Long.compare(a[3], b[3]));
                sweep.addAll(level);
            }
            sweepAt = 0;
            com.nirncraft.NirnCraft.LOGGER.info("atlas: pre-building the far field's coarse tiles (LOD {}..{}, {} tiles) "
                    + "while the server idles", AtlasFarField.LODS - 1, SWEEP_FINEST_LOD, sweep.size());
        }
        while (sweepAt < sweep.size()) {
            long[] t = sweep.get(sweepAt++);
            if (columns.cached((int) t[0], (int) t[1], (int) t[2])) continue;
            return new Job((int) t[0], (int) t[1], (int) t[2], null, 1e9 + sweepAt, order.incrementAndGet());
        }
        if (!sweepLogged) {
            sweepLogged = true;
            com.nirncraft.NirnCraft.LOGGER.info("atlas: far field coarse tiles all built ({} tiles)", sweep.size());
        }
        return null;
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
                com.nirncraft.NirnCraft.LOGGER.warn("atlas tile {} {} {}: {}", j.lod, j.tx, j.tz, e.toString());
                data = new byte[0];
            }
            if (j.player == null) {
                pregenPending.decrementAndGet();
                continue;
            }
            Peer p = peers.get(j.player.getUUID());
            if (p == null) continue;
            synchronized (p) {
                p.outbox.add(new AtlasNet.TileData(j.lod, j.tx, j.tz, data));
            }
        }
    }
}
