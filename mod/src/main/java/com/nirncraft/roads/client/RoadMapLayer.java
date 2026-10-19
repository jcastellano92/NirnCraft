package com.nirncraft.roads.client;

import com.mojang.math.Axis;
import com.nirncraft.roads.Road;
import com.nirncraft.roads.RoadsPackets;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

/**
 * Draws the planned road network on the map screen: highways as bold ink lines, roads thinner, lanes and trails only
 * when zoomed in, and the names of the historic roads (the Gold Road, the Red Ring Road...) written along them. The
 * network comes from the server ({@link RoadsPackets.NetworkLines}) as soon as roads are planned, before they are paved.
 */
public final class RoadMapLayer {
    private static final int[] COLOR = {0x7A2E14, 0x8A4A26, 0x8E6A44, 0x8E7656};
    private static final float[] WIDTH = {2.2f, 1.5f, 1.1f, 0.9f};

    private record Label(Component text, double x, double z, float angle, double length) {
    }

    private static List<Label> labels;

    private RoadMapLayer() {}

    static void invalidate() {
        labels = null;
    }

    /**
     * @param sx    world x to screen x
     * @param sy    world z to screen y
     * @param scale screen pixels per block
     */
    public static void draw(GuiGraphicsExtractor g, Font font, DoubleUnaryOperator sx, DoubleUnaryOperator sy, double scale,
                            int left, int top, int right, int bottom) {
        List<RoadsPackets.Line> lines = RoadsClient.network();
        if (lines.isEmpty()) return;
        var vertices=new it.unimi.dsi.fastutil.floats.FloatArrayList();
        var colors=new it.unimi.dsi.fastutil.ints.IntArrayList();
        int quads = 0;
        float zoom = (float) Math.max(0.6, Math.min(2.2, Math.sqrt(scale * 12)));
        // draw lesser roads first so highways lie on top
        for (int kind = Road.TRAIL; kind >= Road.HIGHWAY; kind--) {
            if (kind == Road.TRAIL && scale < 1 / 6.0) continue;
            if (kind == Road.LANE && scale < 1 / 14.0) continue;
            float w = WIDTH[kind] * zoom * 0.5f;
            int rgb = COLOR[kind];
            int alpha = kind == Road.TRAIL ? 0x90 : 0xD0;
            float r = (rgb >> 16 & 255) / 255f, gg = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f, a = alpha / 255f;
            for (RoadsPackets.Line l : lines) {
                if (l.kind() != kind) continue;
                int[] xz = l.xz();
                float px = (float) sx.applyAsDouble(xz[0]), py = (float) sy.applyAsDouble(xz[1]);
                for (int i = 1; i < xz.length / 2; i++) {
                    float qx = (float) sx.applyAsDouble(xz[i * 2]), qy = (float) sy.applyAsDouble(xz[i * 2 + 1]);
                    boolean visible = !(Math.max(px, qx) < left - 4 || Math.min(px, qx) > right + 4
                            || Math.max(py, qy) < top - 4 || Math.min(py, qy) > bottom + 4);
                    // trails are dashed: every other segment
                    if (visible && (kind != Road.TRAIL || (i & 1) == 0)) {
                        float dx = qx - px, dy = qy - py;
                        float len = (float) Math.sqrt(dx * dx + dy * dy);
                        if (len > 0.01f) {
                            float nx = -dy / len * w, ny = dx / len * w;
                            // extend each segment by half the width so the joints have no gaps
                            float ex = dx / len * w, ey = dy / len * w;
                            vertices.add(px-ex+nx);vertices.add(py-ey+ny);
                            vertices.add(qx+ex+nx);vertices.add(qy+ey+ny);
                            vertices.add(qx+ex-nx);vertices.add(qy+ey-ny);
                            vertices.add(px-ex-nx);vertices.add(py-ey-ny);
                            colors.add((alpha<<24)|rgb);
                            quads++;
                        }
                    }
                    px = qx;
                    py = qy;
                }
            }
        }
        if(quads>0) g.submitGuiElementRenderState(new RoadState(vertices.toFloatArray(),colors.toIntArray(),new org.joml.Matrix3x2f(g.pose()),g.peekScissorStack(),
            new net.minecraft.client.gui.navigation.ScreenRectangle(left-4,top-4,right-left+8,bottom-top+8).transformMaxBounds(g.pose())));
        if (scale > 1 / 45.0 && scale < 1.0) drawNames(g, font, sx, sy, scale, left, top, right, bottom);
    }

    private record RoadState(float[] xy,int[] colors,org.joml.Matrix3x2fc pose,
        net.minecraft.client.gui.navigation.ScreenRectangle scissorArea,net.minecraft.client.gui.navigation.ScreenRectangle bounds)
        implements net.minecraft.client.renderer.state.gui.GuiElementRenderState {
        @Override public com.mojang.renderpearl.api.pipeline.RenderPipeline pipeline() { return net.minecraft.client.renderer.RenderPipelines.GUI; }
        @Override public net.minecraft.client.gui.render.TextureSetup textureSetup() { return net.minecraft.client.gui.render.TextureSetup.noTexture(); }
        @Override public void buildVertices(com.mojang.blaze3d.vertex.VertexConsumer consumer) {
            for(int q=0;q<colors.length;q++) for(int v=0;v<4;v++) {
                int i=q*8+v*2;
                consumer.addVertexWith2DPose(pose,xy[i],xy[i+1]).setColor(colors[q]);
            }
        }
    }

    private static void drawNames(GuiGraphicsExtractor g, Font font, DoubleUnaryOperator sx, DoubleUnaryOperator sy, double scale,
                                  int left, int top, int right, int bottom) {
        if (labels == null) labels = buildLabels(RoadsClient.network());
        for (Label l : labels) {
            double x = sx.applyAsDouble(l.x()), y = sy.applyAsDouble(l.z());
            if (x < left || x > right || y < top || y > bottom) continue;
            int w = font.width(l.text());
            if (w * 0.75 > l.length() * scale) continue; // the road is too short on screen for its name
            g.pose().pushMatrix();
            g.pose().translate((float)x,(float)y);
            g.pose().rotate(l.angle());
            g.pose().scale(.75f,.75f);
            g.text(font, l.text(), -w / 2, -10, 0xC05A2810, false);
            g.pose().popMatrix();
        }
    }

    /** One label per historic road, at the middle of its longest stretch. */
    private static List<Label> buildLabels(List<RoadsPackets.Line> lines) {
        Map<String, List<RoadsPackets.Line>> byName = new HashMap<>();
        for (RoadsPackets.Line l : lines) {
            if (!l.name().isEmpty()) byName.computeIfAbsent(l.name(), k -> new ArrayList<>()).add(l);
        }
        List<Label> out = new ArrayList<>();
        for (Map.Entry<String, List<RoadsPackets.Line>> e : byName.entrySet()) {
            RoadsPackets.Line best = null;
            double bestLen = 0, total = 0;
            for (RoadsPackets.Line l : e.getValue()) {
                double len = length(l.xz());
                total += len;
                if (len > bestLen) {
                    bestLen = len;
                    best = l;
                }
            }
            if (best == null) continue;
            int[] xz = best.xz();
            double half = bestLen / 2, run = 0;
            for (int i = 1; i < xz.length / 2; i++) {
                double dx = xz[i * 2] - xz[i * 2 - 2], dz = xz[i * 2 + 1] - xz[i * 2 - 1];
                double seg = Math.sqrt(dx * dx + dz * dz);
                if (run + seg >= half) {
                    double t = seg < 1e-6 ? 0 : (half - run) / seg;
                    float angle = (float) Math.atan2(dz, dx);
                    if (angle > Math.PI / 2) angle -= (float) Math.PI;
                    if (angle < -Math.PI / 2) angle += (float) Math.PI;
                    out.add(new Label(Component.translatable("road.nirncraft." + e.getKey()).withStyle(ChatFormatting.ITALIC),
                            xz[i * 2 - 2] + dx * t, xz[i * 2 - 1] + dz * t, angle, Math.max(bestLen, total * 0.5)));
                    break;
                }
                run += seg;
            }
        }
        return out;
    }

    private static double length(int[] xz) {
        double len = 0;
        for (int i = 1; i < xz.length / 2; i++) {
            double dx = xz[i * 2] - xz[i * 2 - 2], dz = xz[i * 2 + 1] - xz[i * 2 - 1];
            len += Math.sqrt(dx * dx + dz * dz);
        }
        return len;
    }
}
