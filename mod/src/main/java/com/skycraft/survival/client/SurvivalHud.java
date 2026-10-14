package com.skycraft.survival.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.skycraft.Skycraft;
import com.skycraft.core.HudStack;
import com.skycraft.inventory.client.SkyUi;
import com.skycraft.survival.SurvivalPackets;
import com.skycraft.survival.mode.Climate;
import com.skycraft.survival.mode.SurvivalMode;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.client.gui.overlay.ForgeGui;

/**
 * Survival Mode meters in the Skyrim HUD style: three small ornate bars right above the health bar (hunger, fatigue,
 * cold; full means well fed, rested and warm, and like Skyrim's bars they shrink toward the middle), each with its
 * icon. They fade in when a meter leaves its best stage and out again when all three are fine; the stage name shows
 * for a few seconds after it changes. Freezing frosts the screen edges; a flame shows while a fire warms you.
 */
public final class SurvivalHud {
    private static final ResourceLocation ICONS = new ResourceLocation(Skycraft.MODID, "textures/gui/survival/icons.png");
    private static final ResourceLocation FROST = new ResourceLocation("textures/misc/powder_snow_outline.png");
    private static final int HUNGER = 0xC8862A, FATIGUE = 0x8C78C8, COLD = 0x6FB8E8;
    private static final int BAR_W = 34;
    private static final long LABEL_MS = 4000;

    private static float alpha;
    private static long lastMs = Util.getMillis();

    private SurvivalHud() {}

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !gui.shouldDrawSurvivalElements() || !ClientSurvival.active()) {
            alpha = 0f;
            return;
        }
        int hs = SurvivalMode.hungerStage(ClientSurvival.hunger);
        int fs = SurvivalMode.fatigueStage(ClientSurvival.fatigue);
        int cs = SurvivalMode.coldStage(ClientSurvival.cold);
        long now = Util.getMillis();
        boolean recent = now - Math.max(ClientSurvival.hungerChanged, Math.max(ClientSurvival.fatigueChanged, ClientSurvival.coldChanged)) < LABEL_MS;
        boolean show = hs > 0 || fs > 0 || cs > 0 || ClientSurvival.trend == 1 || recent;
        float step = 1f - (float) Math.exp(-5.0 * Mth.clamp(now - lastMs, 0, 250) / 1000.0);
        lastMs = now;
        alpha += ((show ? 1f : 0f) - alpha) * step;

        frost(g, width, height, cs, now);
        if (alpha < 0.02f || mc.screen != null) return;

        Font font = mc.font;
        int total = 3 * (BAR_W + 12) + 2 * 8;
        int x = width / 2 - total / 2;
        // one 18 px slot (stage label above the icons and bars) in the bottom-centre stack above the health bar
        int y = HudStack.reserve(HudStack.Zone.BOTTOM_CENTER, HudStack.BC_SURVIVAL, 18, height) + 10;
        meter(g, font, x, y, 0, 1f - ClientSurvival.hunger / SurvivalMode.MAX_HUNGER, HUNGER, hs,
                ClientSurvival.has(SurvivalPackets.Meters.WELL_FED) && hs == 0 ? "survival.skycraft.hunger.well_fed" : "survival.skycraft.hunger.stage." + hs,
                ClientSurvival.hungerChanged, now);
        x += BAR_W + 12 + 8;
        meter(g, font, x, y, 1, 1f - ClientSurvival.fatigue / SurvivalMode.MAX_FATIGUE, FATIGUE, fs,
                "survival.skycraft.fatigue.stage." + fs, ClientSurvival.fatigueChanged, now);
        x += BAR_W + 12 + 8;
        boolean heat = ClientSurvival.has(SurvivalPackets.Meters.HEAT);
        meter(g, font, x, y, heat ? 3 : ClientSurvival.has(SurvivalPackets.Meters.WET) ? 4 : 2,
                1f - ClientSurvival.cold / Climate.MAX, COLD, cs,
                heat && cs == 0 ? "survival.skycraft.cold.warm" : "survival.skycraft.cold.stage." + cs, ClientSurvival.coldChanged, now);
        // the cold trend: a small arrow under the cold bar
        if (ClientSurvival.trend != 0) {
            int ax = x + 12 + BAR_W / 2;
            int col = SkyUi.fade(ClientSurvival.trend == 1 ? 0xFF9FD8FF : 0xFFFFB060, alpha);
            SkyUi.triangle(g, ax, y + 7, ClientSurvival.trend != 1, col);
        }
    }

    /** Icon, ornate bar (fill shrinks toward the middle), stage notches and, after a change, the stage name above. */
    private static void meter(GuiGraphics g, Font font, int x, int y, int icon, float fill, int rgb, int stage, String label,
                              long changed, long now) {
        float a = alpha;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, a);
        g.blit(ICONS, x, y - 3, icon * 10, 0, 10, 10, 64, 16);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        int bx = x + 12;
        int color = stage >= 3 ? lerp(rgb, 0xD04030, 0.45f + 0.15f * (stage - 3)) : rgb;
        if (stage >= 4 && (now / 400) % 2 == 0) color = SkyUi.brighten(color, 40);
        int ia = (int) (a * 255) << 24;
        g.fill(bx - 1, y - 1, bx + BAR_W + 1, y + 4, ia | 0x0C0B0A);
        g.fill(bx, y, bx + BAR_W, y + 3, (int) (a * 0xC0) << 24 | 0x1C1A18);
        int filled = Math.round(BAR_W * Mth.clamp(fill, 0f, 1f));
        int left = bx + (BAR_W - filled) / 2;
        g.fill(left, y, left + filled, y + 3, ia | color);
        g.fill(left, y, left + filled, y + 1, ia | SkyUi.brighten(color, 60));
        // end caps like the vitals bars
        int trim = SkyUi.fade(SkyUi.TRIM, a);
        g.fill(bx - 3, y, bx - 1, y + 3, trim);
        g.fill(bx + BAR_W + 1, y, bx + BAR_W + 3, y + 3, trim);
        if (now - changed < LABEL_MS) {
            float la = a * Mth.clamp((LABEL_MS - (now - changed)) / 600f, 0f, 1f);
            String text = SkyUi.caps(Component.translatable(label));
            SkyUi.scaledCentered(g, font, text, bx + BAR_W / 2f, y - 10, 0.7f,
                    SkyUi.fade(stage >= 3 ? 0xFFE07060 : 0xFFE8E0C8, la), true);
        }
    }

    /** Frost creeping in from the screen edges while Freezing or Numb. */
    private static void frost(GuiGraphics g, int width, int height, int stage, long now) {
        if (stage < 3) return;
        float f = stage == 3 ? 0.3f : 0.55f + 0.1f * (float) Math.sin(now / 500.0);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, f);
        g.blit(FROST, 0, 0, -90, 0f, 0f, width, height, width, height);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    private static int lerp(int a, int b, float t) {
        int r = (int) Mth.lerp(t, (a >> 16) & 0xFF, (b >> 16) & 0xFF);
        int gr = (int) Mth.lerp(t, (a >> 8) & 0xFF, (b >> 8) & 0xFF);
        int bl = (int) Mth.lerp(t, a & 0xFF, b & 0xFF);
        return r << 16 | gr << 8 | bl;
    }
}
