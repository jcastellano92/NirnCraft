package com.nirncraft.fire;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A flat stain on the ground (soot, blood) or a low pile of debris: no collision, replaceable, never floats. Used by the
 * burnt ruins and the aftermath scenes.
 */
public class GroundDecalBlock extends Block {
    private static final VoxelShape FLAT = Block.box(0, 0, 0, 16, 1, 16);
    private final VoxelShape shape;
    private final boolean collide;

    /** A decal (1 px, no collision). */
    public GroundDecalBlock(Properties props) {
        this(props, FLAT, false);
    }

    /** Debris of a given outline; {@code collide} makes the outline solid (a step-over heap). */
    public GroundDecalBlock(Properties props, VoxelShape shape, boolean collide) {
        super(props);
        this.shape = shape;
        this.collide = collide;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shape;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return collide ? shape : Shapes.empty();
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    @Override
    public BlockState updateShape(BlockState state, net.minecraft.world.level.LevelReader level, net.minecraft.world.level.ScheduledTickAccess ticks, BlockPos pos, Direction dir, BlockPos neighborPos, BlockState neighbor, net.minecraft.util.RandomSource random) {
        if (dir == Direction.DOWN && !state.canSurvive(level, pos)) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, level, ticks, pos, dir, neighborPos, neighbor, random);
    }
}
