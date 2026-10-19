package com.skycraft.aether;

import com.skycraft.render.quality.Knob;
import com.skycraft.render.quality.QualityClient;

/**
 * aether's side of adaptive quality v2 (com.skycraft.render.quality, docs/ADAPTIVE-QUALITY.md): this frame's knobs
 * from the quality controller, as aether's render scale (0.4 .. 1 of the window, in 0.05 steps; the pack upsamples),
 * LOD bias (1 .. 3: far detail coarsens first), cloud-pass resolution (half / quarter), far-terrain shadows and the sun
 * map's size (1024 / 512 / 256, shadows off), and the fallbacks below the floor: tier 1 renders at 0.33 out to 16 km,
 * tier 2 turns the far field off (the pack's own sky and cloud deck take over). The controller measures aether's
 * GPU time (reported by AetherClient each frame) and the whole frame's, and keeps both inside the frame budget.
 */
final class AetherQuality {
    static final float SUN_HALF_EXTENT = 2048f;

    private double[] q = new double[Knob.COUNT];
    private int tier;
    /** The machine's memory ceiling (AetherMemory); unlimited until the context reports its device. */
    private AetherMemory.Plan memory = AetherMemory.unlimited("not measured yet");
    /** No shader pack: aether's own clouds and the sun map are not drawn (the mod's cloud deck draws; nobody reads it). */
    private boolean fallbackMode;

    AetherQuality() {
        java.util.Arrays.fill(q, 1);
    }

    /** Takes this frame's knobs (render thread, before the frame is set up). */
    void begin() {
        q = QualityClient.knobs();
        tier = QualityClient.fallbackTier();
    }

    void setMemory(AetherMemory.Plan plan) {
        memory = plan;
    }

    AetherMemory.Plan memory() {
        return memory;
    }

    void setFallbackMode(boolean on) {
        fallbackMode = on;
    }

    boolean fallbackMode() {
        return fallbackMode;
    }

    /** False at fallback tier 2: no far field this frame. */
    boolean enabled() {
        return tier < 2;
    }

    float scale() {
        float s = tier >= 1 ? 0.33f : (float) Knob.aetherScale(q[Knob.AETHER_SCALE.ordinal()]);
        return Math.min(s, (float) memory.maxScale());  // the controller already holds it there; a non-adaptive game too
    }

    /** The sun map's size (256 while far shadows are off: the near field still reads the map's clouds; 0 without a pack). */
    int sunMapSize() {
        if (fallbackMode) return 0;
        int s = Knob.aetherSunMap(q[Knob.AETHER_SHADOWS.ordinal()]);
        return Math.min(s == 0 ? 256 : s, memory.maxSunMap());
    }

    int flags(int base) {
        int f = base;
        if (Knob.AETHER_CLOUDS.snap(q[Knob.AETHER_CLOUDS.ordinal()]) < 1 || memory.quarterClouds())
            f |= AetherNative.FRAME_CLOUDS_QUARTER;
        if (Knob.aetherSunMap(q[Knob.AETHER_SHADOWS.ordinal()]) == 0) f &= ~AetherNative.FRAME_SHADOWS;
        if (fallbackMode) f &= ~(AetherNative.FRAME_CLOUDS | AetherNative.FRAME_CLOUDS_QUARTER);
        return f;
    }

    float lodBias() {
        return (float) Knob.aetherLodBias(q[Knob.AETHER_LOD.ordinal()]);
    }

    /**
     * Far lights a frame (the brightest first): 4096 at full far detail, falling with aether's LOD bias (the knob that
     * coarsens far detail first) to 1024 at bias 3, 512 at fallback tier 1. Each costs a few ALU per pixel of its tiles.
     */
    int lightBudget() {
        if (tier >= 1) return 512;
        float b = lodBias();
        return Math.max(1024, Math.round(4096f / (b * b) / 64f) * 64);
    }

    /** How far aether traces (blocks): 40 km, 16 km at fallback tier 1. */
    float maxDistance() {
        return tier >= 1 ? 16_000f : 40_000f;
    }

    /** aether's render size for a window size (at least 64 x 36). */
    int width(int w) {
        return Math.max(64, Math.round(w * scale()));
    }

    int height(int h) {
        return Math.max(36, Math.round(h * scale()));
    }
}
