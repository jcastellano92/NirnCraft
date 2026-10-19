package com.nirncraft.inventory.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.client.ClientPacketHandlers;
import com.nirncraft.inventory.InventoryConfig;
import com.nirncraft.inventory.ItemWeights;
import com.nirncraft.network.CorePackets;
import com.nirncraft.network.NotifyKind;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.List;

/**
 * Forge-bus client events of the inventory module: swapping the vanilla survival inventory and every vanilla menu screen
 * for the Skyrim ones ({@link MenuSkins}), the favorites key and number keys, and item weights in tooltips
 * (over-encumbered sprint blocking: core ClientEvents).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class InventoryClientEvents {
    /** Class of the world module's map screen (fast travel), matched by name to avoid a hard dependency. */
    private static final String MAP_SCREEN = "com.nirncraft.world.client.MapScreen";

    /** One-shot: the next vanilla InventoryScreen is wanted (opened from the Skyrim inventory's Crafting button). */
    private static boolean vanillaOnce;

    private InventoryClientEvents() {}

    static void allowVanillaOnce() {
        vanillaOnce = true;
    }

    private static boolean replaceInventory() {
        try {
            return InventoryConfig.REPLACE_INVENTORY.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    // ------------------------------------------------------------------ screens

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || next == null) return;

        if (next.getClass() == InventoryScreen.class) {
            if (!player.isCreative() && !player.isSpectator()) {
                event.setNewScreen(new com.nirncraft.client.screen.HubScreen());
            }
            return;
        }

        // chests, barrels, ender chests, shulker boxes, hoppers, dispensers/droppers, corpses, holds, saddlebags -> the
        // container list; furnaces, brewing stand, anvil, grindstone, smithing table, loom, stonecutter, cartography
        // table, enchanting table, beacon, crafting table, trader -> the machine screen; lectern -> the parchment
        if (next instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
            Screen skinned = MenuSkins.replace(next, player);
            if (skinned != next) {
                event.setNewScreen(skinned);
                return;
            }
        }

        // The world map is where fast travel happens: remind an over-encumbered player they can't.
        if (next.getClass().getName().equals(MAP_SCREEN) && ClientInventoryHandlers.over && !player.isCreative()) {
            ClientPacketHandlers.notify(new CorePackets.Notify(NotifyKind.MESSAGE,
                    Component.translatable("inventory.nirncraft.overencumbered_travel"), Component.empty(), 0, 0f));
        }
    }

    /** A way back from the vanilla inventory to the Skyrim one. */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen().getClass() != InventoryScreen.class || !replaceInventory()) return;
        Minecraft mc = Minecraft.getInstance();
        Component label = Component.translatable("inventory.nirncraft.open_skyrim");
        int w = mc.font.width(label) + 16;
        event.addListener(Button.builder(label, b -> mc.gui.setScreen(new SkyrimInventoryScreen())).bounds(4, 4, w, 16).build());
    }

    // ------------------------------------------------------------------ keys & sprinting

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        // Universal [F] Key for Interactions (talk to NPCs, pick up items, open doors, harvest plants)
        while (com.nirncraft.client.SkyKeys.INTERACT.consumeClick()) {
            if (mc.gui.screen() != null || player.isSpectator()) continue;
            // 1. Pick up looked-at world item
            var lookedItem = com.nirncraft.crime.client.CrimeHud.getLookedAtItem(mc, 3.5);
            if (lookedItem != null) {
                com.nirncraft.network.SkyNetwork.sendToServer(new CorePackets.Action(CorePackets.Action.TAKE_WORLD_ITEM, lookedItem.getId()));
                continue;
            }
            // 2. Interact with entity under crosshair (NPC, villager, horse, corpse)
            if (mc.crosshairPickEntity != null) {
                if (mc.crosshairPickEntity instanceof com.nirncraft.creatures.entity.CorpseEntity corpse) {
                    if (mc.gameMode != null) {
                        mc.gameMode.interact(player, corpse, mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit && hit.getEntity()==corpse?hit:new net.minecraft.world.phys.EntityHitResult(corpse), net.minecraft.world.InteractionHand.MAIN_HAND);
                    }
                    continue;
                }
                if (mc.crosshairPickEntity instanceof net.minecraft.world.entity.LivingEntity le && le.isAlive()) {
                    if (mc.gameMode != null) {
                        mc.gameMode.interact(player, le, mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit && hit.getEntity()==le?hit:new net.minecraft.world.phys.EntityHitResult(le), net.minecraft.world.InteractionHand.MAIN_HAND);
                    }
                    continue;
                }
            }
            // 3. Interact with block under crosshair (door, chest, plant, flower, mushroom, crafting table)
            if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                net.minecraft.core.BlockPos bpos = bhr.getBlockPos();
                net.minecraft.world.level.block.state.BlockState bs = player.level().getBlockState(bpos);
                if (com.nirncraft.survival.Harvesting.isHarvestable(bs)) {
                    com.nirncraft.network.SkyNetwork.sendToServer(new CorePackets.HarvestBlock(bpos));
                    continue;
                }
                if (mc.gameMode != null) {
                    mc.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, bhr);
                }
            }
        }

        while (InventoryKeys.FAVORITES.consumeClick()) {
            if (mc.gui.screen() == null && !player.isSpectator()) mc.gui.setScreen(new FavoritesScreen());
        }
        // Over-encumbered: no running. The client-side sprint block lives in ClientEvents (with exhaustion).
    }

    /**
     * Favorites hotbar mapping: number keys 1-8 equip Favorites 1-8. Runs before vanilla handles the keys (tick START)
     * and only takes a key press if that favorite exists; otherwise the number key selects the hotbar slot as usual.
     * Key 9 is never touched.
     */
    @SubscribeEvent
    public static void onClientTickStart(net.neoforged.neoforge.client.event.ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gui.screen() != null || mc.options == null || player.isCreative() || player.isSpectator()) return;
        List<FavoritesScreen.Row> favs = null;
        for (int i = 0; i < Math.min(8, mc.options.keyHotbarSlots.length); i++) {
            while (mc.options.keyHotbarSlots[i].consumeClick()) {
                if (favs == null) favs = getFavorites(player);
                if (i < favs.size()) {
                    if (com.nirncraft.combat.Sheathe.isSheathed(player)) {
                        com.nirncraft.network.SkyNetwork.sendToServer(new CorePackets.Action(CorePackets.Action.SHEATHE_TOGGLE, 0));
                    }
                    FavoritesScreen.activate(favs.get(i), false);
                } else {
                    player.getInventory().setSelectedSlot(i); // what vanilla would have done with this press
                }
            }
        }
    }

    private static int scrollFavIndex = -1;

    /** Favorite items, spells, shouts and powers, in the order the quick menu lists them. */
    public static List<FavoritesScreen.Row> getFavorites(LocalPlayer player) {
        return FavoritesScreen.rows(player);
    }

    private static int lastDrawnMainSlot = 0;

    @SubscribeEvent
    public static void onMouseScroll(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gui.screen() != null || player.isCreative() || player.isSpectator()) return;
        double delta = event.getScrollDeltaY();
        if (delta == 0) return;

        // The wheel only draws and sheathes (Skyrim): up draws the weapon, down puts it away. It never cycles the
        // favorites (their number keys and the quick menu do that) and no longer scrolls the hotbar.
        event.setCanceled(true);
        boolean sheathed = com.nirncraft.combat.Sheathe.isSheathed(player);
        if (delta < 0 && !sheathed) {
            if (!player.getMainHandItem().isEmpty()) lastDrawnMainSlot = player.getInventory().getSelectedSlot();
            com.nirncraft.combat.Sheathe.setSheathed(player, true);
            com.nirncraft.network.SkyNetwork.sendToServer(new CorePackets.Action(CorePackets.Action.SHEATHE_TOGGLE, 0));
            for (int i = 0; i < 9; i++) {
                if (player.getInventory().getItem(i).isEmpty()) {
                    player.getInventory().setSelectedSlot(i);
                    break;
                }
            }
            ClientPacketHandlers.notify(new CorePackets.Notify(NotifyKind.MESSAGE, Component.literal("Hands Sheathed"), Component.empty(), 0, 0f));
        } else if (delta > 0 && sheathed) {
            com.nirncraft.combat.Sheathe.setSheathed(player, false);
            com.nirncraft.network.SkyNetwork.sendToServer(new CorePackets.Action(CorePackets.Action.SHEATHE_TOGGLE, 0));
            if (lastDrawnMainSlot >= 0 && lastDrawnMainSlot < 9 && !player.getInventory().getItem(lastDrawnMainSlot).isEmpty()) {
                player.getInventory().setSelectedSlot(lastDrawnMainSlot);
            }
        }
    }


    // ------------------------------------------------------------------ misc

    /** Skyrim shows every item's weight. */
    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        float weight = ItemWeights.get(stack);
        if (weight <= 0f) return;
        event.getToolTip().add(Component.translatable("inventory.nirncraft.tooltip.weight", ItemWeights.format(weight)).withStyle(ChatFormatting.GRAY));
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientInventoryHandlers.reset();
        vanillaOnce = false;
    }
}
