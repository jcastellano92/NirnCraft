package com.skycraft.economy.homes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a province's player home on its plot (the bounds and front door the settlement recorded), in the style of its
 * culture ({@link HomeStyle}): foundations or posts, square or round walls with windows, one to three storeys joined by
 * ladders, a gable, hipped, flat, domed, conical or mushroom roof, and a furnished interior with the owner's bed,
 * chests, a crafting corner, a table and lights. Runs on the server thread once the plot's chunks are loaded.
 *
 * <p>Local frame: x runs along the front ({@code 0..w-1}), z from the front row inwards ({@code 0..d-1}), y from the
 * floor; the front door is at ({@code (w-1)/2}, 1, 0).</p>
 */
public final class HomeBuilder {
    /** What was built: the front door (lower half), the owner's bed, and the second bed upstairs (if any). */
    public record Built(BlockPos door, BlockPos bed, BlockPos spareBed) {
    }

    private final ServerLevel level;
    private final HomeStyle st;
    private final BlockPos origin;
    private final Direction front, inward, along;
    private final int w, d;
    private int floorY;
    private final List<BlockPos> shaped = new ArrayList<>();
    private final boolean[][] used;

    private HomeBuilder(ServerLevel level, HomeStyle style, BlockPos min, BlockPos max, Direction front) {
        this.level = level;
        this.st = style;
        this.front = front;
        this.inward = front.getOpposite();
        this.along = front.getClockWise();
        // the footprint inside the recorded bounds (which add a block all round)
        int x0 = min.getX() + 1, x1 = max.getX() - 1, z0 = min.getZ() + 1, z1 = max.getZ() - 1;
        int ox = switch (front) {
            case SOUTH, EAST -> x1;
            default -> x0;
        };
        int oz = switch (front) {
            case SOUTH, WEST -> z1;
            default -> z0;
        };
        this.origin = new BlockPos(ox, min.getY(), oz);
        boolean xAlong = along.getAxis() == Direction.Axis.X;
        this.w = (xAlong ? x1 - x0 : z1 - z0) + 1;
        this.d = (xAlong ? z1 - z0 : x1 - x0) + 1;
        this.floorY = min.getY();
        this.used = new boolean[w][d];
    }

    /** The front of a plot: the side of its bounds the recorded door is on. */
    public static Direction frontOf(BlockPos min, BlockPos max, BlockPos door) {
        if (door.getZ() <= min.getZ() + 1) return Direction.NORTH;
        if (door.getZ() >= max.getZ() - 1) return Direction.SOUTH;
        if (door.getX() <= min.getX() + 1) return Direction.WEST;
        return Direction.EAST;
    }

    public static Built build(ServerLevel level, HomeStyle style, BlockPos min, BlockPos max, BlockPos door) {
        return new HomeBuilder(level, style, min, max, frontOf(min, max, door)).run();
    }

    // ------------------------------------------------------------------ frame

    private BlockPos at(int lx, int ly, int lz) {
        return origin.relative(along, lx).relative(inward, lz).above(ly);
    }

    private void set(int lx, int ly, int lz, BlockState s) {
        BlockPos p = at(lx, ly, lz);
        level.setBlock(p, s, Block.UPDATE_CLIENTS);
        Block b = s.getBlock();
        if (b instanceof StairBlock || b instanceof net.minecraft.world.level.block.IronBarsBlock || b instanceof net.minecraft.world.level.block.FenceBlock
                || b instanceof net.minecraft.world.level.block.WallBlock) shaped.add(p);
    }

    private BlockState stair(BlockState s, Direction facing, boolean top) {
        if (!(s.getBlock() instanceof StairBlock)) return s;
        return s.setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, top ? Half.TOP : Half.BOTTOM);
    }

    private BlockState slab(BlockState s, boolean top) {
        return s.getBlock() instanceof SlabBlock ? s.setValue(SlabBlock.TYPE, top ? SlabType.TOP : SlabType.BOTTOM) : s;
    }

    private BlockState pillar(BlockState s) {
        return s.hasProperty(RotatedPillarBlock.AXIS) ? s.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y) : s;
    }

    private BlockState beam(BlockState s, Direction run) {
        return s.hasProperty(RotatedPillarBlock.AXIS) ? s.setValue(RotatedPillarBlock.AXIS, run.getAxis()) : s;
    }

    // ------------------------------------------------------------------ shape

    private int cx() {
        return (w - 1) / 2;
    }

    private int cz() {
        return (d - 1) / 2;
    }

    private double radius() {
        return (Math.min(w, d) - 1) / 2.0;
    }

    private double dist(int lx, int lz) {
        double dx = lx - (w - 1) / 2.0, dz = lz - (d - 1) / 2.0;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Inside the footprint (walls included). */
    private boolean inside(int lx, int lz) {
        if (lx < 0 || lz < 0 || lx >= w || lz >= d) return false;
        return !st.round || dist(lx, lz) <= radius() + 0.45;
    }

    private boolean wall(int lx, int lz) {
        if (!inside(lx, lz)) return false;
        if (!st.round) return lx == 0 || lz == 0 || lx == w - 1 || lz == d - 1;
        return dist(lx, lz) > radius() - 0.6;
    }

    private boolean interior(int lx, int lz) {
        return inside(lx, lz) && !wall(lx, lz);
    }

    private boolean corner(int lx, int lz) {
        return !st.round && (lx == 0 || lx == w - 1) && (lz == 0 || lz == d - 1);
    }

    private int storeyBase(int s) {
        return s * (st.wallH + 1);
    }

    private int top() {
        return storeyBase(st.storeys);
    }

    // ------------------------------------------------------------------ build

    private Built run() {
        boolean overWater = !level.getFluidState(at(cx(), -1, cz())).isEmpty() || !level.getFluidState(at(cx(), -2, cz())).isEmpty();
        int rise = st.stilts && !overWater ? 3 : 0;
        int base = floorY;
        floorY = base + rise;
        clear(base);
        foundations(base, overWater || st.stilts);
        for (int s = 0; s < st.storeys; s++) storey(s);
        // ladders up through the floors (after the floors, which they cut through)
        int[] l = ladderSpot();
        for (int y = 1; y <= storeyBase(st.storeys - 1); y++) put(l[0], y, l[1], ladder());
        roof();
        BlockPos door = entrance(base, rise);
        BlockPos[] beds = furnish();
        for (BlockPos p : shaped) {
            BlockState s = level.getBlockState(p);
            BlockState u = Block.updateFromNeighbourShapes(s, level, p);
            if (u != s) level.setBlock(p, u, Block.UPDATE_CLIENTS);
        }
        return new Built(door, beds[0], beds[1]);
    }

    private BlockPos wpos(int lx, int ly, int lz) {
        return at(lx, ly + floorY - origin.getY(), lz);
    }

    private void put(int lx, int ly, int lz, BlockState s) {
        set(lx, ly + floorY - origin.getY(), lz, s);
    }

    /** Clears trees, grass and old blocks from the plot (and a block round it). */
    private void clear(int base) {
        int h = top() + Math.max(w, d) / 2 + 8;
        int m = st.roof == HomeStyle.Roof.MUSHROOM ? 7 : 5;
        for (int lx = -m; lx < w + m; lx++) {
            for (int lz = -m; lz < d + m; lz++) {
                boolean plot = lx >= -1 && lx <= w && lz >= -1 && lz <= d;
                for (int y = base + 1 - origin.getY(); y <= h + floorY - origin.getY(); y++) {
                    BlockPos p = at(lx, y, lz);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir() || !level.getFluidState(p).isEmpty()) continue;
                    // the plot itself is cleared outright; round it, only the wild growth (trees, giant flora, plants)
                    if (plot || natural(s)) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /**
     * Wild growth only: tree canopies (natural, non-persistent leaves), plants, and the giant flora of the biomes
     * (Telvanni mushrooms, Graht-oaks, palms, Hist). Logs, mushroom blocks and placed leaves stay: the neighbours'
     * houses are built of them.
     */
    private static boolean natural(BlockState s) {
        if (s.is(net.minecraft.tags.BlockTags.LEAVES)) {
            return s.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT) && !s.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT);
        }
        if (s.is(Blocks.VINE) || s.canBeReplaced() && s.getFluidState().isEmpty()) return true;
        var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(s.getBlock());
        if (key == null || !key.getNamespace().equals("skycraft")) return false;
        String p = key.getPath();
        return p.contains("mushroom") || p.contains("cap") || p.contains("frond");
    }

    /** Solid footings down to the ground, or posts (stilts, piers over water). */
    private void foundations(int base, boolean posts) {
        for (int lx = 0; lx < w; lx++) {
            for (int lz = 0; lz < d; lz++) {
                if (!inside(lx, lz)) continue;
                put(lx, 0, lz, wall(lx, lz) ? st.foundation : st.floor);
                boolean post = !posts || (lx % 3 == 0 || lx == w - 1) && (lz % 3 == 0 || lz == d - 1) && wall(lx, lz);
                if (!post) continue;
                for (int dy = 1; dy <= 24; dy++) {
                    BlockPos p = wpos(lx, -dy, lz);
                    BlockState below = level.getBlockState(p);
                    if (!below.isAir() && below.getFluidState().isEmpty() && !below.canBeReplaced()) break;
                    level.setBlock(p, posts ? pillar(st.post) : st.foundation, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    private void storey(int s) {
        int yb = storeyBase(s);
        boolean ground = s == 0;
        for (int lx = 0; lx < w; lx++) {
            for (int lz = 0; lz < d; lz++) {
                if (!inside(lx, lz)) continue;
                if (!ground) put(lx, yb, lz, wall(lx, lz) ? st.wallLow : st.floor);
                if (!wall(lx, lz)) continue;
                for (int y = 1; y <= st.wallH; y++) {
                    BlockState b;
                    boolean frontBack = lz == 0 || lz == d - 1;
                    if (corner(lx, lz) || st.timber && !st.round && (frontBack ? lx % 4 == 0 : lz % 4 == 0) && isPostSpot(lx, lz)) {
                        b = pillar(st.post);
                    } else if (y == 1 && ground) {
                        b = st.wallLow;
                    } else if (y == st.wallH && st.timber && !st.round) {
                        b = beam(st.post, lz == 0 || lz == d - 1 ? along : inward);
                    } else {
                        b = st.wall;
                    }
                    if ((y == 2 || y == 3 && st.wallH >= 4) && window(lx, lz, ground)) b = st.window;
                    put(lx, yb + y, lz, b);
                }
            }
        }
        // ceiling / next floor
        for (int lx = 0; lx < w; lx++) {
            for (int lz = 0; lz < d; lz++) {
                if (inside(lx, lz) && !wall(lx, lz)) put(lx, yb + st.wallH + 1, lz, st.floor);
                else if (wall(lx, lz)) put(lx, yb + st.wallH + 1, lz, st.wallLow);
            }
        }
        // light
        int[] c = {cx(), cz()};
        if (st.light.getBlock() instanceof LanternBlock) {
            put(c[0], yb + st.wallH, c[1], st.light.setValue(LanternBlock.HANGING, true));
        } else {
            put(c[0], yb + st.wallH + 1, c[1], st.light);
        }
    }

    private boolean isPostSpot(int lx, int lz) {
        int door = cx();
        return !(lz == 0 && Math.abs(lx - door) <= 1);
    }

    private boolean window(int lx, int lz, boolean ground) {
        if (corner(lx, lz)) return false;
        if (lz == 0 && Math.abs(lx - cx()) <= 1 && ground) return false; // the door
        if (st.round) {
            double a = Math.atan2(lz - (d - 1) / 2.0, lx - (w - 1) / 2.0);
            int k = (int) Math.round(a / (Math.PI / 4));
            double ka = k * Math.PI / 4;
            return Math.abs(a - ka) < 0.12 && !(ground && k == -2);
        }
        int[] l = ladderSpot();
        if (lx == l[0] && lz == l[1] + 1) return false;
        boolean side = lx == 0 || lx == w - 1;
        int pos = side ? lz : lx;
        int len = side ? d : w;
        return pos >= 2 && pos <= len - 3 && pos % 3 == 2;
    }

    /** Where the ladder rises: against the back wall, at the left. */
    private int[] ladderSpot() {
        if (st.round) return new int[]{cx(), (int) Math.floor(cz() + radius() - 1)};
        return new int[]{1, d - 2};
    }

    private BlockState ladder() {
        return Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, front);
    }

    // ------------------------------------------------------------------ roofs

    private void roof() {
        int t = top();
        switch (st.roof) {
            case GABLE -> gable(t);
            case HIP -> hip(t);
            case FLAT -> flat(t);
            case DOME -> {
                flat(t);
                dome(t + 1);
            }
            case CONE -> cone(t);
            case MUSHROOM -> mushroom(t);
        }
    }

    private void gable(int t) {
        boolean ridgeAlong = w >= d; // the ridge runs along the longer side
        int span = ridgeAlong ? d : w;
        int len = ridgeAlong ? w : d;
        for (int k = 0; ; k++) {
            int a = -1 + k, b = span - k;
            if (a > b) break;
            int y = t + k;
            for (int i = -1; i <= len; i++) {
                if (a == b) {
                    putAcross(ridgeAlong, i, a, y, slab(st.roofSlab, false));
                    continue;
                }
                putAcross(ridgeAlong, i, a, y, stair(st.roofStairs, ridgeAlong ? inward : along, false));
                putAcross(ridgeAlong, i, b, y, stair(st.roofStairs, ridgeAlong ? front : along.getOpposite(), false));
            }
            // the gable ends: wall up under this course
            if (k >= 1) {
                for (int c = a + 1; c <= b - 1; c++) {
                    putAcross(ridgeAlong, 0, c, y, st.wall);
                    putAcross(ridgeAlong, len - 1, c, y, st.wall);
                }
            }
            if (a == b || a + 1 == b) break;
        }
    }

    private void putAcross(boolean ridgeAlong, int i, int c, int y, BlockState s) {
        if (ridgeAlong) put(i, y, c, s);
        else put(c, y, i, s);
    }

    private void hip(int t) {
        int x0 = -1, z0 = -1, x1 = w, z1 = d;
        for (int k = 0; x0 <= x1 && z0 <= z1; k++, x0++, z0++, x1--, z1--) {
            int y = t + k;
            if (x0 == x1 || z0 == z1) {
                for (int lx = x0; lx <= x1; lx++) for (int lz = z0; lz <= z1; lz++) put(lx, y, lz, slab(st.roofSlab, false));
                break;
            }
            for (int lx = x0; lx <= x1; lx++) {
                put(lx, y, z0, stair(st.roofStairs, inward, false));
                put(lx, y, z1, stair(st.roofStairs, front, false));
            }
            for (int lz = z0 + 1; lz < z1; lz++) {
                put(x0, y, lz, stair(st.roofStairs, along, false));
                put(x1, y, lz, stair(st.roofStairs, along.getOpposite(), false));
            }
        }
    }

    private void flat(int t) {
        for (int lx = 0; lx < w; lx++) {
            for (int lz = 0; lz < d; lz++) {
                if (!inside(lx, lz)) continue;
                put(lx, t, lz, wall(lx, lz) ? st.wallLow : st.roofBlock);
                if (wall(lx, lz)) put(lx, t + 1, lz, (lx + lz) % 2 == 0 ? st.wallLow : slab(st.trim, false));
            }
        }
        // the Hlaalu roof terrace: a hatch by the ladder
        int[] l = ladderSpot();
        if (st.roof == HomeStyle.Roof.FLAT) {
            for (int y = storeyBase(st.storeys - 1) + 1; y <= t; y++) put(l[0], y, l[1], ladder());
        }
    }

    private void dome(int y0) {
        double r = Math.min(w, d) / 2.0 - 1.5;
        int cx = cx(), cz = cz();
        for (int dy = 0; dy <= (int) r + 1; dy++) {
            for (int lx = 0; lx < w; lx++) {
                for (int lz = 0; lz < d; lz++) {
                    double dx = lx - (w - 1) / 2.0, dz = lz - (d - 1) / 2.0;
                    double dd = Math.sqrt(dx * dx + dz * dz + dy * dy * 1.3);
                    if (dd <= r && dd > r - 1.3) put(lx, y0 + dy, lz, st.roofBlock);
                }
            }
        }
        put(cx, y0 + (int) (r / 1.14) + 1, cz, st.post);
    }

    private void cone(int t) {
        double r = radius() + 1.5;
        for (int k = 0; r - k * 0.8 > 0.3; k++) {
            double rr = r - k * 0.8;
            for (int lx = -2; lx <= w + 1; lx++) {
                for (int lz = -2; lz <= d + 1; lz++) {
                    double dd = dist(lx, lz);
                    if (dd <= rr && dd > rr - 1.4) put(lx, t + 1 + k, lz, st.roofBlock);
                }
            }
        }
        put(cx(), t + 1 + (int) (r / 0.8), cz(), pillar(st.post));
    }

    private void mushroom(int t) {
        double r = radius() + 3;
        for (int lx = -4; lx <= w + 3; lx++) {
            for (int lz = -4; lz <= d + 3; lz++) {
                double dd = dist(lx, lz);
                if (dd <= r) put(lx, t + 1, lz, st.roofBlock);
                if (dd <= r && dd > r - 1) put(lx, t, lz, st.roofBlock);
                if (dd <= r - 2) put(lx, t + 2, lz, st.roofBlock);
                if (dd <= r - 4) put(lx, t + 3, lz, st.roofBlock);
                if (dd > r - 1.5 && dd <= r - 0.5 && (lx + lz) % 3 == 0) put(lx, t, lz, Blocks.SHROOMLIGHT.defaultBlockState());
            }
        }
    }

    // ------------------------------------------------------------------ entrance and interior

    /** The front door, and steps up to it when the house stands on posts. */
    private BlockPos entrance(int base, int rise) {
        int dx = cx();
        BlockState door = st.door.setValue(DoorBlock.FACING, inward);
        put(dx, 1, 0, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        put(dx, 2, 0, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        // a porch slab and a lamp beside the door
        put(dx, 0, -1, st.foundation);
        BlockState lamp = st.light.getBlock() instanceof LanternBlock ? st.light.setValue(LanternBlock.HANGING, false) : Blocks.LANTERN.defaultBlockState();
        int steps = rise > 0 ? rise : groundDrop(dx);
        for (int i = 1; i <= steps; i++) {
            put(dx, -i, -1 - i, stair(st.chair, inward, false));
            for (int y = -i - 1; y >= -i - 6; y--) {
                BlockPos p = wpos(dx, y, -1 - i);
                BlockState below = level.getBlockState(p);
                if (!below.isAir() && below.getFluidState().isEmpty() && !below.canBeReplaced()) break;
                level.setBlock(p, st.foundation, Block.UPDATE_CLIENTS);
            }
        }
        BlockPos lampPos = wpos(dx + 1, 1, -1);
        if (level.getBlockState(lampPos.below()).isAir()) level.setBlock(lampPos.below(), st.foundation, Block.UPDATE_CLIENTS);
        level.setBlock(lampPos, lamp, Block.UPDATE_CLIENTS);
        return wpos(dx, 1, 0);
    }

    /** How far the ground in front of the door lies below the floor (0 to 3 steps). */
    private int groundDrop(int dx) {
        for (int i = 0; i <= 3; i++) {
            BlockState s = level.getBlockState(wpos(dx, -i, -2));
            if (!s.isAir() && !s.canBeReplaced()) return i;
        }
        return 3;
    }

    /** Furnishing: returns {owner's bed, spare bed or null}. */
    private BlockPos[] furnish() {
        int dx = cx();
        // keep the way in clear
        for (int lz = 0; lz <= Math.min(3, d - 1); lz++) mark(dx, lz);
        int[] l = ladderSpot();
        mark(l[0], l[1]);
        mark(l[0] + 1, l[1]);
        // the owner's bed against the back wall, on the right
        BlockPos bed = bed(0, true);
        // chests (the house's storage), a barrel, the crafting corner
        int[] c1 = free(1, 2, 1);
        if (c1 != null && interior(c1[0], c1[1] + 1) && !isUsed(c1[0], c1[1] + 1)) {
            // a double chest along the left wall (a LEFT half's partner is clockwise of its facing: further in)
            put(c1[0], 1, c1[1], chest(ChestType.LEFT));
            put(c1[0], 1, c1[1] + 1, chest(ChestType.RIGHT));
            mark(c1[0], c1[1] + 1);
        } else if (c1 != null) {
            put(c1[0], 1, c1[1], chest(ChestType.SINGLE));
        }
        place(w - 2, 1, Blocks.BARREL.defaultBlockState());
        place(1, 1, Blocks.CRAFTING_TABLE.defaultBlockState());
        place(2, 1, Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING, inward));
        if (d >= 11) place(w - 2, cz(), Blocks.SMOKER.defaultBlockState().setValue(FurnaceBlock.FACING, along.getOpposite()));
        // a table with chairs, or the hearth fire with benches
        int[] tc = free(cx(), cz() + (st.hearth ? 2 : 0), 0);
        if (tc != null) {
            put(tc[0], 1, tc[1], st.table);
            put(tc[0], 2, tc[1], Blocks.CANDLE.defaultBlockState().setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
            if (free1(tc[0] - 1, tc[1])) put(tc[0] - 1, 1, tc[1], stair(st.chair, along.getOpposite(), false));
            if (free1(tc[0] + 1, tc[1])) put(tc[0] + 1, 1, tc[1], stair(st.chair, along, false));
        }
        if (st.hearth) {
            // a fire pit sunk in the floor
            int[] hc = free(cx(), cz() - 1, 0);
            if (hc != null) put(hc[0], 0, hc[1], Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.SIGNAL_FIRE, false));
        }
        // books by the back wall, a runner from the door
        place(2, d - 2, Blocks.BOOKSHELF.defaultBlockState());
        place(3, d - 2, Blocks.BOOKSHELF.defaultBlockState());
        for (int lz = 1; lz <= Math.min(3, d - 2); lz++) if (interior(dx, lz)) put(dx, 1, lz, st.carpet);
        place(w - 2, 2, Blocks.FLOWER_POT.defaultBlockState());
        // upstairs: a second bed (for a spouse or a guest), a chest, a rug
        BlockPos spare = null;
        if (st.storeys > 1) {
            for (int i = 0; i < w; i++) java.util.Arrays.fill(used[i], false);
            mark(l[0], l[1]);
            mark(l[0] + 1, l[1]);
            mark(l[0], l[1] - 1);
            spare = bed(storeyBase(1), false);
            int[] c = free(w - 2, 1, 1);
            if (c != null) put(c[0], storeyBase(1) + 1, c[1], chest(ChestType.SINGLE));
            for (int lx = cx() - 1; lx <= cx() + 1; lx++) {
                for (int lz = cz() - 1; lz <= cz() + 1; lz++) {
                    if (interior(lx, lz) && !isUsed(lx, lz)) put(lx, storeyBase(1) + 1, lz, st.carpet);
                }
            }
            if (st.storeys > 2) {
                // the Telvanni study at the top: enchanting and books
                int y = storeyBase(2) + 1;
                for (int i = 0; i < w; i++) java.util.Arrays.fill(used[i], false);
                mark(l[0], l[1]);
                int[] e = free(cx(), cz(), 0);
                if (e != null) put(e[0], y, e[1], Blocks.ENCHANTING_TABLE.defaultBlockState());
                int[] b1 = free(1, cz(), 0);
                if (b1 != null) put(b1[0], y, b1[1], Blocks.BOOKSHELF.defaultBlockState());
                int[] b2 = free(w - 2, cz(), 0);
                if (b2 != null) put(b2[0], y, b2[1], Blocks.BOOKSHELF.defaultBlockState());
            }
        }
        return new BlockPos[]{bed, spare};
    }

    private BlockPos bed(int yb, boolean right) {
        // foot one in from the back wall, head against it
        for (int pass = 0; pass < 2; pass++) {
            for (int lx = right ? w - 2 : 2; right ? lx >= 1 : lx <= w - 2; lx += right ? -1 : 1) {
                for (int lz = d - 2; lz >= 2; lz--) {
                    if (!interior(lx, lz) || !interior(lx, lz - 1) || isUsed(lx, lz) || isUsed(lx, lz - 1)) continue;
                    if (pass == 0 && (interior(lx, lz + 1))) continue; // prefer the head against a wall
                    BlockState b = st.bed.setValue(BedBlock.FACING, inward);
                    put(lx, yb + 1, lz - 1, b.setValue(BedBlock.PART, BedPart.FOOT));
                    put(lx, yb + 1, lz, b.setValue(BedBlock.PART, BedPart.HEAD));
                    mark(lx, lz);
                    mark(lx, lz - 1);
                    if (interior(lx - 1, lz) && !isUsed(lx - 1, lz)) {
                        put(lx - 1, yb + 1, lz, Blocks.BARREL.defaultBlockState());
                        mark(lx - 1, lz);
                    }
                    return wpos(lx, yb + 1, lz - 1);
                }
            }
        }
        return wpos(cx(), yb + 1, cz());
    }

    private BlockState chest(ChestType type) {
        return Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, along).setValue(ChestBlock.TYPE, type);
    }

    private void place(int lx, int lz, BlockState s) {
        int[] c = free(lx, lz, 0);
        if (c != null) put(c[0], 1, c[1], s);
    }

    private boolean free1(int lx, int lz) {
        if (!interior(lx, lz) || isUsed(lx, lz)) return false;
        mark(lx, lz);
        return true;
    }

    /** The nearest free interior cell to (lx, lz) within {@code reach}, marked used; null if none. */
    private int[] free(int lx, int lz, int reach) {
        for (int r = 0; r <= Math.max(reach, 3); r++) {
            for (int ox = -r; ox <= r; ox++) {
                for (int oz = -r; oz <= r; oz++) {
                    if (Math.max(Math.abs(ox), Math.abs(oz)) != r) continue;
                    int x = lx + ox, z = lz + oz;
                    if (interior(x, z) && !isUsed(x, z)) {
                        mark(x, z);
                        return new int[]{x, z};
                    }
                }
            }
        }
        return null;
    }

    private void mark(int lx, int lz) {
        if (lx >= 0 && lz >= 0 && lx < w && lz < d) used[lx][lz] = true;
    }

    private boolean isUsed(int lx, int lz) {
        return lx < 0 || lz < 0 || lx >= w || lz >= d || used[lx][lz];
    }
}
