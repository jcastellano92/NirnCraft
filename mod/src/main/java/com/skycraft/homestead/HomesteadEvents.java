package com.skycraft.homestead;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class HomesteadEvents {
    private HomesteadEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) return;

        BlockPos pos = event.getPos();
        Level level = player.level();
        HomesteadData data = HomesteadData.get(player.server);
        BlockState placedState = event.getPlacedBlock();

        // 1. Placing the Drafting Table establishing a claim
        if (placedState.getBlock() instanceof DraftingTableBlock) {
            String denyReason = data.checkCanClaim(player, pos);
            if (denyReason != null) {
                event.setCanceled(true);
                Notifier.message(player, Component.translatable(denyReason));
                return;
            }
            // Establish the claim
            data.addClaim(player, pos);
            Notifier.message(player, Component.translatable("message.skycraft.homestead.claimed"));
            level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.8f, 1.0f);
            return;
        }

        // 2. Placing blocks within a homestead claim
        HomesteadClaim claim = data.getClaimHorizontal(level, pos);
        if (claim != null) {
            // Permission check
            if (!claim.canBuild(player) && !player.isCreative()) {
                event.setCanceled(true);
                Notifier.message(player, Component.translatable("message.skycraft.homestead.no_build_permission", claim.getOwnerName()));
                return;
            }

            // Depth limit (foundation limit, e.g. 10 blocks below table)
            if (pos.getY() < claim.getCenter().getY() - claim.getMinDepth()) {
                event.setCanceled(true);
                Notifier.message(player, Component.translatable("message.skycraft.homestead.depth_limit", claim.getMinDepth()));
                return;
            }

            // Height limit
            if (pos.getY() > claim.getCenter().getY() + claim.getMaxHeight()) {
                event.setCanceled(true);
                Notifier.message(player, Component.translatable("message.skycraft.homestead.height_limit", claim.getMaxHeight()));
                return;
            }

            // Whitelist check
            if (!HomesteadBlocks.isAllowedBuildingBlock(placedState) && !player.isCreative()) {
                event.setCanceled(true);
                Notifier.message(player, Component.translatable("message.skycraft.homestead.block_not_allowed"));
                return;
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (player.level().isClientSide() || !(player instanceof ServerPlayer sp)) return;

        BlockPos pos = event.getPos();
        Level level = sp.level();
        HomesteadData data = HomesteadData.get(sp.server);
        BlockState state = event.getState();

        // 1. Breaking the Drafting Table
        if (state.getBlock() instanceof DraftingTableBlock) {
            HomesteadClaim claim = data.getClaimAt(level, pos);
            if (claim != null && !claim.isOwner(sp) && !sp.isCreative()) {
                event.setCanceled(true);
                Notifier.message(sp, Component.translatable("message.skycraft.homestead.cannot_destroy", claim.getOwnerName()));
                return;
            }
            // a house stands on these plans: the table stays (sneak to give up the homestead anyway)
            if (claim != null && !claim.getBuilt().isEmpty() && !sp.isShiftKeyDown()) {
                event.setCanceled(true);
                Notifier.message(sp, Component.translatable("message.skycraft.homestead.table_in_use"));
                return;
            }
            if (claim != null) {
                data.removeClaimAt(level, pos);
                Notifier.message(sp, Component.translatable("message.skycraft.homestead.dismantled"));
            }
            return;
        }

        // 2. Breaking blocks within a homestead claim
        HomesteadClaim claim = data.getClaimHorizontal(level, pos);
        if (claim != null) {
            if (!claim.canBuild(sp) && !sp.isCreative()) {
                event.setCanceled(true);
                Notifier.message(sp, Component.translatable("message.skycraft.homestead.no_build_permission", claim.getOwnerName()));
                return;
            }

            // Depth limit
            if (pos.getY() < claim.getCenter().getY() - claim.getMinDepth()) {
                event.setCanceled(true);
                Notifier.message(sp, Component.translatable("message.skycraft.homestead.depth_limit", claim.getMinDepth()));
                return;
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;

        BlockPos pos = event.getPos();
        HomesteadData data = HomesteadData.get(sp.server);
        HomesteadClaim claim = data.getClaimAt(level, pos);
        if (claim == null) return;

        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        // Doors & Trapdoors & Gates
        if (block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock) {
            if (!claim.canAccessDoor(sp) && !sp.isCreative()) {
                event.setCanceled(true);
                level.playSound(null, pos, SoundEvents.CHEST_LOCKED, SoundSource.BLOCKS, 0.8f, 1.0f);
                Notifier.message(sp, Component.translatable("message.skycraft.homestead.door_locked", claim.getOwnerName()));
                return;
            }
        }

        // Containers (chests, barrels, etc. - not counting bodies)
        if (block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock
                || block instanceof HopperBlock || block instanceof DispenserBlock) {
            if (!claim.canAccessContainer(sp) && !sp.isCreative()) {
                event.setCanceled(true);
                level.playSound(null, pos, SoundEvents.CHEST_LOCKED, SoundSource.BLOCKS, 0.8f, 1.0f);
                Notifier.message(sp, Component.translatable("message.skycraft.homestead.container_locked", claim.getOwnerName()));
                return;
            }
        }
    }
}
