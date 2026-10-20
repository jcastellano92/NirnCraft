package com.nirncraft.atlas;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * A column source that is never empty: the seed-free raster stand-in fills every tile of the window at once
 * (atlas generates it natively in milliseconds), then each tile is replaced by its exact geometry as that becomes
 * available, nearest first (computed here in single player, sent by the server in multiplayer). atlas dresses both
 * from the same raster, so a refined tile changes shape, not palette.
 */
abstract class AtlasRefiningSource implements AtlasFarField.ColumnSource {
    private final Callable<Path> raster;
    /** Tiles holding exact geometry: (lod, tx, tz) packed by key(). Entries of evicted tiles are dropped on sight. */
    private final Set<Long> exact = new HashSet<>();
    private volatile double viewX, viewZ, viewFx, viewFz = 1, viewMinDot = -1;
    private volatile boolean visiblePending;

    @Override
    public void view(double x, double z, double yawDeg, double halfDeg) {
        double yaw = Math.toRadians(yawDeg);
        viewX = x;
        viewZ = z;
        viewFx = -Math.sin(yaw);
        viewFz = Math.cos(yaw);
        viewMinDot = Math.cos(Math.toRadians(Math.min(89, halfDeg)));
    }

    @Override
    public void beginPass() {
        visiblePending = false;
    }

    @Override
    public boolean visiblePending() {
        return visiblePending;
    }

    /** A tile the camera can see: its centre inside the view cone, or the tile under the camera itself. */
    private boolean seen(int lod, int tx, int tz) {
        double span = AtlasNative.TILE << lod;
        double dx = (tx + 0.5) * span - viewX, dz = (tz + 0.5) * span - viewZ;
        double len = Math.hypot(dx, dz);
        return len < span || (dx * viewFx + dz * viewFz) / len >= viewMinDot;
    }

    AtlasRefiningSource(Callable<Path> raster) {
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
    public void prepare(AtlasNative.Context ctx) throws IOException {
        try {
            ctx.previewLoadRaster(raster.call());
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public final boolean cover(AtlasNative.Context ctx, int lod, int x0, int z0, int x1, int z1) {
        int span = AtlasNative.TILE << lod;
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
        // what the camera sees first, then nearest the window's centre
        todo.sort((a, b) -> {
            boolean sa = seen(lod, a[0], a[1]), sb = seen(lod, b[0], b[1]);
            if (sa != sb) return sa ? -1 : 1;
            return Double.compare(Math.hypot(a[0] - cx, a[1] - cz), Math.hypot(b[0] - cx, b[1] - cz));
        });
        // tiles made before (the world's disk cache: a revisit, a teleport back) go up exact at once, before any
        // stand-in: decoding is milliseconds, and a stand-in first showed the raster's ground and forests for passes
        int quick = CACHED_PER_PASS;
        for (int i = 0; i < todo.size() && quick > 0; i++) {
            int[] t = todo.get(i);
            if (!cached(lod, t[0], t[1])) continue;
            quick--;
            if (refine(ctx, lod, t[0], t[1]) == Refined.DONE) {
                exact.add(key(lod, t[0], t[1]));
                todo.remove(i--);
            }
        }
        ctx.previewGenerate(lod, x0, z0, x1, z1);  // stand-ins where nothing is resident (skips the rest)
        boolean pending = false;
        int budget = budget(lod);
        for (int i = 0; i < todo.size(); i++) {
            int[] t = todo.get(i);
            if (budget <= 0) {
                pending = true;
                for (int k = i; k < todo.size() && !visiblePending; k++)  // left for later: is any of it on screen?
                    if (seen(lod, todo.get(k)[0], todo.get(k)[1])) visiblePending = true;
                break;
            }
            switch (refine(ctx, lod, t[0], t[1])) {
                case DONE -> {
                    exact.add(key(lod, t[0], t[1]));
                    budget--;
                }
                case LATER -> {
                    pending = true;
                    if (seen(lod, t[0], t[1])) visiblePending = true;
                }
                case NEVER -> exact.add(key(lod, t[0], t[1]));  // keep the stand-in, stop asking
            }
        }
        endCover(ctx);
        return pending;
    }

    @Override
    public final void coverQuick(AtlasNative.Context ctx, int lod, int x0, int z0, int x1, int z1) {
        int span = AtlasNative.TILE << lod;
        int tx0 = Math.floorDiv(x0, span), tz0 = Math.floorDiv(z0, span);
        int tx1 = Math.floorDiv(x1 - 1, span), tz1 = Math.floorDiv(z1 - 1, span);
        int quick = CACHED_PER_PASS;
        for (int tz = tz0; tz <= tz1 && quick > 0; tz++)  // ready-made tiles (the disk cache) go up exact
            for (int tx = tx0; tx <= tx1 && quick > 0; tx++) {
                long k = key(lod, tx, tz);
                if (exact.contains(k) && ctx.hasTile(lod, tx, tz)) continue;
                if (!cached(lod, tx, tz)) continue;
                quick--;
                if (refine(ctx, lod, tx, tz) == Refined.DONE) exact.add(k);
            }
        ctx.previewGenerate(lod, x0, z0, x1, z1);  // stand-ins for the rest
    }

    enum Refined { DONE, LATER, NEVER }

    /** Cached tiles put up per LOD and pass ahead of the budget (each a decode and an upload). */
    static final int CACHED_PER_PASS = 32;

    /** Whether tile (lod, tx, tz) is ready made (a disk cache): it is put up before stand-ins, outside the budget. */
    boolean cached(int lod, int tx, int tz) {
        return false;
    }

    /** The context's block palette, for tiles that carry surface blocks (codec v3). */
    void materials(AtlasMaterials m) {}

    /** Uploads the exact geometry of one tile if it is available now (DONE), will be (LATER), or never (NEVER). */
    abstract Refined refine(AtlasNative.Context ctx, int lod, int tx, int tz);

    /** Exact tiles refined per cover() call of LOD `lod`, so the clipmap rebuilds and shows progress in between. */
    abstract int budget(int lod);

    /** After each cover() pass (batch outgoing requests here). */
    void endCover(AtlasNative.Context ctx) {}
}
