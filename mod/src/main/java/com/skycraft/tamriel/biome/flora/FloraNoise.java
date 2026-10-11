package com.skycraft.tamriel.biome.flora;

/**
 * Allocation-free, thread-safe noise for vegetation: integer hashes, smooth value noise, fractal (fbm) noise and
 * cellular (Worley) noise. Every function is a pure function of the world seed and block coordinates, so a field
 * looks the same from every chunk that samples it: forests, meadows and rock fields run on across chunk borders
 * without seams, and no grid shows.
 */
public final class FloraNoise {
    private FloraNoise() {}

    /** A well-mixed 32-bit hash of two coordinates and a seed. */
    public static int hash(int x, int z, long seed) {
        long h = seed ^ 0x9E3779B97F4A7C15L;
        h ^= x * 0xC2B2AE3D27D4EB4FL;
        h = Long.rotateLeft(h, 31) * 0x165667B19E3779F9L;
        h ^= z * 0xD6E8FEB86659FD93L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (int) h;
    }

    /** Uniform in [0, 1). */
    public static double unit01(int h) {
        return (h >>> 8) * 0x1.0p-24;
    }

    /** Uniform in [-1, 1). */
    public static double unit(int h) {
        return unit01(h) * 2.0 - 1.0;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    /** Smooth value noise in [-1, 1] with one feature per unit. */
    public static double value(double x, double z, long seed) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = fade(x - x0), fz = fade(z - z0);
        double a = unit(hash(x0, z0, seed)), b = unit(hash(x0 + 1, z0, seed));
        double c = unit(hash(x0, z0 + 1, seed)), d = unit(hash(x0 + 1, z0 + 1, seed));
        double ab = a + (b - a) * fx, cd = c + (d - c) * fx;
        return ab + (cd - ab) * fz;
    }

    /**
     * Fractal value noise, roughly in [-1, 1]: {@code octaves} layers, each at double the frequency and half the
     * amplitude, each rotated and offset so the layers' lattices never line up (that is what hides the grid).
     */
    public static double fbm(double x, double z, long seed, int octaves) {
        double sum = 0, amp = 1, norm = 0;
        for (int o = 0; o < octaves; o++) {
            sum += value(x, z, seed + o * 0x632BE59BD9B4E019L) * amp;
            norm += amp;
            amp *= 0.5;
            // rotate by ~37 degrees and scale by 2.03 between octaves
            double nx = (x * 0.799 - z * 0.602) * 2.03 + 17.3;
            double nz = (x * 0.602 + z * 0.799) * 2.03 - 9.1;
            x = nx;
            z = nz;
        }
        return sum / norm;
    }

    /**
     * Cellular noise: the distance (in cell units, 0 to about 1.2) from (x, z) to the nearest of the jittered feature
     * points, one per unit cell. Low near the points: good for rock fields and tufts.
     */
    public static double worley(double x, double z, long seed) {
        int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
        double best = 9;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int h = hash(cx + dx, cz + dz, seed);
                double px = cx + dx + 0.15 + 0.7 * unit01(h);
                double pz = cz + dz + 0.15 + 0.7 * unit01(h * 0x2C1B3C6D + 0x297A2D39);
                double d = (px - x) * (px - x) + (pz - z) * (pz - z);
                if (d < best) best = d;
            }
        }
        return Math.sqrt(best);
    }

    public static double smoothstep(double lo, double hi, double v) {
        if (v <= lo) return 0;
        if (v >= hi) return 1;
        double t = (v - lo) / (hi - lo);
        return t * t * (3 - 2 * t);
    }
}
