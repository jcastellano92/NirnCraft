package com.nirncraft.roads;

import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.PlaceType;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.TamrielMap;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Renders the planned road network over the Tamriel map to a PNG (operator command {@code /nirncraft-roads render}):
 * relief-shaded land, water, every planned road by class, links still waiting to be planned as thin grey lines, failed
 * links in red, settlements as dots and the major names. A development aid for checking the plan by eye.
 */
final class NetworkRender {
    record Snapshot(List<int[]> xz, List<Byte> kinds, List<int[]> pending, List<int[]> failed, List<int[]> towns,
                    List<int[]> sites) {
    }

    private NetworkRender() {}

    static Snapshot snapshot(RoadsData data) {
        List<int[]> xz = new java.util.ArrayList<>();
        List<Byte> kinds = new java.util.ArrayList<>();
        for (Road r : data.roads.values()) {
            RoadsPackets.Line l = RoadsPackets.simplify(r);
            xz.add(l.xz());
            kinds.add(r.kind);
        }
        List<int[]> pending = new java.util.ArrayList<>();
        for (long p : data.pending) addPair(data, p, pending);
        for (long p : data.inFlight) addPair(data, p, pending);
        List<int[]> failed = new java.util.ArrayList<>();
        for (long p : data.failed) addPair(data, p, failed);
        List<int[]> towns = new java.util.ArrayList<>(), sites = new java.util.ArrayList<>();
        for (Settlement s : data.settlements) (s.town ? towns : sites).add(new int[]{s.x, s.z, s.placeId.isEmpty() ? 0 : 1});
        return new Snapshot(xz, kinds, pending, failed, towns, sites);
    }

    private static void addPair(RoadsData data, long p, List<int[]> out) {
        Settlement a = data.settlement(RoadsData.pairA(p)), b = data.settlement(RoadsData.pairB(p));
        if (a != null && b != null) out.add(new int[]{a.x, a.z, b.x, b.z});
    }

    /** Draws the area [x0, x1) x [z0, z1) at {@code bpp} blocks per pixel and writes it to {@code file}. */
    static void render(Snapshot snap, int x0, int z0, int x1, int z1, int bpp, File file) throws IOException {
        int w = Math.max(1, (x1 - x0) / bpp), h = Math.max(1, (z1 - z0) / bpp);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        MapSample s = new MapSample();
        float[] elev = new float[w * h];
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                TamrielMap.sample(x0 + px * bpp, z0 + py * bpp, s);
                elev[py * w + px] = s.elevation;
                int rgb;
                if (s.region == 0 || s.coastDistance < 0) rgb = 0x2E5C8A;
                else if (s.inWater()) rgb = 0x3C78B4;
                else {
                    float e = Math.max(0, Math.min(1, (s.elevation - 63) / 260f));
                    int r = (int) (110 + 120 * e), g = (int) (140 + 60 * e - 40 * e * e), b = (int) (80 + 140 * e * e);
                    if (s.road > 0.3f) {
                        r = (r * 3 + 200) / 4;
                        g = (g * 3 + 180) / 4;
                        b = (b * 3 + 120) / 4;
                    }
                    rgb = (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
                }
                img.setRGB(px, py, rgb);
            }
        }
        // hill shading from the north-west
        for (int py = 1; py < h; py++) {
            for (int px = 1; px < w; px++) {
                float d = elev[py * w + px] - elev[(py - 1) * w + px - 1];
                int rgb = img.getRGB(px, py);
                float f = Math.max(0.6f, Math.min(1.35f, 1f + d / bpp * 0.6f));
                int r = Math.min(255, (int) (((rgb >> 16) & 255) * f)), g = Math.min(255, (int) (((rgb >> 8) & 255) * f)),
                        b = Math.min(255, (int) ((rgb & 255) * f));
                img.setRGB(px, py, (r << 16) | (g << 8) | b);
            }
        }
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float px = 1f / bpp;
        g.setColor(new Color(90, 90, 90, 160));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{4f, 4f}, 0f));
        for (int[] p : snap.pending()) g.drawLine((p[0] - x0) / bpp, (p[1] - z0) / bpp, (p[2] - x0) / bpp, (p[3] - z0) / bpp);
        g.setColor(new Color(220, 30, 30, 200));
        for (int[] p : snap.failed()) g.drawLine((p[0] - x0) / bpp, (p[1] - z0) / bpp, (p[2] - x0) / bpp, (p[3] - z0) / bpp);
        Color[] colors = {new Color(120, 20, 10), new Color(170, 60, 20), new Color(200, 120, 40), new Color(90, 70, 50)};
        float[] widths = {Math.max(2.2f, 40 * px), Math.max(1.6f, 30 * px), Math.max(1.1f, 20 * px), Math.max(0.8f, 12 * px)};
        for (int kind = 3; kind >= 0; kind--) {
            g.setColor(colors[kind]);
            g.setStroke(new BasicStroke(widths[kind], BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < snap.xz().size(); i++) {
                if (snap.kinds().get(i) != kind) continue;
                int[] xz = snap.xz().get(i);
                Path2D.Float path = new Path2D.Float();
                path.moveTo((xz[0] - x0) * px, (xz[1] - z0) * px);
                for (int k = 1; k < xz.length / 2; k++) path.lineTo((xz[k * 2] - x0) * px, (xz[k * 2 + 1] - z0) * px);
                g.draw(path);
            }
        }
        g.setColor(new Color(40, 40, 40));
        for (int[] t : snap.sites()) g.fillRect((int) ((t[0] - x0) * px) - 1, (int) ((t[1] - z0) * px) - 1, 2, 2);
        for (int[] t : snap.towns()) {
            g.setColor(t[2] == 1 ? Color.BLACK : new Color(60, 60, 140));
            int r = t[2] == 1 ? 3 : 2;
            g.fillOval((int) ((t[0] - x0) * px) - r, (int) ((t[1] - z0) * px) - r, r * 2, r * 2);
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(10, Math.min(16, (int) (220 * px)))));
        for (Place p : Places.all()) {
            boolean major = p.type() == PlaceType.CAPITAL || p.type() == PlaceType.CITY;
            if (!major && !(bpp <= 4 && p.type().isSettlement())) continue;
            int sx = (int) ((p.x() - x0) * px), sy = (int) ((p.z() - z0) * px);
            if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
            String name = p.name().getString();
            g.setColor(Color.WHITE);
            g.drawString(name, sx + 5, sy - 3);
            g.setColor(Color.BLACK);
            g.drawString(name, sx + 4, sy - 4);
        }
        g.dispose();
        ImageIO.write(img, "png", file);
    }
}
