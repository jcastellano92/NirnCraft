package com.skycraft.inventory.client;

import com.skycraft.Skycraft;
import com.skycraft.client.ClientPacketHandlers;
import com.skycraft.inventory.InventoryConfig;
import com.skycraft.inventory.ItemWeights;
import com.skycraft.network.CorePackets;
import com.skycraft.network.NotifyKind;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Forge-bus client events of the inventory module: swapping the vanilla survival inventory for the Skyrim one, the
 * favorites key and number keys, and item weights in tooltips (over-encumbered sprint blocking: core ClientEvents).
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class InventoryClientEvents {
    /** Class of the world module's map screen (fast travel), matched by name to avoid a hard dependency. */
    private static final String MAP_SCREEN = "com.skycraft.world.client.MapScreen";

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
                event.setNewScreen(new com.skycraft.client.screen.HubScreen());
            }
            return;
        }

        if (next instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> acs
                && acs.getMenu() instanceof net.minecraft.world.inventory.ChestMenu cm) {
            if (!player.isCreative() && !player.isSpectator()) {
                event.setNewScreen(new SkyrimContainerScreen(cm, player.getInventory(), acs.getTitle()));
                return;
            }
        }

        // The world map is where fast travel happens: remind an over-encumbered player they can't.
        if (next.getClass().getName().equals(MAP_SCREEN) && ClientInventoryHandlers.over && !player.isCreative()) {
            ClientPacketHandlers.notify(new CorePackets.Notify(NotifyKind.MESSAGE,
                    Component.translatable("inventory.skycraft.overencumbered_travel"), Component.empty(), 0, 0f));
        }
    }

    /** A way back from the vanilla inventory to the Skyrim one. */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen().getClass() != InventoryScreen.class || !replaceInventory()) return;
        Minecraft mc = Minecraft.getInstance();
        Component label = Component.translatable("inventory.skycraft.open_skyrim");
        int w = mc.font.width(label) + 16;
        event.addListener(Button.builder(label, b -> mc.setScreen(new SkyrimInventoryScreen())).bounds(4, 4, w, 16).build());
    }

    // ------------------------------------------------------------------ keys & sprinting

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        // Universal [F] Key for Interactions (talk to NPCs, pick up items, open doors, harvest plants)
        while (com.skycraft.client.SkyKeys.INTERACT.consumeClick()) {
            if (mc.screen != null || player.isSpectator()) continue;
            // 1. Pick up looked-at world item
            var lookedItem = com.skycraft.crime.client.CrimeHud.getLookedAtItem(mc, 3.5);
            if (lookedItem != null) {
                com.skycraft.network.SkyNetwork.sendToServer(new CorePackets.Action(CorePackets.Action.TAKE_WORLD_ITEM, lookedItem.getId()));
                continue;
            }
            // 2. Interact with entity under crosshair (NPC, villager, horse, corpse)
            if (mc.crosshairPickEntity != null) {
                if (mc.crosshairPickEntity instanceof com.skycraft.creatures.entity.CorpseEntity corpse) {
                    if (mc.gameMode != null) {
                        mc.gameMode.interact(player, corpse, net.minecraft.world.InteractionHand.MAIN_HAND);
                    }
                    continue;
                }
                if (mc.crosshairPickEntity instanceof net.minecraft.world.entity.LivingEntity le && le.isAlive()) {
                    if (mc.gameMode != null) {
                        mc.gameMode.interact(player, le, net.minecraft.world.InteractionHand.MAIN_HAND);
                    }
                    continue;
                }
            }
            // 3. Interact with block under crosshair (door, chest, plant, flower, mushroom, crafting table)
            if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                net.minecraft.core.BlockPos bpos = bhr.getBlockPos();
                net.minecraft.world.level.block.state.BlockState bs = player.level().getBlockState(bpos);
                if (com.skycraft.survival.Harvesting.isHarvestable(bs)) {
                    com.skycraft.network.SkyNetwork.sendToServer(new CorePackets.HarvestBlock(bpos));
                    continue;
                }
                if (mc.gameMode != null) {
                    mc.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, bhr);
                }
            }
        }

        while (InventoryKeys.FAVORITES.consumeClick()) {
            if (mc.screen == null && !player.isSpectator()) mc.setScreen(new FavoritesScreen());
        }
        // Over-encumbered: no running. The client-side sprint block lives in ClientEvents (with exhaustion).
    }

    /**
     * Favorites hotbar mapping: number keys 1-8 equip Favorites 1-8. Runs before vanilla handles the keys (tick START)
     * and only takes a key press if that favorite exists; otherwise the number key selects the hotbar slot as usual.
     * Key 9 is never touched.
     */
    @SubscribeEvent
    public static void onClientTickStart(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.screen != null || mc.options == null || player.isCreative() || player.isSpectator()) return;
        List<FavoritesScreen.Row> favs = null;
        for (int i = 0; i < Math.min(8, mc.options.keyHotbarSlots.length); i++) {
            while (mc.options.keyHotbarSlots[i].consumeClick()) {
                if (favs == null) favs = getFavorites(player);
                if (i < favs.size()) {
                    if (com.skycraft.combat.Sheathe.isSheathed(player)) {
                        com.skycraft.network.SkyNetwork.sendToServer(new CorePackets.Action(CorePackets.Action.SHEATHE_TOGGLE, 0));
                    }
                    FavoritesScreen.activate(favs.get(i), false);
                } else {
                    player.getInventory().selected = i; // what vanilla would have done with this press
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
    public static void onMouseScroll(net.minecraftforge.client.event.InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.screen != null || player.isCreative() || player.isSpectator()) return;
        double delta = event.getScrollDelta();
        if (delta == 0) return;

        // The wheel only draws and sheathes (Skyrim): up draws the weapon, down puts it away. It never cycles the
        // favorites (their number keys and the quick menu do that) and no longer scrolls the hotbar.
        event.setCanceled(true);
        boolean sheathed = com.skycraft.combat.Sheathe.isSheathed(player);
        if (delta < 0 && !sheathed) {
            if (!player.getMainHandItem().isEmpty()) lastDrawnMainSlot = player.getInventory().selected;
            com.skycraft.combat.Sheathe.setSheathed(player, true);
            com.skycraft.network.SkyNetwork.sendToServer(new CorePackets.Action(CorePackets.Action.SHEATHE_TOGGLE, 0));
            for (int i = 0; i < 9; i++) {
                if (player.getInventory().getItem(i).isEmpty()) {
                    player.getInventory().selected = i;
                    break;
                }
            }
            ClientPacketHandlers.notify(new CorePackets.Notify(NotifyKind.MESSAGE, Component.literal("Hands Sheathed"), Component.empty(), 0, 0f));
        } else if (delta > 0 && sheathed) {
            com.skycraft.combat.Sheathe.setSheathed(player, false);
            com.skycraft.network.SkyNetwork.sendToServer(new CorePackets.Action(CorePackets.Action.SHEATHE_TOGGLE, 0));
            if (lastDrawnMainSlot >= 0 && lastDrawnMainSlot < 9 && !player.getInventory().getItem(lastDrawnMainSlot).isEmpty()) {
                player.getInventory().selected = lastDrawnMainSlot;
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
        event.getToolTip().add(Component.translatable("inventory.skycraft.tooltip.weight", ItemWeights.format(weight)).withStyle(ChatFormatting.GRAY));
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientInventoryHandlers.reset();
        vanillaOnce = false;
    }
}
