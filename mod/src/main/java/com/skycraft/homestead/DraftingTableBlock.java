package com.skycraft.homestead;

import com.skycraft.core.Notifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

public class DraftingTableBlock extends HorizontalDirectionalBlock {
    public DraftingTableBlock() {
        super(BlockBehaviour.Properties.of()
                .setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK,
                        net.minecraft.resources.Identifier.fromNamespaceAndPath(com.skycraft.Skycraft.MODID, "drafting_table")))
                .mapColor(MapColor.WOOD)
                .strength(2.5f)
                .sound(SoundType.WOOD));
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
    protected InteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return use(state, level, pos, player, hand, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return use(state, level, pos, player, InteractionHand.MAIN_HAND, hit);
    }

    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) {
            HomesteadData data = HomesteadData.get(sp.level().getServer());
            HomesteadClaim claim = data.getClaimAt(level, pos);
            if (claim != null) {
                if (claim.isOwner(sp)) {
                    HomesteadMenu.open(sp, claim, pos);
                } else {
                    Notifier.message(sp, Component.translatable("message.skycraft.homestead.owned_by", claim.getOwnerName()));
                    level.playSound(null, pos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8f, 0.9f);
                }
            } else {
                // If claim was missing, attempt re-establishment if allowed
                HomesteadData.Denial reason = data.checkCanClaim(sp, pos);
                if (reason == null) {
                    claim = data.addClaim(sp, pos);
                    HomesteadDiscovery.sync(sp);
                    HomesteadMenu.open(sp, claim, pos);
                } else {
                    Notifier.message(sp, reason.message());
                }
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && player instanceof ServerPlayer sp) {
            HomesteadData data = HomesteadData.get(sp.level().getServer());
            HomesteadClaim claim = data.getClaimAt(level, pos);
            if (claim != null && !claim.isOwner(sp) && !sp.isCreative()) {
                Notifier.message(sp, Component.translatable("message.skycraft.homestead.cannot_destroy", claim.getOwnerName()));
                return state;
            }
            if (claim != null && (claim.isOwner(sp) || sp.isCreative())) {
                data.removeClaimAt(level, pos);
                Notifier.message(sp, Component.translatable("message.skycraft.homestead.dismantled"));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
