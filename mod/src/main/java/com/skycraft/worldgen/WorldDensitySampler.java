package com.skycraft.worldgen;
import net.minecraft.world.level.levelgen.densityfunction.*;
/** Native scalar sampler with the engine's ordered volume fallback; implementations allocate no per-value context. */
@FunctionalInterface
public interface WorldDensitySampler extends DensitySampler {
    @Override float sampleValue(SamplerContext context, int x, int y, int z);
    @Override default void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
        DensitySampler.sampleVolumeNaive(context, output, volume, this);
    }
}
