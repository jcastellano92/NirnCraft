package com.nirncraft.roads;

import com.nirncraft.tamriel.Regions;
import com.nirncraft.tamriel.settlement.Culture;
import com.nirncraft.worldplan.RoadPlan;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.WallSide;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The road painter's data for the GPU (com.nirncraft.atlas: the overlays' road stage, native/shaders/
 * atlas_overlays_roads.slang, a port of {@link RoadPaint}): the baked road plan as words, every culture's materials as
 * block ids (each surface and edge choice for every roll 0..99, the wood, the stone, walls, stairs, lights, connected
 * fences and walls), and the culture of every region. Ids come from the caller's block table.
 */
public final class RoadPaintGpu {
    public static final int MAGIC = 0x44524F41;  // 'AORD'
    public static final int HEADER = 40;
    /** Words of one culture's block (see {@link #cultureWords}). */
    public static final int CULTURE_WORDS = 600;
    // a culture block: surfaces by kind (4 x 100 rolls), edges (100 rolls), then single states (C_*)
    public static final int C_SURFACE = 0, C_EDGE = 400, C_MISC = 500;
    public static final int M_DECK = 0, M_DECK_SLAB = 1, M_FENCE = 2, M_PLANKS = 3, M_WOOD_SLAB = 4, M_LOG_X = 5, M_LOG_Z = 6,
            M_LOG_Y = 7, M_STONE = 8, M_MOSSY = 9, M_STONE_SLAB = 10, M_WALL = 11, M_WALL_UP = 12, M_STONE_STAIRS = 13 /* N E S W */,
            M_WOOD_STAIRS = 17 /* N E S W */, M_LAMP = 21, M_LAMP_HANGING = 22, M_TORCH = 23, M_TORCH_OUT = 24,
            M_WALL_TORCH = 25 /* N E S W */, M_WALL_TORCH_OUT = 29 /* N E S W */, M_FENCE_ARM = 33 /* N E S W */,
            M_FENCE_CONNECT = 37 /* 16: N 1 E 2 S 4 W 8 */, M_WALL_CONNECT = 53 /* 16 */;
    private static final Direction[] NESW = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private RoadPaintGpu() {}

    /** Every state the road stage may write (for the generator program's table). */
    public static List<BlockState> states() {
        List<BlockState> out = new ArrayList<>();
        for (Culture c : Culture.VALUES)
            cultureWords(c, s -> {
                out.add(s);
                return 0;
            });
        for (Block b : new Block[] {Blocks.AIR, Blocks.DIRT, Blocks.STONE, Blocks.COARSE_DIRT, Blocks.GRAVEL, Blocks.COBBLESTONE,
                Blocks.MOSSY_COBBLESTONE}) out.add(b.defaultBlockState());
        return out;
    }

    /**
     * The words: header (magic; counts: vertices, segments, spans, buckets, regions; then offsets, see the slang file's
     * RH_*), the plan's arrays (floats as bits), the bucket keys sorted (bx, bz, k), the region cultures, the cultures'
     * blocks, then the global ids (air, dirt, stone, coarse dirt, gravel, cobblestone, mossy cobblestone).
     */
    public static int[] words(RoadPlan p, ToIntFunction<BlockState> id) {
        List<int[]> arrays = new ArrayList<>();
        int nv = p.vx.length, ns = p.segStart.length, nsp = p.spanFrom.length;
        int[] bucketChunks = p.bucketChunks(), bucketOff = p.bucketOffsets(), bucketEdges = p.bucketEdgeList();
        float[] inv = p.invLen2();
        int nb = bucketOff.length - 1;
        // bucket keys sorted by (bx, bz)
        Integer[] order = new Integer[nb];
        for (int k = 0; k < nb; k++) order[k] = k;
        java.util.Arrays.sort(order, (a, b) -> bucketChunks[a * 2] != bucketChunks[b * 2] ? Integer.compare(bucketChunks[a * 2], bucketChunks[b * 2])
                : Integer.compare(bucketChunks[a * 2 + 1], bucketChunks[b * 2 + 1]));
        int[] keys = new int[nb * 3];
        for (int i = 0; i < nb; i++) {
            keys[i * 3] = bucketChunks[order[i] * 2];
            keys[i * 3 + 1] = bucketChunks[order[i] * 2 + 1];
            keys[i * 3 + 2] = order[i];
        }
        int nr = Regions.count();
        int[] cultures = new int[nr];
        for (int r = 0; r < nr; r++) {
            Culture c;
            try {
                c = Culture.of(Regions.byIndex(r).culture());
            } catch (RuntimeException e) {
                c = Culture.NORD;
            }
            cultures[r] = c.ordinal();
        }
        int[] cult = new int[Culture.VALUES.length * CULTURE_WORDS];
        for (Culture c : Culture.VALUES) System.arraycopy(cultureWords(c, id), 0, cult, c.ordinal() * CULTURE_WORDS, CULTURE_WORDS);
        int[] globals = new int[8];
        Block[] g = {Blocks.AIR, Blocks.DIRT, Blocks.STONE, Blocks.COARSE_DIRT, Blocks.GRAVEL, Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE};
        for (int i = 0; i < g.length; i++) globals[i] = id.applyAsInt(g[i].defaultBlockState());
        // in RH_* order (slang): vx vz vh vg vw varc vspan vseg invLen2 segLink segKind segHalfWidth segStart segEnd
        // segJoinA segJoinB spanOff spanFrom spanTo pierOff pierAt bucketKeys bucketOff bucketEdges cultures cult globals
        arrays.add(fbits(p.vx));
        arrays.add(fbits(p.vz));
        arrays.add(fbits(p.vh));
        arrays.add(fbits(p.vg));
        arrays.add(fbits(p.vw));
        arrays.add(fbits(p.varc));
        arrays.add(bytes(p.vspan));
        arrays.add(p.vseg);
        arrays.add(fbits(inv));
        arrays.add(p.segLink);
        arrays.add(bytes(p.segKind));
        arrays.add(fbits(p.segHalfWidth));
        arrays.add(p.segStart);
        arrays.add(p.segEnd);
        arrays.add(p.segJoinA);
        arrays.add(p.segJoinB);
        arrays.add(p.spanOff);
        arrays.add(p.spanFrom);
        arrays.add(p.spanTo);
        arrays.add(p.pierOff);
        arrays.add(fbits(p.pierAt));
        arrays.add(keys);
        arrays.add(bucketOff);
        arrays.add(bucketEdges);
        arrays.add(cultures);
        arrays.add(cult);
        arrays.add(globals);
        // the places that keep a lane up (Painter.keptUp: settlements and forts): x, z, radius, fort
        List<int[]> keepers = new ArrayList<>();
        for (com.nirncraft.tamriel.Place pl : com.nirncraft.tamriel.Places.all()) {
            com.nirncraft.tamriel.PlaceType t = pl.type();
            if (!t.isSettlement() && t != com.nirncraft.tamriel.PlaceType.FORT) continue;
            keepers.add(new int[] {pl.x(), pl.z(), pl.radius(), t == com.nirncraft.tamriel.PlaceType.FORT ? 1 : 0});
        }
        int[] places = new int[1 + keepers.size() * 4];
        places[0] = keepers.size();
        for (int i = 0; i < keepers.size(); i++) System.arraycopy(keepers.get(i), 0, places, 1 + i * 4, 4);
        arrays.add(places);
        int total = HEADER;
        for (int[] a : arrays) total += a.length;
        int[] w = new int[total];
        w[0] = MAGIC;
        w[1] = nv;
        w[2] = ns;
        w[3] = nsp;
        w[4] = nb;
        w[5] = nr;
        int at = HEADER;
        for (int i = 0; i < arrays.size(); i++) {
            w[8 + i] = at;
            System.arraycopy(arrays.get(i), 0, w, at, arrays.get(i).length);
            at += arrays.get(i).length;
        }
        return w;
    }

    /** One culture's materials as ids (C_* / M_*). */
    static int[] cultureWords(Culture c, ToIntFunction<BlockState> id) {
        int[] w = new int[CULTURE_WORDS];
        for (int k = 0; k < 4; k++)
            for (int r = 0; r < 100; r++) w[C_SURFACE + k * 100 + r] = id.applyAsInt(RoadStyle.surface(c, (byte) k, r));
        for (int r = 0; r < 100; r++) w[C_EDGE + r] = id.applyAsInt(RoadStyle.edge(c, r));
        RoadStyle.Wood wood = RoadStyle.wood(c);
        RoadStyle.Stone st = RoadStyle.stone(c);
        BlockState fence = wood.fence().defaultBlockState(), wall = wallFor(st.block().defaultBlockState());
        int m = C_MISC;
        w[m + M_DECK] = id.applyAsInt(RoadStyle.deck(c));
        w[m + M_DECK_SLAB] = id.applyAsInt(RoadStyle.deckSlab(c));
        w[m + M_FENCE] = id.applyAsInt(fence);
        w[m + M_PLANKS] = id.applyAsInt(wood.planks().defaultBlockState());
        w[m + M_WOOD_SLAB] = id.applyAsInt(wood.slab().defaultBlockState());
        BlockState log = wood.log().defaultBlockState();
        w[m + M_LOG_X] = id.applyAsInt(log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        w[m + M_LOG_Z] = id.applyAsInt(log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z));
        w[m + M_LOG_Y] = id.applyAsInt(log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y));
        w[m + M_STONE] = id.applyAsInt(st.block().defaultBlockState());
        w[m + M_MOSSY] = id.applyAsInt(st.mossy().defaultBlockState());
        w[m + M_STONE_SLAB] = id.applyAsInt(st.slab().defaultBlockState());
        w[m + M_WALL] = id.applyAsInt(wall);
        w[m + M_WALL_UP] = id.applyAsInt(wall.setValue(WallBlock.UP, true));
        Block stoneStairs = stoneStairs(c), woodStairs = woodStairs(c);
        boolean soul = c == Culture.DUNMER;
        for (int k = 0; k < 4; k++) {
            w[m + M_STONE_STAIRS + k] = id.applyAsInt(stoneStairs.defaultBlockState().setValue(StairBlock.FACING, NESW[k]));
            w[m + M_WOOD_STAIRS + k] = id.applyAsInt(woodStairs.defaultBlockState().setValue(StairBlock.FACING, NESW[k]));
            w[m + M_WALL_TORCH + k] = id.applyAsInt(RoadLights.wallTorch(NESW[k], true));
            w[m + M_WALL_TORCH_OUT + k] = id.applyAsInt(RoadLights.wallTorch(NESW[k], false));
            w[m + M_FENCE_ARM + k] = id.applyAsInt(fence.setValue(side(NESW[k]), true));
        }
        w[m + M_LAMP] = id.applyAsInt(RoadLights.lamp(soul, false));
        w[m + M_LAMP_HANGING] = id.applyAsInt(RoadLights.lamp(soul, true));
        w[m + M_TORCH] = id.applyAsInt(RoadLights.torch(true));
        w[m + M_TORCH_OUT] = id.applyAsInt(RoadLights.torch(false));
        for (int b = 0; b < 16; b++) {
            boolean n = (b & 1) != 0, e = (b & 2) != 0, so = (b & 4) != 0, we = (b & 8) != 0;
            // RoadPaint.Painter.connect
            w[m + M_FENCE_CONNECT + b] = id.applyAsInt(fence.setValue(CrossCollisionBlock.NORTH, n).setValue(CrossCollisionBlock.EAST, e)
                    .setValue(CrossCollisionBlock.SOUTH, so).setValue(CrossCollisionBlock.WEST, we));
            w[m + M_WALL_CONNECT + b] = id.applyAsInt(wall.setValue(WallBlock.NORTH, n ? WallSide.LOW : WallSide.NONE)
                    .setValue(WallBlock.EAST, e ? WallSide.LOW : WallSide.NONE).setValue(WallBlock.SOUTH, so ? WallSide.LOW : WallSide.NONE)
                    .setValue(WallBlock.WEST, we ? WallSide.LOW : WallSide.NONE).setValue(WallBlock.UP, !(n && so && !e && !we) && !(e && we && !n && !so)));
        }
        return w;
    }

    private static int[] fbits(float[] a) {
        int[] o = new int[a.length];
        for (int i = 0; i < a.length; i++) o[i] = Float.floatToRawIntBits(a[i]);
        return o;
    }

    private static int[] bytes(byte[] a) {
        int[] o = new int[a.length];
        for (int i = 0; i < a.length; i++) o[i] = a[i];
        return o;
    }

    private static net.minecraft.world.level.block.state.properties.BooleanProperty side(Direction d) {
        return switch (d) {
            case NORTH -> CrossCollisionBlock.NORTH;
            case SOUTH -> CrossCollisionBlock.SOUTH;
            case EAST -> CrossCollisionBlock.EAST;
            default -> CrossCollisionBlock.WEST;
        };
    }

    // RoadPaint's private material helpers, the same choices
    static BlockState wallFor(BlockState masonry) {
        Block b = masonry.getBlock();
        Block wall = b == Blocks.COBBLESTONE || b == Blocks.MOSSY_COBBLESTONE ? Blocks.COBBLESTONE_WALL
                : b == Blocks.SANDSTONE || b == Blocks.CUT_SANDSTONE ? Blocks.SANDSTONE_WALL
                : b == Blocks.POLISHED_BLACKSTONE_BRICKS ? Blocks.POLISHED_BLACKSTONE_BRICK_WALL
                : b == Blocks.CALCITE || b == Blocks.QUARTZ_BRICKS ? Blocks.DIORITE_WALL
                : b == Blocks.MUD_BRICKS ? Blocks.MUD_BRICK_WALL
                : Blocks.STONE_BRICK_WALL;
        return wall.defaultBlockState();
    }

    static Block stoneStairs(Culture c) {
        Block b = RoadStyle.stone(c).block();
        return b == Blocks.COBBLESTONE ? Blocks.COBBLESTONE_STAIRS
                : b == Blocks.SANDSTONE ? Blocks.SANDSTONE_STAIRS
                : b == Blocks.POLISHED_BLACKSTONE_BRICKS ? Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS
                : b == Blocks.CALCITE ? Blocks.QUARTZ_STAIRS
                : b == Blocks.MUD_BRICKS ? Blocks.MUD_BRICK_STAIRS
                : Blocks.STONE_BRICK_STAIRS;
    }

    static Block woodStairs(Culture c) {
        Block planks = RoadStyle.wood(c).planks();
        return planks == Blocks.OAK_PLANKS ? Blocks.OAK_STAIRS
                : planks == Blocks.BIRCH_PLANKS ? Blocks.BIRCH_STAIRS
                : planks == Blocks.DARK_OAK_PLANKS ? Blocks.DARK_OAK_STAIRS
                : planks == Blocks.MANGROVE_PLANKS ? Blocks.MANGROVE_STAIRS
                : planks == Blocks.ACACIA_PLANKS ? Blocks.ACACIA_STAIRS
                : Blocks.SPRUCE_STAIRS;
    }

    /** RoadPaint's block classes (its isGround, isSupport, clearable and naturalColumn tests; Paver's). */
    public static final int R_GROUND = 1, R_SUPPORT = 2, R_CLEARABLE = 4, R_NATURAL = 8, R_LOGS = 16, R_LEAVES = 32, R_FALLING = 64,
            R_VERGE = 128, R_MOTION_NL = 256, R_SNOWLAYER = 512;

    public static int bits(BlockState s) {
        int v = 0;
        boolean ground = Paver.isNaturalGround(s) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM)
                || s.is(Blocks.DIRT_PATH) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.ROOTED_DIRT) || s.is(Blocks.MOSS_BLOCK);
        if (ground) v |= R_GROUND;
        boolean support = !(s.isAir() || s.canBeReplaced() || s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.LOGS))
                && !(s.getBlock() instanceof net.minecraft.world.level.block.BushBlock) && s.getFluidState().isEmpty() && s.canOcclude();
        if (support) v |= R_SUPPORT;
        boolean clearable = Paver.clearable(s) || s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.FLOWERS);
        if (clearable) v |= R_CLEARABLE;
        if (ground || clearable || s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(Blocks.SNOW)
                || s.is(Blocks.ICE) || s.is(Blocks.BLUE_ICE) || s.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)) v |= R_NATURAL;
        if (s.is(net.minecraft.tags.BlockTags.LOGS)) v |= R_LOGS;
        if (s.is(net.minecraft.tags.BlockTags.LEAVES)) v |= R_LEAVES;
        if (s.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) v |= R_FALLING;
        if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.PODZOL)) v |= R_VERGE;
        if (net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(s)) v |= R_MOTION_NL;
        if (s.is(Blocks.SNOW)) v |= R_SNOWLAYER;
        return v;
    }
}
