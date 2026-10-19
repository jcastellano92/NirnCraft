package com.nirncraft.render.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.util.Arrays;

/** Immutable CPU-only procedural quads, safe to carry from extraction to the GPU preparation phase. */
public final class WorldVertices {
    private final float[] attributes;
    private final int[] colors;
    private final boolean textured;
    private WorldVertices(float[] attributes, int[] colors, boolean textured) {
        this.attributes = attributes; this.colors = colors; this.textured = textured;
    }
    public boolean isEmpty() { return colors.length == 0; }
    public WorldMesh upload() {
        if (isEmpty()) return null;
        try (var builder = CpuMesh.builder(PrimitiveTopology.QUADS,
                textured ? DefaultVertexFormat.POSITION_TEX_COLOR : DefaultVertexFormat.POSITION_COLOR)) {
            var bb = builder.vertices();
            for (int vertex = 0; vertex < colors.length; vertex++) {
                int offset = vertex * 5;
                bb.addVertex(attributes[offset], attributes[offset + 1], attributes[offset + 2]).setColor(colors[vertex]);
                if (textured) bb.setUv(attributes[offset + 3], attributes[offset + 4]);
            }
            return new WorldMesh(builder.build());
        }
    }
    public static final class Builder implements VertexConsumer {
        private float[] attributes = new float[128 * 5];
        private int[] colors = new int[128];
        private final boolean textured;
        private int count;
        public Builder(boolean textured) { this.textured = textured; }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            if (count == colors.length) {
                attributes = Arrays.copyOf(attributes, attributes.length * 2);
                colors = Arrays.copyOf(colors, colors.length * 2);
            }
            int offset = count * 5;
            attributes[offset] = x; attributes[offset + 1] = y; attributes[offset + 2] = z;
            colors[count++] = -1;
            return this;
        }
        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            colors[count - 1] = (alpha << 24) | (red << 16) | (green << 8) | blue; return this;
        }
        @Override public VertexConsumer setColor(int argb) { colors[count - 1] = argb; return this; }
        @Override public VertexConsumer setUv(float u, float v) {
            int offset = (count - 1) * 5; attributes[offset + 3] = u; attributes[offset + 4] = v; return this;
        }
        // The supported unlit layouts deliberately have no normals, lightmap, overlay or line-width attributes.
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setUv3(float u, float v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
        public WorldVertices build() {
            if ((count & 3) != 0) throw new IllegalStateException("Procedural quads need four vertices per face");
            return new WorldVertices(Arrays.copyOf(attributes, count * 5), Arrays.copyOf(colors, count), textured);
        }
    }
}
