package com.skycraft.landmarks;

/** SplitMix64 hashing: every landmark parameter is a pure function of the world seed and a position. */
public final class H {
    private H() {}

    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static long of(long a, long b, long c) {
        return mix(a ^ mix(b * 0x9E3779B97F4A7C15L + c * 0xC2B2AE3D27D4EB4FL + 0x1A2B3C4D5E6F7L));
    }

    /** Uniform [0, 1) number {@code k} of hash {@code h}. */
    public static double unit(long h, int k) {
        return (mix(h + k * 0x9E3779B97F4A7C15L) >>> 11) * 0x1.0p-53;
    }

    /** Uniform int in [0, n) number {@code k} of hash {@code h}. */
    public static int range(long h, int k, int n) {
        return (int) (unit(h, k) * n);
    }

    /** Uniform in [lo, hi). */
    public static double between(long h, int k, double lo, double hi) {
        return lo + unit(h, k) * (hi - lo);
    }
}
