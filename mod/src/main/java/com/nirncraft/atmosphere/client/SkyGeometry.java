package com.nirncraft.atmosphere.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.nirncraft.render.client.CpuMesh;
import com.nirncraft.render.client.WorldMesh;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;

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
    private static final int AURORA_SEGMENTS = 224;

    /** Galaxy plane normal in the celestial frame (the frame vanilla rotates the stars in). */
    private static final double[] GALAXY_N = normalize(0.28, 0.42, 0.86);

    static WorldMesh starsA;
    static WorldMesh starsB;
    static final WorldMesh[] starLayers = new WorldMesh[8];
    static WorldMesh galaxy;
    static WorldMesh constellations;
    static WorldMesh serpent;
    static WorldMesh veil;
    static final WorldMesh[] moonPhases = new WorldMesh[16];
    static WorldMesh halo;
    static final WorldMesh[][] AURORA = new WorldMesh[AURORA_BANDS][AURORA_KEYS];
    private static boolean built;
    private static long meshVertexBytes;

    private SkyGeometry() {}

    static void ensureBuilt() {
        if (built) return;
        try {
        for (int i = 0; i < starLayers.length; i++)
            starLayers[i] = upload(buildStars(new Random(0x5EC0DAL + i * 71L), 1600));
        galaxy = upload(buildGalaxy());
        constellations = upload(buildConstellations(false));
        serpent = upload(buildConstellations(true));
        veil = upload(buildVeil());
        for (int phase = 0; phase < moonPhases.length; phase++) {
            float u0 = (phase % 4) / 4f, v0 = (phase / 4) / 4f;
            moonPhases[phase] = upload(buildLunarQuad(u0, v0, u0 + .25f, v0 + .25f));
        }
        halo = upload(buildLunarQuad(0f, 0f, 1f, 1f));
        Random r = new Random(0xA0C0BAL);
        for (int b = 0; b < AURORA_BANDS; b++) {
            AuroraBand band = new AuroraBand(r, b);
            for (int k = 0; k < AURORA_KEYS; k++) {
                AURORA[b][k] = upload(band.build(k));
            }
        }
        built = true;
        } catch (RuntimeException | Error failure) {
            // A failed upload must not leave earlier meshes allocated on the next attempt.
            close();
            throw failure;
        }
    }

    static void close() {
        for(int i=0;i<starLayers.length;i++) {
            if(starLayers[i]!=null) starLayers[i].close();
            starLayers[i]=null;
        }
        for(int b=0;b<AURORA_BANDS;b++) for(int k=0;k<AURORA_KEYS;k++) {
            if(AURORA[b][k]!=null) AURORA[b][k].close();
            AURORA[b][k]=null;
        }
        for(var buffer:new WorldMesh[]{galaxy,constellations,serpent,veil}) if(buffer!=null) buffer.close();
        galaxy=constellations=serpent=veil=null;
        for (int i = 0; i < moonPhases.length; i++) {
            if (moonPhases[i] != null) moonPhases[i].close();
            moonPhases[i] = null;
        }
        if (halo != null) halo.close();
        halo = null;
        built=false;meshVertexBytes=0;
    }

    static String diagnostics() {
        int valid=0;
        for(var buffer:starLayers) if(buffer!=null && !buffer.isClosed()) valid++;
        for(var band:AURORA) for(var buffer:band) if(buffer!=null && !buffer.isClosed()) valid++;
        for(var buffer:new WorldMesh[]{galaxy,constellations,serpent,veil}) if(buffer!=null && !buffer.isClosed()) valid++;
        return "native_sky_built="+built+" native_sky_buffers="+valid+" native_sky_vertex_bytes="+meshVertexBytes
                +" native_sky_tex_stride="+(galaxy==null?-1:galaxy.format().getVertexSize())
                +" native_sky_color_stride="+(veil==null?-1:veil.format().getVertexSize());
    }

    private static WorldMesh upload(CpuMesh rendered) {
        WorldMesh mesh = new WorldMesh(rendered);
        meshVertexBytes += mesh.vertexBytes();
        return mesh;
    }

    /** Unit X/Z moon quad; per-body scale leaves its original y=-100 orbital plane unchanged. */
    private static CpuMesh buildLunarQuad(float u0, float v0, float u1, float v1) {
        try (var builder = CpuMesh.builder(PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)) {
            var bb = builder.vertices();
            bb.addVertex(-1f, -100f, 1f).setUv(u1, v1).setColor(1f, 1f, 1f, 1f);
            bb.addVertex(1f, -100f, 1f).setUv(u0, v1).setColor(1f, 1f, 1f, 1f);
            bb.addVertex(1f, -100f, -1f).setUv(u0, v0).setColor(1f, 1f, 1f, 1f);
            bb.addVertex(-1f, -100f, -1f).setUv(u1, v0).setColor(1f, 1f, 1f, 1f);
            return builder.build();
        }
    }

    // ------------------------------------------------------------------ stars

    private static CpuMesh buildStars(Random r, int count) {
        try (var nativeBuilder = CpuMesh.builder(PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)) {
        BufferBuilder bb = nativeBuilder.vertices();
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
            float size = 0.035F + (float) Math.pow(r.nextFloat(), 9) * 0.16F;
            float alpha = 0.10F + 0.65F * (float) Math.pow(r.nextFloat(), 4);
            float cr, cg, cb;
            float t = r.nextFloat();
            if (t < 0.22F) { cr = 0.72F; cg = 0.82F; cb = 1.0F; }        // blue-white
            else if (t < 0.70F) { cr = 0.95F; cg = 0.96F; cb = 1.0F; }   // white
            else if (t < 0.90F) { cr = 1.0F; cg = 0.92F; cb = 0.74F; }   // yellow
            else { cr = 1.0F; cg = 0.72F; cb = 0.55F; }                  // orange giant
            // the star sprite is a soft dot filling about a third of its quad: scale the quad to keep the star's size
            quad(bb, d, size * 2.4F, cr, cg, cb, alpha);
        }
        return nativeBuilder.build();
    
        }
    }

    private static void quad(BufferBuilder bb, double[] d, float size, float r, float g, float b, float a) {
        double[] u = perpendicular(d);
        double[] v = cross(d, u);
        double cx = d[0] * R, cy = d[1] * R, cz = d[2] * R;
        double[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (double[] c : corners) {
            bb.addVertex((float) (cx + (u[0] * c[0] + v[0] * c[1]) * size), (float) (cy + (u[1] * c[0] + v[1] * c[1]) * size), (float) (cz + (u[2] * c[0] + v[2] * c[1]) * size))
                    .setUv((float) (c[0] + 1) * 0.5F, (float) (c[1] + 1) * 0.5F)
                    .setColor(r, g, b, a);
        }
    }

    // ------------------------------------------------------------------ constellations

    /**
     * The thirteen birthsigns as original star figures. Each row: name (for reference), centre angle around the
     * celestial equator (degrees), offset off the equator (-1..1, along the celestial pole axis), star points
     * (x, y in units of {@link #SIGN_UNIT} degrees), then index pairs joined by faint lines.
     */
    private static final Object[][] SIGNS = com.nirncraft.atmosphere.NightSkyMath.SIGNS;
    private static final float SIGN_UNIT = 1.9F;

    /** All signs but the Serpent, or the Serpent alone (centred on the celestial equator at angle 0). */
    private static CpuMesh buildConstellations(boolean serpentOnly) {
        try (var nativeBuilder = CpuMesh.builder(PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)) {
        BufferBuilder bb = nativeBuilder.vertices();
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
                quad(bb, d, 0.80F, 0.85F, 0.90F, 1.0F, 1.0F);
                quad(bb, d, 2.1F, 0.55F, 0.65F, 1.0F, 0.12F); // soft glow
            }
        }
        return nativeBuilder.build();
    
        }
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
        // the constellation lines share the star sprite's buffer: its centre texel is opaque
        bb.addVertex((float) ((p[0] + side[0] * w) * R), (float) ((p[1] + side[1] * w) * R), (float) ((p[2] + side[2] * w) * R)).setUv(0.5F, 0.5F).setColor(r, g, b, a);
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return normalize(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t);
    }

    // ------------------------------------------------------------------ overcast veil

    /** A dome over the camera (down to 25 degrees below the horizon), white; tinted and faded at draw time. */
    private static CpuMesh buildVeil() {
        try (var nativeBuilder = CpuMesh.builder(PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR)) {
        BufferBuilder bb = nativeBuilder.vertices();
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
        return nativeBuilder.build();
    
        }
    }

    private static void veilVertex(BufferBuilder bb, double az, double el, float a) {
        double r = R * 0.9;
        bb.addVertex((float) (Math.sin(az) * Math.cos(el) * r), (float) (Math.sin(el) * r), (float) (Math.cos(az) * Math.cos(el) * r)).setColor(1.0F, 1.0F, 1.0F, a);
    }

    // ------------------------------------------------------------------ galaxy

    private static CpuMesh buildGalaxy() {
        try (var nativeBuilder = CpuMesh.builder(PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)) {
        BufferBuilder bb = nativeBuilder.vertices();
        double[] a = perpendicular(GALAXY_N);
        double[] b = cross(GALAXY_N, a);
        int seg = 96;
        int rows = 4;
        double halfWidth = 0.30;  // radians-ish, ~11 degrees each side
        float repeat = 1.0F;      // texture repeats around the circle (the texture tiles horizontally)
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
                    bb.addVertex((float) (d[0] * R), (float) (d[1] * R), (float) (d[2] * R))
                            .setUv((float) (p[0] / seg) * repeat, (float) (p[1] / rows))
                            .setColor(1.0F, 1.0F, 1.0F, 1.0F)
                            ;
                }
            }
        }
        return nativeBuilder.build();
    
        }
    }

    // ------------------------------------------------------------------ aurora

    static float latitudeFactor(double blockZ) {
        return com.nirncraft.atmosphere.AtmosphereMath.latitudeFactor(blockZ);
    }

    static float auroraElevationMinDeg(double blockZ) {
        return com.nirncraft.atmosphere.AtmosphereMath.auroraElevationMinDeg(blockZ);
    }

    static float auroraElevationMaxDeg(double blockZ) {
        return com.nirncraft.atmosphere.AtmosphereMath.auroraElevationMaxDeg(blockZ);
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

        CpuMesh build(int key) {
            try (var nativeBuilder = CpuMesh.builder(PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR)) {
        BufferBuilder bb = nativeBuilder.vertices();
            double kp = Math.PI * 2 * key / AURORA_KEYS;
            float teal = (float) (0.10 + 0.2 * hue);
            float[][] rowColor = {
                    {0.25F, 1.0F, 0.55F},                    // bottom edge (transparent)
                    {0.32F, 1.0F, 0.48F},                    // bright green hem
                    {0.28F, 0.98F, 0.55F},
                    {teal, 0.88F, 0.66F},                    // teal body
                    {0.28F, 0.62F, 0.80F},
                    {0.45F, 0.38F, 0.90F},                   // violet fringe
                    {0.55F, 0.30F, 0.95F}                    // top (transparent)
            };
            float[] rowAlpha = {0.0F, 0.90F, 0.62F, 0.36F, 0.18F, 0.07F, 0.0F};
            double[] rowFrac = {0.0, 0.05, 0.16, 0.36, 0.60, 0.84, 1.0};
            for (int i = 0; i < AURORA_SEGMENTS; i++) {
                for (int row = 0; row < rowFrac.length - 1; row++) {
                    int[][] pts = {{i, row}, {i, row + 1}, {i + 1, row + 1}, {i + 1, row}};
                    for (int[] p : pts) {
                        double s = (double) p[0] / AURORA_SEGMENTS;
                        double az = center - half + 2 * half * s + foldAmp * Math.sin(foldFreq * s * Math.PI * 2 + foldSpeed * kp);
                        double ends = Math.pow(Math.sin(Math.PI * s), 0.7);
                        double rays = 0.62;
                        for (int q = 0; q < 4; q++) {
                            rays += 0.30 / (q + 1) * Math.sin(rayFreq[q] * s + rayPhase[q] + raySpeed[q] * kp);
                        }
                        rays = Math.max(0.15, Math.min(1.0, rays));
                        double h = height * (0.78 + 0.30 * rays);
                        double el = baseElev + 0.03 * Math.sin(s * 5 + kp) + h * rowFrac[p[1]];
                        // the rays stand out at the hem and melt into an even glow higher up (no hairline stripes)
                        double rayMix = 1.0 - 0.75 * rowFrac[p[1]];
                        double lit = 1.0 - rayMix * (1.0 - rays);
                        double x = Math.sin(az) * Math.cos(el);
                        double y = Math.sin(el);
                        double z = -Math.cos(az) * Math.cos(el);
                        float[] c = rowColor[p[1]];
                        float alpha = (float) (rowAlpha[p[1]] * ends * lit);
                        bb.addVertex((float) (x * R), (float) (y * R), (float) (z * R)).setColor(c[0], c[1], c[2], alpha);
                    }
                }
            }
            return nativeBuilder.build();
        
        }
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
