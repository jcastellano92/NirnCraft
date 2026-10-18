package com.skycraft.tamriel.settlement.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reachability pass for one settlement piece in one chunk (runs after {@link Sanity}):
 * <ul>
 *   <li><b>Doors:</b> every entrance a builder declared with {@link Canvas#entrance} gets a clear
 *   two-high approach of up to six blocks along its open side: fences, barrels, chests, crops, trees and furniture
 *   in the way are removed, a missing floor is filled, and the doorstep becomes a path block. Walls of other
 *   buildings are never touched: a door that is walled in is only counted.</li>
 *   <li><b>Stairs:</b> every indoor stair run keeps its bottom landing and its top exit free (beds, chests, tables,
 *   chairs, pots on them go), and the two cells above each step are cleared of furniture.</li>
 * </ul>
 * Only reads and writes blocks inside the chunk being generated. The counters feed the QA log.
 */
public final class Accessibility {
    private Accessibility() {}

    public static final AtomicInteger DOORS = new AtomicInteger(), DOORS_FIXED = new AtomicInteger(), DOORS_BLOCKED = new AtomicInteger();
    public static final AtomicInteger STAIRS = new AtomicInteger(), STAIRS_FIXED = new AtomicInteger(), STAIRS_BLOCKED = new AtomicInteger();

    public static void sweep(WorldGenLevel level, BoundingBox clip, BoundingBox chunkBox) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int minY = Math.max(clip.minY(), level.getMinY() + 1), maxY = Math.min(clip.maxY(), (level.getMaxY() + 1) - 4);
        for (int x = clip.minX(); x <= clip.maxX(); x++) {
            for (int z = clip.minZ(); z <= clip.maxZ(); z++) {
                for (int y = minY; y <= maxY; y++) {
                    p.set(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) continue;
                    if (s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                        door(level, chunkBox, p.immutable(), s);
                    } else if (s.getBlock() instanceof StairBlock && s.getValue(StairBlock.HALF) == Half.BOTTOM) {
                        stair(level, chunkBox, p.immutable(), s);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ doors

    private static void door(WorldGenLevel level, BoundingBox box, BlockPos p, BlockState s) {
        Direction out = Canvas.entranceOutward(p); // only entrances the builders declared (front and back doors)
        if (out == null) return;
        DOORS.incrementAndGet();
        boolean changed = false;
        for (int k = 1; k <= 6; k++) {
            BlockPos c = p.relative(out, k);
            if (!box.isInside(c)) return;
            int y = c.getY();
            // follow a slope of one block either way: ground standing in the way is stepped onto, a drop is stepped down
            BlockState here = level.getBlockState(c);
            if (!clear(level, box, c) && !isProp(level, c, here) && clear(level, box, c.above()) && clear(level, box, c.above(2))) {
                y++;
            } else if (k > 1 && clear(level, box, c) && !solidTop(level, box, c.below()) && solidTop(level, box, c.below(2))) {
                y--;
            }
            BlockPos feet = new BlockPos(c.getX(), y, c.getZ());
            BlockState feetState = level.getBlockState(feet);
            if (feetState.getBlock() instanceof FenceGateBlock && !feetState.getValue(FenceGateBlock.OPEN)) {
                level.setBlock(feet, feetState.setValue(FenceGateBlock.OPEN, true), 2);
                changed = true;
            }
            if (!clear(level, box, feet)) {
                BlockState fs = level.getBlockState(feet);
                if (isWall(level, feet, fs)) {
                    if (k <= 2) {
                        DOORS_BLOCKED.incrementAndGet();
                        com.skycraft.Skycraft.LOGGER.debug("Accessibility: door {} is walled in by {} at {}", p, fs.getBlock(), feet);
                        return;
                    }
                    break; // the street side is walled: the approach so far is what we can do
                }
                changed |= remove(level, feet);
            }
            BlockPos head = feet.above();
            if (!clear(level, box, head)) {
                BlockState hs = level.getBlockState(head);
                if (!isWall(level, head, hs)) changed |= remove(level, head);
                else if (k <= 2) {
                    DOORS_BLOCKED.incrementAndGet();
                    com.skycraft.Skycraft.LOGGER.debug("Accessibility: door {} has no headroom ({} at {})", p, hs.getBlock(), head);
                    return;
                }
            }
            BlockState below = level.getBlockState(feet.below());
            if (roadLike(below)) break; // joined the street
            if (!solidTop(level, box, feet.below())) {
                // a missing floor: fill down to the ground (up to 24 deep) and pave the top
                int d = 0;
                BlockPos g = feet.below();
                while (d < 24 && box.isInside(g) && !solidTop(level, box, g)) {
                    level.setBlock(g, Blocks.DIRT.defaultBlockState(), 2);
                    g = g.below();
                    d++;
                }
                level.setBlock(feet.below(), Blocks.DIRT_PATH.defaultBlockState(), 2);
                changed = true;
            } else {
                if (!solidTop(level, box, feet.below(2))) {
                    int d = 0;
                    BlockPos g = feet.below(2);
                    while (d < 24 && box.isInside(g) && !solidTop(level, box, g)) {
                        level.setBlock(g, Blocks.DIRT.defaultBlockState(), 2);
                        g = g.below();
                        d++;
                    }
                }
                if (k <= 2 && (below.is(BlockTags.DIRT) || below.is(BlockTags.SAND) || below.is(Blocks.GRASS_BLOCK))) {
                    level.setBlock(feet.below(), Blocks.DIRT_PATH.defaultBlockState(), 2);
                }
            }
        }
        if (changed) DOORS_FIXED.incrementAndGet();
    }

    // ------------------------------------------------------------------ stairs

    private static void stair(WorldGenLevel level, BoundingBox box, BlockPos p, BlockState s) {
        Direction f = s.getValue(StairBlock.FACING);
        // only indoor runs: a roof overhead within a few blocks
        boolean roofed = false;
        for (int dy = 3; dy <= 6 && !roofed; dy++) {
            BlockPos u = p.above(dy);
            if (box.isInside(u) && !level.getBlockState(u).getCollisionShape(level, u).isEmpty() && !level.getBlockState(u).is(BlockTags.LEAVES)) roofed = true;
        }
        if (!roofed) return;
        boolean bottom = !sameRun(level, box, p.relative(f.getOpposite()).below(), f);
        boolean top = !sameRun(level, box, p.relative(f).above(), f);
        // a lone stair (a chair, an awning or bracket under a balcony) is not a run: what stands over it stays (the
        // balcony deck slab two above an awning is not a table)
        if (bottom && top) return;
        // the cells above this step
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos u = p.above(dy);
            if (box.isInside(u) && isProp(level, u, level.getBlockState(u), true)) clearPropBlock(level, u);
        }
        if (!bottom && !top) return;
        STAIRS.incrementAndGet();
        boolean ok = true;
        if (bottom) {
            ok &= landing(level, box, p, new BlockPos[]{p.relative(f.getOpposite()), p.relative(f.getClockWise()), p.relative(f.getCounterClockWise())});
        }
        if (top) {
            BlockPos up = p.above();
            ok &= landing(level, box, up, new BlockPos[]{up.relative(f), up.relative(f.getClockWise()), up.relative(f.getCounterClockWise())});
        }
        if (!ok) STAIRS_BLOCKED.incrementAndGet();
    }

    private static boolean sameRun(WorldGenLevel level, BoundingBox box, BlockPos p, Direction f) {
        if (!box.isInside(p)) return true;
        BlockState s = level.getBlockState(p);
        return s.getBlock() instanceof StairBlock && s.getValue(StairBlock.FACING) == f;
    }

    private static void clearPropBlock(WorldGenLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        if (s.getBlock() instanceof BedBlock) {
            Direction f = s.getValue(BedBlock.FACING);
            Direction toOther = s.getValue(BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.FOOT ? f : f.getOpposite();
            BlockPos other = p.relative(toOther);
            if (level.getBlockState(other).getBlock() instanceof BedBlock) {
                level.setBlock(other, Blocks.AIR.defaultBlockState(), 2 | 16);
            }
        }
    }

    /**
     * One of the candidate feet cells (floor under it, two free cells) must exist; furniture is removed from the first
     * candidate that has a floor and is blocked by furniture only. {@code ref} is the stair's feet level (the candidate
     * cells are given at that y).
     */
    private static boolean landing(WorldGenLevel level, BoundingBox box, BlockPos ref, BlockPos[] cands) {
        int ey = ref.getY();
        for (BlockPos c0 : cands) {
            BlockPos c = new BlockPos(c0.getX(), ey, c0.getZ());
            if (!box.isInside(c) || !box.isInside(c.above())) return true; // unknown: assume fine
            if (!solidTop(level, box, c.below())) continue;
            if (clear(level, box, c) && clear(level, box, c.above())) return true;
        }
        for (BlockPos c0 : cands) {
            BlockPos c = new BlockPos(c0.getX(), ey, c0.getZ());
            if (!solidTop(level, box, c.below())) continue;
            boolean fixable = true;
            for (BlockPos q : new BlockPos[]{c, c.above()}) {
                if (clear(level, box, q)) continue;
                if (!isProp(level, q, level.getBlockState(q), true)) fixable = false;
            }
            if (!fixable) continue;
            for (BlockPos q : new BlockPos[]{c, c.above()}) {
                if (!clear(level, box, q)) clearPropBlock(level, q);
            }
            STAIRS_FIXED.incrementAndGet();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ block classes

    /** Walkable through (air, plants, carpets, plates, doors, gates) or unknown (outside the chunk). */
    private static boolean clear(WorldGenLevel level, BoundingBox box, BlockPos p) {
        if (!box.isInside(p)) return true;
        BlockState s = level.getBlockState(p);
        if (s.isAir() || s.canBeReplaced()) return true;
        var b = s.getBlock();
        if (b instanceof CarpetBlock || b instanceof PressurePlateBlock || b instanceof DoorBlock || b instanceof FenceGateBlock) return true;
        if (b instanceof TrapDoorBlock) return true;
        return s.getCollisionShape(level, p).isEmpty();
    }

    /** Solid enough to stand on (or unknown). */
    private static boolean solidTop(WorldGenLevel level, BoundingBox box, BlockPos p) {
        if (!box.isInside(p)) return true;
        BlockState s = level.getBlockState(p);
        return !s.isAir() && s.getFluidState().isEmpty() && !s.canBeReplaced() && !s.getCollisionShape(level, p).isEmpty();
    }

    private static boolean roadLike(BlockState s) {
        return s.is(Blocks.DIRT_PATH) || s.is(Blocks.GRAVEL) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.MOSSY_COBBLESTONE)
                || s.is(Blocks.STONE_BRICKS) || s.is(Blocks.STONE) || s.is(Blocks.PACKED_MUD) || s.is(Blocks.MUD_BRICKS)
                || s.is(Blocks.SMOOTH_STONE) || s.is(Blocks.COBBLED_DEEPSLATE) || s.is(Blocks.POLISHED_ANDESITE)
                || s.is(Blocks.DEEPSLATE_BRICKS) || s.is(Blocks.BRICKS) || s.is(BlockTags.PLANKS);
    }

    /** A full building block (a wall of this or another building), as opposed to furniture and vegetation. */
    private static boolean isWall(WorldGenLevel level, BlockPos p, BlockState s) {
        if (isProp(level, p, s)) return false;
        return s.isSolidRender() || s.getBlock() instanceof StairBlock || s.getBlock() instanceof SlabBlock
                || s.is(BlockTags.WALLS) || s.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock
                || s.getBlock() instanceof net.minecraft.world.level.block.TransparentBlock;
    }

    /** Furniture and clutter, vegetation and street furniture: what may be removed from a path. */
    private static boolean isProp(WorldGenLevel level, BlockPos p, BlockState s) {
        return isProp(level, p, s, false);
    }

    /** {@code indoor}: also tables (slabs), chairs (lone stairs) and bookshelves, which a street approach must keep. */
    private static boolean isProp(WorldGenLevel level, BlockPos p, BlockState s, boolean indoor) {
        var b = s.getBlock();
        if (b instanceof BedBlock || b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof FenceBlock
                || b instanceof FlowerPotBlock || b instanceof CandleBlock || b instanceof LanternBlock
                || b instanceof AbstractCauldronBlock || b instanceof AbstractFurnaceBlock || b instanceof CraftingTableBlock
                || b instanceof LecternBlock || b instanceof CampfireBlock || b instanceof CropBlock
                || b instanceof LeavesBlock || b instanceof net.minecraft.world.level.block.HayBlock
                || b instanceof net.minecraft.world.level.block.LoomBlock || b instanceof net.minecraft.world.level.block.GrindstoneBlock
                || b instanceof net.minecraft.world.level.block.AnvilBlock || b instanceof net.minecraft.world.level.block.BrewingStandBlock
                || b instanceof net.minecraft.world.level.block.DecoratedPotBlock || b instanceof net.minecraft.world.level.block.SkullBlock
                || b instanceof net.minecraft.world.level.block.WallBannerBlock || b instanceof net.minecraft.world.level.block.StandingSignBlock
                || b instanceof net.minecraft.world.level.block.StemBlock || b instanceof net.minecraft.world.level.block.SweetBerryBushBlock
                || b instanceof com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlock
                || b instanceof com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureContainerBlock) {
            return true;
        }
        if (s.is(BlockTags.LOGS) && !s.is(BlockTags.PLANKS)) {
            // a tree trunk standing in the way (not a timber post: those have a log above and below)
            return !(level.getBlockState(p.below()).is(BlockTags.LOGS) && level.getBlockState(p.above()).is(BlockTags.LOGS)) || s.is(BlockTags.OVERWORLD_NATURAL_LOGS);
        }
        if (!indoor) return false;
        if (s.is(Blocks.BOOKSHELF) || s.is(Blocks.CHISELED_BOOKSHELF) || s.is(Blocks.TARGET)) return true;
        if (b instanceof SlabBlock) return true; // a table top
        if (b instanceof StairBlock) {
            // a lone stair block is a chair; a stair that belongs to a run (same facing one step up or down) is not
            Direction f = s.getValue(StairBlock.FACING);
            BlockState up = level.getBlockState(p.relative(f).above()), dn = level.getBlockState(p.relative(f.getOpposite()).below());
            boolean run = up.getBlock() instanceof StairBlock && up.getValue(StairBlock.FACING) == f
                    || dn.getBlock() instanceof StairBlock && dn.getValue(StairBlock.FACING) == f;
            return !run && s.getValue(StairBlock.HALF) == Half.BOTTOM;
        }
        return false;
    }

    private static boolean remove(WorldGenLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (!isProp(level, p, s)) return false;
        boolean pole = s.getBlock() instanceof FenceBlock || s.is(BlockTags.WALLS);
        clearPropBlock(level, p);
        if (pole) {
            for (int dy = 1; dy <= 9; dy++) {
                BlockPos up = p.above(dy);
                BlockState us = level.getBlockState(up);
                if (us.getBlock() instanceof FenceBlock || us.is(BlockTags.WALLS) || us.is(BlockTags.BANNERS)
                        || us.is(BlockTags.ALL_SIGNS) || us.is(BlockTags.WOOL) || us.is(Blocks.GOLD_BLOCK)
                        || us.is(com.skycraft.worldgen.VanillaBlocks.LIGHTNING_ROD) || us.getBlock() instanceof LanternBlock) {
                    level.setBlock(up, Blocks.AIR.defaultBlockState(), 2);
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        for (int step = 1; step <= 2; step++) {
                            BlockPos arm = up.relative(dir, step);
                            BlockState as = level.getBlockState(arm);
                            if (as.getBlock() instanceof FenceBlock || as.is(BlockTags.BANNERS)
                                    || as.is(BlockTags.ALL_SIGNS) || as.is(BlockTags.WOOL)) {
                                level.setBlock(arm, Blocks.AIR.defaultBlockState(), 2);
                            }
                        }
                    }
                } else if (us.isAir()) {
                    continue;
                } else {
                    break;
                }
            }
        }
        return true;
    }
}
