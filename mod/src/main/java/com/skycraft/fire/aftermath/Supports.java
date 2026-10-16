package com.skycraft.fire.aftermath;

import com.skycraft.fire.BigFireBlock;
import com.skycraft.fire.GroundDecalBlock;
import com.skycraft.tamriel.settlement.build.Canvas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The "nothing floats" pass of the aftermath painters: after a piece (or a burnt building) is painted, every block that
 * needs a floor under it (fires, soot and debris decals, carpets and sheets, beds, candles, flowers, ash) and has none
 * is removed, bottom up, so a flame column whose base burnt through goes with it. Worldgen writes do not run block
 * updates, so without this a collapsed floor leaves fire and bedrolls hanging in the air.
 */
public final class Supports {
    private Supports() {}

    /** True for blocks that cannot hang in the air. */
    public static boolean needsFloor(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof BigFireBlock || b instanceof GroundDecalBlock || b instanceof CarpetBlock || b instanceof BedBlock
                || b instanceof CandleBlock || b instanceof BushBlock || b instanceof BasePressurePlateBlock || b == Blocks.SNOW
                || b == Blocks.HAY_BLOCK || b instanceof net.minecraft.world.level.block.FenceBlock
                || b instanceof net.minecraft.world.level.block.WallBlock) {
            return true;
        }
        if (b instanceof com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlock) {
            return s.getValue(com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlock.SHAPE)
                    != com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.ShapeCat.WALL;
        }
        if (s.isAir()) return false;
        String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
        return path.equals("ash_layer") || path.equals("bedroll");
    }

    /** True when something under a block holds it up. */
    public static boolean holds(BlockState below) {
        if (below.isAir() || Canvas.open(below) || !below.getFluidState().isEmpty()) return false;
        if (below.getBlock() instanceof BigFireBlock) return true;
        if (below.getBlock() instanceof GroundDecalBlock) return false;
        if (below.getBlock() instanceof com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlock
                || below.getBlock() instanceof com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureContainerBlock) {
            return true;
        }
        return !below.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }

    /**
     * Sweeps the columns {@code x0..x1, z0..z1} of a painter's canvas from {@code y0} to {@code y1} (canvas-local y).
     * Returns the number of blocks removed. A no-op while only recording anchors.
     */
    public static int sweep(Canvas c, int x0, int z0, int x1, int z1, int y0, int y1) {
        if (c.recording()) return 0;
        int n = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!c.touches(x, z, x, z)) continue;
                for (int y = y0; y <= y1; y++) {
                    BlockState s = c.get(x, y, z);
                    if (!needsFloor(s)) continue;
                    if (holds(c.get(x, y - 1, z))) continue;
                    c.air(x, y, z);
                    n++;
                }
            }
        }
        return n;
    }

    /** The natural ground height (canvas-local y) of every column of a piece, taken before anything is painted. */
    public static int[][] ground(Canvas c, int w, int d) {
        int[][] g = new int[w][d];
        if (c.recording()) return g;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) g[x][z] = c.touches(x, z, x, z) ? c.wground(c.wx(x, z), c.wz(x, z)) - c.oy : 0;
        }
        return g;
    }

    /** As {@link #sweep} for a painter piece that stands on the ground: from each column's ground up {@code up} blocks. */
    public static int sweepGround(Canvas c, int[][] ground, int w, int d, int up) {
        if (c.recording()) return 0;
        int n = 0;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                if (!c.touches(x, z, x, z)) continue;
                int g = ground[x][z];
                for (int y = g + 1; y <= g + up; y++) {
                    BlockState s = c.get(x, y, z);
                    if (!needsFloor(s)) continue;
                    if (holds(c.get(x, y - 1, z))) continue;
                    c.air(x, y, z);
                    n++;
                }
            }
        }
        return n;
    }
}
