package com.skycraft.tamriel.biome.block;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Predicate;

/**
 * Wild plants of the Tamriel biomes that grow on more than dirt: ash grass and scathecraw on Morrowind's ash and
 * volcanic rock, reeds on swamp mud.
 */
public final class GroundPlantBlock {
    private GroundPlantBlock() {}

    /** Dirt, sand, gravel, mud, clay and moss: anything soft and natural. */
    public static boolean softSoil(BlockState state) {
        return state.is(BlockTags.SUBSTRATE_OVERWORLD) || state.is(BlockTags.SAND) || state.is(net.minecraft.world.level.block.Blocks.GRAVEL)
                || state.is(net.minecraft.world.level.block.Blocks.CLAY) || state.is(BlockTags.SNOW);
    }

    /** A short single-block plant. */
    public static class Short extends BushBlock {
        private static final VoxelShape SHAPE = Block.box(2.0, 0.0, 2.0, 14.0, 12.0, 14.0);
        private final Predicate<BlockState> soil;

        public Short(Properties properties, Predicate<BlockState> soil) {
            super(properties);
            this.soil = soil;
        }

        @Override
        protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
            return soil.test(state);
        }

        @Override
        public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return SHAPE.move(state.getOffset( pos).x, 0, state.getOffset( pos).z);
        }
    }

    /** A two-block-tall plant (swamp reeds). */
    public static class Tall extends DoublePlantBlock {
        private final Predicate<BlockState> soil;

        public Tall(Properties properties, Predicate<BlockState> soil) {
            super(properties);
            this.soil = soil;
        }

        @Override
        protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
            return soil.test(state);
        }
    }
}
