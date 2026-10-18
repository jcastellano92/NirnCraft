package com.skycraft.aether;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * A column source that is never empty: the seed-free raster stand-in fills every tile of the window at once
 * (aether generates it natively in milliseconds), then each tile is replaced by its exact geometry as that becomes
 * available, nearest first (computed here in single player, sent by the server in multiplayer). aether dresses both
 * from the same raster, so a refined tile changes shape, not palette.
 */
abstract class AetherRefiningSource implements AetherFarField.ColumnSource {
    private final Callable<Path> raster;
    /** Tiles holding exact geometry: (lod, tx, tz) packed by key(). Entries of evicted tiles are dropped on sight. */
    private final Set<Long> exact = new HashSet<>();

    AetherRefiningSource(Callable<Path> raster) {
        this.raster = raster;
    }

    static long key(int lod, int tx, int tz) {
        return (long) lod << 56 | (tx & 0xFFFFFFFL) << 28 | (tz & 0xFFFFFFFL);
    }

    @Override
    public final boolean usesBlockPalette() {
        return false;  // the raster dressing (preview palette) for stand-in and exact tiles alike
    }

    @Override
    public void prepare(AetherNative.Context ctx) throws IOException {
        try {
            ctx.previewLoadRaster(raster.call());
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public final boolean cover(AetherNative.Context ctx, int lod, int x0, int z0, int x1, int z1) {
        int span = AetherNative.TILE << lod;
        int tx0 = Math.floorDiv(x0, span), tz0 = Math.floorDiv(z0, span);
        int tx1 = Math.floorDiv(x1 - 1, span), tz1 = Math.floorDiv(z1 - 1, span);
        // nearest the window's centre first
        double cx = (tx0 + tx1) / 2.0, cz = (tz0 + tz1) / 2.0;
        List<int[]> todo = new ArrayList<>();
        for (int tz = tz0; tz <= tz1; tz++)
            for (int tx = tx0; tx <= tx1; tx++) {
                long k = key(lod, tx, tz);
                if (exact.contains(k)) {
                    if (ctx.hasTile(lod, tx, tz)) continue;
                    exact.remove(k);  // evicted since: the stand-in comes back below, then the exact tile again
                }
                todo.add(new int[] {tx, tz});
            }
        ctx.previewGenerate(lod, x0, z0, x1, z1);  // stand-ins where nothing is resident (skips the rest)
        todo.sort((a, b) -> Double.compare(Math.hypot(a[0] - cx, a[1] - cz), Math.hypot(b[0] - cx, b[1] - cz)));
        boolean pending = false;
        int budget = budget(lod);
        for (int[] t : todo) {
            if (budget <= 0) {
                pending = true;
                break;
            }
            switch (refine(ctx, lod, t[0], t[1])) {
                case DONE -> {
                    exact.add(key(lod, t[0], t[1]));
                    budget--;
                }
                case LATER -> pending = true;
                case NEVER -> exact.add(key(lod, t[0], t[1]));  // keep the stand-in, stop asking
            }
        }
        endCover(ctx);
        return pending;
    }

    enum Refined { DONE, LATER, NEVER }

    /** Uploads the exact geometry of one tile if it is available now (DONE), will be (LATER), or never (NEVER). */
    abstract Refined refine(AetherNative.Context ctx, int lod, int tx, int tz);

    /** Exact tiles refined per cover() call of LOD `lod`, so the clipmap rebuilds and shows progress in between. */
    abstract int budget(int lod);

    /** After each cover() pass (batch outgoing requests here). */
    void endCover(AetherNative.Context ctx) {}
}
