package com.skycraft.quest.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The opaque dark loading cover of the opening ("The valley wakes"), as a shared piece: {@link #draw} is exactly what
 * {@link IntroLoadingScreen} paints (title, subtitle, progress bar, tip), and this class is the same cover for the
 * server to raise on its own say-so with another title and subtitle (a prisoner taken to a jail that is being built:
 * {@code crime.JailLoading} sends the title, the subtitle and the chunks ready of needed). It fades in and out like the
 * opening's and cannot be closed by the player; the server decides when it goes ({@link #hide}).
 */
public final class LoadingCover extends Screen {
    private static LoadingCover current;

    private final long since = System.currentTimeMillis();
    private long fadeStart = -1;
    private float shown;
    private float target;
    private Component sub = Component.empty();
    private Component tip = Component.empty();

    private LoadingCover(Component title) {
        super(title);
    }

    /** Raises the cover (or updates it): {@code progress} 0..1. */
    public static void show(Component title, Component sub, Component tip, float progress) {
        Minecraft mc = Minecraft.getInstance();
        if (current == null || mc.gui.screen() != current) {
            current = new LoadingCover(title);
            mc.gui.setScreen(current);
        }
        current.sub = sub;
        current.tip = tip;
        current.target = Mth.clamp(progress, 0f, 1f);
        current.fadeStart = -1;
    }

    /** The world fades in again. */
    public static void hide() {
        if (current != null && current.fadeStart < 0) current.fadeStart = System.currentTimeMillis();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        shown += (target - shown) * 0.2f;
        if (fadeStart >= 0 && System.currentTimeMillis() - fadeStart > 900) {
            if (current == this) current = null;
            onClose();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        long now = System.currentTimeMillis();
        float fadeIn = Mth.clamp((now - since) / 400f, 0f, 1f);
        float fadeOut = fadeStart < 0 ? 0f : Mth.clamp((now - fadeStart) / 900f, 0f, 1f);
        draw(g, font, width, height, fadeIn * (1f - fadeOut), getTitle(), sub, tip, shown);
    }

    /** The cover itself, as the opening paints it: {@code a} is the fade (0..1), {@code shown} the bar (0..1). */
    static void draw(GuiGraphicsExtractor g, Font font, int width, int height, float a, Component title, Component sub, Component tip, float shown) {
        int alpha = (int) (a * 255);
        g.fill(0, 0, width, height, (alpha << 24) | 0x0A0806);
        if (alpha < 8) return;
        int ta = Math.max(8, alpha);
        int cx = width / 2, cy = height / 2;
        g.centeredText(font, title, cx, cy - 34, (ta << 24) | 0xE8D9A8);
        g.centeredText(font, sub, cx, cy - 18, (ta << 24) | 0xA89C80);
        int bw = Math.min(220, width - 40), bx = cx - bw / 2, by = cy + 4;
        g.fill(bx - 1, by - 1, bx + bw + 1, by + 7, (ta << 24) | 0x4A3E26);
        g.fill(bx, by, bx + bw, by + 6, (ta << 24) | 0x16120C);
        int fill = (int) (bw * Mth.clamp(shown, 0f, 1f));
        g.fill(bx, by, bx + fill, by + 6, (ta << 24) | 0xC9A650);
        g.centeredText(font, tip, cx, by + 18, (ta << 24) | 0x7C735E);
    }
}
