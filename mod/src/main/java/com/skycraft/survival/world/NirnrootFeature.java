package com.skycraft.survival.world;

import com.mojang.serialization.Codec;
import com.skycraft.crafting.arcane.ArcaneRegistry;
import com.skycraft.crafting.arcane.block.IngredientPlantBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.skycraft.worldgen.ConfiguredFeature;
import com.skycraft.worldgen.FeaturePlaceContext;
import com.skycraft.worldgen.NoneFeatureConfiguration;

/**
 * Generates patches of Nirnroot along shorelines, riverbanks, and swamp waters.
 */
public class NirnrootFeature extends ConfiguredFeature<NoneFeatureConfiguration> {
    public NirnrootFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos origin = ctx.origin();

        boolean placedAny = false;
        for (int i = 0; i < 6; i++) {
            int dx = random.nextInt(7) - 3;
            int dz = random.nextInt(7) - 3;
            int dy = random.nextInt(3) - 1;
            BlockPos target = origin.offset(dx, dy, dz);

            if (!level.isEmptyBlock(target)) continue;

            BlockState ground = level.getBlockState(target.below());
            if (!ground.is(Blocks.GRASS_BLOCK) && !ground.is(Blocks.DIRT) && !ground.is(Blocks.MUD) && !ground.is(Blocks.SAND)) {
                continue;
            }

            // Must be adjacent to water like in Skyrim
            boolean nearWater = false;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (level.getFluidState(target.below().relative(dir)).is(FluidTags.WATER)
                        || level.getFluidState(target.relative(dir)).is(FluidTags.WATER)) {
                    nearWater = true;
                    break;
                }
            }

            if (nearWater) {
                level.setBlock(target, ArcaneRegistry.NIRNROOT.get().defaultBlockState().setValue(IngredientPlantBlock.HARVESTED, false), 2);
                placedAny = true;
            }
        }

        return placedAny;
    }
}

