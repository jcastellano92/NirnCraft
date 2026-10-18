package com.skycraft.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

/** Shared option binding for Skycraft feature algorithms under the 26.3 unified feature registry. */
public abstract class ConfiguredFeature<C> {
    private final MapCodec<Bound<C>> codec;

    protected ConfiguredFeature(Codec<C> options) {
        codec = options.fieldOf("config").xmap(c -> new Bound<>(this, c), Bound::config);
    }

    public final MapCodec<? extends Feature> codec() { return codec; }
    public abstract boolean place(FeaturePlaceContext<C> context);
    public final boolean place(C config, WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        return place(new FeaturePlaceContext<>(level, generator, random, origin, config));
    }
    protected final void setBlock(WorldGenLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        level.setBlock(pos, state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    }
    protected java.util.stream.Stream<Holder<Feature>> getSubFeatures(C config) {
        return java.util.stream.Stream.empty();
    }

    private record Bound<C>(ConfiguredFeature<C> algorithm, C config) implements Feature {
        @Override public MapCodec<? extends Feature> codec() { return algorithm.codec(); }
        @Override public java.util.stream.Stream<Holder<Feature>> getSubFeatures() {
            return algorithm.getSubFeatures(config);
        }
        @Override public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
            return algorithm.place(new FeaturePlaceContext<>(level, generator, random, origin, config));
        }
    }
}
