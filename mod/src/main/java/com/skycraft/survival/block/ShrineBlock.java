package com.skycraft.survival.block;

import com.skycraft.survival.Divine;
import com.skycraft.survival.Shrines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A shrine of one of the Nine Divines: praying (right-click) cures all diseases and grants an 8-hour blessing. */
public class ShrineBlock extends Block {
    private static final VoxelShape SHAPE = Shapes.join(
            Shapes.join(Block.box(1, 0, 1, 15, 4, 15), Block.box(3, 4, 3, 13, 13, 13), BooleanOp.OR),
            Block.box(2, 13, 2, 14, 16, 14), BooleanOp.OR);

    public final Divine divine;

    public ShrineBlock(Divine divine, Properties props) {
        super(props);
        this.divine = divine;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return useItemOn(net.minecraft.world.item.ItemStack.EMPTY, state, level, pos, player, InteractionHand.MAIN_HAND, hit);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult useItemOn(net.minecraft.world.item.ItemStack held263, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) Shrines.pray(sp, divine, pos);
        return InteractionResult.CONSUME;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(4) != 0) return;
        level.addParticle(ParticleTypes.END_ROD, pos.getX() + 0.3 + random.nextDouble() * 0.4, pos.getY() + 1.05,
                pos.getZ() + 0.3 + random.nextDouble() * 0.4, 0, 0.015, 0);
    }
}
