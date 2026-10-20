package com.nirncraft.atlas;

import java.util.Locale;

/**
 * Sizes atlas to the memory this machine can spare: its render scale ceiling (the per-pixel targets dominate:
 * ~114 bytes per atlas pixel over both frame slots, the shared images and the pack's copies), the sun map, the cloud
 * pass and how many clipmap levels stay resident (each level is a window of tiles in host memory and regions on the
 * GPU). The plan is a ceiling the adaptive quality controller works under, plus its starting fallback tier.
 *
 * <p>The allowance is a fraction of what the device has free: VK_EXT_memory_budget's budget minus what the process
 * already uses where the driver reports it (the game's own textures and buffers are in that usage), else a share of
 * the heap. Discrete cards give atlas a quarter of the free VRAM; integrated GPUs and the Steam Deck share system RAM
 * with the game and the OS, so a fifth of the free part of their (larger) heap, and never more than a tenth of system
 * RAM. Out of memory at run time ({@link #shrink}) steps the plan down: half the pixels, a smaller sun map.</p>
 *
 * <p>Pure logic (AtlasMemoryTest): no game or native types beyond the memory record.</p>
 */
final class AtlasMemory {
    static final long MB = 1L << 20;
    /** Device bytes per atlas pixel at scale 1 (2 slots x 41 B + 20 B shared images + 12 B pack copies). */
    static final double BYTES_PER_PIXEL = 114;
    /** Resident scene on the device per clipmap level (two staged copies of its regions), roughly. */
    static final long SCENE_PER_LEVEL = 26 * MB;
    static final double[] SCALES = {1.0, 0.85, 0.7, 0.55, 0.4};

    /**
     * The ceiling for this machine. off: the far field cannot fit at all (reason says why). initialTier: the quality
     * controller's starting fallback tier (1: the far field at its lowest from the first frame).
     */
    record Plan(boolean off, double maxScale, int maxSunMap, boolean quarterClouds, int lods, int initialTier,
                long allowance, String reason) {
        /** The ATLAS_SCALE knob's ceiling for this plan (render scale 0.4 + 0.6 q). */
        double scaleKnobCeiling() {
            return Math.max(0, Math.min(1, (maxScale - 0.4) / 0.6));
        }

        /** The ATLAS_SHADOWS knob's ceiling (sun map 1024 at 1, 512 at 0.67, 256 at 0.34, off below). */
        double shadowKnobCeiling() {
            return maxSunMap >= 1024 ? 1 : maxSunMap >= 512 ? 0.67 : 0.34;
        }

        String describe() {
            if (off) return "far field off: " + reason;
            return String.format(Locale.ROOT, "atlas memory: %d MB allowed (%s); scale <= %.2f, sun map <= %d, %s clouds, %d clipmap levels%s",
                    allowance / MB, reason, maxScale, maxSunMap, quarterClouds ? "quarter-res" : "half-res", lods,
                    initialTier > 0 ? ", starting at fallback tier " + initialTier : "");
        }
    }

    /** No information at all (an older library): everything at its maximum, as before. */
    static Plan unlimited(String why) {
        return new Plan(false, 1.0, 1024, false, 7, 0, Long.MAX_VALUE, why);
    }

    /**
     * @param m          the device's memory (null: unknown, then the plan only follows system RAM)
     * @param width      the window's width (pixels), height its height
     * @param systemFree free physical memory (bytes, &lt;= 0 unknown), systemTotal total physical memory
     */
    static Plan plan(AtlasNative.MemoryInfo m, int width, int height, long systemFree, long systemTotal) {
        long allowance;
        String basis;
        boolean integrated = m != null && m.integrated();
        if (m == null || m.deviceLocal() <= 0) {
            allowance = Long.MAX_VALUE;
            basis = "device memory unknown";
        } else {
            long heap = m.deviceLocal();
            // integrated: the device-local heap may be a small carve-out (the Deck's 1 GB) beside the shared system
            // heap the GPU reads just as fast; count the larger
            if (integrated) heap = Math.max(heap, m.hostHeap());
            long free;
            if (m.budgetExt() && m.budget() > 0) {
                long budget = integrated ? Math.max(m.budget(), heap / 2) : m.budget();
                free = budget - Math.max(0, m.usage() - m.atlas());  // what the rest of the game uses is not ours
                basis = "budget " + budget / MB + " MB, game uses " + Math.max(0, m.usage() - m.atlas()) / MB + " MB";
            } else {
                free = heap * 6 / 10;  // no budget extension: assume the game and the OS hold 40%
                basis = "heap " + heap / MB + " MB, no budget extension";
            }
            allowance = (long) (Math.max(0, free) * (integrated ? 0.20 : 0.25));
            if (integrated && systemTotal > 0) allowance = Math.min(allowance, systemTotal / 10);
            basis = (integrated ? "integrated, " : "") + basis;
        }
        int lods = 7;
        // host RAM: every clipmap level keeps ~25-80 tiles of ~2 MB in the native heap
        if (systemFree > 0 && systemFree < 1536 * MB) lods = 6;
        if (allowance < 400 * MB) lods = Math.min(lods, 6);
        long scene = SCENE_PER_LEVEL * lods;
        double px = Math.max(1, (double) width * height);
        double maxScale = 0;
        for (double s : SCALES) {
            if (allowance == Long.MAX_VALUE || scene + px * s * s * BYTES_PER_PIXEL <= allowance) {
                maxScale = s;
                break;
            }
        }
        if (maxScale == 0) {
            // not even the floor at 0.4: try six levels before giving up
            long six = SCENE_PER_LEVEL * 6;
            if (six + px * 0.16 * BYTES_PER_PIXEL <= allowance) {
                lods = 6;
                maxScale = 0.4;
            } else {
                return new Plan(true, 0, 0, true, 0, 2, allowance, String.format(Locale.ROOT,
                        "not enough video memory (%d MB to spare, the far field needs %d MB at its lowest; %s)",
                        allowance / MB, (six + (long) (px * 0.16 * BYTES_PER_PIXEL)) / MB, basis));
            }
        }
        int sun = allowance >= 1024 * MB ? 1024 : allowance >= 384 * MB ? 512 : 256;
        boolean quarter = allowance < 256 * MB;
        int tier = maxScale <= 0.55 ? 1 : 0;
        return new Plan(false, maxScale, sun, quarter, lods, tier, allowance, basis);
    }

    /**
     * The CPU backend (no usable Vulkan device): what a CPU can render, under the memory plan. Scale at most 0.55 (it
     * starts at a third: AtlasQuality's CPU scale), quarter-resolution clouds, a 256 sun map (its rows are traced on
     * the CPU too), the GPU controller's fallback tier 1 (16 km, 512 far lights) from the first frame.
     */
    static Plan cpu(Plan p) {
        if (p.off()) return p;
        return new Plan(false, Math.min(p.maxScale(), 0.55), Math.min(p.maxSunMap(), 256), true, p.lods(),
                Math.max(p.initialTier(), 1), p.allowance(), p.reason() + "; CPU backend");
    }

    /** One step down after running out of memory (null: nothing left to give up: the far field goes off). */
    static Plan shrink(Plan p) {
        if (p.off()) return null;
        int i = 0;
        while (i < SCALES.length && SCALES[i] > p.maxScale() + 1e-9) i++;
        double scale = i + 1 < SCALES.length ? SCALES[i + 1] : -1;
        int sun = Math.max(256, p.maxSunMap() / 2);
        if (scale < 0 && sun == p.maxSunMap() && p.lods() <= 6 && p.quarterClouds()) return null;
        return new Plan(false, scale < 0 ? p.maxScale() : scale, sun, true, Math.min(p.lods(), scale < 0 ? 6 : 7),
                Math.max(p.initialTier(), scale < 0 || scale <= 0.55 ? 1 : 0), p.allowance(), p.reason() + "; shrunk after running out of memory");
    }
}
