package com.skycraft.crafting.block;

import com.skycraft.crafting.StationType;
import com.skycraft.crafting.menu.StationMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/** A Skyrim crafting station (forge, smelter, tanning rack, grindstone, armor workbench). Right-click to use. */
public class StationBlock extends HorizontalDirectionalBlock {
    public final StationType type;

    public StationBlock(StationType type, Properties props) {
        super(props);
        this.type = type;
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
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) {
            if (com.skycraft.crime.Ownership.isOwnedByOther(sp, level, pos) && !com.skycraft.economy.Shop.isOpen(level)) {
                com.skycraft.core.Notifier.message(sp, net.minecraft.network.chat.Component.translatable("dialogue.skycraft.economy.closed"));
                level.playSound(null, pos, net.minecraft.sounds.SoundEvents.CHEST_LOCKED, net.minecraft.sounds.SoundSource.BLOCKS, 0.8f, 1.0f);
                return InteractionResult.CONSUME;
            }
            StationMenu.open(sp, type, pos);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (type != StationType.FORGE && type != StationType.SMELTER) return;
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 1.0;
        double z = pos.getZ() + 0.5;
        if (random.nextInt(3) == 0) {
            level.addParticle(ParticleTypes.SMOKE, x + (random.nextDouble() - 0.5) * 0.6, y, z + (random.nextDouble() - 0.5) * 0.6, 0, 0.03, 0);
        }
        Direction facing = state.getValue(FACING);
        if (random.nextInt(4) == 0) {
            double fx = x + facing.getStepX() * 0.52 + (random.nextDouble() - 0.5) * 0.4;
            double fz = z + facing.getStepZ() * 0.52 + (random.nextDouble() - 0.5) * 0.4;
            level.addParticle(ParticleTypes.FLAME, fx, pos.getY() + 0.3 + random.nextDouble() * 0.3, fz, 0, 0, 0);
        }
    }
}
