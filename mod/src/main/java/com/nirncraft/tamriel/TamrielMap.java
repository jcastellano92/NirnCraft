package com.nirncraft.tamriel;

import com.nirncraft.tamriel.terrain.Beyond;
import com.nirncraft.tamriel.terrain.MapRaster;

/**
 * The map of Nirn: Tamriel from the bundled raster (block 0,0 is Whiterun), the fixed foreign lands, and the
 * endless generated lands beyond. Every method is static, thread-safe and usable on both logical sides; worldgen
 * threads and Distant Horizons call it heavily, so the hot paths do not allocate. See docs/TAMRIEL.md.
 *
 * <p>The raster is written by {@code tools/worldgen/tamriel_map.py} from {@code tools/worldgen/tamriel_vectors.json};
 * the bounds below must match that file's {@code meta} block.</p>
 */
public final class TamrielMap {
    /** Blocks per raster cell. */
    public static final int CELL = 32;
    public static final int MIN_X = -15360;
    public static final int MIN_Z = -5120;
    public static final int WIDTH_CELLS = 880;
    public static final int HEIGHT_CELLS = 672;
    public static final int MAX_X = MIN_X + WIDTH_CELLS * CELL;
    public static final int MAX_Z = MIN_Z + HEIGHT_CELLS * CELL;
    public static final int SEA_LEVEL = 63;

    /** Per-thread sample plus the column it holds, so the many density functions of one column share one lookup. */
    private static final class Scratch {
        final MapSample sample = new MapSample();
        int x, z;
        boolean valid;
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private TamrielMap() {}

    /** Fills {@code out} for block column (x, z). */
    public static void sample(int x, int z, MapSample out) {
        out.peak = 0f;
        out.calm = 0f;
        out.slope = 0f;
        out.rainshadow = 0f;
        out.heat = 0f;
        out.aspectX = 0f;
        out.aspectZ = 0f;
        if (MapRaster.covers(x, z)) {
            MapRaster.get().sample(x, z, out);
            if (Beyond.nearForeign(x, z)) Beyond.mergeForeign(x, z, out);
        } else {
            Beyond.sample(x, z, out);
        }
    }

    /**
     * A per-thread sample for callers that only need it briefly: read-only, and overwritten by the next call for a
     * different column. Repeated calls for the same column reuse the last result.
     */
    public static MapSample sampleScratch(int x, int z) {
        Scratch s = SCRATCH.get();
        if (!s.valid || s.x != x || s.z != z) {
            sample(x, z, s.sample);
            s.x = x;
            s.z = z;
            s.valid = true;
        }
        return s.sample;
    }

    /** The region at block column (x, z): authored inside Tamriel and the foreign lands, generated beyond. */
    public static Region region(int x, int z) {
        return Regions.byIndex(regionIndex(x, z));
    }

    /** Region index at (x, z) (see {@link MapSample#region}). */
    public static int regionIndex(int x, int z) {
        if (MapRaster.covers(x, z) && !Beyond.nearForeign(x, z)) return MapRaster.get().regionIndex(x, z);
        return sampleScratch(x, z).region;
    }

    /** Macro surface height in blocks. */
    public static float elevation(int x, int z) {
        return sampleScratch(x, z).elevation;
    }

    public static boolean inTamriel(int x, int z) {
        return x >= MIN_X && x < MAX_X && z >= MIN_Z && z < MAX_Z;
    }

    /** True when the map rasters loaded (false means the world falls back to open sea). */
    public static boolean loaded() {
        return MapRaster.get().loaded();
    }

    /** Narrowest channel (half width, blocks) that counts as a river for the biome context. */
    public static final float RIVER_BIOME_HALF_WIDTH = 3.5f;

    /** Derives the 0..1 river/lake strengths of a sample from its distances. */
    public static void deriveStrengths(MapSample s) {
        if (Float.isNaN(s.waterLevel)) {
            s.river = 0f;
            s.lake = 0f;
            return;
        }
        float over = Math.max(0f, s.riverDistance - s.riverHalfWidth);
        // brooks and small streams carve and fill but do not turn the biome into "river"
        s.river = s.riverHalfWidth >= RIVER_BIOME_HALF_WIDTH ? Math.max(0f, 1f - over / 24f) : 0f;
        s.lake = Math.max(0f, Math.min(1f, 0.5f + s.lakeDistance / 32f));
    }
}
