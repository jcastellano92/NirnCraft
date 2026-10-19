package com.nirncraft.atlas;

import com.nirncraft.render.quality.Knob;
import com.nirncraft.render.quality.QualityClient;

/**
 * atlas's side of adaptive quality v2 (com.nirncraft.render.quality, docs/ADAPTIVE-QUALITY.md): this frame's knobs
 * from the quality controller, as atlas's render scale (0.4 .. 1 of the window, in 0.05 steps; the pack upsamples),
 * LOD bias (1 .. 3: far detail coarsens first), cloud-pass resolution (half / quarter), far-terrain shadows and the sun
 * map's size (1024 / 512 / 256, shadows off), and the fallbacks below the floor: tier 1 renders at 0.33 out to 16 km,
 * tier 2 turns the far field off (the pack's own sky and cloud deck take over). The controller measures atlas's
 * GPU time (reported by AtlasClient each frame) and the whole frame's, and keeps both inside the frame budget.
 */
final class AtlasQuality {
    static final float SUN_HALF_EXTENT = 2048f;

    private double[] q = new double[Knob.COUNT];
    private int tier;
    /** The machine's memory ceiling (AtlasMemory); unlimited until the context reports its device. */
    private AtlasMemory.Plan memory = AtlasMemory.unlimited("not measured yet");
    /** No shader pack: atlas's own clouds and the sun map are not drawn (the mod's cloud deck draws; nobody reads it). */
    private boolean fallbackMode;

    AtlasQuality() {
        java.util.Arrays.fill(q, 1);
    }

    /** Takes this frame's knobs (render thread, before the frame is set up). */
    void begin() {
        q = QualityClient.knobs();
        tier = QualityClient.fallbackTier();
    }

    void setMemory(AtlasMemory.Plan plan) {
        memory = plan;
    }

    AtlasMemory.Plan memory() {
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
        float s = tier >= 1 ? 0.33f : (float) Knob.atlasScale(q[Knob.ATLAS_SCALE.ordinal()]);
        return Math.min(s, (float) memory.maxScale());  // the controller already holds it there; a non-adaptive game too
    }

    /** The sun map's size (256 while far shadows are off: the near field still reads the map's clouds; 0 without a pack). */
    int sunMapSize() {
        if (fallbackMode) return 0;
        int s = Knob.atlasSunMap(q[Knob.ATLAS_SHADOWS.ordinal()]);
        return Math.min(s == 0 ? 256 : s, memory.maxSunMap());
    }

    int flags(int base) {
        int f = base;
        if (Knob.ATLAS_CLOUDS.snap(q[Knob.ATLAS_CLOUDS.ordinal()]) < 1 || memory.quarterClouds())
            f |= AtlasNative.FRAME_CLOUDS_QUARTER;
        if (Knob.atlasSunMap(q[Knob.ATLAS_SHADOWS.ordinal()]) == 0) f &= ~AtlasNative.FRAME_SHADOWS;
        if (fallbackMode) f &= ~(AtlasNative.FRAME_CLOUDS | AtlasNative.FRAME_CLOUDS_QUARTER);
        return f;
    }

    float lodBias() {
        return (float) Knob.atlasLodBias(q[Knob.ATLAS_LOD.ordinal()]);
    }

    /**
     * Far lights a frame (the brightest first): 4096 at full far detail, falling with atlas's LOD bias (the knob that
     * coarsens far detail first) to 1024 at bias 3, 512 at fallback tier 1. Each costs a few ALU per pixel of its tiles.
     */
    int lightBudget() {
        if (tier >= 1) return 512;
        float b = lodBias();
        return Math.max(1024, Math.round(4096f / (b * b) / 64f) * 64);
    }

    /** How far atlas traces (blocks): 40 km, 16 km at fallback tier 1. */
    float maxDistance() {
        return tier >= 1 ? 16_000f : 40_000f;
    }

    /** atlas's render size for a window size (at least 64 x 36). */
    int width(int w) {
        return Math.max(64, Math.round(w * scale()));
    }

    int height(int h) {
        return Math.max(36, Math.round(h * scale()));
    }
}
