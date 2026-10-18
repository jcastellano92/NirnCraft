package com.skycraft.aether;

/**
 * Keeps aether inside a GPU budget on any hardware by trading what shows least first: the far field's internal
 * resolution (distant terrain is soft anyway; the pack upsamples it), then the cloud pass's resolution, then far
 * terrain's own sun shadows. Measured from aether's GPU timestamps (two frames late), smoothed, with hysteresis and a
 * dwell time, so it settles instead of oscillating. Levels: 0 is everything at full resolution.
 * <p>Budget: skycraft.aether.budgetMs (default 4); the whole frame running under 50 fps also steps down.</p>
 */
final class AetherQuality {
    /** {render scale, clouds quarter, far shadows} per level. */
    private static final float[][] LEVELS = {
            {1.00f, 0, 1}, {0.85f, 0, 1}, {0.72f, 0, 1}, {0.72f, 1, 1}, {0.60f, 1, 1}, {0.50f, 1, 1}, {0.50f, 1, 0}, {0.40f, 1, 0}};
    static final float BUDGET_MS = Float.parseFloat(System.getProperty("skycraft.aether.budgetMs", "4"));
    private static final long DWELL_NS = 2_000_000_000L;

    private int level = Integer.getInteger("skycraft.aether.startLevel", 0);
    private double ema = -1;
    private int over, under;
    private long lastChange;

    /** The sun map's size per level (far shadows on the near field): 4 m texels over 4 km, coarser lower down. */
    private static final int[] SUN_MAP = {1024, 1024, 1024, 1024, 512, 512, 256, 256};
    static final float SUN_HALF_EXTENT = 2048f;

    int sunMapSize() {
        return SUN_MAP[level];
    }

    float scale() {
        return LEVELS[level][0];
    }

    int flags(int base) {
        int f = base;
        if (LEVELS[level][1] > 0) f |= AetherNative.FRAME_CLOUDS_QUARTER;
        if (LEVELS[level][2] == 0) f &= ~AetherNative.FRAME_SHADOWS;
        return f;
    }

    int level() {
        return level;
    }

    /** aether's render size for a window size (at least 64 x 36). */
    int width(int w) {
        return Math.max(64, Math.round(w * scale()));
    }

    int height(int h) {
        return Math.max(36, Math.round(h * scale()));
    }

    /**
     * One frame's measurements: aether's GPU ms and the game's fps. True when the level changed (the caller resizes
     * its targets).
     */
    boolean update(float gpuMs, int fps, long now) {
        if (!(gpuMs > 0)) return false;
        ema = ema < 0 ? gpuMs : ema * 0.94 + gpuMs * 0.06;
        boolean slowGame = fps > 0 && fps < 50;
        if (ema > BUDGET_MS * 1.1 || (slowGame && ema > BUDGET_MS * 0.5)) {
            over++;
            under = 0;
        } else if (ema < BUDGET_MS * 0.55 && !slowGame) {
            under++;
            over = 0;
        } else {
            over = under = 0;
        }
        if (now - lastChange < DWELL_NS) return false;
        int next = level;
        if (over > 30 && level < LEVELS.length - 1) next = level + 1;
        else if (under > 180 && level > 0) next = level - 1;
        if (next == level) return false;
        level = next;
        over = under = 0;
        lastChange = now;
        ema = -1;  // a new level measures afresh
        return true;
    }
}
