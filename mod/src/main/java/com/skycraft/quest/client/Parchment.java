package com.skycraft.quest.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Drawing helpers for the Skyrim journal look: an aged parchment page with inked rules. */
public final class Parchment {
    public static final int INK = 0xFF3A2814;
    public static final int INK_LIGHT = 0xFF6E5638;
    public static final int INK_FADED = 0xFF9A8662;
    public static final int RED = 0xFF8A2A1A;
    public static final int GREEN = 0xFF3E6B2A;
    public static final int GOLD = 0xFF9A6A10;
    public static final int HIGHLIGHT = 0x40A07A3A;

    private Parchment() {}

    public static void page(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        g.fill(x0 - 4, y0 - 4, x1 + 4, y1 + 4, 0xC0000000);
        g.fill(x0 - 3, y0 - 3, x1 + 3, y1 + 3, 0xFF2A1E10);
        g.fill(x0 - 2, y0 - 2, x1 + 2, y1 + 2, 0xFF7A5E36);
        g.fillGradient(x0, y0, x1, y1, 0xFFEEE0BC, 0xFFDCC796);
        // darker, worn edges
        g.fillGradient(x0, y0, x1, y0 + 6, 0x40704C20, 0x00704C20);
        g.fillGradient(x0, y1 - 6, x1, y1, 0x00704C20, 0x40704C20);
        g.fill(x0, y0, x0 + 2, y1, 0x30704C20);
        g.fill(x1 - 2, y0, x1, y1, 0x30704C20);
        // a few deterministic stains
        int w = x1 - x0;
        int h = y1 - y0;
        for (int i = 0; i < 7; i++) {
            int sx = x0 + 10 + (i * 97 % Math.max(1, w - 30));
            int sy = y0 + 10 + (i * 61 % Math.max(1, h - 30));
            g.fill(sx, sy, sx + 6 + i % 3 * 3, sy + 4 + i % 2 * 3, 0x10603A10);
        }
    }

    /** A horizontal inked rule with a small diamond in the middle. */
    public static void rule(GuiGraphicsExtractor g, int x0, int x1, int y) {
        g.fill(x0, y, x1, y + 1, 0x906E5638);
        int cx = (x0 + x1) / 2;
        g.fill(cx - 2, y - 1, cx + 3, y + 2, 0xFF6E5638);
    }

    public static void vrule(GuiGraphicsExtractor g, int x, int y0, int y1) {
        g.fill(x, y0, x + 1, y1, 0x906E5638);
    }

    /** Checkbox, ticked when done. */
    public static void checkbox(GuiGraphicsExtractor g, int x, int y, boolean done, boolean failed) {
        g.fill(x, y, x + 7, y + 7, INK_LIGHT);
        g.fill(x + 1, y + 1, x + 6, y + 6, 0xFFEFE3C2);
        if (failed) {
            for (int i = 0; i < 5; i++) {
                g.fill(x + 1 + i, y + 1 + i, x + 2 + i, y + 2 + i, RED);
                g.fill(x + 5 - i, y + 1 + i, x + 6 - i, y + 2 + i, RED);
            }
        } else if (done) {
            g.fill(x + 1, y + 3, x + 2, y + 5, GREEN);
            g.fill(x + 2, y + 4, x + 3, y + 6, GREEN);
            g.fill(x + 3, y + 3, x + 4, y + 5, GREEN);
            g.fill(x + 4, y + 2, x + 5, y + 4, GREEN);
            g.fill(x + 5, y + 1, x + 6, y + 3, GREEN);
            g.fill(x + 6, y, x + 7, y + 2, GREEN);
        }
    }

    /** Quest icons: main quest (dragon seal), faction (banner), bounty (crossed blades), misc (dot). */
    public static void icon(GuiGraphicsExtractor g, int x, int y, com.skycraft.quest.Quest.Category cat, int factionColor) {
        switch (cat) {
            case MAIN -> {
                // golden seal with a red center
                g.fill(x + 2, y, x + 6, y + 8, 0xFFB08A2A);
                g.fill(x, y + 2, x + 8, y + 6, 0xFFB08A2A);
                g.fill(x + 1, y + 1, x + 7, y + 7, 0xFFD4AE48);
                g.fill(x + 3, y + 2, x + 5, y + 6, 0xFF8A1A10);
                g.fill(x + 2, y + 3, x + 6, y + 5, 0xFF8A1A10);
            }
            case FACTION -> {
                int c = 0xFF000000 | factionColor;
                g.fill(x + 1, y, x + 7, y + 6, c);
                g.fill(x + 1, y + 6, x + 3, y + 8, c);
                g.fill(x + 5, y + 6, x + 7, y + 8, c);
                g.fill(x + 1, y, x + 7, y + 1, INK);
            }
            case BOUNTY -> {
                for (int i = 0; i < 7; i++) {
                    g.fill(x + i, y + i, x + i + 1, y + i + 1, INK);
                    g.fill(x + 6 - i, y + i, x + 7 - i, y + i + 1, INK);
                }
                g.fill(x, y + 6, x + 2, y + 8, RED);
                g.fill(x + 5, y + 6, x + 7, y + 8, RED);
            }
            default -> {
                g.fill(x + 2, y + 2, x + 6, y + 6, INK_LIGHT);
                g.fill(x + 3, y + 3, x + 5, y + 5, 0xFFEFE3C2);
            }
        }
    }

    public static void bar(GuiGraphicsExtractor g, int x, int y, int w, float fill, int color) {
        g.fill(x - 1, y - 1, x + w + 1, y + 4, INK_LIGHT);
        g.fill(x, y, x + w, y + 3, 0xFFCBB68A);
        g.fill(x, y, x + (int) (w * Math.max(0, Math.min(1, fill))), y + 3, color);
    }
}
