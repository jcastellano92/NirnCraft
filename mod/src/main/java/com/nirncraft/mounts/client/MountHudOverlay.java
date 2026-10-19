package com.nirncraft.mounts.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.HudStack;
import com.nirncraft.mounts.MountPackets;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.HorseInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.HasCustomInventoryScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * The rider's side of the mount system: sends the gallop (sprint key) state while riding, keeps the vanilla horse
 * screen from ever opening (the inventory key while mounted opens the Skyrim inventory like anywhere else; your mount's
 * gear and saddlebags are the container screen of sneak-using it), and draws a thin health bar for the mount when it is
 * hurt. There is no mount stamina bar: while you steer a mount the stamina bar of the HUD is its stamina
 * ({@code MountStamina}).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class MountHudOverlay {
    private static MountPackets.MountHud last;
    private static long lastAt;
    private static boolean sentGallop;

    private MountHudOverlay() {}

    public static void update(MountPackets.MountHud hud) {
        last = hud;
        lastAt = System.currentTimeMillis();
    }

    @SubscribeEvent
    public static void onClientTickPre(net.neoforged.neoforge.client.event.ClientTickEvent.Pre event) { onClientTick(event); }

    @SubscribeEvent
    public static void onClientTickPost(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) { onClientTick(event); }

    private static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (event instanceof net.neoforged.neoforge.client.event.ClientTickEvent.Pre) {
            // before the key handling of this tick: the inventory key opens the normal inventory, not the horse's
            if (mc.gui.screen() == null && mc.player.getVehicle() instanceof HasCustomInventoryScreen
                    && !mc.player.isCreative() && !mc.player.isSpectator()) {
                while (mc.options.keyInventory.consumeClick()) mc.gui.setScreen(new InventoryScreen(mc.player));
            }
            return;
        }
        boolean want = mc.player.isPassenger() && mc.options.keySprint.isDown() && mc.gui.screen() == null;
        if (want != sentGallop) {
            sentGallop = want;
            SkyNetwork.sendToServer(new MountPackets.Gallop(want));
        }
    }

    /** The server opens the horse screen on some paths (mod interactions): there is none, the server closes it too. */
    @SubscribeEvent
    public static void onScreen(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof HorseInventoryScreen) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        MountPackets.MountHud hud = last;
        if (mc.player == null || hud == null || !mc.player.isPassenger() || System.currentTimeMillis() - lastAt > 60000 || mc.gui.hud.isHidden()
                || mc.gui.screen() != null || hud.maxHealth() <= 0 || hud.health() >= hud.maxHealth()) return;
        GuiGraphicsExtractor g = event.getGuiGraphics();
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        // bottom-centre stack above the health bar, below the survival meters (they used to share this row)
        int barW = 60, x = w / 2 - barW / 2, y = HudStack.reserve(HudStack.Zone.BOTTOM_CENTER, HudStack.BC_MOUNT, 10, h) + 1;
        float hp = Math.max(0, Math.min(1, hud.health() / hud.maxHealth()));
        g.fill(x - 1, y - 1, x + barW + 1, y + 3, 0xA0000000);
        g.fill(x, y, x + (int) (barW * hp), y + 2, 0xFFB02828);
    }
}
