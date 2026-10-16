package com.skycraft.atmosphere.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.Random;

/**
 * Static sky meshes, built once on the render thread and drawn every frame: two star layers (twinkling in
 * counter-phase), the galaxy band, the thirteen birthsign constellations (the Serpent in its own buffer, because it
 * wanders), the overcast veil dome, and the aurora curtains (several keyframes per band, cross-faded at draw time
 * so the folds drift without rebuilding geometry). Everything lies on a sphere of radius {@link #R} around the
 * camera, like the vanilla sky.
 */
final class SkyGeometry {
    static final float R = 100.0F;
    static final int AURORA_BANDS = 4;
    static final int AURORA_KEYS = 6;
    private static final int AURORA_SEGMENTS = 112;

    /** Galaxy plane normal in the celestial frame (the frame vanilla rotates the stars in). */
    private static final double[] GALAXY_N = normalize(0.28, 0.42, 0.86);

    static VertexBuffer starsA;
    static VertexBuffer starsB;
    static VertexBuffer galaxy;
    static VertexBuffer constellations;
    static VertexBuffer serpent;
    static VertexBuffer veil;
    static final VertexBuffer[][] AURORA = new VertexBuffer[AURORA_BANDS][AURORA_KEYS];
    private static boolean built;

    private SkyGeometry() {}

    static void ensureBuilt() {
        if (built) return;
        built = true;
        starsA = upload(buildStars(new Random(0x5EC0DAL), 1700));
        starsB = upload(buildStars(new Random(0x3A55E1L), 1700));
        galaxy = upload(buildGalaxy());
        constellations = upload(buildConstellations(false));
        serpent = upload(buildConstellations(true));
        veil = upload(buildVeil());
        Random r = new Random(0xA0C0BAL);
        for (int b = 0; b < AURORA_BANDS; b++) {
            AuroraBand band = new AuroraBand(r, b);
            for (int k = 0; k < AURORA_KEYS; k++) {
                AURORA[b][k] = upload(band.build(k));
            }
        }
    }

    private static VertexBuffer upload(BufferBuilder.RenderedBuffer rendered) {
        VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vb.bind();
        vb.upload(rendered);
        VertexBuffer.unbind();
        return vb;
    }

    // ------------------------------------------------------------------ stars

    private static BufferBuilder.RenderedBuffer buildStars(Random r, int count) {
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        double[] a = perpendicular(GALAXY_N);
        double[] b = cross(GALAXY_N, a);
        for (int i = 0; i < count; i++) {
            double[] d;
            if (r.nextFloat() < 0.35F) {
                // crowd a third of the stars along the galaxy band
                double th = r.nextDouble() * Math.PI * 2;
                double off = r.nextGaussian() * 0.12;
                d = normalize(a[0] * Math.cos(th) + b[0] * Math.sin(th) + GALAXY_N[0] * off,
                        a[1] * Math.cos(th) + b[1] * Math.sin(th) + GALAXY_N[1] * off,
                        a[2] * Math.cos(th) + b[2] * Math.sin(th) + GALAXY_N[2] * off);
            } else {
                double z = r.nextDouble() * 2 - 1;
                double phi = r.nextDouble() * Math.PI * 2;
                double rr = Math.sqrt(1 - z * z);
                d = new double[]{rr * Math.cos(phi), z, rr * Math.sin(phi)};
            }
            float size = 0.10F + (float) Math.pow(r.nextFloat(), 7) * 0.42F;
            float alpha = 0.30F + 0.70F * r.nextFloat() * r.nextFloat();
            if (size > 0.3F) alpha = 1.0F;
            float cr, cg, cb;
            float t = r.nextFloat();
            if (t < 0.22F) { cr = 0.72F; cg = 0.82F; cb = 1.0F; }        // blue-white
            else if (t < 0.70F) { cr = 0.95F; cg = 0.96F; cb = 1.0F; }   // white
            else if (t < 0.90F) { cr = 1.0F; cg = 0.92F; cb = 0.74F; }   // yellow
            else { cr = 1.0F; cg = 0.72F; cb = 0.55F; }                  // orange giant
            quad(bb, d, size, cr, cg, cb, alpha);
        }
        return bb.end();
    }

    private static void quad(BufferBuilder bb, double[] d, float size, float r, float g, float b, float a) {
        double[] u = perpendicular(d);
        double[] v = cross(d, u);
        double cx = d[0] * R, cy = d[1] * R, cz = d[2] * R;
        double[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (double[] c : corners) {
            bb.vertex(cx + (u[0] * c[0] + v[0] * c[1]) * size,
                            cy + (u[1] * c[0] + v[1] * c[1]) * size,
                            cz + (u[2] * c[0] + v[2] * c[1]) * size)
                    .color(r, g, b, a).endVertex();
        }
    }

    // ------------------------------------------------------------------ constellations

    /**
     * The thirteen birthsigns as original star figures. Each row: name (for reference), centre angle around the
     * celestial equator (degrees), offset off the equator (-1..1, along the celestial pole axis), star points
     * (x, y in units of {@link #SIGN_UNIT} degrees), then index pairs joined by faint lines.
     */
    private static final Object[][] SIGNS = {
            {"warrior", 10f, 0.30f, new float[]{0, -3, 0, -1, 0, 1, 0, 3.5f, -1.5f, 1, 1.5f, 1, 0, 4.5f}, new int[]{0, 1, 1, 2, 2, 3, 4, 2, 2, 5, 3, 6}},
            {"mage", 38f, -0.25f, new float[]{0, -3.5f, 0, -1, 0, 1.5f, -1, 2.5f, 0, 3.5f, 1, 2.5f}, new int[]{0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 2}},
            {"thief", 66f, 0.40f, new float[]{-2.5f, -1, -1, 1, 0, -0.5f, 1, 1.5f, 2.5f, 0}, new int[]{0, 1, 1, 2, 2, 3, 3, 4}},
            {"lady", 94f, -0.10f, new float[]{0, 3, -1, 1.5f, 1, 1.5f, -2, -2, 2, -2, 0, 0}, new int[]{0, 1, 0, 2, 1, 5, 2, 5, 5, 3, 5, 4, 3, 4}},
            {"steed", 122f, 0.35f, new float[]{-3, -1, -1.5f, 0, 0, 0.5f, 1.5f, 2, 2.5f, 1, 1, -1, -1, -2}, new int[]{0, 1, 1, 2, 2, 3, 3, 4, 2, 5, 1, 6}},
            {"lord", 150f, -0.35f, new float[]{-2, 0, -1, 1.5f, 0, 0.5f, 1, 1.5f, 2, 0, 0, -2}, new int[]{0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0}},
            {"apprentice", 178f, 0.15f, new float[]{0, 0, 0, 2, 1.5f, -1, -1.5f, -1, 0, -2.5f}, new int[]{0, 1, 0, 2, 0, 3, 0, 4}},
            {"atronach", 206f, -0.30f, new float[]{0, 2, 1.4f, 1.4f, 2, 0, 1.4f, -1.4f, 0, -2, -1.4f, -1.4f, -2, 0, -1.4f, 1.4f}, new int[]{0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 0}},
            {"ritual", 234f, 0.30f, new float[]{-2, 2, -2.5f, 0, -1.5f, -2, 0.5f, -2.5f, 2, -1.5f, 0, 0}, new int[]{0, 1, 1, 2, 2, 3, 3, 4, 2, 5}},
            {"lover", 262f, -0.15f, new float[]{-2, 1, -1, 2, 0, 1, 1, 2, 2, 1, 0, -2}, new int[]{0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 0}},
            {"shadow", 290f, 0.40f, new float[]{0, 2.5f, -2, -2, 2, -2, 0, -0.5f}, new int[]{0, 1, 1, 3, 3, 2, 2, 0}},
            {"tower", 318f, -0.25f, new float[]{-1, -3, -1, 2, 1, 2, 1, -3, -1, 3, 0, 2.5f, 1, 3}, new int[]{0, 1, 1, 2, 2, 3, 1, 4, 4, 5, 5, 6, 6, 2}},
            {"serpent", 0f, 0f, new float[]{-4, 0, -3, 1, -1.5f, 0.5f, 0, -0.5f, 1.5f, -0.5f, 3, 0.5f, 4, 1.5f}, new int[]{0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6}},
    };
    private static final float SIGN_UNIT = 1.9F;

    /** All signs but the Serpent, or the Serpent alone (centred on the celestial equator at angle 0). */
    private static BufferBuilder.RenderedBuffer buildConstellations(boolean serpentOnly) {
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (Object[] sign : SIGNS) {
            boolean isSerpent = "serpent".equals(sign[0]);
            if (isSerpent != serpentOnly) continue;
            double ang = Math.toRadians((Float) sign[1]);
            double off = (Float) sign[2];
            // celestial frame: vanilla spins the sky about the local X axis, so the "equator" is the Y-Z circle
            double[] c = normalize(off, Math.cos(ang), Math.sin(ang));
            double[] u = cross(c, cross(new double[]{1, 0, 0}, c)); // the pole axis projected onto the tangent plane
            double[] v = cross(c, u);
            float[] pts = (float[]) sign[3];
            int[] lines = (int[]) sign[4];
            double unit = Math.toRadians(SIGN_UNIT);
            double[][] dirs = new double[pts.length / 2][];
            for (int i = 0; i < dirs.length; i++) {
                double x = pts[i * 2] * unit, y = pts[i * 2 + 1] * unit;
                dirs[i] = normalize(c[0] + u[0] * y + v[0] * x, c[1] + u[1] * y + v[1] * x, c[2] + u[2] * y + v[2] * x);
            }
            for (int i = 0; i < lines.length; i += 2) {
                line(bb, dirs[lines[i]], dirs[lines[i + 1]], 0.07F, 0.62F, 0.72F, 0.95F, 0.16F);
            }
            for (double[] d : dirs) {
                quad(bb, d, 0.34F, 0.85F, 0.90F, 1.0F, 1.0F);
                quad(bb, d, 0.85F, 0.55F, 0.65F, 1.0F, 0.10F); // soft glow
            }
        }
        return bb.end();
    }

    /** A thin quad along the sky sphere between two directions (trimmed so it doesn't run through the stars). */
    private static void line(BufferBuilder bb, double[] a, double[] b, float width, float r, float g, float bl, float alpha) {
        double[] mid = normalize(a[0] + b[0], a[1] + b[1], a[2] + b[2]);
        double[] along = normalize(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
        double[] side = cross(mid, along);
        double[] p0 = lerp(a, b, 0.12), p1 = lerp(a, b, 0.88);
        double w = width / R;
        lineVertex(bb, p0, side, w, r, g, bl, alpha);
        lineVertex(bb, p1, side, w, r, g, bl, alpha);
        lineVertex(bb, p1, side, -w, r, g, bl, alpha);
        lineVertex(bb, p0, side, -w, r, g, bl, alpha);
    }

    private static void lineVertex(BufferBuilder bb, double[] p, double[] side, double w, float r, float g, float b, float a) {
        bb.vertex((p[0] + side[0] * w) * R, (p[1] + side[1] * w) * R, (p[2] + side[2] * w) * R).color(r, g, b, a).endVertex();
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return normalize(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t);
    }

    // ------------------------------------------------------------------ overcast veil

    /** A dome over the camera (down to 25 degrees below the horizon), white; tinted and faded at draw time. */
    private static BufferBuilder.RenderedBuffer buildVeil() {
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int seg = 32;
        double[] elev = {-25, -5, 8, 25, 50, 90};
        float[] alpha = {1.0F, 1.0F, 0.95F, 0.85F, 0.8F, 0.8F};
        for (int i = 0; i < seg; i++) {
            double a0 = Math.PI * 2 * i / seg, a1 = Math.PI * 2 * (i + 1) / seg;
            for (int j = 0; j < elev.length - 1; j++) {
                double e0 = Math.toRadians(elev[j]), e1 = Math.toRadians(elev[j + 1]);
                veilVertex(bb, a0, e0, alpha[j]);
                veilVertex(bb, a0, e1, alpha[j + 1]);
                veilVertex(bb, a1, e1, alpha[j + 1]);
                veilVertex(bb, a1, e0, alpha[j]);
            }
        }
        return bb.end();
    }

    private static void veilVertex(BufferBuilder bb, double az, double el, float a) {
        double r = R * 0.9;
        bb.vertex(Math.sin(az) * Math.cos(el) * r, Math.sin(el) * r, Math.cos(az) * Math.cos(el) * r).color(1.0F, 1.0F, 1.0F, a).endVertex();
    }

    // ------------------------------------------------------------------ galaxy

    private static BufferBuilder.RenderedBuffer buildGalaxy() {
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        double[] a = perpendicular(GALAXY_N);
        double[] b = cross(GALAXY_N, a);
        int seg = 96;
        int rows = 4;
        double halfWidth = 0.20;  // radians-ish, ~11 degrees each side
        float repeat = 3.0F;      // texture repeats around the circle (the texture tiles horizontally)
        for (int i = 0; i < seg; i++) {
            for (int j = 0; j < rows; j++) {
                double[][] pts = {
                        {i, j}, {i + 1, j}, {i + 1, j + 1}, {i, j + 1}
                };
                for (double[] p : pts) {
                    double th = p[0] / seg * Math.PI * 2;
                    double w = (p[1] / rows * 2 - 1) * halfWidth * (1.0 + 0.25 * Math.sin(th * 3 + 1.0));
                    double[] d = normalize(a[0] * Math.cos(th) + b[0] * Math.sin(th) + GALAXY_N[0] * w,
                            a[1] * Math.cos(th) + b[1] * Math.sin(th) + GALAXY_N[1] * w,
                            a[2] * Math.cos(th) + b[2] * Math.sin(th) + GALAXY_N[2] * w);
                    bb.vertex(d[0] * R, d[1] * R, d[2] * R)
                            .uv((float) (p[0] / seg) * repeat, (float) (p[1] / rows))
                            .color(1.0F, 1.0F, 1.0F, 1.0F)
                            .endVertex();
                }
            }
        }
        return bb.end();
    }

    // ------------------------------------------------------------------ aurora

    static float latitudeFactor(double blockZ) {
        return com.skycraft.atmosphere.AtmosphereMath.latitudeFactor(blockZ);
    }

    static float auroraElevationMinDeg(double blockZ) {
        return com.skycraft.atmosphere.AtmosphereMath.auroraElevationMinDeg(blockZ);
    }

    static float auroraElevationMaxDeg(double blockZ) {
        return com.skycraft.atmosphere.AtmosphereMath.auroraElevationMaxDeg(blockZ);
    }

    /**
     * One curtain in the northern sky (Minecraft north is -Z); keyframes loop seamlessly.
     * Built in the reference Skyrim elevation band (18°..42°); {@link SkyRenderer} pitches and scales each band
     * continuously by world-Z latitude so it rises into overhead coronas (38°..76°) in Atmora and sinks to a low
     * northern horizon glow (4°..16°) in Cyrodiil before vanishing south of Z = +6000.
     */
    private static final class AuroraBand {
        final double center, half, baseElev, height, foldAmp, foldFreq, hue;
        final double[] rayFreq = new double[4];
        final double[] rayPhase = new double[4];
        final int[] raySpeed = new int[4];
        final int foldSpeed;

        AuroraBand(Random r, int index) {
            center = Math.toRadians(-35 + 70 * r.nextDouble() + (index - 1.5) * 12);
            half = Math.toRadians(45 + 30 * r.nextDouble());
            baseElev = Math.toRadians(18 + 6 * r.nextDouble());
            height = Math.toRadians(20 + 6 * r.nextDouble());
            foldAmp = Math.toRadians(3 + 5 * r.nextDouble());
            foldFreq = 1.5 + 2.5 * r.nextDouble();
            hue = r.nextDouble();
            foldSpeed = r.nextBoolean() ? 1 : -1;
            for (int i = 0; i < 4; i++) {
                rayFreq[i] = 9 + r.nextDouble() * (20 + i * 25);
                rayPhase[i] = r.nextDouble() * Math.PI * 2;
                raySpeed[i] = (r.nextBoolean() ? 1 : -1) * (1 + r.nextInt(2));
            }
        }

        BufferBuilder.RenderedBuffer build(int key) {
            BufferBuilder bb = Tesselator.getInstance().getBuilder();
            bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            double kp = Math.PI * 2 * key / AURORA_KEYS;
            float[][] rowColor = {
                    {0.25F, 1.0F, 0.55F},                                           // bottom edge (transparent)
                    {0.30F, 1.0F, 0.50F},                                           // bright green hem
                    {(float) (0.10 + 0.2 * hue), 0.85F, (float) (0.65 + 0.2 * hue)}, // teal body
                    {0.55F, 0.30F, 0.95F}                                           // violet fringe (transparent)
            };
            float[] rowAlpha = {0.0F, 0.85F, 0.40F, 0.0F};
            double[] rowFrac = {0.0, 0.10, 0.45, 1.0};
            for (int i = 0; i < AURORA_SEGMENTS; i++) {
                for (int row = 0; row < 3; row++) {
                    int[][] pts = {{i, row}, {i, row + 1}, {i + 1, row + 1}, {i + 1, row}};
                    for (int[] p : pts) {
                        double s = (double) p[0] / AURORA_SEGMENTS;
                        double az = center - half + 2 * half * s + foldAmp * Math.sin(foldFreq * s * Math.PI * 2 + foldSpeed * kp);
                        double ends = Math.pow(Math.sin(Math.PI * s), 0.7);
                        double rays = 0.55;
                        for (int q = 0; q < 4; q++) {
                            rays += 0.45 / (q + 1) * Math.sin(rayFreq[q] * s + rayPhase[q] + raySpeed[q] * kp);
                        }
                        rays = Math.max(0.05, Math.min(1.0, rays));
                        double h = height * (0.75 + 0.35 * rays);
                        double el = baseElev + 0.03 * Math.sin(s * 5 + kp) + h * rowFrac[p[1]];
                        double x = Math.sin(az) * Math.cos(el);
                        double y = Math.sin(el);
                        double z = -Math.cos(az) * Math.cos(el);
                        float[] c = rowColor[p[1]];
                        float alpha = (float) (rowAlpha[p[1]] * ends * rays);
                        bb.vertex(x * R, y * R, z * R).color(c[0], c[1], c[2], alpha).endVertex();
                    }
                }
            }
            return bb.end();
        }
    }

    // ------------------------------------------------------------------ math

    private static double[] normalize(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l};
    }

    private static double[] cross(double[] a, double[] b) {
        return normalize(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]);
    }

    private static double[] perpendicular(double[] d) {
        double[] up = Math.abs(d[1]) < 0.9 ? new double[]{0, 1, 0} : new double[]{1, 0, 0};
        return cross(d, up);
    }
}
