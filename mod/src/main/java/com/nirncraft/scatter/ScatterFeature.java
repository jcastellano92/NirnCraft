package com.nirncraft.scatter;

import com.mojang.serialization.Codec;
import com.nirncraft.NirnCraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import com.nirncraft.worldgen.ConfiguredFeature;
import com.nirncraft.worldgen.FeaturePlaceContext;
import com.nirncraft.worldgen.NoneFeatureConfiguration;

/**
 * Scatters the clutter of the decorated chunk: the discovery whose centre is in it (if any), the planned scenes that
 * reach it ({@link SceneRealiser}), then the zone's natural props.
 * Placed once per chunk (count 1) in every overworld biome at the last decoration step, after the trees and plants,
 * like {@code nirncraft:minor_site}. See {@link Scatter}.
 */
public class ScatterFeature extends ConfiguredFeature<NoneFeatureConfiguration> {
    public ScatterFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        ChunkPos chunk = ChunkPos.containing(ctx.origin());
        boolean any = false;
        long t0 = System.nanoTime();
        try {
            any = Discoveries.generate(level, chunk);
            // the planned scenes first (every human prop), then the chunk's natural micro-scatter around them
            any |= SceneRealiser.generate(level, chunk) > 0;
            any |= Scatter.generate(level, chunk) > 0;
        } catch (RuntimeException e) {
            NirnCraft.LOGGER.warn("NirnCraft scatter: failed in chunk {}", chunk, e);
        }
        ScatterStats.time(System.nanoTime() - t0);
        return any;
    }
}
