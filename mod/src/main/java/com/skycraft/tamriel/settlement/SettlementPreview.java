package com.skycraft.tamriel.settlement;

import com.skycraft.tamriel.settlement.grammar.Use;
import com.skycraft.tamriel.settlement.plan.Archetype;
import com.skycraft.tamriel.settlement.plan.Plan;
import com.skycraft.tamriel.settlement.plan.Spec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Offline images of settlements for the developer preview tool ({@link SettlementDevCommands}): an isometric render
 * of a built area (map colours, three face shades, painter's order from the far corner), a top-down shaded map of a
 * built area, and a plan diagram drawn straight from a plan's specs (streets, squares, buildings coloured by use,
 * yards, pens, fields, walls and gates) without building anything. Server side, plain {@code java.awt}.
 */
public final class SettlementPreview {
    private SettlementPreview() {}

    static int color(ServerLevel level, BlockPos pos, BlockState s) {
        if (s.is(Blocks.WATER)) return 0x3F76E4;
        MapColor mc = s.getMapColor(level, pos);
        int c = mc == null || mc == MapColor.NONE ? 0x9A9A9A : mc.col;
        return c == 0 ? 0x9A9A9A : c;
    }

    static int shade(int rgb, double f) {
        int r = (int) Math.min(255, ((rgb >> 16) & 255) * f), g = (int) Math.min(255, ((rgb >> 8) & 255) * f), b = (int) Math.min(255, (rgb & 255) * f);
        return (r << 16) | (g << 8) | b;
    }

    static boolean solidish(BlockState s) {
        return !s.isAir() && !s.is(Blocks.CAVE_AIR);
    }

    /** Isometric render (seen from the south-east) of the box; {@code k} pixels per block. */
    public static File iso(ServerLevel level, int x0, int z0, int x1, int z1, int k, File out) throws IOException {
        // the ground's low decile (minus a little), so caves and ravines don't drag the view underground
        java.util.List<Integer> hs = new java.util.ArrayList<>();
        int y1 = Integer.MIN_VALUE;
        for (int x = x0; x <= x1; x += 3) for (int z = z0; z <= z1; z += 3) hs.add(level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z));
        java.util.Collections.sort(hs);
        int y0 = hs.isEmpty() ? level.getSeaLevel() : hs.get(hs.size() / 10) - 4;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) y1 = Math.max(y1, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
        y1 = Math.min(y1, y0 + 90);
        y0 = Math.max(level.getMinBuildHeight(), y0);
        int dx = x1 - x0 + 1, dz = z1 - z0 + 1, dy = y1 - y0 + 1;
        int w = (dx + dz) * k + 2 * k, h = (dx + dz) * k / 2 + dy * k + 2 * k;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xC9DDF0));
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        int ox = dz * k + k, oy = dy * k + k;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (int s = 0; s <= dx + dz - 2; s++) {
            for (int y = y0; y <= y1; y++) {
                for (int lx = Math.max(0, s - dz + 1); lx <= Math.min(dx - 1, s); lx++) {
                    int lz = s - lx;
                    int x = x0 + lx, z = z0 + lz;
                    BlockState st = level.getBlockState(m.set(x, y, z));
                    if (!solidish(st)) continue;
                    boolean up = !solidish(level.getBlockState(n.set(x, y + 1, z))) || !level.getBlockState(n).canOcclude();
                    boolean east = lx == dx - 1 || !level.getBlockState(n.set(x + 1, y, z)).canOcclude();
                    boolean south = lz == dz - 1 || !level.getBlockState(n.set(x, y, z + 1)).canOcclude();
                    if (!up && !east && !south) continue;
                    int c = color(level, m, st);
                    int yy = y - y0;
                    double[] a = proj(lx, yy + 1, lz, k, ox, oy), b = proj(lx + 1, yy + 1, lz, k, ox, oy),
                            cc = proj(lx + 1, yy + 1, lz + 1, k, ox, oy), d = proj(lx, yy + 1, lz + 1, k, ox, oy),
                            e = proj(lx + 1, yy, lz + 1, k, ox, oy), f = proj(lx + 1, yy, lz, k, ox, oy), h2 = proj(lx, yy, lz + 1, k, ox, oy);
                    if (up) poly4(g, shade(c, 1.0), a[0], a[1], b[0], b[1], cc[0], cc[1], d[0], d[1]);
                    if (east) poly4(g, shade(c, 0.78), b[0], b[1], cc[0], cc[1], e[0], e[1], f[0], f[1]);
                    if (south) poly4(g, shade(c, 0.6), d[0], d[1], cc[0], cc[1], e[0], e[1], h2[0], h2[1]);
                }
            }
        }
        g.dispose();
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        return out;
    }

    private static double[] proj(int x, int y, int z, int k, int ox, int oy) {
        return new double[]{(x - z) * k + ox, (x + z) * k / 2.0 - y * k + oy};
    }

    private static void poly4(Graphics2D g, int rgb, double ax, double ay, double bx, double by, double cx, double cy, double dx, double dy) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(ax, ay);
        p.lineTo(bx, by);
        p.lineTo(cx, cy);
        p.lineTo(dx, dy);
        p.closePath();
        g.setColor(new Color(rgb));
        g.fill(p);
    }

    /** Top-down map of the box: surface map colours, shaded by height steps. */
    public static File top(ServerLevel level, int x0, int z0, int x1, int z1, int k, File out) throws IOException {
        int dx = x1 - x0 + 1, dz = z1 - z0 + 1;
        BufferedImage img = new BufferedImage(dx * k, dz * k, BufferedImage.TYPE_INT_RGB);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int[] prev = new int[dx];
        for (int lz = 0; lz < dz; lz++) {
            for (int lx = 0; lx < dx; lx++) {
                int x = x0 + lx, z = z0 + lz;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                BlockState s = level.getBlockState(m.set(x, y, z));
                int c = color(level, m, s);
                double f = lz == 0 ? 1 : y > prev[lx] ? 1.12 : y < prev[lx] ? 0.82 : 1.0;
                prev[lx] = y;
                int rgb = shade(c, f);
                for (int a = 0; a < k; a++) for (int b = 0; b < k; b++) img.setRGB(lx * k + a, lz * k + b, rgb);
            }
        }
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        return out;
    }

    /** Plan diagram of a plan's specs (local frame not applied: world north up). */
    public static File plan(Plan plan, int from, int k, File out) throws IOException {
        int pad = 8;
        int minX = plan.cx - plan.radius - pad, minZ = plan.cz - plan.radius - pad;
        int size = (plan.radius + pad) * 2 + 1;
        BufferedImage img = new BufferedImage(size * k, size * k, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x9FBF7F));
        g.fillRect(0, 0, size * k, size * k);
        g.setColor(new Color(0x88A86E));
        g.drawOval(pad * k, pad * k, plan.radius * 2 * k, plan.radius * 2 * k);
        // pass 1: ground works; pass 2: streets; pass 3: buildings and props
        for (int pass = 0; pass < 3; pass++) {
            for (int i = from; i < plan.specs.size(); i++) {
                Spec s = plan.specs.get(i);
                int layer = s.isLine() ? 1 : s.kind.building || s.kind == Archetype.YARD || s.kind == Archetype.STALL || s.kind == Archetype.GATE ? 2 : 0;
                if (layer != pass) continue;
                if (s.isLine()) {
                    Color c = switch (s.kind) {
                        case WALL -> s.has(Spec.PALISADE) ? new Color(0x6B4A2B) : new Color(0x505050);
                        case CANAL -> new Color(0x3F76E4);
                        case BRIDGE -> new Color(0x8B6B3E);
                        default -> s.has(Spec.MAJOR) ? new Color(0xB8B0A0) : new Color(0xD2C29A);
                    };
                    g.setColor(c);
                    g.setStroke(new BasicStroke(Math.max(1, s.w * k), BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
                    g.drawLine((s.x - minX) * k + k / 2, (s.z - minZ) * k + k / 2, (s.x2 - minX) * k + k / 2, (s.z2 - minZ) * k + k / 2);
                    continue;
                }
                int hx = s.halfX() - s.margin, hz = s.halfZ() - s.margin;
                int px = (s.x - hx - minX) * k, pz = (s.z - hz - minZ) * k, pw = (2 * hx + 1) * k, ph = (2 * hz + 1) * k;
                g.setColor(colorOf(s));
                g.fillRect(px, pz, pw, ph);
                if (s.kind.building) {
                    g.setColor(new Color(0x202020));
                    g.setStroke(new BasicStroke(1));
                    g.drawRect(px, pz, pw - 1, ph - 1);
                    // the front (door side)
                    g.setColor(Color.WHITE);
                    int fx = (s.x - minX) * k + s.facing.getStepX() * hx * k, fz = (s.z - minZ) * k + s.facing.getStepZ() * hz * k;
                    g.fillRect(fx, fz, Math.max(2, k), Math.max(2, k));
                }
            }
        }
        g.dispose();
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        return out;
    }

    static Color colorOf(Spec s) {
        if (s.grammar()) {
            Use u = Use.byOrdinal(s.use);
            if (s.kind == Archetype.RESERVED) return new Color(0xFF00FF);
            return switch (u) {
                case HOVEL, SHACK -> new Color(0x8C6E52);
                case COTTAGE -> new Color(0xB08A5A);
                case HOUSE, FARMHOUSE -> new Color(0xC89B5E);
                case TOWNHOUSE -> new Color(0xD8B46A);
                case MANOR, KEEP -> new Color(0xE8D070);
                case INN -> new Color(0xD0502C);
                case TEMPLE, HALL_OF_DEAD -> new Color(0xF0F0F0);
                case GUARDHOUSE, BARRACKS, WATCHTOWER -> new Color(0x6A6A8A);
                case SMITHY, SMELTER, TANNERY, BUTCHER, FLETCHER, SAWMILL -> new Color(0x8A3A2A);
                case BARN, STABLES, MILL, WAREHOUSE, FISHERY -> new Color(0x7A5030);
                default -> new Color(0xE07A30);
            };
        }
        return switch (s.kind) {
            case PLAZA, TERRACE -> new Color(0xC8C0B0);
            case FIELD, GARDEN -> new Color(0xC8B040);
            case PEN -> new Color(0x8A7A50);
            case CAMP, LUMBER -> new Color(0x6A8A40);
            case QUARRY, MINE -> new Color(0x707070);
            case GRAVEYARD -> new Color(0x5A6A5A);
            case YARD -> new Color(0x3050C0);
            case STALL -> new Color(0xE0C030);
            case GATE, TOWER -> new Color(0x404040);
            case DOCK -> new Color(0x8B6B3E);
            case LANDMARK, PALACE -> new Color(0xFFFFFF);
            default -> s.kind.building ? new Color(0xA08060) : new Color(0x909090);
        };
    }
}
