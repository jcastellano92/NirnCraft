package com.skycraft.mounts.client;

import com.skycraft.Skycraft;
import com.skycraft.core.HudStack;
import com.skycraft.mounts.MountPackets;
import com.skycraft.network.SkyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The rider's side of the mount system: sends the gallop (sprint key) state while riding, and draws the mount's
 * stamina bar (green, amber while galloping, red when exhausted) and health bar over the hotbar.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
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
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        boolean want = mc.player.isPassenger() && mc.options.keySprint.isDown() && mc.screen == null;
        if (want != sentGallop) {
            sentGallop = want;
            SkyNetwork.sendToServer(new MountPackets.Gallop(want));
        }
    }

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        // drawn after the whole HUD (Skycraft hides the vanilla hotbar overlay, so not attached to it)
        Minecraft mc = Minecraft.getInstance();
        MountPackets.MountHud hud = last;
        if (mc.player == null || hud == null || !mc.player.isPassenger() || System.currentTimeMillis() - lastAt > 1500 || mc.options.hideGui) return;
        GuiGraphics g = event.getGuiGraphics();
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        // bottom-centre stack above the health bar, below the survival meters (they used to share this row)
        int barW = 120, x = w / 2 - barW / 2, y = HudStack.reserve(HudStack.Zone.BOTTOM_CENTER, HudStack.BC_MOUNT, 10, h) + 1;
        float st = hud.max() <= 0 ? 0 : Math.max(0, Math.min(1, hud.stamina() / hud.max()));
        float hp = hud.maxHealth() <= 0 ? 0 : Math.max(0, Math.min(1, hud.health() / hud.maxHealth()));
        int stColor = hud.exhausted() ? 0xFFC03A2B : hud.gallop() ? 0xFFE0A030 : 0xFF5FB04A;
        // stamina
        g.fill(x - 1, y - 1, x + barW + 1, y + 3, 0xA0000000);
        g.fill(x, y, x + (int) (barW * st), y + 2, stColor);
        // health
        g.fill(x - 1, y + 4, x + barW + 1, y + 8, 0xA0000000);
        g.fill(x, y + 5, x + (int) (barW * hp), y + 7, 0xFFB02828);
    }
}
