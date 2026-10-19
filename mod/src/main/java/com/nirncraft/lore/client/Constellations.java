package com.nirncraft.lore.client;

import com.nirncraft.lore.StandingStone;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The sigil of every Standing Stone as stars and connecting lines, in a 12 x 22 grid (same shapes the block
 * textures carve; see {@code tools/textures/lore.py}).
 */
public final class Constellations {
    private Constellations() {}

    /** {x, y} star positions. */
    public static int[][] stars(StandingStone s) {
        return switch (s) {
            case WARRIOR -> new int[][]{{6, 1}, {6, 5}, {2, 6}, {10, 6}, {6, 6}, {6, 19}};
            case MAGE -> new int[][]{{6, 2}, {9, 5}, {6, 8}, {3, 5}, {6, 20}};
            case THIEF -> new int[][]{{6, 5}, {3, 8}, {9, 8}, {10, 14}, {6, 18}, {2, 14}};
            case LADY -> new int[][]{{6, 2}, {4, 6}, {8, 6}, {2, 19}, {10, 19}};
            case LORD -> new int[][]{{6, 1}, {2, 4}, {10, 4}, {2, 11}, {10, 11}, {6, 17}};
            case LOVER -> new int[][]{{6, 6}, {3, 3}, {1, 7}, {6, 14}, {11, 7}, {9, 3}, {6, 19}};
            case ATRONACH -> new int[][]{{6, 1}, {3, 4}, {9, 4}, {9, 11}, {3, 11}, {2, 19}, {10, 19}};
            case APPRENTICE -> new int[][]{{1, 8}, {6, 10}, {11, 8}, {11, 16}, {6, 18}, {1, 16}, {6, 3}};
            case RITUAL -> new int[][]{{6, 5}, {9, 7}, {10, 11}, {9, 15}, {6, 17}, {3, 15}, {2, 11}, {3, 7}, {6, 11}};
            case SERPENT -> new int[][]{{2, 2}, {9, 5}, {3, 9}, {10, 13}, {4, 17}, {8, 20}};
            case SHADOW -> new int[][]{{8, 2}, {4, 4}, {2, 10}, {4, 16}, {8, 18}, {6, 10}};
            case STEED -> new int[][]{{2, 19}, {4, 11}, {8, 4}, {11, 6}, {8, 11}, {10, 19}};
            case TOWER -> new int[][]{{4, 20}, {4, 6}, {6, 1}, {8, 6}, {8, 20}, {6, 11}};
        };
    }

    /** Pairs of star indices joined by lines. */
    public static int[][] lines(StandingStone s) {
        return switch (s) {
            case WARRIOR -> new int[][]{{0, 1}, {2, 4}, {4, 3}, {4, 5}};
            case MAGE -> new int[][]{{0, 1}, {1, 2}, {2, 3}, {3, 0}, {2, 4}};
            case THIEF -> new int[][]{{0, 1}, {0, 2}, {1, 2}, {2, 3}, {3, 4}, {4, 5}, {5, 1}};
            case LADY -> new int[][]{{0, 1}, {0, 2}, {1, 3}, {2, 4}, {3, 4}};
            case LORD -> new int[][]{{0, 1}, {0, 2}, {1, 3}, {2, 4}, {3, 5}, {4, 5}};
            case LOVER -> new int[][]{{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 5}, {5, 0}, {3, 6}};
            case ATRONACH -> new int[][]{{0, 1}, {0, 2}, {1, 2}, {2, 3}, {3, 4}, {4, 1}, {4, 5}, {3, 6}};
            case APPRENTICE -> new int[][]{{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 5}, {5, 0}, {1, 4}, {6, 1}};
            case RITUAL -> new int[][]{{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 5}, {5, 6}, {6, 7}, {7, 0}};
            case SERPENT -> new int[][]{{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 5}};
            case SHADOW -> new int[][]{{0, 1}, {1, 2}, {2, 3}, {3, 4}};
            case STEED -> new int[][]{{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 1}, {4, 5}};
            case TOWER -> new int[][]{{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 0}};
        };
    }

    /** Draws the constellation with its top-left at (x, y), {@code scale} GUI pixels per grid unit. */
    public static void draw(GuiGraphicsExtractor g, StandingStone s, int x, int y, int scale, int alpha) {
        int[][] stars = stars(s);
        int lineColor = (alpha * 2 / 3) << 24 | 0xB8C4E0;
        for (int[] l : lines(s)) {
            int[] a = stars[l[0]];
            int[] b = stars[l[1]];
            line(g, x + a[0] * scale, y + a[1] * scale, x + b[0] * scale, y + b[1] * scale, lineColor);
        }
        int glow = (alpha / 4) << 24 | s.color;
        int core = alpha << 24 | 0xFFF8E8;
        for (int[] p : stars) {
            int px = x + p[0] * scale;
            int py = y + p[1] * scale;
            g.fill(px - 2, py - 2, px + 3, py + 3, glow);
            g.fill(px - 1, py, px + 2, py + 1, core);
            g.fill(px, py - 1, px + 1, py + 2, core);
        }
    }

    private static void line(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        int dx = x1 - x0, dy = y1 - y0;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps == 0) return;
        for (int i = 0; i <= steps; i++) {
            int x = x0 + dx * i / steps;
            int y = y0 + dy * i / steps;
            g.fill(x, y, x + 1, y + 1, color);
        }
    }
}
