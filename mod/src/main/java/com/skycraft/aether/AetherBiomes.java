package com.skycraft.aether;

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
 * grass and foliage tints are the near field's), sampled on AetherTints.GRID^2 points per tile (one per 4 x 4
 * columns) at the tile's surface. Each thread gets its own resolver (TamrielBiomeSource caches per thread).
 */
final class AetherBiomes implements AetherExactSource.Tinter {
    private final ThreadLocal<BiomeResolver> resolver;

    AetherBiomes(BiomeSource source, RandomState random) {
        resolver = ThreadLocal.withInitial(() -> source.createResolver(random.createClimateSampler(SamplerContext.EMPTY_UNCACHED)));
    }

    static AetherBiomes forServer(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return new AetherBiomes(overworld.getChunkSource().getGenerator().getBiomeSource(), overworld.getChunkSource().randomState());
    }

    /** Grass and foliage rgb of tile (lod, tx, tz) on the tint grid, given its surface (row-major 256^2). */
    void fill(int lod, int tx, int tz, float[] surface, int[] grass, int[] foliage) {
        for (int j = 0; j < AetherTints.GRID; j++) fillRow(lod, tx, tz, surface, grass, foliage, j);
    }

    /** Row j of the tint grid (thread-safe: rows may run in parallel). */
    @Override
    public void fillRow(int lod, int tx, int tz, float[] surface, int[] grass, int[] foliage, int j) {
        int s = 1 << lod, step = AetherNative.TILE / AetherTints.GRID;
        int bx = tx * (AetherNative.TILE << lod), bz = tz * (AetherNative.TILE << lod);
        BiomeResolver r = resolver.get();
        for (int i = 0; i < AetherTints.GRID; i++) {
            int ci = i * step + step / 2, cj = j * step + step / 2;
            int x = bx + ci * s + (s >> 1), z = bz + cj * s + (s >> 1);
            int y = (int) Math.floor(surface[cj * AetherNative.TILE + ci]);
            Holder<Biome> b = r.getNoiseBiome(x >> 2, y >> 2, z >> 2);
            grass[j * AetherTints.GRID + i] = b.value().getGrassColor(x, z) & 0xFFFFFF;
            foliage[j * AetherTints.GRID + i] = b.value().getFoliageColor() & 0xFFFFFF;
        }
    }
}
