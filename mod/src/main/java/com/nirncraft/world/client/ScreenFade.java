package com.nirncraft.world.client;

import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Fade-to-black overlay shown while time passes (waiting, sleeping, fast travel). */
public final class ScreenFade {
    private static long start = -1;
    private static long duration = 1800;
    private static Component text = Component.empty();

    private ScreenFade() {}

    public static void start(Component message, long millis) {
        start = Util.getMillis();
        duration = Math.max(200, millis);
        text = message;
    }

    public static boolean active() {
        return start >= 0 && Util.getMillis() - start < duration;
    }

    public static void render(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int width = g.guiWidth(), height = g.guiHeight();
        if (start < 0) return;
        float t = (Util.getMillis() - start) / (float) duration;
        if (t >= 1f) {
            start = -1;
            return;
        }
        // in fast (25%), hold (35%), out slowly (40%)
        float alpha;
        if (t < 0.25f) alpha = t / 0.25f;
        else if (t < 0.6f) alpha = 1f;
        else alpha = 1f - (t - 0.6f) / 0.4f;
        int a = Math.max(0, Math.min(255, (int) (alpha * 255)));
        g.fill(0, 0, width, height, a << 24);
        if (a > 40 && !text.getString().isEmpty()) {
            int ta = Math.max(4, a) << 24;
            var font = Minecraft.getInstance().font;
            g.pose().pushMatrix();
            g.pose().translate(width / 2f,  height / 2f - 6);
            g.pose().scale(1.5f,  1.5f);
            g.centeredText(font, text, 0, 0, ta | 0xE8DFC8);
            g.pose().popMatrix();
        }
    }
}
