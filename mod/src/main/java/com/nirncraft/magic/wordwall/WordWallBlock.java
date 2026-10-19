package com.nirncraft.magic.wordwall;

import com.nirncraft.magic.MagicRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * A Word Wall: an ancient carved stone with glowing dragon script. Walk up to it or touch it to learn a Word of
 * Power. Each wall teaches each player once.
 */
public class WordWallBlock extends BaseEntityBlock {
    public static final EnumProperty<net.minecraft.core.Direction> FACING = HorizontalDirectionalBlock.FACING;

    public WordWallBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WordWallBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, MagicRegistry.WORD_WALL_BE.get(), WordWallBlockEntity::serverTick);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof WordWallBlockEntity be) {
            WordWalls.teach(sp, server, pos, be.shout(level.getRandom()), true);
        }
        return InteractionResult.CONSUME;
    }

    /** Dragon script drifts out of the air into the wall, like an enchanting table. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        Direction facing = state.getValue(FACING);
        for (int i = 0; i < 3; i++) {
            if (random.nextInt(2) != 0) continue;
            double ox = facing.getStepX() * (1.2 + random.nextDouble() * 2) + (random.nextDouble() - 0.5) * 3;
            double oz = facing.getStepZ() * (1.2 + random.nextDouble() * 2) + (random.nextDouble() - 0.5) * 3;
            double oy = random.nextDouble() * 2 - 0.5;
            level.addParticle(ParticleTypes.ENCHANT, pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5, ox, oy, oz);
        }
        if (random.nextInt(6) == 0) {
            double x = pos.getX() + 0.5 + facing.getStepX() * 0.52 + (random.nextDouble() - 0.5) * Math.abs(facing.getStepZ()) * 0.8;
            double z = pos.getZ() + 0.5 + facing.getStepZ() * 0.52 + (random.nextDouble() - 0.5) * Math.abs(facing.getStepX()) * 0.8;
            level.addParticle(ParticleTypes.END_ROD, x, pos.getY() + 0.2 + random.nextDouble() * 0.6, z, 0, 0.01, 0);
        }
    }
}
