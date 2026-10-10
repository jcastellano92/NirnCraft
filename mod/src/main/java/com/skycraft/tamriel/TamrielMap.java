package com.skycraft.tamriel;

/**
 * The map of Nirn: Tamriel from the bundled raster (block 0,0 is Whiterun), the fixed foreign lands, and the
 * endless generated lands beyond. Every method is static, thread-safe and usable on both logical sides; worldgen
 * threads and Distant Horizons call it heavily, so the hot paths must not allocate. See docs/TAMRIEL.md.
 *
 * <p>CONTRACT STUB: the Phase 2 terrain workstream replaces the bodies; the signatures are fixed.</p>
 */
public final class TamrielMap {
    /** Blocks per raster cell. */
    public static final int CELL = 32;
    public static final int MIN_X = -10240;
    public static final int MIN_Z = -6656;
    public static final int WIDTH_CELLS = 640;
    public static final int HEIGHT_CELLS = 560;
    public static final int MAX_X = MIN_X + WIDTH_CELLS * CELL;
    public static final int MAX_Z = MIN_Z + HEIGHT_CELLS * CELL;
    public static final int SEA_LEVEL = 63;

    private TamrielMap() {}

    /** Fills {@code out} for block column (x, z). */
    public static void sample(int x, int z, MapSample out) {
        out.clear();
        out.region = 1;
        out.elevation = 72f;
        out.coastDistance = 1000f;
    }

    /** The region at block column (x, z): authored inside Tamriel and the foreign lands, generated beyond. */
    public static Region region(int x, int z) {
        return Regions.fallback();
    }

    /** Macro surface height in blocks. */
    public static float elevation(int x, int z) {
        return 72f;
    }

    public static boolean inTamriel(int x, int z) {
        return x >= MIN_X && x < MAX_X && z >= MIN_Z && z < MAX_Z;
    }
}
