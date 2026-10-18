package com.skycraft.sailing.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * A baked list of textured quads in ship-local block units, built from boxes the way a ship would be built from
 * blocks: every face is cut into one-block cells, each showing a 16 px tile of the sailing atlas
 * ({@code textures/entity/sailing/ship_atlas.png}, 256 x 256, 16 x 16 tiles), so planks read like block planks. Boxes
 * can be placed through a transform stack (for angled parts: stems, dragon necks, rigging, oars).
 *
 * <p>Face axes: on the X faces the tile runs along Z (across) and Y (up); on the Z faces along X and Y; on the Y
 * faces along Z and X. Plank tiles have their grain along the first axis, so hull strakes and deck boards run
 * fore-and-aft.</p>
 */
public final class ShipMesh {
    public static final float ATLAS = 256f;
    private static final int STRIDE = 23; // 4 x (x y z u v) + normal

    /** A material: an atlas tile, optionally stretched over the whole face (shields, eyes, emblems). */
    public record Mat(int tile, boolean stretch) {
        public static Mat of(int tile) {
            return new Mat(tile, false);
        }

        public Mat stretched() {
            return new Mat(tile, true);
        }

        float u0() {
            return (tile % 16) * 16f;
        }

        float v0() {
            return (tile / 16) * 16f;
        }
    }

    private float[] data = new float[STRIDE * 256];
    private int quads;
    private final Deque<Matrix4f> stack = new ArrayDeque<>();
    private Matrix4f m = new Matrix4f();

    public int quadCount() {
        return quads;
    }

    // ------------------------------------------------------------------ transform

    public ShipMesh push() {
        stack.push(new Matrix4f(m));
        return this;
    }

    public ShipMesh pop() {
        m = stack.pop();
        return this;
    }

    public ShipMesh translate(float x, float y, float z) {
        m.translate(x, y, z);
        return this;
    }

    /** Rotation in degrees around the local X, Y, Z axes (in that order). */
    public ShipMesh rotate(float rx, float ry, float rz) {
        if (rx != 0) m.rotateX((float) Math.toRadians(rx));
        if (ry != 0) m.rotateY((float) Math.toRadians(ry));
        if (rz != 0) m.rotateZ((float) Math.toRadians(rz));
        return this;
    }

    // ------------------------------------------------------------------ geometry

    /** An axis-aligned box (in the current transform). */
    public ShipMesh box(float x0, float y0, float z0, float x1, float y1, float z1, Mat mat) {
        if (x1 < x0) { float t = x0; x0 = x1; x1 = t; }
        if (y1 < y0) { float t = y0; y0 = y1; y1 = t; }
        if (z1 < z0) { float t = z0; z0 = z1; z1 = t; }
        // +X / -X faces: s along z, t along y
        face(new float[]{x1, y0, z0}, new float[]{0, 0, 1}, new float[]{0, 1, 0}, z1 - z0, y1 - y0, new float[]{1, 0, 0}, mat);
        face(new float[]{x0, y0, z1}, new float[]{0, 0, -1}, new float[]{0, 1, 0}, z1 - z0, y1 - y0, new float[]{-1, 0, 0}, mat);
        // +Z / -Z faces: s along x, t along y
        face(new float[]{x1, y0, z1}, new float[]{-1, 0, 0}, new float[]{0, 1, 0}, x1 - x0, y1 - y0, new float[]{0, 0, 1}, mat);
        face(new float[]{x0, y0, z0}, new float[]{1, 0, 0}, new float[]{0, 1, 0}, x1 - x0, y1 - y0, new float[]{0, 0, -1}, mat);
        // +Y / -Y faces: s along z, t along x
        face(new float[]{x0, y1, z0}, new float[]{0, 0, 1}, new float[]{1, 0, 0}, z1 - z0, x1 - x0, new float[]{0, 1, 0}, mat);
        face(new float[]{x1, y0, z0}, new float[]{0, 0, 1}, new float[]{-1, 0, 0}, z1 - z0, x1 - x0, new float[]{0, -1, 0}, mat);
        return this;
    }

    /** A box of square section {@code thick} running from point a to point b (rigging, poles, necks). */
    public ShipMesh line(float ax, float ay, float az, float bx, float by, float bz, float thick, Mat mat) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4) return this;
        float yaw = (float) Math.toDegrees(Math.atan2(dx, dz));
        float pitch = (float) -Math.toDegrees(Math.asin(dy / len));
        push().translate(ax, ay, az).rotate(0, yaw, 0).rotate(pitch, 0, 0);
        float h = thick / 2f;
        box(-h, -h, 0, h, h, len, mat);
        return pop();
    }

    /** A single quad face (used by {@link #box}): origin corner, unit s and t directions, sizes and normal. */
    private void face(float[] o, float[] s, float[] t, float sw, float th, float[] n, Mat mat) {
        if (sw <= 1e-5 || th <= 1e-5) return;
        if (mat.stretch()) {
            quad(o, s, t, 0, 0, sw, th, n, mat.u0(), mat.v0(), mat.u0() + 16, mat.v0() + 16);
            return;
        }
        for (float s0 = 0; s0 < sw - 1e-4f; s0 += 1f) {
            float s1 = Math.min(sw, s0 + 1f);
            for (float t0 = 0; t0 < th - 1e-4f; t0 += 1f) {
                float t1 = Math.min(th, t0 + 1f);
                // tiles are painted top-down: v grows downwards while t grows upwards
                quad(o, s, t, s0, t0, s1, t1, n, mat.u0(), mat.v0() + 16 - (t1 - t0) * 16, mat.u0() + (s1 - s0) * 16, mat.v0() + 16);
            }
        }
    }

    private void quad(float[] o, float[] s, float[] t, float s0, float t0, float s1, float t1, float[] n,
                      float u0, float v0, float u1, float v1) {
        ensure();
        int i = quads * STRIDE;
        float[][] corners = {{s0, t0, u0, v1}, {s1, t0, u1, v1}, {s1, t1, u1, v0}, {s0, t1, u0, v0}};
        Vector3f p = new Vector3f();
        for (float[] c : corners) {
            p.set(o[0] + s[0] * c[0] + t[0] * c[1], o[1] + s[1] * c[0] + t[1] * c[1], o[2] + s[2] * c[0] + t[2] * c[1]);
            m.transformPosition(p);
            data[i++] = p.x;
            data[i++] = p.y;
            data[i++] = p.z;
            data[i++] = c[2] / ATLAS;
            data[i++] = c[3] / ATLAS;
        }
        Vector3f nv = new Vector3f(n[0], n[1], n[2]);
        m.transformDirection(nv);
        nv.normalize();
        data[i++] = nv.x;
        data[i++] = nv.y;
        data[i] = nv.z;
        quads++;
    }

    /** A free quad with explicit corners (counter-clockwise) and atlas pixel UVs per corner (sails, flags). */
    public void rawQuad(float[][] pos, float[][] uv) {
        ensure();
        int i = quads * STRIDE;
        Vector3f a = new Vector3f(pos[1][0] - pos[0][0], pos[1][1] - pos[0][1], pos[1][2] - pos[0][2]);
        Vector3f b = new Vector3f(pos[3][0] - pos[0][0], pos[3][1] - pos[0][1], pos[3][2] - pos[0][2]);
        Vector3f nv = a.cross(b);
        if (nv.lengthSquared() < 1e-12f) nv.set(0, 1, 0);
        nv.normalize();
        Vector3f p = new Vector3f();
        for (int k = 0; k < 4; k++) {
            p.set(pos[k][0], pos[k][1], pos[k][2]);
            m.transformPosition(p);
            data[i++] = p.x;
            data[i++] = p.y;
            data[i++] = p.z;
            data[i++] = uv[k][0] / ATLAS;
            data[i++] = uv[k][1] / ATLAS;
        }
        m.transformDirection(nv);
        data[i++] = nv.x;
        data[i++] = nv.y;
        data[i] = nv.z;
        quads++;
    }

    private void ensure() {
        if ((quads + 1) * STRIDE > data.length) data = Arrays.copyOf(data, data.length * 2);
    }

    public void clear() {
        quads = 0;
        stack.clear();
        m = new Matrix4f();
    }

    // ------------------------------------------------------------------ rendering

    public void render(PoseStack.Pose pose, VertexConsumer vc, int light, int overlay, float r, float g, float b, float a) {
        Matrix4f pm = pose.pose();
        Matrix3f nm = pose.normal();
        float[] d = data;
        for (int q = 0; q < quads; q++) {
            int i = q * STRIDE;
            float nx = d[i + 20], ny = d[i + 21], nz = d[i + 22];
            for (int k = 0; k < 4; k++) {
                int j = i + k * 5;
                vc.addVertex(pm, d[j], d[j + 1], d[j + 2]).setColor(r, g, b, a).setUv(d[j + 3], d[j + 4]).setOverlay(overlay)
                        .setLight(light).setNormal(pose, nx, ny, nz);
            }
        }
    }
}
