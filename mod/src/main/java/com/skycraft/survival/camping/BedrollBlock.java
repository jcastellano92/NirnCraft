package com.skycraft.survival.camping;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Skyrim's bedroll: a two-block bed of hides you can lay out anywhere. Right-click opens the Wait/Sleep menu like any
 * bed, but it never becomes your respawn point (see {@code CampingEvents}), and you wake up only Rested, not Well
 * Rested ({@code SurvivalMode#onRest}). Rendered from a plain block model (no bed block entity).
 */
public class BedrollBlock extends BedBlock {
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 3, 16);

    public BedrollBlock(Properties props) {
        super(DyeColor.BROWN, props);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Entity(pos, state);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    public static class Entity extends BlockEntity {
        public Entity(BlockPos pos, BlockState state) {
            super(com.skycraft.survival.SurvivalRegistry.BEDROLL_ENTITY.get(), pos, state);
        }
    }
}
