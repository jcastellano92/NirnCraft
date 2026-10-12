package com.skycraft.scatter;

import com.mojang.serialization.Codec;
import com.skycraft.Skycraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Scatters the clutter of the decorated chunk: the discovery whose centre is in it (if any), then the zone's props.
 * Placed once per chunk (count 1) in every overworld biome at the last decoration step, after the trees and plants,
 * like {@code skycraft:minor_site}. See {@link Scatter}.
 */
public class ScatterFeature extends Feature<NoneFeatureConfiguration> {
    public ScatterFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        ChunkPos chunk = new ChunkPos(ctx.origin());
        boolean any = false;
        long t0 = System.nanoTime();
        try {
            any = Discoveries.generate(level, chunk);
            any |= Scatter.generate(level, chunk) > 0;
        } catch (RuntimeException e) {
            Skycraft.LOGGER.warn("Skycraft scatter: failed in chunk {}", chunk, e);
        }
        ScatterStats.time(System.nanoTime() - t0);
        return any;
    }
}
