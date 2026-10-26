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
        if (cpu) return (float) Math.min(cpuScale, memory.maxScale());
        float s = tier >= 1 ? 0.33f : (float) Knob.atlasScale(q[Knob.ATLAS_SCALE.ordinal()]);
        return Math.min(s, (float) memory.maxScale());  // the controller already holds it there; a non-adaptive game too
    }

    // ---- the CPU backend: the GPU controller cannot see atlas's cost there (atlas uses no GPU), so its render scale
    // follows its worker's time instead: a third of the window to start, the cheap light then a smaller scale when a
    // frame takes over CPU_TARGET_MS (the textures fall further behind the camera the longer it takes), back up when
    // well under, at most every 2 s and in 0.05 steps (each size change re-attaches: a synchronous frame)
    static final double CPU_START = 1 / 3.0, CPU_MIN = 0.2, CPU_TARGET_MS = 25;
    private boolean cpu;
    private double cpuScale = CPU_START, cpuEma = -1;
    private boolean cpuLightLow;
    private long cpuNextChange;

    void setCpu(boolean on) {
        if (on != cpu) {
            cpuScale = CPU_START;
            cpuLightLow = false;
            cpuEma = -1;
            cpuNextChange = 0;
        }
        cpu = on;
    }

    boolean cpu() {
        return cpu;
    }

    /** The CPU worker's time for its latest frame (ms; atlas_core's stats), now: System.nanoTime(). */
    void observeCpu(double ms, long now) {
        if (!cpu || !(ms > 0) || ms > 5000) return;
        cpuEma = cpuEma < 0 ? ms : cpuEma * 0.8 + ms * 0.2;
        if (cpuNextChange == 0) cpuNextChange = now + 2_000_000_000L;  // the first frames (a synchronous one) settle
        if (now < cpuNextChange) return;
        double next = cpuScale;
        boolean low = cpuLightLow;
        boolean atStart = cpuScale >= CPU_START - 1e-3;
        // over: the cheap light first (ATLAS_FRAME_LIGHT_LOW, about 0.6 x the traced light's time), then the scale;
        // under: the scale back to the start first, then the traced light, then the scale beyond
        if (cpuEma > CPU_TARGET_MS * 1.15) {
            if (!low && atStart) low = true;
            else next = Math.max(CPU_MIN, cpuScale * Math.sqrt(CPU_TARGET_MS / cpuEma));
        } else if (cpuEma < CPU_TARGET_MS * 0.6) {
            if (low && atStart) low = false;
            else next = Math.min(low ? CPU_START : memory.maxScale(), cpuScale + 0.05);
        }
        next = Math.max(CPU_MIN, Math.abs(next - CPU_START) < 1e-3 ? CPU_START : Math.round(next * 20) / 20.0);
        if (Math.abs(next - cpuScale) > 1e-3 || low != cpuLightLow) {
            com.nirncraft.NirnCraft.LOGGER.info("atlas (CPU): render scale {} -> {}, light {} (worker {} ms a frame)",
                    String.format(java.util.Locale.ROOT, "%.2f", cpuScale), String.format(java.util.Locale.ROOT, "%.2f", next),
                    low ? "cheap" : "traced", String.format(java.util.Locale.ROOT, "%.1f", cpuEma));
            cpuScale = next;
            cpuLightLow = low;
            cpuEma = -1;
        }
        cpuNextChange = now + 2_000_000_000L;
    }

    /** The CPU backend's cheap far and near light this frame (ATLAS_FRAME_LIGHT_LOW). */
    boolean cpuLightLow() {
        return cpu && cpuLightLow;
    }

    /** The sun map's size (256 at the shadows' floor: far shadows stay on; 0 without a pack). */
    int sunMapSize() {
        if (fallbackMode) return 0;
        int s = Knob.atlasSunMap(q[Knob.ATLAS_SHADOWS.ordinal()]);
        return Math.min(s, memory.maxSunMap());
    }

    int flags(int base) {
        int f = base;
        if (Knob.ATLAS_CLOUDS.snap(q[Knob.ATLAS_CLOUDS.ordinal()]) < 1 || memory.quarterClouds())
            f |= AtlasNative.FRAME_CLOUDS_QUARTER;
        // (the far shadows are never switched off by quality: their floor is the 256 sun map, the owner, 10-26)
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
