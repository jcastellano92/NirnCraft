package com.nirncraft.tamriel.biome;

/**
 * Tiny allocation-free value noise for the biome source (fixed seeds: the Tamriel layout is the same in every world;
 * the world seed still varies the vanilla climate noises it is mixed with).
 */
public final class BiomeNoise {
    private BiomeNoise() {}

    public static int hash(int x, int z, int seed) {
        int h = x * 0x27D4EB2D ^ z * 0x165667B1 ^ seed * 0x5BD1E995;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        h *= 0x297A2D39;
        h ^= h >>> 15;
        return h;
    }

    public static int hash(int x, int y, int z, int seed) {
        return hash(x, hash(y, z, seed), seed + 0x3C6EF372);
    }

    /** Uniform in [-1, 1). */
    public static double unit(int h) {
        return (h & 0xFFFFFF) / (double) 0x800000 - 1.0;
    }

    private static double fade(double t) {
        return t * t * (3 - 2 * t);
    }

    /** Smooth value noise in [-1, 1]. */
    public static double value2(double x, double z, int seed) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = fade(x - x0), fz = fade(z - z0);
        double a = unit(hash(x0, z0, seed)), b = unit(hash(x0 + 1, z0, seed));
        double c = unit(hash(x0, z0 + 1, seed)), d = unit(hash(x0 + 1, z0 + 1, seed));
        double ab = a + (b - a) * fx, cd = c + (d - c) * fx;
        return ab + (cd - ab) * fz;
    }

    public static double value3(double x, double y, double z, int seed) {
        int y0 = (int) Math.floor(y);
        double fy = fade(y - y0);
        double lo = value2(x, z, hash(y0, 0, seed)), hi = value2(x, z, hash(y0 + 1, 0, seed));
        return lo + (hi - lo) * fy;
    }

    /** Two octaves. */
    public static double fbm2(double x, double z, int seed) {
        return value2(x, z, seed) * 0.7 + value2(x * 2.3, z * 2.3, seed + 17) * 0.3;
    }

    /** Standard normal CDF (logistic approximation): spreads a roughly normal value over [0, 1). */
    public static double cdf(double v) {
        double p = 1.0 / (1.0 + StrictMath.exp(-1.702 * v));
        return Math.min(0.99999, Math.max(0.0, p));
    }
}
