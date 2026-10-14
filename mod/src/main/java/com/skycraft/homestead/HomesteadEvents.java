package com.skycraft.homestead;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The homestead's own rules: founding (placing the drafting table), building, build mode (picking up stations and
 * containers) and who may use doors, containers, crafting stations and beds. Breaking and placing by strangers is
 * refused first by {@code com.skycraft.protect} and the dig module; this handler adds the homestead-specific limits.
 */
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
            HomesteadData.Denial deny = data.checkCanClaim(player, pos);
            if (deny != null) {
                event.setCanceled(true);
                Notifier.message(player, deny.message());
                return;
            }
            // Establish the claim
            data.addClaim(player, pos);
            Notifier.message(player, Component.translatable("message.skycraft.homestead.claimed"));
            level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.8f, 1.0f);
            HomesteadDiscovery.sync(player);
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

            // Griefing blocks (TNT, spawners, portals...) are never placed
            if (!HomesteadBlocks.isAllowedBuildingBlock(placedState) && !player.isCreative()) {
                event.setCanceled(true);
                Notifier.message(player, Component.translatable("message.skycraft.homestead.block_not_allowed"));
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
                HomesteadDiscovery.forget(sp.server, claim);
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

            // 3. Build mode: stations and containers are carried, never broken (their contents travel with them)
            if (!sp.isCreative() && level instanceof ServerLevel sl && HomesteadBuildMode.isMovable(level, pos, state)) {
                event.setCanceled(true);
                if (HomesteadBuildMode.isOn(sp) && claim.getOwnerId().equals(sp.getUUID())) {
                    HomesteadBuildMode.pickUp(sp, sl, pos, state);
                } else {
                    Notifier.message(sp, Component.translatable("message.skycraft.homestead.needs_build_mode"));
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;

        BlockPos clicked = event.getPos();
        HomesteadData data = HomesteadData.get(sp.server);
        HomesteadClaim claim = data.getClaimHorizontal(level, clicked);
        if (claim == null) return;

        BlockState state = level.getBlockState(clicked);

        // the drafting table: sneak and use with empty hands toggles build mode (the owner's)
        if (state.getBlock() instanceof DraftingTableBlock) {
            if (sp.isShiftKeyDown() && sp.getMainHandItem().isEmpty() && claim.getOwnerId().equals(sp.getUUID())) {
                event.setCanceled(true);
                if (event.getHand() == InteractionHand.MAIN_HAND) HomesteadBuildMode.toggle(sp, claim);
            }
            return;
        }

        HomesteadClaim.Access kind = HomesteadAccess.classify(level, clicked, state);
        if (kind == null) return;
        BlockPos pos = HomesteadAccess.canonical(clicked, state);

        // build mode: sneak and use with empty hands sets this door, container, station or bed's own level
        if (HomesteadBuildMode.isOn(sp) && claim.getOwnerId().equals(sp.getUUID()) && sp.isShiftKeyDown() && sp.getMainHandItem().isEmpty()) {
            event.setCanceled(true);
            if (event.getHand() == InteractionHand.MAIN_HAND) {
                int now = claim.cycleOverride(pos);
                HomesteadData.get(sp.server).setDirty();
                String who = now < 0 ? "default" : now == HomesteadClaim.PERM_OWNER ? "owner" : now == HomesteadClaim.PERM_PARTY ? "party" : "public";
                Notifier.message(sp, Component.translatable("message.skycraft.homestead.access_set",
                        Component.translatable("homestead.skycraft.access." + kind.name().toLowerCase(java.util.Locale.ROOT)),
                        Component.translatable("homestead.skycraft.perm." + who)));
            }
            return;
        }

        if (sp.isCreative() || claim.mayUse(sp, kind, pos)) return;
        event.setCanceled(true);
        level.playSound(null, clicked, SoundEvents.CHEST_LOCKED, SoundSource.BLOCKS, 0.8f, 1.0f);
        Notifier.message(sp, Component.translatable(HomesteadAccess.lockedKey(kind), claim.getOwnerName()));
    }
}
