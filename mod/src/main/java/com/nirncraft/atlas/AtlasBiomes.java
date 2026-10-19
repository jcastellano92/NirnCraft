package com.nirncraft.atlas;

import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * The overworld's biome colours over a far tile: the generator's own biome source and climate (so the far field's
 * grass and foliage tints are the near field's), sampled on AtlasTints.GRID^2 points per tile (one per 4 x 4
 * columns) at the tile's surface. Each thread gets its own resolver (TamrielBiomeSource caches per thread).
 */
final class AtlasBiomes implements AtlasExactSource.Tinter {
    private final ThreadLocal<BiomeResolver> resolver;

    AtlasBiomes(BiomeSource source, RandomState random) {
        resolver = ThreadLocal.withInitial(() -> source.createResolver(random.createClimateSampler(SamplerContext.EMPTY_UNCACHED)));
    }

    static AtlasBiomes forServer(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return new AtlasBiomes(overworld.getChunkSource().getGenerator().getBiomeSource(), overworld.getChunkSource().randomState());
    }

    /** Grass and foliage rgb of tile (lod, tx, tz) on the tint grid, given its surface (row-major 256^2). */
    void fill(int lod, int tx, int tz, float[] surface, int[] grass, int[] foliage) {
        for (int j = 0; j < AtlasTints.GRID; j++) fillRow(lod, tx, tz, surface, grass, foliage, j);
    }

    /** Row j of the tint grid (thread-safe: rows may run in parallel). */
    @Override
    public void fillRow(int lod, int tx, int tz, float[] surface, int[] grass, int[] foliage, int j) {
        int s = 1 << lod, step = AtlasNative.TILE / AtlasTints.GRID;
        int bx = tx * (AtlasNative.TILE << lod), bz = tz * (AtlasNative.TILE << lod);
        BiomeResolver r = resolver.get();
        for (int i = 0; i < AtlasTints.GRID; i++) {
            int ci = i * step + step / 2, cj = j * step + step / 2;
            int x = bx + ci * s + (s >> 1), z = bz + cj * s + (s >> 1);
            int y = (int) Math.floor(surface[cj * AtlasNative.TILE + ci]);
            Holder<Biome> b = r.getNoiseBiome(x >> 2, y >> 2, z >> 2);
            grass[j * AtlasTints.GRID + i] = b.value().getGrassColor(x, z) & 0xFFFFFF;
            foliage[j * AtlasTints.GRID + i] = b.value().getFoliageColor() & 0xFFFFFF;
        }
    }
}
