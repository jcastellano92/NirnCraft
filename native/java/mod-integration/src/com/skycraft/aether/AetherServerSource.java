package com.skycraft.aether;

import com.skycraft.network.SkyNetwork;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Multiplayer column source: the raster stand-in at once, refined with exact tiles the server sends
 * (AetherTileServer). Tiles are cached per world under the game directory, so a revisit costs no traffic; requests
 * are capped in flight and re-sent after a timeout (the server drops what it is too busy for, or what left the window).
 */
final class AetherServerSource extends AetherRefiningSource {
    static final int IN_FLIGHT = AetherTileServer.OUTSTANDING;
    static final long TIMEOUT_MS = 20_000;

    private final Path cache;
    private final ConcurrentLinkedQueue<AetherNet.TileData> arrived = new ConcurrentLinkedQueue<>();
    private final Map<Long, byte[]> ready = new HashMap<>();
    private final Map<Long, Long> asked = new HashMap<>();  // key -> when
    private final List<int[]> toAsk = new ArrayList<>();
    private final float[] surface = new float[AetherNative.TILE * AetherNative.TILE], water = new float[surface.length];

    AetherServerSource(long worldId, Path gameDir, Callable<Path> raster) {
        super(raster);
        cache = gameDir.resolve("aether-cache").resolve(Long.toHexString(worldId)).resolve("v" + AetherTileCodec.VERSION);
    }

    /** From the network (client thread). */
    void accept(AetherNet.TileData m) {
        arrived.add(m);
    }

    @Override
    Refined refine(AetherNative.Context ctx, int lod, int tx, int tz) {
        for (AetherNet.TileData m; (m = arrived.poll()) != null; ) {
            long k = key(m.lod(), m.tx(), m.tz());
            asked.remove(k);
            if (ready.size() >= 64) ready.clear();  // arrivals for tiles the camera left: the disk cache has them
            ready.put(k, m.data());
            if (m.data().length > 0) store(m.lod(), m.tx(), m.tz(), m.data());
        }
        long k = key(lod, tx, tz);
        byte[] b = ready.remove(k);
        if (b != null && b.length == 0) return Refined.NEVER;  // the server does not serve this tile
        boolean cached = b == null;
        if (cached) b = load(lod, tx, tz);
        if (b != null) {
            if (AetherTileCodec.decode(lod, b, surface, water)) {
                ctx.uploadColumnHeights(lod, tx, tz, surface, water);
                return Refined.DONE;
            }
            if (!cached) return Refined.NEVER;  // the server sent garbage: keep the stand-in
            try {
                Files.deleteIfExists(file(lod, tx, tz));  // a damaged cache entry: ask again
            } catch (IOException ignored) {
                return Refined.NEVER;
            }
        }
        long now = System.currentTimeMillis();
        Long t = asked.get(k);
        if (t != null && now - t < TIMEOUT_MS) return Refined.LATER;
        if (t != null) asked.remove(k);
        if (asked.size() < IN_FLIGHT && toAsk.size() < AetherNet.MAX_REQUEST) {
            asked.put(k, now);
            toAsk.add(new int[] {lod, tx, tz});
        }
        return Refined.LATER;
    }

    @Override
    int budget(int lod) {
        return 8;  // decode + dress: a few ms each
    }

    @Override
    void endCover(AetherNative.Context ctx) {
        if (toAsk.isEmpty()) return;
        int n = toAsk.size();
        byte[] lods = new byte[n];
        int[] txs = new int[n], tzs = new int[n];
        for (int i = 0; i < n; i++) {
            lods[i] = (byte) toAsk.get(i)[0];
            txs[i] = toAsk.get(i)[1];
            tzs[i] = toAsk.get(i)[2];
        }
        toAsk.clear();
        SkyNetwork.sendToServer(new AetherNet.TileRequest(lods, txs, tzs));
    }

    private Path file(int lod, int tx, int tz) {
        return cache.resolve("l" + lod).resolve(tx + "_" + tz + ".bin");
    }

    private byte[] load(int lod, int tx, int tz) {
        Path f = file(lod, tx, tz);
        try {
            return Files.isRegularFile(f) && Files.size(f) <= AetherTileCodec.MAX_BYTES ? Files.readAllBytes(f) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void store(int lod, int tx, int tz, byte[] data) {
        try {
            Path f = file(lod, tx, tz);
            Files.createDirectories(f.getParent());
            Files.write(f, data);
        } catch (IOException e) {
            // the cache is an optimisation: the tile is re-requested next visit
        }
    }
}
