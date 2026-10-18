package com.skycraft.crafting.arcane.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Drawing helpers and palette shared by the arcane screens (Skyrim-like dark panels with gold trim). */
public final class ArcaneUi {
    public static final int GOLD = 0xFFE8C060;
    public static final int TEXT = 0xFFF5EBC8;
    public static final int MUTED = 0xFF948A74;
    public static final int GOOD = 0xFF7FD07F;
    public static final int BAD = 0xFFE07060;
    public static final int MAGIC = 0xFFB59CFF;

    private ArcaneUi() {}

    public static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** A dark translucent panel with a double gold border and corner studs. */
    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int accent) {
        g.fillGradient(x, y, x + w, y + h, 0xF0080A14, 0xF0141A2C);
        frame(g, x, y, w, h, 0xFFB89A50);
        frame(g, x + 2, y + 2, w - 4, h - 4, 0x60B89A50);
        int s = 3;
        g.fill(x - 1, y - 1, x + s, y + s, accent);
        g.fill(x + w - s, y - 1, x + w + 1, y + s, accent);
        g.fill(x - 1, y + h - s, x + s, y + h + 1, accent);
        g.fill(x + w - s, y + h - s, x + w + 1, y + h + 1, accent);
    }

    /** A thin decorative divider with a diamond in the middle. */
    public static void divider(GuiGraphicsExtractor g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 1, 0x80B89A50);
        int cx = x + w / 2;
        g.fill(cx - 1, y - 2, cx + 2, y + 3, 0xFFE8C060);
        g.fill(cx - 2, y - 1, cx + 3, y + 2, 0xFFE8C060);
    }
}
