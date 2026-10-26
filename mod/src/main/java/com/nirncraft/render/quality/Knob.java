package com.nirncraft.render.quality;

/**
 * One continuous quality knob, 0..1: 1 is the most the renderer draws (the shader pack's #define ceiling, atlas's
 * full resolution), 0 its floor. Every knob carries
 * <ul>
 *   <li>a <b>visual weight</b> (how much of the look is lost when it sits at 0) and a <b>shape</b> alpha of its value
 *   curve {@code V(q) = w (1 - e^(-alpha q)) / (1 - e^(-alpha))}: alpha near 0 is linear (every sample shows, e.g.
 *   waves), a large alpha means the first samples give nearly all of the look (the air march, halos);</li>
 *   <li>a <b>work</b> shape: how the GPU work it causes grows with q ({@link QualityModel} turns work into ms with
 *   fitted per-machine costs);</li>
 *   <li>a ladder (how finely it moves; discrete knobs jump), a floor and an "off below" threshold.</li>
 * </ul>
 * Weights: the pack's knobs from pack_lab image differences against an all-maximum reference (native/tools/
 * pack_lab_quality.py: Oklab, 1-px blur, mean + 0.25 p95 dE over five scenes); what the lab cannot show (shadow
 * taps: no shadow map offline; smoke columns; atlas's own knobs) is set by judgment. Table and method:
 * docs/ADAPTIVE-QUALITY.md.
 */
public enum Knob {
    // ---- the shader pack (lib/quality.glsl texel, channel)
    /** Soft-shadow taps (1 .. SHADOW_TAPS) and penumbra width; fewer far away. Floor: one tap (shadows stay). */
    SHADOW_TAPS(Owner.PACK, 1.5, 3.0, 0.05, 0.0, 1, 0, true),
    /** SSAO samples; fewer far away. Floor: its fewest samples (lighting is never switched off: the owner, 10-26). */
    SSAO(Owner.PACK, 1.0, 2.5, 0.2, 0.2, 2, 0, true),
    /** Screen-space reflection steps (water); beyond the march the far field / sky mirror. */
    SSR(Owner.PACK, 1.5, 4.0, 0.0, 0.2, 3, 0, true),
    /** Light-shaft samples over the same path. */
    LIGHT_SHAFTS(Owner.PACK, 0.6, 7.0, 0.15, 0.15, 4, 0, false),
    /** Waves drawn (4 .. WAVE_COUNT, shortest first) and their LOD distance. */
    WAVES(Owner.PACK, 1.9, 0.3, 0.0, 0.0, 5, 0, false),
    /** Caustics and falls spray (on above 0.5). */
    WATER_EXTRAS(Owner.PACK, 0.3, 1.0, 0.0, 0.5, 5, 2, false),
    /** The near air march (fog sealed out of houses, dungeon air, rain mist); the closed-form fog stays at 0. */
    AIR_MARCH(Owner.PACK, 1.5, 10.0, 0.0, 0.1, 6, 0, true),
    /** Lamp / torch / spell halos in the air. */
    HALOS(Owner.PACK, 3.2, 12.0, 0.0, 0.1, 6, 1, false),
    /** Smoke-column march steps (the volcano); never fewer than two. */
    SMOKE_COLUMNS(Owner.PACK, 1.0, 5.0, 0.125, 0.0, 6, 2, false),
    /** The pack's own cloud deck's octaves (only without atlas's clouds). */
    FALLBACK_CLOUDS(Owner.PACK, 0.8, 3.0, 0.0, 0.0, 7, 0, false),
    /** Bloom strength. Nearly free; drops only in the minimal tier. */
    BLOOM(Owner.PACK, 0.3, 0.3, 0.0, 0.0, 8, 0, false),
    /**
     * Far detail: where per-pixel sample counts start to fall with distance (16 .. 192 blocks) and how far they fall
     * (to 0.3 .. 1 of the near count). Lowering it cheapens every distance-scaled knob at once for little visible
     * loss: far detail goes first.
     */
    FAR_DETAIL(Owner.PACK, 0.3, 4.0, 0.0, 0.0, 9, 0, false),

    // ---- atlas (the native far field)
    /** atlas's internal resolution, 0.4 .. 1 of the window (the pack upsamples; distant terrain is soft anyway). */
    ATLAS_SCALE(Owner.ATLAS, 3.0, 2.5, 0.0, 0.0, -1, 0, false),
    /** atlas's LOD bias (1 .. 3: coarser levels sooner with distance). */
    ATLAS_LOD(Owner.ATLAS, 1.0, 3.0, 0.0, 0.0, -1, 0, false),
    /** atlas's cloud pass at half (1) or quarter (0) resolution. Discrete. */
    ATLAS_CLOUDS(Owner.ATLAS, 0.8, 1.0, 0.0, 0.5, -1, 0, false),
    /**
     * atlas's far-terrain sun shadows and the sun map's size: 256, 512, 1024. Discrete; never off (a tier at its floor
     * drew the far field flat and shadowless: the owner, 10-26), its floor is the 256 map.
     */
    ATLAS_SHADOWS(Owner.ATLAS, 2.5, 2.0, 0.34, 0.3, -1, 0, false);

    public enum Owner { PACK, ATLAS }

    public static final Knob[] ALL = values();
    public static final int COUNT = ALL.length;

    public final Owner owner;
    /** Visual weight: value lost at q = 0 (0..10 scale; the far field itself would be 10). */
    public final double weight;
    /** Value curve shape (see the class comment). */
    public final double alpha;
    /** Lowest q the controller ever sets (a feature that must not vanish). */
    public final double floor;
    /** Below this the feature does nothing worth having: q jumps from 0 straight to it (and its value counts from it). */
    public final double offBelow;
    /** Texel and channel in quality.png (pack knobs), -1 for atlas's. */
    public final int texel, channel;
    /** True when its per-pixel work falls with {@link #FAR_DETAIL} (its count scales with distance in the pack). */
    public final boolean distanceScaled;

    Knob(Owner owner, double weight, double alpha, double floor, double offBelow, int texel, int channel,
         boolean distanceScaled) {
        this.owner = owner;
        this.weight = weight;
        this.alpha = alpha;
        this.floor = floor;
        this.offBelow = offBelow;
        this.texel = texel;
        this.channel = channel;
        this.distanceScaled = distanceScaled;
    }

    /** Visual value at q (0 at q = 0 or below the off threshold, weight at q = 1). */
    public double value(double q) {
        if (q <= 0 || q < offBelow - 1e-9) return 0;
        double a = alpha;
        double s = a < 1e-3 ? q : (1 - Math.exp(-a * q)) / (1 - Math.exp(-a));
        return weight * Math.min(1, Math.max(0, s));
    }

    /** True for knobs that only take a few values (the ladder jumps). */
    public boolean discrete() {
        return this == ATLAS_CLOUDS || this == ATLAS_SHADOWS || this == WATER_EXTRAS;
    }

    /** The next rung above q (q itself when at 1). Continuous knobs move in sixteenths; discrete ones jump. */
    public double next(double q) {
        if (q >= 1 - 1e-9) return 1;
        if (this == ATLAS_CLOUDS || this == WATER_EXTRAS) return 1;
        if (this == ATLAS_SHADOWS) return q < 0.3 - 1e-9 ? 0.34 : q < 0.67 - 1e-9 ? 0.67 : 1;
        if (q < offBelow - 1e-9) return offBelow;
        return Math.min(1, Math.floor(q * 16 + 1e-6) / 16 + 1.0 / 16);
    }

    /** The rung at or below q (what a slewed value is snapped to for discrete knobs). */
    public double snap(double q) {
        if (this == ATLAS_CLOUDS || this == WATER_EXTRAS) return q >= 0.5 ? 1 : 0;
        if (this == ATLAS_SHADOWS) return q >= 0.995 ? 1 : q >= 0.67 ? 0.67 : q >= 0.3 ? 0.34 : 0;
        return q;
    }

    // ---- what the knobs mean to atlas

    /** atlas's render scale for ATLAS_SCALE q: 0.4 .. 1. */
    public static double atlasScale(double q) {
        return 0.4 + 0.6 * clamp01(q);
    }

    /** atlas's LOD bias for ATLAS_LOD q: 3 (coarse) .. 1. */
    public static double atlasLodBias(double q) {
        return 1 + 2 * (1 - clamp01(q));
    }

    /** The sun map's size for ATLAS_SHADOWS q: 256, 512 or 1024 (never 0: far shadows stay on at the floor). */
    public static int atlasSunMap(double q) {
        return q >= 0.995 ? 1024 : q >= 0.67 ? 512 : 256;
    }

    /** The pack's falloff: the far fraction for FAR_DETAIL q (counts far away / near). */
    public static double farFraction(double q) {
        return 0.3 + 0.7 * clamp01(q);
    }

    static double clamp01(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }
}
