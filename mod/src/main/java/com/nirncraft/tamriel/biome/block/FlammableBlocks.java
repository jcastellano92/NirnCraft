package com.nirncraft.tamriel.biome.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Wood and leaves of the Tamriel trees burn like vanilla wood (Forge's per-block flammability hooks, since
 * {@code FireBlock.setFlammable} is private).
 */
public final class FlammableBlocks {
    private FlammableBlocks() {}

    /** Logs and wood: catch fire slowly, burn long (vanilla 5 / 5). */
    public static class Log extends RotatedPillarBlock {
        public Log(Properties properties) {
            super(properties);
        }

        @Override
        public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
            return 5;
        }

        @Override
        public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
            return 5;
        }
    }

    /** Planks (vanilla 5 / 20). */
    public static class Planks extends Block {
        public Planks(Properties properties) {
            super(properties);
        }

        @Override
        public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
            return 20;
        }

        @Override
        public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
            return 5;
        }
    }

    /** Leaves (vanilla 30 / 60). */
    public static class Leaves extends LeavesBlock {
        public Leaves(Properties properties) {
            super(net.minecraft.world.level.block.sounds.AmbientLeavesBlockSoundPlayer.noAmbientSound(), properties);
        }

        @Override
        public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
            return 60;
        }

        @Override
        public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
            return 30;
        }
    }

    /** Giant mushroom flesh: burns like wood (it is wood, in Morrowind). */
    public static class Fungus extends Block {
        public Fungus(Properties properties) {
            super(properties);
        }

        @Override
        public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
            return 10;
        }

        @Override
        public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
            return 5;
        }
    }

    /** Giant mushroom stems. */
    public static class FungusStem extends RotatedPillarBlock {
        public FungusStem(Properties properties) {
            super(properties);
        }

        @Override
        public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
            return 5;
        }

        @Override
        public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
            return 5;
        }
    }
}
