package com.skycraft.dungeons.world;

import net.minecraft.util.Mth;

/**
 * Seed-free smooth value noise from absolute coordinates, for landmarks split over several structure starts (each
 * start has its own painter seed, but the shapes must agree where they meet). Range [-1, 1].
 */
public final class Noise {
    private Noise() {}

    public static double value(double x, double y, double z, int salt) {
        int ix = Mth.floor(x), iy = Mth.floor(y), iz = Mth.floor(z);
        double tx = smooth(x - ix), ty = smooth(y - iy), tz = smooth(z - iz);
        double c000 = lattice(ix, iy, iz, salt), c100 = lattice(ix + 1, iy, iz, salt);
        double c010 = lattice(ix, iy + 1, iz, salt), c110 = lattice(ix + 1, iy + 1, iz, salt);
        double c001 = lattice(ix, iy, iz + 1, salt), c101 = lattice(ix + 1, iy, iz + 1, salt);
        double c011 = lattice(ix, iy + 1, iz + 1, salt), c111 = lattice(ix + 1, iy + 1, iz + 1, salt);
        double x00 = Mth.lerp(tx, c000, c100), x10 = Mth.lerp(tx, c010, c110);
        double x01 = Mth.lerp(tx, c001, c101), x11 = Mth.lerp(tx, c011, c111);
        return Mth.lerp(tz, Mth.lerp(ty, x00, x10), Mth.lerp(ty, x01, x11));
    }

    private static double lattice(int x, int y, int z, int salt) {
        long h = (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L) ^ (salt * 0xD6E8FEB86659FD93L);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (h & 0xFFFF) / 32767.5 - 1.0;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }
}
