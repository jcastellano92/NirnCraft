package com.nirncraft.client.pinned;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The pinned copy's geometry (PinTransform), box (PinBox) and snapshot volume (PinVolume): client/pinned. */
class PinTransformTest {
    private static final double EPS = 1e-6;

    @Test
    void forwardFollowsMinecraftsConventions() {
        assertArrayEquals(new double[] {0, 0, 1}, PinTransform.forward(0, 0), EPS);    // yaw 0: south (+Z)
        assertArrayEquals(new double[] {-1, 0, 0}, PinTransform.forward(90, 0), EPS);  // yaw 90: west (-X)
        assertArrayEquals(new double[] {0, 0, -1}, PinTransform.forward(180, 0), EPS); // north
        assertArrayEquals(new double[] {0, -1, 0}, PinTransform.forward(0, 90), EPS);  // pitch 90: straight down
    }

    @Test
    void scaledCopyLooksExactlyLikeTheOriginalFromTheEquivalentEye() {
        // a subject 4 km away seen from a camera on the sky stage: drawn 32 blocks away, 125 times smaller
        double ox = -1500.5, oy = 80, oz = 300.25, cx = 4096.3, cy = 528.62, cz = 4096.7;
        PinTransform t = PinTransform.view(ox, oy, oz, cx, cy, cz, 4000, 30, 10, 24, 32, 1000);
        assertEquals(32.0 / 4000.0, t.scale, 1e-12);
        assertEquals(4000, t.equivalentDistance(cx, cy, cz), 1e-6);
        double[] e = t.equivalentEye(cx, cy, cz);
        double[] f = PinTransform.forward(30, 10);
        // the equivalent eye is 4 km back from the subject along the view
        for (int k = 0; k < 3; k++) assertEquals(new double[] {ox, oy, oz}[k] - f[k] * 4000, e[k], 1e-6);
        // every point of the original: the same direction from the eye as its copy from the camera, distance x scale
        double[][] pts = {{ox, oy, oz}, {ox + 12, oy - 7, oz + 3}, {ox - 20, oy + 15, oz - 18}};
        for (double[] p : pts) {
            double[] w = t.toWorld(p[0], p[1], p[2]);
            for (int k = 0; k < 3; k++)
                assertEquals((p[k] - e[k]) * t.scale, w[k] - new double[] {cx, cy, cz}[k], 1e-9);
        }
    }

    @Test
    void nearSubjectsAreDrawnAtTheirRealSize() {
        PinTransform t = PinTransform.view(10, 64, 10, 0, 70, 0, 16, 0, 0, 12, 32, 1000);
        assertEquals(1.0, t.scale);
        assertArrayEquals(new double[] {0, 70, 16}, new double[] {t.ax, t.ay, t.az}, EPS);
        assertArrayEquals(new double[] {10, 64, -6}, t.equivalentEye(0, 70, 0), EPS);
    }

    @Test
    void theCopyStaysInsideTheFarPlane() {
        // a tiny far limit: the copy shrinks further so its far side stays in front of it
        PinTransform t = PinTransform.view(0, 0, 0, 0, 0, 0, 64000, 0, 0, 40, 32, 20);
        assertTrue((64000 + 40) * t.scale <= 20 + 1e-9);
        assertEquals(64000, t.equivalentDistance(0, 0, 0), 1e-6);
    }

    @Test
    void terrainModelViewPlacesEveryVertexWhereTheCopyBelongs() {
        // the vanilla terrain shader: pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset, with
        // ChunkPosition = (section origin - box origin) + K; then ModelViewMat * pos must be view * (copy point - camera)
        PinBox box = PinBox.of(-1530, 70, 280, -1471, 101, 321);
        double cx = 4300.37, cy = 536.62, cz = 3840.81;
        int kx = (int) Math.floor(cx) - 3, ky = (int) Math.floor(cy) + 1, kz = (int) Math.floor(cz) + 2;  // K near the camera
        PinTransform t = PinTransform.view(box.cx(), box.cy(), box.cz(), cx, cy, cz, 2048, 200, 8, box.radius(), 32, 1000);
        Matrix4f view = new Matrix4f().rotateX(0.3f).rotateY(1.1f);
        Matrix4f mv = t.terrainModelView(view, cx, cy, cz, kx, ky, kz, box.x0(), box.y0(), box.z0());
        int[][] blocks = {{-1530, 70, 280}, {-1471, 101, 321}, {-1500, 85, 300}};
        for (int[] b : blocks) {
            int sx = b[0] >> 4 << 4, sy = b[1] >> 4 << 4, sz = b[2] >> 4 << 4;  // its section's origin
            float px = b[0] - sx + 0.5f, py = b[1] - sy + 0.25f, pz = b[2] - sz + 0.75f;  // Position in the section
            int cbx = (int) Math.floor(cx), cby = (int) Math.floor(cy), cbz = (int) Math.floor(cz);
            int chx = sx - box.x0() + kx, chy = sy - box.y0() + ky, chz = sz - box.z0() + kz;
            // the shader's arithmetic: the integer difference first, then the float camera offset
            Vector3f pos = new Vector3f(px + (chx - cbx) + (float) (cbx - cx), py + (chy - cby) + (float) (cby - cy),
                    pz + (chz - cbz) + (float) (cbz - cz));
            Vector3f got = mv.transformPosition(pos, new Vector3f());
            double[] w = t.toWorld(b[0] + 0.5, b[1] + 0.25, b[2] + 0.75);
            Vector3f want = view.transformPosition(new Vector3f((float) (w[0] - cx), (float) (w[1] - cy), (float) (w[2] - cz)), new Vector3f());
            assertEquals(want.x, got.x, 1e-4);
            assertEquals(want.y, got.y, 1e-4);
            assertEquals(want.z, got.z, 1e-4);
        }
    }

    @Test
    void flyInIsGeometric() {
        double[] d = PinTransform.flyIn(32768, 8, 13);
        assertEquals(32768, d[0], EPS);
        assertEquals(8, d[12], 1e-9);
        for (int i = 1; i < d.length; i++) assertEquals(0.5, d[i] / d[i - 1], 1e-9);  // 4096x in 12 steps: halves
        assertThrows(IllegalArgumentException.class, () -> PinTransform.flyIn(10, 5, 1));
    }

    @Test
    void boxParsesRelativeCoordinatesAndIndexesLikeTheExports() {
        PinBox b = PinBox.parse(new String[] {"~-2", "~", "~3", "~2", "~4", "-7"}, 100, 64, -10);
        assertEquals(PinBox.of(98, 64, -7, 102, 68, -7), b);
        assertEquals(5, b.sx());
        assertEquals(5, b.sy());
        assertEquals(1, b.sz());
        assertEquals(0, b.index(98, 64, -7));
        assertEquals(1, b.index(99, 64, -7));
        assertEquals(5, b.index(98, 65, -7));  // x fastest, then z (one row here), then y
        assertEquals(-1, b.index(97, 64, -7));
        assertEquals(100.5, b.cx(), EPS);
        assertEquals(6, b.sectionX0());  // 98 >> 4
        assertEquals(-1, b.sectionZ0());
        assertThrows(IllegalArgumentException.class, () -> PinBox.parse(new String[] {"1", "2"}, 0, 0, 0));
    }

    @Test
    void volumeIsTheBoxAloneLitByItsMargin() {
        PinVolume v = new PinVolume(PinBox.of(0, 0, 0, 3, 2, 1));
        v.setState(1, 1, 1, 42);
        v.setLight(1, 1, 1, 12, 3);
        v.setLight(-1, 0, 0, 4, 9);  // the margin: the world's light beside the box
        v.setTint(PinVolume.TINT_GRASS, 3, 2, 1, 0xFF91BD59);
        assertEquals(42, v.state(1, 1, 1));
        assertEquals(PinVolume.OUTSIDE, v.state(-1, 0, 0));  // outside the box: air
        assertEquals(12, v.skyLight(1, 1, 1));
        assertEquals(3, v.blockLight(1, 1, 1));
        assertEquals(4, v.skyLight(-1, 0, 0));
        assertEquals(9, v.blockLight(-1, 0, 0));
        assertEquals(15, v.skyLight(-2, 0, 0));  // beyond the margin: open sky, as atlas_truth answers there
        assertEquals(0, v.blockLight(-2, 0, 0));
        assertEquals(15, v.skyLight(0, 0, 0));  // never set: open sky too
        assertEquals(0xFF91BD59, v.tint(PinVolume.TINT_GRASS, 9, 9, 9));  // outside: the nearest block's tint
        assertEquals(PinBox.of(-1, -1, -1, 4, 3, 2), v.lightBox);
        assertThrows(IndexOutOfBoundsException.class, () -> v.setState(4, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PinVolume(PinBox.of(0, 0, 0, 4095, 255, 4095)));
    }
}
