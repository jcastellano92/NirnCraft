package com.skycraft.landmarks;

import com.mojang.serialization.Codec;
import com.skycraft.Skycraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import java.util.List;

/**
 * Builds the natural landmarks whose centre lies in the decorated chunk ({@link Landmarks#inChunk}). Placed once per
 * chunk in every overworld biome at the last decoration step, after the trees, like the explore module's minor
 * sites. A site writes only inside the 3x3 chunks around its own, never loads another chunk, and gives way to
 * structures already planned over it.
 */
public class LandmarkFeature extends Feature<NoneFeatureConfiguration> {
    public LandmarkFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        ChunkPos chunk = new ChunkPos(ctx.origin());
        List<LandmarkSite> sites = Landmarks.inChunk(level.getSeed(), chunk.x, chunk.z);
        if (sites.isEmpty()) return false;
        boolean any = false;
        for (LandmarkSite site : sites) {
            try {
                any |= LandmarkBuilder.build(level, site, false, null);
            } catch (RuntimeException e) {
                Skycraft.LOGGER.warn("Skycraft landmarks: failed to place {} at {} {}", site.kind().id(), site.x(), site.z(), e);
            }
        }
        return any;
    }
}
