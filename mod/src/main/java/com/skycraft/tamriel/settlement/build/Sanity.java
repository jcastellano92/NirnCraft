package com.skycraft.tamriel.settlement.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Post-placement validator for one settlement piece in one chunk: removes what the builders left without support or
 * sense: doors that are not in a wall, trapdoors, carpets, pressure plates and lanterns hanging in the air, ladders
 * with nothing behind them, and containers that cannot be opened or reached (a block above, a ladder over them,
 * walled in on every side). Only reads blocks inside the chunk being generated (neighbours outside count as fine).
 */
public final class Sanity {
    private Sanity() {}

    public static void sweep(WorldGenLevel level, BoundingBox clip, BoundingBox chunkBox) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        int minY = Math.max(clip.minY(), level.getMinBuildHeight() + 1), maxY = Math.min(clip.maxY(), level.getMaxBuildHeight() - 2);
        for (int x = clip.minX(); x <= clip.maxX(); x++) {
            for (int z = clip.minZ(); z <= clip.maxZ(); z++) {
                for (int y = minY; y <= maxY; y++) {
                    p.set(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) continue;
                    var b = s.getBlock();
                    if (b instanceof DoorBlock) {
                        if (s.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                            // an upper half without its lower half
                            if (!level.getBlockState(p.below()).is(b)) level.setBlock(p, air, 2);
                            continue;
                        }
                        if (!doorInWall(level, chunkBox, p, s)) {
                            level.setBlock(p, air, 2);
                            level.setBlock(p.above(), air, 2);
                        }
                    } else if (b instanceof TrapDoorBlock) {
                        if (!anyNeighbour(level, chunkBox, p)) level.setBlock(p, air, 2);
                    } else if (b instanceof StairBlock) {
                        if (strayStair(level, chunkBox, p)) level.setBlock(p, air, 2);
                    } else if (b instanceof CarpetBlock || b instanceof PressurePlateBlock) {
                        if (!supported(level, chunkBox, p.below())) level.setBlock(p, air, 2);
                    } else if (b instanceof LanternBlock) {
                        boolean hanging = s.getValue(BlockStateProperties.HANGING);
                        if (!supported(level, chunkBox, hanging ? p.above() : p.below())) level.setBlock(p, air, 2);
                    } else if (b instanceof LadderBlock) {
                        Direction back = s.getValue(LadderBlock.FACING).getOpposite();
                        BlockPos behind = p.relative(back);
                        boolean climbs = level.getBlockState(p.below()).is(b) || level.getBlockState(p.above()).is(b);
                        if (!supported(level, chunkBox, behind) && !climbs) level.setBlock(p, air, 2);
                    } else if (b instanceof ChestBlock || b instanceof BarrelBlock) {
                        if (!reachable(level, chunkBox, p, s)) {
                            level.removeBlock(p, false);
                        }
                    }
                }
            }
        }
    }

    /**
     * A stair that hangs on nothing: air below it and no block beside it, above it or one step up/down any side
     * (a flight's neighbours are the next steps; a stair against a wall or on a deck has something). Unknown
     * (outside the chunk) neighbours count as present.
     */
    private static boolean strayStair(WorldGenLevel level, BoundingBox chunkBox, BlockPos p) {
        if (supported(level, chunkBox, p.below())) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos n = p.relative(d).above(dy);
                if (supported(level, chunkBox, n)) return false;
            }
        }
        return !supported(level, chunkBox, p.above()) ;
    }

    private static boolean inside(BoundingBox box, BlockPos p) {
        return box.isInside(p);
    }

    /** A solid-ish block (anything that is not air, fluid or a replaceable plant); unknown (outside) counts as yes. */
    private static boolean supported(WorldGenLevel level, BoundingBox chunkBox, BlockPos p) {
        if (!inside(chunkBox, p)) return true;
        BlockState s = level.getBlockState(p);
        return !s.isAir() && s.getFluidState().isEmpty() && !s.canBeReplaced();
    }

    private static boolean anyNeighbour(WorldGenLevel level, BoundingBox chunkBox, BlockPos p) {
        for (Direction d : Direction.values()) {
            if (supported(level, chunkBox, p.relative(d))) return true;
        }
        return false;
    }

    /** A door has wall or frame on at least one side at foot or head height, and ground under it. */
    private static boolean doorInWall(WorldGenLevel level, BoundingBox chunkBox, BlockPos p, BlockState s) {
        Direction f = s.getValue(DoorBlock.FACING);
        Direction side = f.getClockWise();
        BlockPos below = p.below();
        if (inside(chunkBox, below) && level.getBlockState(below).isAir()) return false;
        for (Direction d : new Direction[]{side, side.getOpposite()}) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos n = p.relative(d).above(dy);
                if (!inside(chunkBox, n)) return true;
                BlockState ns = level.getBlockState(n);
                if (!ns.isAir() && ns.getFluidState().isEmpty() && !ns.canBeReplaced()) return true;
            }
        }
        return false;
    }

    /** Opens upwards (nothing solid, no ladder or container over it) and has a free side to be reached from. */
    private static boolean reachable(WorldGenLevel level, BoundingBox chunkBox, BlockPos p, BlockState s) {
        BlockPos up = p.above();
        if (inside(chunkBox, up)) {
            BlockState u = level.getBlockState(up);
            if (!u.isAir()) {
                if (u.isSolidRender(level, up) || u.getBlock() instanceof LadderBlock) return false;
                // a stacked container or a pot, candle, carpet or lantern on top: the top one goes
                level.removeBlock(up, false);
            }
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = p.relative(d);
            if (!inside(chunkBox, n)) return true;
            BlockState ns = level.getBlockState(n);
            if (ns.isAir() || ns.canBeReplaced() || !ns.isSolidRender(level, n)) return true;
        }
        return false;
    }
}
