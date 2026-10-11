package com.skycraft.survival.client;

import com.skycraft.inventory.client.SkyUi;
import com.skycraft.survival.SurvivalPackets;
import com.skycraft.survival.fishing.SkyFishingHook;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.client.gui.overlay.ForgeGui;

/**
 * The fishing minigame's reel bar (below the crosshair) while a fish has taken the bait: a marker sweeps across the
 * bar and you reel in (use the rod) while it is inside the gold catch zone. Then the result for a moment. While the
 * bobber floats with nothing biting, a faint "waiting for a bite" reminds you to be patient.
 */
public final class FishingHud {
    private static final int BAR_W = 150;

    private static long biteStart = -1;
    private static int zoneStart, zoneEnd, period, window;
    private static Component result = Component.empty();
    private static int resultColor;
    private static long resultUntil;

    private FishingHud() {}

    public static void receive(SurvivalPackets.Bite m) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (m.phase() == 1) {
            biteStart = mc.level.getGameTime();
            zoneStart = m.zoneStart();
            zoneEnd = m.zoneEnd();
            period = Math.max(1, m.periodTicks());
            window = m.windowTicks();
            resultUntil = 0;
            return;
        }
        biteStart = -1;
        switch (m.result()) {
            case 1 -> {
                result = Component.translatable("fishing.skycraft.caught", Component.translatable(m.fish()));
                resultColor = 0xFFE8C060;
            }
            case 2 -> {
                result = Component.translatable("fishing.skycraft.too_early");
                resultColor = 0xFFD07060;
            }
            case 3 -> {
                result = Component.translatable("fishing.skycraft.too_late");
                resultColor = 0xFFD07060;
            }
            default -> {
                result = Component.translatable("fishing.skycraft.slipped");
                resultColor = 0xFFBDB59E;
            }
        }
        resultUntil = Util.getMillis() + 2500;
    }

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui || mc.screen != null) return;
        Font font = mc.font;
        int cx = width / 2;
        int y = height / 2 + 34;
        long nowMs = Util.getMillis();
        if (biteStart >= 0) {
            float elapsed = mc.level.getGameTime() - biteStart + partialTick;
            if (elapsed > window + 10 || mc.player.fishing == null) {
                biteStart = -1;
            } else {
                float a = Mth.clamp(elapsed / 4f, 0f, 1f);
                SkyUi.scaledCentered(g, font, SkyUi.caps(Component.translatable("fishing.skycraft.reel_in")), cx, y - 14, 1.0f,
                        SkyUi.fade(0xFFF5EBC8, a), true);
                int x = cx - BAR_W / 2;
                SkyUi.ornateBar(g, x, y, BAR_W, 5, 0f, 0x000000, a, false, false);
                int zs = x + zoneStart * BAR_W / 1000;
                int ze = x + zoneEnd * BAR_W / 1000;
                int ia = (int) (a * 255) << 24;
                g.fill(zs, y, ze, y + 5, ia | 0xB89038);
                g.fill(zs, y, ze, y + 1, ia | 0xF0D070);
                float m = SkyFishingHook.markerAt(elapsed, period);
                int mx = x + Math.round(m * BAR_W / 1000f);
                boolean inside = m >= zoneStart && m <= zoneEnd;
                g.fill(mx - 1, y - 3, mx + 1, y + 8, ia | (inside ? 0xFFFFFF : 0xD8D0B8));
                // time left: a thin line under the bar
                float left = 1f - Mth.clamp(elapsed / window, 0f, 1f);
                g.fill(cx - Math.round(BAR_W / 2f * left), y + 8, cx + Math.round(BAR_W / 2f * left), y + 9, ia | 0x8A7F66);
                return;
            }
        }
        if (nowMs < resultUntil) {
            float a = Mth.clamp((resultUntil - nowMs) / 500f, 0f, 1f);
            SkyUi.scaledCentered(g, font, result.getString(), cx, y - 6, 1.0f, SkyUi.fade(resultColor, a), true);
            return;
        }
        if (mc.player.fishing instanceof SkyFishingHook hook && hook.isInWater()) {
            SkyUi.scaledCentered(g, font, Component.translatable("fishing.skycraft.waiting").getString(), cx, y - 6, 0.8f, 0x90BDB59E, true);
        }
    }
}
