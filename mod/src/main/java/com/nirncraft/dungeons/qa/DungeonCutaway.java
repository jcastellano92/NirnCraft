package com.nirncraft.dungeons.qa;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * A whole dungeon at a glance, from its in-memory paint (QaLevel; the world is not touched): the walkable space drawn
 * from the inside. Every floor (an air cell over a solid block, underground) is a tile in its block's colour, shaded
 * by depth, with walls as dark edges, so rooms, corridors, stairs and stacked levels read like a 3D blueprint.
 * <ul><li>iso: seen from the south-east, painter's order;</li>
 * <li>plan: from above, floors coloured by height (deeper = bluer), so overlapping levels can be told apart.</li></ul>
 */
public final class DungeonCutaway {
    private DungeonCutaway() {}

    public record Stats(int floors, int minY, int maxY, int width, int depth) {}

    private static boolean open(BlockState s) {
        return s.isAir() || !s.isSolid();
    }

    static boolean floor(QaLevel lvl, int x, int y, int z) {
        return lvl.painted(x, y, z) && open(lvl.get(x, y, z)) && !open(lvl.get(x, y - 1, z))
                && y < lvl.groundTop(x, z) - 1;  // underground: the forecourt and the sky are not the dungeon
    }

    private static int colour(QaLevel lvl, int x, int y, int z) {
        BlockState below = lvl.get(x, y - 1, z);
        try {
            return below.getMapColor(lvl, new BlockPos(x, y - 1, z)).col;
        } catch (RuntimeException e) {
            return 0x808080;
        }
    }

    private static int shade(int rgb, double f) {
        int r = (int) Math.max(0, Math.min(255, ((rgb >> 16) & 255) * f));
        int g = (int) Math.max(0, Math.min(255, ((rgb >> 8) & 255) * f));
        int b = (int) Math.max(0, Math.min(255, (rgb & 255) * f));
        return (r << 16) | (g << 8) | b;
    }

    public static Stats render(QaLevel lvl, BoundingBox box, File iso, File plan) throws IOException {
        int dx = box.getXSpan(), dz = box.getZSpan();
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, floors = 0;
        for (int x = box.minX(); x <= box.maxX(); x++)
            for (int z = box.minZ(); z <= box.maxZ(); z++)
                for (int y = box.minY(); y <= box.maxY(); y++)
                    if (floor(lvl, x, y, z)) {
                        floors++;
                        minY = Math.min(minY, y);
                        maxY = Math.max(maxY, y);
                    }
        if (floors == 0) {
            minY = maxY = box.minY();
        }
        int span = Math.max(1, maxY - minY);
        // ---- plan: top-down, the highest floor per column, coloured by height
        int k = Math.max(2, Math.min(6, 1200 / Math.max(dx, dz)));
        BufferedImage top = new BufferedImage(dx * k, dz * k, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < dx; x++)
            for (int z = 0; z < dz; z++) {
                int rgb = 0x15151A;
                for (int y = box.maxY(); y >= box.minY(); y--) {
                    if (floor(lvl, box.minX() + x, y, box.minZ() + z)) {
                        double t = (y - minY) / (double) span;  // 0 deep .. 1 high
                        Color c = Color.getHSBColor((float) (0.62 - 0.55 * t), 0.75f, (float) (0.55 + 0.4 * t));
                        rgb = c.getRGB() & 0xFFFFFF;
                        break;
                    }
                }
                for (int a = 0; a < k; a++) for (int b = 0; b < k; b++) top.setRGB(x * k + a, z * k + b, rgb);
            }
        plan.getParentFile().mkdirs();
        ImageIO.write(top, "png", plan);
        // ---- iso: floor tiles from the south-east (screen x = x - z, screen y = (x + z) / 2 - y)
        int t = Math.max(2, Math.min(6, 1400 / (dx + dz)));
        int w = (dx + dz) * t + 8, h = (dx + dz) * t / 2 + span * t * 2 + 40;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(new Color(0x101014));
        g.fillRect(0, 0, w, h);
        int ox = dz * t + 4, oy = 20 + span * t * 2;
        // painter's order: far (small x + z) first, low before high
        for (int s = 0; s <= dx + dz - 2; s++) {
            for (int y = minY; y <= maxY; y++) {
                for (int x = Math.max(0, s - dz + 1); x <= Math.min(dx - 1, s); x++) {
                    int z = s - x, wx = box.minX() + x, wz = box.minZ() + z;
                    if (!floor(lvl, wx, y, wz)) continue;
                    double depth = (y - minY) / (double) span;
                    int c = shade(colour(lvl, wx, y, wz), 0.55 + 0.6 * depth);
                    int sx = ox + (x - z) * t, sy = oy + (x + z) * t / 2 - (y - minY) * t * 2;
                    Polygon tile = new Polygon(new int[]{sx, sx + t, sx, sx - t}, new int[]{sy - t / 2, sy, sy + t / 2, sy}, 4);
                    g.setColor(new Color(c));
                    g.fillPolygon(tile);
                    // a wall beside the tile: a dark edge on that side
                    g.setColor(new Color(shade(c, 0.35)));
                    if (!open(lvl.get(wx + 1, y, wz))) g.drawLine(sx, sy + t / 2, sx + t, sy);
                    if (!open(lvl.get(wx, y, wz + 1))) g.drawLine(sx - t, sy, sx, sy + t / 2);
                }
            }
        }
        g.dispose();
        ImageIO.write(img, "png", iso);
        return new Stats(floors, minY, maxY, dx, dz);
    }
}
