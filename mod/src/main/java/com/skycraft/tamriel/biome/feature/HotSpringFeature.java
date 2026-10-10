package com.skycraft.tamriel.biome.feature;

import com.mojang.serialization.Codec;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Eastmarch's hot springs: a shallow, roughly oval pool sunk into flat ground, rimmed with pale calcite, diorite and
 * tuff, with a vent of glowing rock at the bottom (the biome tints the water a sulphurous teal).
 */
public class HotSpringFeature extends Feature<NoneFeatureConfiguration> {
    public HotSpringFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        RandomSource random = ctx.random();
        GenBox box = GenBox.of(ctx.level(), ctx.origin());
        int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        double rx = 2.5 + random.nextDouble() * 3.5, rz = 2.5 + random.nextDouble() * 3.5;
        int rim = (int) Math.ceil(Math.max(rx, rz)) + 2;
        if (box.reach(cx, cz) < rim + 1) return false;
        // the water line is the lowest ground on the rim; give up on slopes
        int water = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int dx = -rim; dx <= rim; dx++) {
            for (int dz = -rim; dz <= rim; dz++) {
                double e = (dx * dx) / ((rx + 1.5) * (rx + 1.5)) + (dz * dz) / ((rz + 1.5) * (rz + 1.5));
                if (e > 1.0) continue;
                int h = box.surface(cx + dx, cz + dz);
                if (!box.get(cx + dx, h, cz + dz).getFluidState().isEmpty()) return false;
                water = Math.min(water, h - 1);
                highest = Math.max(highest, h);
            }
        }
        if (highest - water > 3) return false;
        BlockState[] rimBlocks = {Blocks.CALCITE.defaultBlockState(), Blocks.CALCITE.defaultBlockState(),
                Blocks.DIORITE.defaultBlockState(), Blocks.TUFF.defaultBlockState(), Blocks.GRAVEL.defaultBlockState()};
        for (int dx = -rim; dx <= rim; dx++) {
            for (int dz = -rim; dz <= rim; dz++) {
                double e = (dx * dx) / (rx * rx) + (dz * dz) / (rz * rz);
                double eRim = (dx * dx) / ((rx + 1.5) * (rx + 1.5)) + (dz * dz) / ((rz + 1.5) * (rz + 1.5));
                int x = cx + dx, z = cz + dz;
                if (e <= 1.0) {
                    int depth = 1 + (int) Math.round((1 - e) * (1.5 + random.nextDouble()));
                    for (int y = water + 4; y > water; y--) box.set(x, y, z, Blocks.AIR.defaultBlockState());
                    for (int y = water; y > water - depth; y--) box.set(x, y, z, Blocks.WATER.defaultBlockState());
                    BlockState bed = e < 0.08 ? Blocks.MAGMA_BLOCK.defaultBlockState()
                            : random.nextBoolean() ? Blocks.CALCITE.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
                    box.set(x, water - depth, z, bed);
                    box.set(x, water - depth - 1, z, Blocks.STONE.defaultBlockState());
                } else if (eRim <= 1.0) {
                    // the rim: solid at and below the water line so the pool can't leak
                    for (int y = water + 4; y > water; y--) box.set(x, y, z, Blocks.AIR.defaultBlockState());
                    box.set(x, water, z, rimBlocks[random.nextInt(rimBlocks.length)]);
                    box.set(x, water - 1, z, Blocks.STONE.defaultBlockState());
                }
            }
        }
        return true;
    }
}
