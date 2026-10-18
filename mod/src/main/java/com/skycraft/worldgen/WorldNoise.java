package com.skycraft.worldgen;
import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
/** Immutable registry reference, bound once to the world's seeded noise during sampler compilation. */
public record WorldNoise(Holder<NormalNoise> noiseData, Noise noise) {
    public static final Codec<WorldNoise> CODEC = NormalNoise.CODEC.xmap(h -> new WorldNoise(h, null), WorldNoise::noiseData);
    public WorldNoise bind(DensityFunction.CompileContext context) { return new WorldNoise(noiseData, context.createNoiseSampler(noiseData)); }
    public double getValue(double x, double y, double z) {
        if (noise == null) throw new IllegalStateException("World noise used before sampler compilation");
        return noise.get(x, y, z);
    }
}
