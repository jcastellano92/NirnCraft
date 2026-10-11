package com.skycraft.tamriel.settlement.plan;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Noise-only terrain sampler for planning (structure-start time: no chunk access). Columns are sampled on a
 * {@link #grid}-block lattice and interpolated in between, so planning a city costs a few hundred column samples.
 */
public final class Terrain {
    public static final int NO_WATER = Integer.MIN_VALUE;
    /** Lattice spacing of the samples (coarser for big plans). */
    public final int grid;
    private final com.skycraft.tamriel.MapSample map = new com.skycraft.tamriel.MapSample();

    private final ChunkGenerator gen;
    private final LevelHeightAccessor heights;
    private final RandomState random;
    private final Long2LongOpenHashMap cache = new Long2LongOpenHashMap();
    public final int sea;

    public Terrain(Structure.GenerationContext ctx) {
        this(ctx, 8);
    }

    public Terrain(Structure.GenerationContext ctx, int grid) {
        this.grid = grid;
        this.gen = ctx.chunkGenerator();
        this.heights = ctx.heightAccessor();
        this.random = ctx.randomState();
        this.sea = gen.getSeaLevel();
    }

    /** Packed (land, water) of a lattice node. */
    private long node(int gx, int gz) {
        long key = (((long) gx) << 32) ^ (gz & 0xFFFFFFFFL);
        if (cache.containsKey(key)) return cache.get(key);
        int x = gx * grid, z = gz * grid;
        int land = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heights, random) - 1;
        // water: the sea below sea level, and the map's lakes and rivers above it (no second noise column needed)
        int water = NO_WATER;
        if (land < sea) {
            water = sea - 1;
        } else {
            com.skycraft.tamriel.TamrielMap.sample(x, z, map);
            if (map.lake > 0.5f || map.river > 0.6f) water = land + 1;
        }
        long packed = (((long) land) << 32) | (water & 0xFFFFFFFFL);
        cache.put(key, packed);
        return packed;
    }

    /** y of the topmost solid block (interpolated between lattice nodes). */
    public int land(int x, int z) {
        int gx = Math.floorDiv(x, grid), gz = Math.floorDiv(z, grid);
        double fx = (x - gx * grid) / (double) grid, fz = (z - gz * grid) / (double) grid;
        int a = (int) (node(gx, gz) >> 32), b = (int) (node(gx + 1, gz) >> 32);
        int c = (int) (node(gx, gz + 1) >> 32), d = (int) (node(gx + 1, gz + 1) >> 32);
        return (int) Math.round(Mth.lerp(fz, Mth.lerp(fx, a, b), Mth.lerp(fx, c, d)));
    }

    /** y of the water surface over the nearest lattice node, or {@link #NO_WATER}. */
    public int water(int x, int z) {
        int gx = Math.round(x / (float) grid), gz = Math.round(z / (float) grid);
        return (int) node(gx, gz);
    }

    public boolean wet(int x, int z) {
        int w = water(x, z);
        return w != NO_WATER && w > land(x, z);
    }

    /** Median land height of five points: the centre and four corners of a box of half-extents hx, hz. */
    public int median(int x, int z, int hx, int hz) {
        int[] v = {land(x, z), land(x - hx, z - hz), land(x + hx, z - hz), land(x - hx, z + hz), land(x + hx, z + hz)};
        java.util.Arrays.sort(v);
        return v[2];
    }

    /** Height spread (max - min) over the five points of {@link #median}. */
    public int spread(int x, int z, int hx, int hz) {
        int[] v = {land(x, z), land(x - hx, z - hz), land(x + hx, z - hz), land(x - hx, z + hz), land(x + hx, z + hz)};
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int h : v) {
            lo = Math.min(lo, h);
            hi = Math.max(hi, h);
        }
        return hi - lo;
    }

    /** True if any of the five points is under water. */
    public boolean anyWet(int x, int z, int hx, int hz) {
        return wet(x, z) || wet(x - hx, z - hz) || wet(x + hx, z - hz) || wet(x - hx, z + hz) || wet(x + hx, z + hz);
    }
}
