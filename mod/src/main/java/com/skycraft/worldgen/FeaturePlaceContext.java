package com.skycraft.worldgen;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
/** Immutable invocation data; no world or mutable option state is retained by a registered feature. */
public record FeaturePlaceContext<C>(WorldGenLevel level, ChunkGenerator chunkGenerator, RandomSource random, BlockPos origin, C config) {}
