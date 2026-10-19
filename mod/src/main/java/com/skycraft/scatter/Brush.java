package com.skycraft.scatter;

import com.skycraft.explore.build.SiteBuilder;
import com.skycraft.scatter.Terrain.RockSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * One prop being painted: a {@link SiteBuilder} (local coordinates, rotation, NBT-safe items) plus the rock of the
 * land it stands on. Natural blocks are never overwritten above the base level, supported blocks get a short
 * pillar, and fences, walls and stairs have their shapes connected at the end (worldgen skips neighbour updates).
 */
public final class Brush {
    public final SiteBuilder b;
    public final RockSet rock;
    public final RandomSource r;
    private final List<BlockPos> shaped = new ArrayList<>();
    /** Blocks written. */
    public int written;

    public Brush(SiteBuilder b, RockSet rock) {
        this.b = b;
        this.rock = rock;
        this.r = b.r;
    }

    /** Local y of the first air block over the ground at (dx, dz), relative to the base level. */
    public int gy(int dx, int dz) {
        return b.ground(dx, dz) + 1 - b.y;
    }

    /** Rigid placement relative to the base level. Above-ground blocks only go into air or plants. */
    public boolean set(int dx, int dy, int dz, BlockState state) {
        BlockPos p = b.at(dx, dy, dz);
        if (!b.writable(p)) return false; // first: nothing outside the write zone is read either
        if (dy >= 0 && !b.free(dx, dy, dz)) return false;
        b.set(dx, dy, dz, state);
        written++;
        track(p, state);
        return true;
    }

    public boolean set(int dx, int dy, int dz, Block block) {
        return set(dx, dy, dz, block.defaultBlockState());
    }

    /** A block resting {@code up} blocks above the ground of its own column. */
    public boolean top(int dx, int dz, int up, BlockState state) {
        BlockPos col = b.at(dx, 0, dz);
        if (!b.writableColumn(col.getX(), col.getZ())) return false; // set() would refuse it: skip the ground probe too
        int dy = gy(dx, dz) + up;
        if (up > 0 && b.free(dx, dy - 1, dz)) return false;
        return set(dx, dy, dz, state);
    }

    public boolean top(int dx, int dz, int up, Block block) {
        return top(dx, dz, up, block.defaultBlockState());
    }

    /** Placement that may replace anything (carving into a slope, a buried foundation). */
    public void force(int dx, int dy, int dz, BlockState state) {
        BlockPos p = b.at(dx, dy, dz);
        if (!b.writable(p)) return;
        b.set(dx, dy, dz, state);
        written++;
        track(p, state);
    }

    /** Fills below (dx, dy, dz) down to solid ground, so a rigid prop on a slope does not hang. */
    public void support(int dx, int dy, int dz, BlockState state) {
        b.pillarDown(dx, dy, dz, state.rotate(b.rot));
    }

    private void track(BlockPos p, BlockState s) {
        Block k = s.getBlock();
        if (k instanceof FenceBlock || k instanceof WallBlock || k instanceof IronBarsBlock || k instanceof StairBlock) {
            shaped.add(p.immutable());
        }
    }

    /** Connects fences, walls and stairs to what stands beside them. Call once, last. */
    public void finish() {
        for (BlockPos p : shaped) {
            BlockState s = b.level.getBlockState(p);
            if (!(s.getBlock() instanceof FenceBlock || s.getBlock() instanceof WallBlock
                    || s.getBlock() instanceof IronBarsBlock || s.getBlock() instanceof StairBlock)) continue;
            // a stair reads its neighbours through the level itself: leave one whose neighbours are outside the zone
            if (s.getBlock() instanceof StairBlock && !(com.skycraft.worldgen.SafeReads.readable(b.level, p.offset(-1, 0, -1))
                    && com.skycraft.worldgen.SafeReads.readable(b.level, p.offset(1, 0, 1)))) continue;
            BlockState n = s;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos np = p.relative(d);
                n = n.updateShape(b.level, b.level, p, d, np, com.skycraft.worldgen.SafeReads.state(b.level, np), b.level.getRandom());
            }
            if (n != s && b.writable(p)) b.level.setBlock(p, n, 2);
        }
        shaped.clear();
    }

    // ------------------------------------------------------------------ small helpers

    public BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }

    public int range(int lo, int hi) {
        return lo + r.nextInt(Math.max(1, hi - lo + 1));
    }
}
