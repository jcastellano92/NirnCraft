package com.nirncraft.tamriel.settlement.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.WallBlock;
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
        java.util.List<BlockPos> shaped = new java.util.ArrayList<>();
        int minY = Math.max(clip.minY(), level.getMinY() + 1), maxY = Math.min(clip.maxY(), (level.getMaxY() + 1) - 2);
        for (int pass = 0; pass < 2; pass++) {
            for (int x = clip.minX(); x <= clip.maxX(); x++) {
                for (int z = clip.minZ(); z <= clip.maxZ(); z++) {
                    for (int k = 0, span = maxY - minY; k <= span; k++) {
                        int y = pass == 0 ? minY + k : maxY - k;
                        p.set(x, y, z);
                        BlockState s = level.getBlockState(p);
                        if (s.isAir()) continue;
                        var b = s.getBlock();
                        if (b instanceof DoorBlock) {
                            if (s.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                                // an upper half without its lower half
                                if (!level.getBlockState(p.below()).is(b)) level.setBlock(p, air, 2 | 16);
                                continue;
                            }
                            if (!doorInWall(level, chunkBox, p, s)) {
                                level.setBlock(p, air, 2 | 16);
                                level.setBlock(p.above(), air, 2 | 16);
                            }
                        } else if (b instanceof net.minecraft.world.level.block.BedBlock) {
                            Direction facing = s.getValue(net.minecraft.world.level.block.BedBlock.FACING);
                            net.minecraft.world.level.block.state.properties.BedPart part = s.getValue(net.minecraft.world.level.block.BedBlock.PART);
                            Direction toPartner = part == net.minecraft.world.level.block.state.properties.BedPart.FOOT ? facing : facing.getOpposite();
                            BlockPos partnerPos = p.relative(toPartner);
                            boolean ok = supported(level, chunkBox, p.below()) && supported(level, chunkBox, partnerPos.below());
                            if (ok && readable(chunkBox, partnerPos)) {
                                BlockState ps = level.getBlockState(partnerPos);
                                net.minecraft.world.level.block.state.properties.BedPart expected =
                                        part == net.minecraft.world.level.block.state.properties.BedPart.FOOT
                                                ? net.minecraft.world.level.block.state.properties.BedPart.HEAD
                                                : net.minecraft.world.level.block.state.properties.BedPart.FOOT;
                                if (!(ps.getBlock() instanceof net.minecraft.world.level.block.BedBlock)
                                        || ps.getValue(net.minecraft.world.level.block.BedBlock.PART) != expected
                                        || ps.getValue(net.minecraft.world.level.block.BedBlock.FACING) != facing) {
                                    ok = false;
                                }
                            }
                            if (!ok) {
                                level.setBlock(p, air, 2 | 16);
                                if (readable(chunkBox, partnerPos) && level.getBlockState(partnerPos).getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
                                    level.setBlock(partnerPos, air, 2 | 16);
                                }
                            }
                        } else if (b instanceof TrapDoorBlock) {
                            if (!anyNeighbour(level, chunkBox, p)) level.setBlock(p, air, 2);
                        } else if (b instanceof StairBlock) {
                            if (strayStair(level, chunkBox, p) || !anyNeighbour(level, chunkBox, p)) level.setBlock(p, air, 2);
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
                            // hard rule: no container over air, liquid or a non-solid block
                            BlockPos under = p.below();
                            BlockState us = inside(chunkBox, under) ? level.getBlockState(under) : null;
                            if (us != null && (us.isAir() || us.canBeReplaced() || !us.getFluidState().isEmpty())) {
                                level.removeBlock(p, false);
                            } else if (!reachable(level, chunkBox, p, s)) {
                                level.removeBlock(p, false);
                            }
                        } else if (b instanceof FenceBlock || b instanceof WallBlock || b instanceof IronBarsBlock) {
                            if ((b instanceof FenceBlock || b instanceof WallBlock)
                                    && !supported(level, chunkBox, p.below()) && !supported(level, chunkBox, p.above())) {
                                level.setBlock(p, air, 2);
                            } else if (!anyNeighbour(level, chunkBox, p)) {
                                level.setBlock(p, air, 2);
                            } else if (pass == 0) {
                                shaped.add(p.immutable());
                            }
                        } else if (s.getFluidState().isEmpty() && !anyNeighbour(level, chunkBox, p)) {
                            // never a fluid (water or any source or flowing state: a river under a bridge stays whole)
                            level.setBlock(p, air, 2);
                        }
                    }
                }
            }
        }
        try {
            connect(level, chunkBox, shaped);
        } catch (RuntimeException ignored) {
            // a neighbour chunk outside the region: the fences stay as placed
        }
    }

    private static boolean connectable(BlockState s) {
        var b = s.getBlock();
        return b instanceof FenceBlock || b instanceof WallBlock || b instanceof IronBarsBlock;
    }

    /** The region neighbourhood this chunk may read: its own chunk and the eight around it. */
    private static boolean readable(BoundingBox chunkBox, BlockPos p) {
        return p.getX() >= chunkBox.minX() - 16 && p.getX() <= chunkBox.maxX() + 16
                && p.getZ() >= chunkBox.minZ() - 16 && p.getZ() <= chunkBox.maxZ() + 16;
    }

    /**
     * Worldgen skips neighbour updates, so every fence, wall and pane would stay a lone post. Connects each one to
     * its neighbours (and the neighbours back to it, across piece and chunk edges).
     */
    private static void connect(WorldGenLevel level, BoundingBox chunkBox, java.util.List<BlockPos> shaped) {
        if (shaped.isEmpty()) return;
        java.util.Set<BlockPos> todo = new java.util.LinkedHashSet<>(shaped);
        for (BlockPos p : shaped) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos np = p.relative(d);
                if (readable(chunkBox, np) && connectable(level.getBlockState(np))) todo.add(np.immutable());
            }
        }
        BlockPos.MutableBlockPos np = new BlockPos.MutableBlockPos();
        for (BlockPos p : todo) {
            BlockState s = level.getBlockState(p);
            if (!connectable(s)) continue;
            BlockState n = s;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                np.set(p).move(d);
                BlockState ns = readable(chunkBox, np) ? level.getBlockState(np) : Blocks.AIR.defaultBlockState();
                n = n.updateShape(level, level, p, d, np, ns, level.getRandom());
            }
            if (n != s) level.setBlock(p, n, 2);
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
        if (!readable(chunkBox, p)) return true;
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
        if (readable(chunkBox, below) && level.getBlockState(below).isAir()) return false;
        for (Direction d : new Direction[]{side, side.getOpposite()}) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos n = p.relative(d).above(dy);
                if (!readable(chunkBox, n)) return true;
                BlockState ns = level.getBlockState(n);
                if (!ns.isAir() && ns.getFluidState().isEmpty() && !ns.canBeReplaced()) return true;
            }
        }
        return false;
    }

    /** Opens upwards (nothing solid, no ladder over it) and has a free side to be reached from. */
    private static boolean reachable(WorldGenLevel level, BoundingBox chunkBox, BlockPos p, BlockState s) {
        BlockPos up = p.above();
        if (inside(chunkBox, up) && s.getBlock() instanceof ChestBlock) {
            BlockState u = level.getBlockState(up);
            if (!u.isAir()) {
                if (u.isSolidRender() || u.getBlock() instanceof LadderBlock) return false;
            }
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = p.relative(d);
            if (!readable(chunkBox, n)) return true;
            BlockState ns = level.getBlockState(n);
            if (ns.isAir() || ns.canBeReplaced() || !ns.isSolidRender()) return true;
        }
        return false;
    }
}
