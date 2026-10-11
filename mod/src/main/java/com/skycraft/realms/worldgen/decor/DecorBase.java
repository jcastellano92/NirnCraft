package com.skycraft.realms.worldgen.decor;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Helpers shared by the Isles, Apocrypha, Soul Cairn and Coldharbour decorators: finding the ground under an origin
 * (through air, plants and fluids), sparse blobs, hanging growth, and block states with the right properties.
 */
final class DecorBase {
    private DecorBase() {}

    static final int NONE = Integer.MIN_VALUE;

    /**
     * The first open block above solid ground at or below {@code y} in column (x, z), passing down through air,
     * plants, leaves and fluids (at most 64 blocks); {@link #NONE} if there is none.
     */
    static int floor(DecorKit k, int x, int y, int z) {
        int yy = y;
        for (int i = 0; i < 8 && !DecorKit.open(k.get(x, yy, z)); i++) yy++;
        for (int i = 0; i < 64; i++, yy--) {
            if (yy <= k.level.getMinBuildHeight() + 1) return NONE;
            BlockState below = k.get(x, yy - 1, z);
            if (!DecorKit.open(below)) return yy;
        }
        return NONE;
    }

    /** True when the block is free of fluid (dry ground decorations refuse to stand in water). */
    static boolean dry(DecorKit k, int x, int y, int z) {
        return k.get(x, y, z).getFluidState().isEmpty();
    }

    static BlockState leaves(Block block) {
        BlockState s = block.defaultBlockState();
        return s.hasProperty(LeavesBlock.PERSISTENT) ? s.setValue(LeavesBlock.PERSISTENT, true) : s;
    }

    static BlockState cluster(Block block, Direction facing) {
        BlockState s = block.defaultBlockState();
        return s.hasProperty(AmethystClusterBlock.FACING) ? s.setValue(AmethystClusterBlock.FACING, facing) : s;
    }

    /** An ellipsoid where each block is placed with probability {@code fill} (open spots only). */
    static void sparseBlob(DecorKit k, RandomSource random, int cx, int cy, int cz, double rx, double ry, double rz,
                           BlockState s, double fill) {
        for (int x = (int) Math.floor(cx - rx); x <= (int) Math.ceil(cx + rx); x++)
            for (int z = (int) Math.floor(cz - rz); z <= (int) Math.ceil(cz + rz); z++)
                for (int y = (int) Math.floor(cy - ry); y <= (int) Math.ceil(cy + ry); y++) {
                    double dx = (x - cx) / rx, dy = (y - cy) / ry, dz = (z - cz) / rz;
                    if (dx * dx + dy * dy + dz * dz > 1) continue;
                    if (random.nextDouble() < fill) k.put(x, y, z, s);
                }
    }

    /**
     * Hangs a strand of up to {@code len} blocks below (x, y, z) through air: {@code body} blocks ending in {@code tip}.
     */
    static void hang(DecorKit k, int x, int y, int z, int len, BlockState body, BlockState tip) {
        int n = 0;
        while (n < len && k.inReach(x, y - n, z) && k.get(x, y - n, z).isAir()) n++;
        for (int i = 0; i < n; i++) k.put(x, y - i, z, i == n - 1 ? tip : body);
    }

    /** A point on the quadratic curve a -> (control c) -> b at t. */
    static double bez(double a, double c, double b, double t) {
        double u = 1 - t;
        return u * u * a + 2 * u * t * c + t * t * b;
    }
}
