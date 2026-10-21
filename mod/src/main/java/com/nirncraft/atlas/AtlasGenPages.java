package com.nirncraft.atlas;

import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.TamrielMap;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * The client generator's per-chunk pages (atlas_gen.slang): the mod's 2D inputs for 16 x 16 columns, computed by the
 * very samplers the generator compiles (so exact by construction), the map water of each column (MapWaterFeature's
 * test), and, for full pages, the chunk's quart biomes filled in the order and with the fresh climate sampler of
 * ChunkGenerator.createBiomes (TamrielBiomeSource caches a column's climate at the first quart asked: the order
 * matters). Thread-safe: callers on several threads compute different chunks.
 */
final class AtlasGenPages {
    static final int W_NONE = 0, W_MAP = 1, W_MARSH = 2;
    private final AtlasGenProgram program;
    private final RandomState random;
    private final BiomeSource biomes;
    private final LevelHeightAccessor heights;
    private final DensitySampler[] fields;

    AtlasGenPages(AtlasGenProgram program, RandomState random, BiomeSource biomes, LevelHeightAccessor heights) {
        this.program = program;
        this.random = random;
        this.biomes = biomes;
        this.heights = heights;
        fields = new DensitySampler[program.fields.size()];
        for (int f = 0; f < fields.length; f++) fields[f] = random.getSampler(program.fields.get(f));
    }

    int[] page(int cx, int cz, boolean full) {
        int nf = fields.length;
        int[] w = new int[program.pageWords()];
        int x0 = cx << 4, z0 = cz << 4;
        for (int f = 0; f < nf; f++)
            for (int lz = 0; lz < 16; lz++)
                for (int lx = 0; lx < 16; lx++)
                    w[f * 256 + (lz << 4 | lx)] = Float.floatToRawIntBits(
                            fields[f].sampleValue(SamplerContext.EMPTY_UNCACHED, x0 + lx, 0, z0 + lz));
        MapSample s = new MapSample();
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                TamrielMap.sample(x0 + lx, z0 + lz, s);
                int word = W_NONE;
                if (!s.inWater()) {
                    if (s.wetland >= 0.5f) word = W_MARSH;
                } else {
                    int top = (int) Math.ceil(s.waterLevel) - 1;
                    if (top >= TamrielMap.SEA_LEVEL) word = W_MAP | top << 16;
                }
                w[nf * 256 + (lz << 4 | lx)] = word;
            }
        if (full) fillBiomes(cx, cz, w, nf * 256 + 256);
        return w;
    }

    private void fillBiomes(int cx, int cz, int[] w, int at) {
        Climate.Sampler climate = random.createClimateSampler(SamplerContext.builder().enableCaches().build());
        int qx0 = QuartPos.fromBlock(cx << 4), qz0 = QuartPos.fromBlock(cz << 4);
        int qy0 = QuartPos.fromBlock(heights.getMinY());
        BiomeResolver r = biomes.createResolverForChunk(climate, qx0, qy0, qz0, 4, QuartPos.fromBlock(heights.getHeight()), 4);
        // ChunkAccess.fillBiomesFromNoise: sections bottom up, then x, y, z within each
        for (int sy = heights.getMinSectionY(); sy <= heights.getMaxSectionY(); sy++) {
            int qyMin = QuartPos.fromSection(sy);
            for (int x = 0; x < 4; x++)
                for (int y = 0; y < 4; y++)
                    for (int z = 0; z < 4; z++) {
                        Holder<Biome> b = r.getNoiseBiome(qx0 + x, qyMin + y, qz0 + z);
                        Integer id = program.biomeIds.get(b);
                        if (id == null) throw new IllegalStateException("biome outside the program's set: " + b);
                        int qy = qyMin + y - qy0;
                        if (qy < 0 || qy >= AtlasGenProgram.QY) continue;
                        int i = (x * 4 + z) * AtlasGenProgram.QY + qy;
                        int k = at + (i >> 1);
                        w[k] = (i & 1) != 0 ? (w[k] & 0xFFFF) | id << 16 : (w[k] & 0xFFFF0000) | id;
                    }
        }
    }
}
