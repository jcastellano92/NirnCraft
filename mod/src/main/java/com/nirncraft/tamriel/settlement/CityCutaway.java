package com.nirncraft.tamriel.settlement;

import com.nirncraft.dungeons.qa.QaLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * A settlement with its roofs off, from its in-memory paint (the world is not touched): every block a piece painted
 * up to CUT blocks above the ground (a storey: 0 the ground floor, 1 the first upper floor ...) drawn from the
 * south-east, the ground as a plain plane, so every building's rooms, walls and furnishing read at once; plus the
 * floor plan at the same cut, from above.
 */
public final class CityCutaway {
    private CityCutaway() {}

    public record Stats(int blocks, int buildingsArea) {}

    private static int colour(QaLevel lvl, int x, int y, int z, BlockState s) {
        try {
            return s.getMapColor(lvl, new BlockPos(x, y, z)).col;
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

    private static boolean shown(QaLevel lvl, int x, int y, int z, int cut) {
        if (!lvl.painted(x, y, z)) return false;
        BlockState s = lvl.get(x, y, z);
        return !s.isAir() && y <= lvl.groundTop(x, z) + cut;
    }

    public static Stats render(QaLevel lvl, BoundingBox box, int storey, File iso, File plan) throws IOException {
        int cut = 1 + storey * 5 + 3;  // a storey is about five blocks; keep walls and furniture of that floor
        int dx = box.getXSpan(), dz = box.getZSpan();
        int blocks = 0, built = 0;
        // ---- plan: from above, the highest shown block per column (the ground where nothing is built)
        int k = Math.max(2, Math.min(5, 1400 / Math.max(dx, dz)));
        BufferedImage top = new BufferedImage(dx * k, dz * k, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < dx; x++)
            for (int z = 0; z < dz; z++) {
                int wx = box.minX() + x, wz = box.minZ() + z, gy = lvl.groundTop(wx, wz);
                int rgb = 0x5E7A4A;
                for (int y = gy + cut; y >= box.minY(); y--) {
                    if (shown(lvl, wx, y, wz, cut)) {
                        rgb = shade(colour(lvl, wx, y, wz, lvl.get(wx, y, wz)), 0.75 + 0.25 * Math.min(1, (y - gy + 2) / (double) cut));
                        built++;
                        break;
                    }
                }
                for (int a = 0; a < k; a++) for (int b = 0; b < k; b++) top.setRGB(x * k + a, z * k + b, rgb);
            }
        plan.getParentFile().mkdirs();
        ImageIO.write(top, "png", plan);
        // ---- iso from the south-east: the ground plane, then the shown blocks back to front
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int x = box.minX(); x <= box.maxX(); x += 4)
            for (int z = box.minZ(); z <= box.maxZ(); z += 4) {
                int gy = lvl.groundTop(x, z);
                minY = Math.min(minY, gy);
                maxY = Math.max(maxY, gy + cut);
            }
        int span = Math.max(1, maxY - minY);
        int t = Math.max(2, Math.min(5, 1600 / (dx + dz)));
        int w = (dx + dz) * t + 8, h = (dx + dz) * t / 2 + span * t + 40;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x16161B));
        g.fillRect(0, 0, w, h);
        int ox = dz * t + 4, oy = 20 + span * t;
        for (int s = 0; s <= dx + dz - 2; s++) {
            for (int x = Math.max(0, s - dz + 1); x <= Math.min(dx - 1, s); x++) {
                int z = s - x, wx = box.minX() + x, wz = box.minZ() + z, gy = lvl.groundTop(wx, wz);
                for (int y = Math.min(minY, gy); y <= gy + cut; y++) {
                    boolean ground = y == gy && !shown(lvl, wx, y, wz, cut);
                    if (!ground && !shown(lvl, wx, y, wz, cut)) continue;
                    int c = ground ? 0x5E7A4A : colour(lvl, wx, y, wz, lvl.get(wx, y, wz));
                    if (!ground) blocks++;
                    int sx = ox + (x - z) * t, sy = oy + (x + z) * t / 2 - (y - minY) * t;
                    // top face, then the two sides that face the viewer (darker)
                    g.setColor(new Color(c));
                    g.fillPolygon(new Polygon(new int[]{sx, sx + t, sx, sx - t}, new int[]{sy - t / 2, sy, sy + t / 2, sy}, 4));
                    if (!ground) {
                        g.setColor(new Color(shade(c, 0.72)));
                        g.fillPolygon(new Polygon(new int[]{sx - t, sx, sx, sx - t}, new int[]{sy, sy + t / 2, sy + t / 2 + t, sy + t}, 4));
                        g.setColor(new Color(shade(c, 0.55)));
                        g.fillPolygon(new Polygon(new int[]{sx, sx + t, sx + t, sx}, new int[]{sy + t / 2, sy, sy + t, sy + t / 2 + t}, 4));
                    }
                }
            }
        }
        g.dispose();
        ImageIO.write(img, "png", iso);
        return new Stats(blocks, built);
    }
}
