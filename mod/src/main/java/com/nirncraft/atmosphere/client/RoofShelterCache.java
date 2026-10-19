package com.nirncraft.atmosphere.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Comparator;

/** Loaded-column weather cover, independent of the camera's indoor/outdoor state.
 * Two-metre cells share spare channels of the existing block-light texture.
 * This excludes outdoor weather; it does not classify rooms or generate cave fog. */
final class RoofShelterCache {
    private static final int SIDE = 64, COUNT = SIDE * SIDE;
    private static final int[] OFF_X = new int[COUNT], OFF_Z = new int[COUNT];
    static {
        Integer[] order = new Integer[COUNT];
        for (int i = 0; i < COUNT; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingInt(i -> {
            int x = i % SIDE - SIDE / 2, z = i / SIDE - SIDE / 2;
            return x * x + z * z;
        }));
        for (int i = 0; i < COUNT; i++) {
            OFF_X[i] = order[i] % SIDE - SIDE / 2;
            OFF_Z[i] = order[i] / SIDE - SIDE / 2;
        }
    }
    private final int[] cellX = new int[COUNT], cellZ = new int[COUNT], height = new int[COUNT];
    private final boolean[] valid = new boolean[COUNT];
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private ClientLevel lastLevel;
    private int centreX, centreZ, sweep, near;

    void tick(ClientLevel level, Vec3 camera) {
        int cx = Mth.floor(camera.x / 2), cz = Mth.floor(camera.z / 2);
        if (lastLevel != level || Math.abs(cx - centreX) >= SIDE / 2 || Math.abs(cz - centreZ) >= SIDE / 2) {
            Arrays.fill(valid, false);
            sweep = near = 0;
            lastLevel = level;
        }
        centreX = cx; centreZ = cz;
        long start = System.nanoTime();
        long budget = AtmosphereBudget.physicsBudget(250_000L);
        // Nearby roof changes refresh frequently; the rest is a bounded outward sweep.
        for (int i = 0; i < 64; i++) {
            int offset = i < 16 ? (near++ & 63) : (sweep++ & (COUNT - 1));
            sample(level, cx + OFF_X[offset], cz + OFF_Z[offset]);
            if (i >= 3 && System.nanoTime() - start >= budget) break;
        }
    }

    /** G/B store unsigned roof height +64; A marks a valid world column. */
    int packed(int textureX, int textureZ) {
        int index = textureZ * SIDE + textureX;
        int expectedX = nearest(centreX, textureX), expectedZ = nearest(centreZ, textureZ);
        if (!valid[index] || cellX[index] != expectedX || cellZ[index] != expectedZ) return 0;
        int encoded = Mth.clamp(height[index] + 64, 0, 65535);
        return ((encoded & 255) << 8) | ((encoded >>> 8) << 16) | 0xFF000000;
    }

    /** Blocks of air under a roof that still count as the room it covers (more: open sky under something high). */
    private static final int MAX_SHELTER = 20;

    private void sample(ClientLevel level, int x, int z) {
        int index = Math.floorMod(z, SIDE) * SIDE + Math.floorMod(x, SIDE);
        cellX[index] = x; cellZ[index] = z; valid[index] = false;
        int highest = level.getMinY();
        // Max of the four block columns conservatively covers narrow roof edges.
        for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
            int bx = x * 2 + dx, bz = z * 2 + dz;
            var chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, ChunkStatus.FULL, false);
            if (chunk == null || chunk instanceof EmptyLevelChunk) return;
            int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15);
            int scans = 0;
            // Leaves are not an indoor roof: retain outdoor mist beneath a forest canopy.
            while (y >= level.getMinY()) {
                var state = chunk.getBlockState(probe.set(bx, y, bz));
                if (!state.is(BlockTags.LEAVES) && (state.isSolid() || !state.getFluidState().isEmpty())) break;
                if (++scans >= 48) return;
                y--;
            }
            // a roof only when there is a floor under it within a room's height: a platform or a bridge far above
            // the ground is not shelter (the space beneath it was treated as indoors: a bright dust column)
            int gap = 0;
            for (int fy = y - 1; fy >= level.getMinY() && gap <= MAX_SHELTER; fy--, gap++) {
                var below = chunk.getBlockState(probe.set(bx, fy, bz));
                if (below.isSolid() || !below.getFluidState().isEmpty()) break;
            }
            if (gap > MAX_SHELTER) continue;
            highest = Math.max(highest, y + 1);
        }
        height[index] = highest; valid[index] = true;
    }

    private static int nearest(int centre, int texel) {
        int start = centre - SIDE / 2;
        return start + Math.floorMod(texel - start, SIDE);
    }
}
