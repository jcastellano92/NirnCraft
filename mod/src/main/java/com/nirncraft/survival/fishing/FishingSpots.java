package com.nirncraft.survival.fishing;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Fishing spots: places where fish gather (Skyrim's fishing spots, marked by jumping fish and ripples). They sit on a
 * fixed jittered grid of {@value #CELL}-block cells, the same for every world, so the client can show the jumping fish
 * without being told; a spot only matters where there is water. Fishing within {@value #RADIUS} blocks of one bites
 * twice as fast and turns up rarer fish.
 */
public final class FishingSpots {
    public static final int CELL = 40;
    public static final int RADIUS = 6;

    private FishingSpots() {}

    /** The spot centre of the cell containing (x, z), or null if that cell has none. */
    @Nullable
    public static BlockPos spotInCell(int cellX, int cellZ) {
        long h = hash(cellX, cellZ);
        if (Math.floorMod(h, 3) != 0) return null; // one cell in three
        int ox = (int) Math.floorMod(h >> 8, CELL - 2 * RADIUS) + RADIUS;
        int oz = (int) Math.floorMod(h >> 20, CELL - 2 * RADIUS) + RADIUS;
        return new BlockPos(cellX * CELL + ox, 0, cellZ * CELL + oz);
    }

    /** The spot whose circle contains (x, z), or null. */
    @Nullable
    public static BlockPos spotAt(int x, int z) {
        BlockPos spot = spotInCell(Math.floorDiv(x, CELL), Math.floorDiv(z, CELL));
        if (spot == null) return null;
        int dx = spot.getX() - x, dz = spot.getZ() - z;
        return dx * dx + dz * dz <= RADIUS * RADIUS ? spot : null;
    }

    private static long hash(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ 0x5DEECE66DL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return h & Long.MAX_VALUE;
    }
}
