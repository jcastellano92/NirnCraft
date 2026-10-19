package com.nirncraft.inventory;

import com.nirncraft.NirnCraft;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Forge-bus events of the inventory module: loading and syncing item weights. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class InventoryEvents {
    private InventoryEvents() {}

    @SubscribeEvent
    public static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(net.minecraft.resources.Identifier.fromNamespaceAndPath(NirnCraft.MODID, "item_weights"), new ItemWeights.Loader());
    }

    /** Fired when a player joins (player set) and after /reload (player null = everyone). */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        InventoryPackets.SyncWeights msg = InventoryPackets.SyncWeights.current();
        ServerPlayer target = event.getPlayer();
        if (target != null) {
            SkyNetwork.sendToPlayer(target, msg);
            CarryWeight.invalidate(target);
        } else {
            for (ServerPlayer p : event.getPlayerList().getPlayers()) {
                SkyNetwork.sendToPlayer(p, msg);
                CarryWeight.invalidate(p);
            }
        }
    }

    /**
     * Convert any coin purse or septims sitting in player inventory directly into gold (every 10 ticks). Not while a
     * container is open: coins taken from a chest stay coins until the menu closes, so theft detection (crime module)
     * sees them leave the container as items.
     */
    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (event.getEntity().level().isClientSide()) return;
        if (event.getEntity().tickCount % 10 != 0 || event.getEntity().containerMenu != event.getEntity().inventoryMenu) return;
        convertCoins(event.getEntity());
    }

    @SubscribeEvent
    public static void onContainerClose(net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Close event) {
        if (!event.getEntity().level().isClientSide()) convertCoins(event.getEntity());
    }

    private static void convertCoins(net.minecraft.world.entity.player.Player player) {
        var inv = player.getInventory();
        long totalFound = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = inv.getItem(i);
            long val = com.nirncraft.core.Currency.valueOf(stack);
            if (val > 0) {
                totalFound += val;
                inv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
            }
        }
        totalFound += Pack.extractCoins(player); // coins that overflowed into the pack
        if (totalFound > 0) {
            // Imperial luck: Imperials find more gold
            com.nirncraft.core.Race race = com.nirncraft.core.SkyData.get(player).getRace();
            if (race != null && race.goldFind() > 1f) totalFound = Math.round(totalFound * (double) race.goldFind());
            com.nirncraft.core.Currency.give(player, totalFound);
            if (player instanceof ServerPlayer sp) {
                sp.containerMenu.broadcastChanges();
                Pack.sync(sp);
            }
        }
    }

    /** Item tags were (re)bound on this side: tag-based weights must be resolved again. */
    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        ItemWeights.clearCache();
    }
}
