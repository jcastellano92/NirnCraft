package com.skycraft.aether;

import com.skycraft.network.SkyNetwork;
import com.skycraft.tamriel.terrain.TamrielHeight;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server side of the multiplayer far field: exact tiles of the overworld's own tamriel_height, computed on request
 * (one tile at a time, each spread over a quarter of the cores), cached on disk per world (a tile never changes: it
 * is the generator's surface, not the edited world) and sent to the player who asked. Requests are honoured only for
 * tiles inside that player's clipmap window, at most OUTSTANDING per player in flight.
 */
final class AetherTileServer {
    static final int OUTSTANDING = 24;
    private static final long SALT = 0x5EEDAE7E40000001L;
    private static volatile AetherTileServer current;

    private final MinecraftServer server;
    private final AetherExactSource columns;
    private final long worldId;
    private final Path cache;
    private final ExecutorService jobs;
    private final Map<UUID, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    private AetherTileServer(MinecraftServer server, TamrielHeight height) {
        this.server = server;
        this.columns = new AetherExactSource(height, () -> null, AetherExactSource.defaultThreads());
        this.worldId = worldId(server.overworld().getSeed());
        this.cache = server.getWorldPath(LevelResource.DATA).resolve("skycraft_aether").resolve("tiles-v" + AetherTileCodec.VERSION);
        this.jobs = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "skycraft-aether-tiles");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    /** ServerStartedEvent: serve tiles when the overworld is a Tamriel world (else clients keep the stand-in). */
    static void start(MinecraftServer server) {
        stop();
        TamrielHeight h = AetherExactSource.heightOf(server);
        if (h != null) current = new AetherTileServer(server, h);
    }

    static void stop() {
        AetherTileServer t = current;
        current = null;
        if (t != null) {
            t.jobs.shutdownNow();
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
        AtomicInteger busy = t.inFlight.computeIfAbsent(player.getUUID(), u -> new AtomicInteger());
        for (int i = 0; i < r.lods().length; i++) {
            int lod = r.lods()[i], tx = r.txs()[i], tz = r.tzs()[i];
            if (lod < 0 || lod >= AetherFarField.LODS) continue;
            int span = AetherNative.TILE << lod, reach = AetherFarField.WINDOW + 1;
            int ptx = Math.floorDiv((int) Math.floor(player.getX()), span), ptz = Math.floorDiv((int) Math.floor(player.getZ()), span);
            if (Math.abs(tx - ptx) > reach || Math.abs(tz - ptz) > reach) continue;  // not in its window (moved on)
            if (busy.incrementAndGet() > OUTSTANDING) {
                busy.decrementAndGet();
                continue;  // the client re-asks after its timeout
            }
            try {
                t.jobs.execute(() -> {
                    byte[] data;
                    try {
                        data = t.tile(lod, tx, tz);
                    } catch (Throwable e) {
                        com.skycraft.Skycraft.LOGGER.warn("aether tile {} {} {}: {}", lod, tx, tz, e.toString());
                        data = new byte[0];
                    }
                    byte[] d = data;
                    t.server.execute(() -> {
                        busy.decrementAndGet();
                        if (!player.hasDisconnected()) SkyNetwork.sendToPlayer(player, new AetherNet.TileData(lod, tx, tz, d));
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                busy.decrementAndGet();  // stopping
            }
        }
    }

    static void forget(ServerPlayer player) {
        AetherTileServer t = current;
        if (t != null) t.inFlight.remove(player.getUUID());
    }

    private byte[] tile(int lod, int tx, int tz) throws IOException {
        Path f = cache.resolve("l" + lod).resolve(tx + "_" + tz + ".bin");
        if (Files.isRegularFile(f)) {
            byte[] b = Files.readAllBytes(f);
            if (b.length <= AetherTileCodec.MAX_BYTES) return b;
        }
        int n = AetherNative.TILE * AetherNative.TILE;
        float[] surface = new float[n], water = new float[n];
        columns.fill(lod, tx, tz, surface, water);
        byte[] b = AetherTileCodec.encode(lod, surface, water);
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.write(tmp, b);
        try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
        return b;
    }
}
