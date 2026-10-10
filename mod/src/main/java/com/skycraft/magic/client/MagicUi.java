package com.skycraft.magic.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Skyrim menu drawing for the magic, spellmaking and staff screens (same look as the inventory's SkyUi: dark
 * translucent panels, thin fading gold lines, small caps), plus a Skyrim-styled button so no vanilla gray buttons show.
 * Hit targets are at least 12 px tall (Steam Deck / Controllable virtual cursor).
 */
public final class MagicUi {
    public static final int TEXT = 0xFFE8E0C8;
    public static final int BRIGHT = 0xFFFFFFFF;
    public static final int DIM = 0xFF8A8478;
    public static final int HEADER = 0xFFA89F86;
    public static final int GOLD = 0xFFE8C060;
    public static final int LINE = 0xFFC8BC9A;
    public static final int GOOD = 0xFF90D090;
    public static final int BAD = 0xFFE07060;
    public static final int MAGICKA = 0xFF7FB2FF;

    private MagicUi() {}

    public static void panel(GuiGraphics g, int x1, int y1, int x2, int y2) {
        g.fillGradient(x1, y1, x2, y2, 0x90000000, 0xA8000000);
        hline(g, x1, x2, y1, 0x70);
        hline(g, x1, x2, y2 - 1, 0x70);
    }

    public static void hline(GuiGraphics g, int x1, int x2, int y, int alpha) {
        int w = x2 - x1;
        if (w <= 0) return;
        int fade = Math.min(24, w / 4);
        int rgb = LINE & 0xFFFFFF;
        for (int i = 0; i < fade; i++) {
            int a = alpha * (i + 1) / (fade + 1);
            g.fill(x1 + i, y, x1 + i + 1, y + 1, a << 24 | rgb);
            g.fill(x2 - i - 1, y, x2 - i, y + 1, a << 24 | rgb);
        }
        g.fill(x1 + fade, y, x2 - fade, y + 1, alpha << 24 | rgb);
    }

    public static void diamond(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int i = -r; i <= r; i++) {
            int w = r - Math.abs(i);
            g.fill(cx - w, cy + i, cx + w + 1, cy + i + 1, color);
        }
    }

    public static String caps(Component c) {
        return c.getString().toUpperCase(Locale.ROOT);
    }

    public static void capsCentered(GuiGraphics g, Font font, Component c, int cx, int y, int color) {
        String s = caps(c);
        g.drawString(font, s, cx - font.width(s) / 2, y, color, false);
    }

    public static String ellipsize(Font font, String s, int width) {
        if (font.width(s) <= width) return s;
        if (width <= font.width("...")) return "";
        return font.plainSubstrByWidth(s, width - font.width("...")) + "...";
    }

    public static boolean inside(double mx, double my, int x1, int y1, int x2, int y2) {
        return mx >= x1 && mx < x2 && my >= y1 && my < y2;
    }

    /** A Skyrim-styled text button: no frame, gold underline and diamond when hovered or focused. */
    public static class SkyButton extends AbstractButton {
        private final Runnable onPress;

        public SkyButton(int x, int y, int w, int h, Component label, Runnable onPress) {
            super(x, y, w, Math.max(12, h), label);
            this.onPress = onPress;
        }

        @Override
        public void onPress() {
            onPress.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            Font font = net.minecraft.client.Minecraft.getInstance().font;
            boolean hot = active && (isHoveredOrFocused());
            g.fill(getX(), getY(), getX() + width, getY() + height, hot ? 0x60302A20 : 0x40000000);
            hline(g, getX(), getX() + width, getY() + height - 1, hot ? 0xD0 : 0x50);
            String s = caps(getMessage());
            int color = !active ? DIM : hot ? BRIGHT : TEXT;
            g.drawString(font, s, getX() + (width - font.width(s)) / 2, getY() + (height - 8) / 2, color, false);
            if (hot) {
                diamond(g, getX() + 4, getY() + height / 2, 2, GOLD);
                diamond(g, getX() + width - 5, getY() + height / 2, 2, GOLD);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }
}
