package com.skycraft.worldgen;
import com.mojang.serialization.Codec;
public record NoneFeatureConfiguration() implements FeatureConfiguration {
    public static final NoneFeatureConfiguration INSTANCE = new NoneFeatureConfiguration();
    public static final Codec<NoneFeatureConfiguration> CODEC = com.mojang.serialization.MapCodec.unit(INSTANCE).codec();
}
