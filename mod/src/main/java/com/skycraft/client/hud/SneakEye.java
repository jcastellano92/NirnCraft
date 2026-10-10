package com.skycraft.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.skycraft.Skycraft;
import com.skycraft.client.ClientState;
import com.skycraft.inventory.client.SkyUi;
import com.skycraft.network.CorePackets;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Skyrim's sneak eye at the crosshair: closed while hidden, half open on "caution", wide open when detected, with
 * the lids easing between states. The crime module estimates caution and reports it each frame ({@link #report});
 * without a report the server's DETECTED flag decides alone. A small label fades in for a moment after the state
 * changes.
 */
public final class SneakEye {
    private static final ResourceLocation EYE = new ResourceLocation(Skycraft.MODID, "textures/gui/hud/sneak_eye.png");
    public static final int HIDDEN = 0;
    public static final int CAUTION = 1;
    public static final int DETECTED = 2;

    private static int reported = -1;
    private static long reportedAt;
    private static float open;
    private static int shownState = -1;
    private static long stateSince;
    private static long lastFrame = Util.getMillis();

    private SneakEye() {}

    /** Called by the crime HUD with its sneak state (0 hidden, 1 caution, 2 detected). Returns true: the eye is drawn here. */
    public static boolean report(int state) {
        reported = state;
        reportedAt = Util.getMillis();
        return true;
    }

    private static int state() {
        if (ClientState.has(CorePackets.SyncVitals.DETECTED)) return DETECTED;
        if (reported >= 0 && Util.getMillis() - reportedAt < 400) return reported;
        return HIDDEN;
    }

    /** Draws the eye centred at (cx, cy) when the player is sneaking. */
    public static void render(GuiGraphics g, int cx, int cy) {
        Minecraft mc = Minecraft.getInstance();
        long now = Util.getMillis();
        float dt = Mth.clamp((now - lastFrame) / 1000f, 0f, 0.25f);
        lastFrame = now;
        if (mc.player == null || !mc.player.isCrouching()) {
            open = 0f;
            shownState = -1;
            return;
        }
        int state = state();
        if (state != shownState) {
            shownState = state;
            stateSince = now;
        }
        float target = state == DETECTED ? 1.0f : state == CAUTION ? 0.55f : 0.0f;
        open += (target - open) * (1f - (float) Math.exp(-8.0 * dt));
        int frame = Mth.clamp(Math.round(open * 4f), 0, 4);

        float r, gr, b, a;
        switch (state) {
            case DETECTED -> { r = 1f; gr = 1f; b = 1f; a = 1f; }
            case CAUTION -> { r = 0.95f; gr = 0.88f; b = 0.68f; a = 0.95f; }
            default -> { r = 0.82f; gr = 0.82f; b = 0.82f; a = 0.9f; }
        }
        RenderSystem.enableBlend();
        // dark outline so the eye reads on snow, sand and sky
        RenderSystem.setShaderColor(0f, 0f, 0f, a * 0.5f);
        g.blit(EYE, cx - 16 + 1, cy - 8 + 1, 32, 16, 0, frame * 32, 64, 32, 64, 160);
        g.blit(EYE, cx - 16 - 1, cy - 8, 32, 16, 0, frame * 32, 64, 32, 64, 160);
        g.blit(EYE, cx - 16, cy - 8 - 1, 32, 16, 0, frame * 32, 64, 32, 64, 160);
        RenderSystem.setShaderColor(r, gr, b, a);
        g.blit(EYE, cx - 16, cy - 8, 32, 16, 0, frame * 32, 64, 32, 64, 160);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        float labelAlpha = Mth.clamp(1.6f - (now - stateSince) / 1000f, 0f, 1f);
        if (labelAlpha > 0.03f) {
            String key = state == DETECTED ? "hud.skycraft.detected" : state == CAUTION ? "hud.skycraft.caution" : "hud.skycraft.hidden";
            String text = SkyUi.caps(Component.translatable(key));
            SkyUi.scaledCentered(g, mc.font, text, cx, cy + 10, 0.6f, SkyUi.fade(0xFFE8E0C8, labelAlpha * a), true);
        }
    }
}
