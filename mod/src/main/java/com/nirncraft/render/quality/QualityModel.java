package com.nirncraft.render.quality;

/**
 * What a knob setting costs this machine, in GPU milliseconds per frame:
 * <pre>
 *   frame(q) = base + sum_k c_k u_k(q) + kappa * atlas(q)
 *   atlas(q) = a0 + a_trace s^2 g(lod) + a_clouds s^2 cloudRes + a_shadows (0.3 s^2 + (map/1024)^2)
 * </pre>
 * u_k is the work knob k causes per frame: window megapixels x (an entry share, paid by every pixel as soon as the
 * feature is on, + the rest x its count fraction, x the far-detail factor for distance-scaled knobs, whose counts
 * fall with distance in the pack). The entry shares are pack_lab's: the air march's setup (the lamp / room lists, two
 * weather reads) costs about as much as all sixteen of its steps, so turning it off saves far more than thinning it. atlas's own GPU timestamps fit atlas(q);
 * the whole frame's timestamps fit the rest (kappa: how much of atlas's time the frame actually waits for, since it
 * runs on its own Vulkan queue). Priors: {@link HardwareTier#speed} x the per-megapixel costs below (a GTX 1080
 * class GPU = 1), from pack_lab's GPU timestamps of each pass on the lab machine (docs/ADAPTIVE-QUALITY.md); the
 * model refines them from every frame.
 */
public final class QualityModel {
    /** Joint (whole-frame) terms: base, one per pack cost, kappa. */
    static final int J_BASE = 0, J_SHADOW = 1, J_SSAO = 2, J_SSR = 3, J_SHAFTS = 4, J_WAVES = 5, J_EXTRAS = 6, J_AIR = 7,
            J_HALOS = 8, J_SMOKE = 9, J_CLOUDS = 10, J_BLOOM = 11, J_KAPPA = 12, JOINT = 13;
    /** atlas terms. */
    static final int A_BASE = 0, A_TRACE = 1, A_CLOUDS = 2, A_SHADOWS = 3, ATLAS = 4;

    /** Prior ms per megapixel at full knob on a speed-1 GPU (GTX 1080 class); base and kappa as noted. */
    static final double[] JOINT_PRIOR = {
            3.0,   // base: the G-buffer, the shadow pass, vanilla passes (per megapixel; most of it really is per pixel)
            0.40,  // shadow taps (20 PCF taps)
            0.16,  // SSAO (12 samples)
            0.50,  // SSR (32 steps where water shows)
            0.40,  // light shafts (32 samples, when the light is on screen)
            0.10,  // waves (16 per water pixel)
            0.08,  // caustics + spray
            2.60,  // the air march (16 steps): the most expensive per-pixel loop
            0.80,  // halos (12 steps, near lamps)
            0.10,  // smoke columns
            0.30,  // the pack's fallback clouds (sky pixels, no atlas)
            0.03,  // bloom taps
            1.00}; // kappa (dimensionless)
    static final double[] ATLAS_PRIOR = {
            0.4,   // atlas's fixed cost (ms, not per pixel)
            3.0,   // trace, per megapixel at scale 1 and LOD bias 1
            4.8,   // clouds, per megapixel of cloud-pass pixels (half resolution: 1/4 of the frame)
            0.8};  // sun shadows (ms at a 1024 map, + per megapixel share)

    final Rls joint, atlas;
    private final double speed;

    /** What the frame looks like: window megapixels, whether the pack and atlas are drawing. */
    public static final class Context {
        public double megapixels = 2.07;
        public boolean packActive = true, atlasActive = true;
    }

    QualityModel(double speed) {
        this.speed = speed;
        double[] j = new double[JOINT], jv = new double[JOINT];
        for (int i = 0; i < JOINT; i++) {
            j[i] = i == J_KAPPA ? JOINT_PRIOR[i] : JOINT_PRIOR[i] * speed;
            // the base varies most between machines and scenes; costs within a factor ~2 of the prior
            jv[i] = i == J_BASE ? Math.pow(j[i] * 2 + 2, 2) : i == J_KAPPA ? 0.25 : Math.pow(j[i] * 1.0 + 0.05, 2);
        }
        double[] a = new double[ATLAS], av = new double[ATLAS];
        for (int i = 0; i < ATLAS; i++) {
            a[i] = ATLAS_PRIOR[i] * speed;
            av[i] = Math.pow(a[i] + 0.2, 2);
        }
        joint = new Rls(j, jv, 0.998);
        atlas = new Rls(a, av, 0.998);
    }

    double speed() {
        return speed;
    }

    /** Far-detail work factor: 45% of pixels are assumed nearer than the falloff (keep their counts). */
    static double farWork(double qFar) {
        return 0.45 + 0.55 * Knob.farFraction(qFar);
    }

    /** atlas's features at q (index A_*). */
    static double[] atlasFeatures(double[] q, Context c, double[] out) {
        if (!c.atlasActive) {
            java.util.Arrays.fill(out, 0);
            return out;
        }
        double s = Knob.atlasScale(q[Knob.ATLAS_SCALE.ordinal()]);
        double px = c.megapixels * s * s;
        double lod = 0.55 + 0.45 * Knob.clamp01(q[Knob.ATLAS_LOD.ordinal()]);
        int map = Knob.atlasSunMap(q[Knob.ATLAS_SHADOWS.ordinal()]);
        out[A_BASE] = 1;
        out[A_TRACE] = px * lod;
        out[A_CLOUDS] = px * (Knob.ATLAS_CLOUDS.snap(q[Knob.ATLAS_CLOUDS.ordinal()]) >= 1 ? 0.25 : 0.0625);
        out[A_SHADOWS] = map == 0 ? 0 : 0.3 * px + (map / 1024.0) * (map / 1024.0);
        return out;
    }

    /** The whole frame's features at q (index J_*), given atlas's predicted ms. */
    static double[] jointFeatures(double[] q, Context c, double atlasMs, double[] out) {
        java.util.Arrays.fill(out, 0);
        double mp = c.megapixels;
        out[J_BASE] = mp;
        out[J_KAPPA] = c.atlasActive ? atlasMs : 0;
        if (!c.packActive) return out;
        double far = farWork(q[Knob.FAR_DETAIL.ordinal()]);
        out[J_SHADOW] = mp * work(Knob.SHADOW_TAPS, q, 0.1, far);
        out[J_SSAO] = mp * work(Knob.SSAO, q, 0.15, far);
        out[J_SSR] = mp * work(Knob.SSR, q, 0.5, far);
        out[J_SHAFTS] = mp * work(Knob.LIGHT_SHAFTS, q, 0.3, 1);
        out[J_WAVES] = mp * (0.25 + 0.75 * Knob.clamp01(q[Knob.WAVES.ordinal()]));
        out[J_EXTRAS] = mp * Knob.WATER_EXTRAS.snap(q[Knob.WATER_EXTRAS.ordinal()]);
        out[J_AIR] = mp * work(Knob.AIR_MARCH, q, 0.7, far);
        out[J_HALOS] = mp * work(Knob.HALOS, q, 0.5, 1);
        out[J_SMOKE] = mp * Math.max(0.125, work(Knob.SMOKE_COLUMNS, q, 0, 1));
        out[J_CLOUDS] = c.atlasActive ? 0 : mp * (0.4 + 0.6 * Knob.clamp01(q[Knob.FALLBACK_CLOUDS.ordinal()]));
        out[J_BLOOM] = mp * work(Knob.BLOOM, q, 0, 1);
        return out;
    }

    /** 0 when off; else the entry share plus the rest by count (x far, for distance-scaled counts). */
    static double work(Knob k, double[] q, double entry, double far) {
        double v = Knob.clamp01(q[k.ordinal()]);
        return v <= 0 ? 0 : entry + (1 - entry) * v * far;
    }

    private final double[] fa = new double[ATLAS], fj = new double[JOINT];

    /** atlas's predicted GPU ms at q. */
    public double atlasMs(double[] q, Context c) {
        return c.atlasActive ? atlas.predict(atlasFeatures(q, c, fa)) : 0;
    }

    /** The whole frame's predicted GPU ms at q. */
    public double frameMs(double[] q, Context c) {
        return joint.predict(jointFeatures(q, c, atlasMs(q, c), fj));
    }

    /** One whole-frame measurement (the knobs that frame really used). */
    boolean observeFrame(double[] q, Context c, double gpuMs) {
        return joint.update(jointFeatures(q, c, atlasMs(q, c), new double[JOINT]).clone(), gpuMs);
    }

    /** One atlas measurement (its own GPU timestamps). */
    boolean observeAtlas(double[] q, Context c, double ms) {
        if (!c.atlasActive) return false;
        return atlas.update(atlasFeatures(q, c, new double[ATLAS]), ms);
    }

    /** The fitted cost of knob k at full (ms at this context), for diagnostics. */
    public double costAtFull(Knob k, Context c) {
        double[] lo = new double[Knob.COUNT], hi = new double[Knob.COUNT];
        java.util.Arrays.fill(lo, 1);
        java.util.Arrays.fill(hi, 1);
        lo[k.ordinal()] = 0;
        return frameMs(hi, c) - frameMs(lo, c);
    }
}
