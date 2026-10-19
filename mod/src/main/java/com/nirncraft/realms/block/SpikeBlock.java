package com.nirncraft.realms.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A tapering spike (the Daedric gates' and citadels' thorns, Coldharbour's iron barbs) pointing any way it's
 * placed: a thick base and a needle tip.
 */
public class SpikeBlock extends DirectionalBlock {
    private static final VoxelShape UP = Shapes.or(Block.box(4, 0, 4, 12, 8, 12), Block.box(6, 8, 6, 10, 16, 10));
    private static final VoxelShape DOWN = Shapes.or(Block.box(4, 8, 4, 12, 16, 12), Block.box(6, 0, 6, 10, 8, 10));
    private static final VoxelShape NORTH = Shapes.or(Block.box(4, 4, 8, 12, 12, 16), Block.box(6, 6, 0, 10, 10, 8));
    private static final VoxelShape SOUTH = Shapes.or(Block.box(4, 4, 0, 12, 12, 8), Block.box(6, 6, 8, 10, 10, 16));
    private static final VoxelShape WEST = Shapes.or(Block.box(8, 4, 4, 16, 12, 12), Block.box(0, 6, 6, 8, 10, 10));
    private static final VoxelShape EAST = Shapes.or(Block.box(0, 4, 4, 8, 12, 12), Block.box(8, 6, 6, 16, 10, 10));

    public SpikeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getClickedFace());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return switch (state.getValue(FACING)) {
            case UP -> UP;
            case DOWN -> DOWN;
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
        };
    }
}
