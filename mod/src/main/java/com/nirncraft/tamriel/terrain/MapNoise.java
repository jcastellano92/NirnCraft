package com.nirncraft.tamriel.terrain;

/**
 * Seed-independent hash value noise for the map layer (domain warp, coast fractals, the beyond-Tamriel lands). The map
 * is the same for every seed, so these noises are fixed; seeded detail comes from the noise router instead.
 * Static, allocation-free and thread-safe.
 */
public final class MapNoise {
    private MapNoise() {}

    public static int hash(int x, int z, int seed) {
        int h = x * 374761393 + z * 668265263 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }

    /** Uniform in [0, 1). */
    public static double rand(int x, int z, int seed) {
        return (hash(x, z, seed) & 0xFFFFFF) / (double) 0x1000000;
    }

    /** Value noise in [-1, 1] with period-free lattice spacing {@code scale} blocks. */
    public static double value(double x, double z, double scale, int seed) {
        double fx = x / scale, fz = z / scale;
        int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
        double tx = fx - ix, tz = fz - iz;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);
        double a = lattice(ix, iz, seed), b = lattice(ix + 1, iz, seed);
        double c = lattice(ix, iz + 1, seed), d = lattice(ix + 1, iz + 1, seed);
        double ab = a + (b - a) * tx;
        return ab + (c + (d - c) * tx - ab) * tz;
    }

    private static double lattice(int x, int z, int seed) {
        return (hash(x, z, seed) & 0xFFFFFF) / (double) 0x7FFFFF - 1.0;
    }

    /** Fractal sum of {@link #value} octaves, normalised to about [-1, 1]. */
    public static double fbm(double x, double z, double scale, int octaves, int seed) {
        double tot = 0, amp = 1, norm = 0;
        for (int o = 0; o < octaves; o++) {
            tot += value(x, z, scale, seed + o * 31) * amp;
            norm += amp;
            amp *= 0.5;
            scale *= 0.5;
        }
        return tot / norm;
    }

    /** Ridged fractal in [0, 1]: sharp crests where the base noise crosses zero. */
    public static double ridged(double x, double z, double scale, int octaves, int seed) {
        double tot = 0, amp = 1, norm = 0;
        for (int o = 0; o < octaves; o++) {
            double n = 1 - Math.abs(value(x, z, scale, seed + o * 17));
            tot += n * n * amp;
            norm += amp;
            amp *= 0.5;
            scale *= 0.5;
        }
        return tot / norm;
    }

    public static double smoothstep(double e0, double e1, double x) {
        double t = (x - e0) / (e1 - e0);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
