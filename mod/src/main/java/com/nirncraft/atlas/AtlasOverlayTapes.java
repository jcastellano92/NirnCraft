package com.nirncraft.atlas;

import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.settlement.SettlementPiece;
import com.nirncraft.tamriel.settlement.build.Builders;
import com.nirncraft.tamriel.settlement.build.Canvas;
import com.nirncraft.tamriel.settlement.plan.Cities;
import com.nirncraft.tamriel.settlement.plan.Spec;
import com.nirncraft.worldplan.street.StreetPlan;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.SpreadingSnowyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.FluidState;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.Predicate;

/**
 * Structures from the plans, stamped on the GPU (native/shaders/atlas_overlays.slang, atlas_ovl_* in atlas.h): the
 * named settlements' pieces as op tapes, the street painter's plan, and the block table both read.
 *
 * <p><b>Tapes.</b> Each piece of a settlement (StreetPlan.layout, the server's SettlementPiece: same spec, seed and
 * owner) is built once by its own builder (Builders.build) on a {@link Canvas} over a level that holds the terrain; the
 * canvas's {@link Canvas.Tape} hook records every write primitive as an op: a plain write (wset: no write over
 * FEATURES_CANNOT_REPLACE, no block over a door or bed, grass under a sturdy block to dirt), set-if-open, air, the
 * plant clearing, a foundation column down to solid ground, bed halves, furniture. The GPU replays them on the
 * client's own blocks, each predicate tested there, so a piece reacts to what the client generated (a tree, a
 * deeper hollow) as the server's does. The builders' own branches on what they read (a step down to the ground, a
 * banner where a wall is) are taken here once, on the terrain. Ops are kept per chunk column in the server's order
 * (piece order, then op order): one GPU thread per column replays its own list, no column is written by two threads
 * (the gather formulation), and the pieces need no neighbour chunk. postProcess's Sanity sweep follows on the GPU
 * over each column's sweep range (kept per chunk with the tape): its removals, then fence, wall and pane shapes; it
 * reads the four neighbour chunks of the batch.</p>
 *
 * <p><b>Ids.</b> 16-bit ids of {@link #blocks} (0 = air); the table carries per id the predicate flags (F_*) and
 * the street painter's transforms (a gate opened, a fence turned into a gate).</p>
 */
final class AtlasOverlayTapes implements Canvas.Tape {
    static final int BLOCKS_MAGIC = 0x4C424F41, TAPES_MAGIC = 0x50544F41, STREETS_MAGIC = 0x52534F41;  // 'AOBL', 'AOTP', 'AOSR'
    /**
     * Ints per batch chunk (atlas_ovl_run): cx, cz, tape record offset (-1 none), street bucket (-1 none), and the batch
     * slots of its north, east, south and west neighbours (-1 none: the sweeps then count what lies there as unknown).
     */
    static final int CHUNK_INTS = 8;
    static final int HEADER = 16;

    // op kinds (Canvas.Tape) and op flags; keep in step with atlas_overlays.slang
    static final int OF_NOSMOTHER = 1, OF_VAR = 2, OF_BOTTOMCLIP = 4;
    /** A raw write (recordRaw): the state over y0..y1 whatever is there (the recording read the world for it). */
    static final int RAW = 9;
    /** The tapes' index: cx, cz, record, layer. */
    static final int INDEX_STRIDE = 4;
    /**
     * The tapes' layers, each stamped at its own stage of the generator's batch (the server's decoration step of its
     * structures): the settlements' (top layer: ATLAS_GEN_STAGE_PIECES, then their sweeps), the underground structures'
     * (ATLAS_GEN_STAGE_DECORATION + 3) and the surface structures' (ATLAS_GEN_STAGE_DECORATION + 4).
     */
    static final int LAYER_SETTLEMENTS = 0, LAYER_UNDERGROUND = 1, LAYER_SURFACE = 2, LAYERS = 3;
    /** wset's door / bed guard applies: the state meant (before a burnt piece's charring) is neither air nor a door or bed. */
    static final int OF_GUARD = 8;

    private static int guard(BlockState meant) {
        return !meant.isAir() && !(meant.getBlock() instanceof BedBlock) && !(meant.getBlock() instanceof DoorBlock) ? OF_GUARD : 0;
    }

    // block flags; keep in step with atlas_overlays.slang
    static final int F_AIR = 1, F_FLUID = 1 << 1, F_OPEN = 1 << 2, F_SOLIDGROUND = 1 << 3, F_FCR = 1 << 4, F_BED = 1 << 5,
            F_DOOR = 1 << 6, F_SNOWY = 1 << 7, F_SMOTHERS = 1 << 8, F_PLANT = 1 << 9, F_CARPET = 1 << 10,
            F_REPL = 1 << 11, F_LEAVES = 1 << 12, F_SPNAT = 1 << 13, F_FENCE = 1 << 14, F_GATE = 1 << 15, F_LADDER = 1 << 16,
            F_TALLPOLE = 1 << 17, F_WBS = 1 << 18, F_SOLIDRENDER = 1 << 19, F_BASESTONE = 1 << 20, F_NATLOG = 1 << 21,
            F_LANTERN = 1 << 22, F_DIRTPATH = 1 << 23, F_GATEOPEN = 1 << 24;

    // ---- the block table --------------------------------------------------------------------------------------

    final List<BlockState> blocks = new ArrayList<>();
    private final Reference2IntOpenHashMap<BlockState> ids = new Reference2IntOpenHashMap<>();

    AtlasOverlayTapes() {
        this(List.of());
    }

    /**
     * A table whose first ids are {@code base}'s states in order (the generator program's blocks: its ids are then this
     * table's, so generated chunks are stamped as they come; base[0] must be air), further states after them.
     */
    AtlasOverlayTapes(List<BlockState> base) {
        ids.defaultReturnValue(-1);
        if (!base.isEmpty() && !base.getFirst().isAir()) throw new IllegalArgumentException("id 0 must be air");
        for (BlockState s : base) {
            if (ids.containsKey(s)) throw new IllegalArgumentException("a state twice in the base table: " + s);
            id(s);
        }
        id(Blocks.AIR.defaultBlockState());
    }

    /**
     * Frozen: the table is the generator program's (its blocks are fixed when it compiles): a state outside it is not
     * added (id -1) and the ops that would place it are dropped (counted in {@link #missing}).
     */
    boolean frozen;
    long missing;
    final java.util.Set<BlockState> missingStates = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The table id of a state (added on first use, unless frozen: -1). */
    synchronized int id(BlockState s) {
        int i = ids.getInt(s);
        if (i >= 0) return i;
        if (frozen) {
            missing++;
            if (missingStates.size() < 64) missingStates.add(s);
            return -1;
        }
        i = blocks.size();
        if (i > 0xFFFF) throw new IllegalStateException("more than 65536 block states in the overlay table");
        blocks.add(s);
        ids.put(s, i);
        return i;
    }

    /** The id of a state, or -1 when the table does not hold it. */
    synchronized int idIfPresent(BlockState s) {
        return ids.getInt(s);
    }

    /** Words per id in the block table: flags, transform, sweep bits, block index and facing and cover, shape table. */
    static final int ID_WORDS = 7;

    // sweep bits (word 2; Sanity and its connect step); keep in step with atlas_overlays.slang
    static final int S_DOOR_LOWER = 1, S_DOOR_UPPER = 1 << 1, S_BED = 1 << 2, S_BED_HEAD = 1 << 3, S_TRAPDOOR = 1 << 4, S_STAIR = 1 << 5,
            S_CARPET_PLATE = 1 << 6, S_LANTERN = 1 << 7, S_HANGING = 1 << 8, S_LADDER = 1 << 9, S_CHEST = 1 << 10, S_BARREL = 1 << 11,
            S_FENCE = 1 << 12, S_WALL = 1 << 13, S_BARS = 1 << 14, S_WATERLOGGED = 1 << 15, S_STURDY_N = 1 << 16, S_EXC = 1 << 20,
            S_WOODFENCE = 1 << 21, S_FENCETAG = 1 << 22, S_GATE = 1 << 23, S_GATE_AXIS_X = 1 << 24, S_WALLS = 1 << 25,
            S_POST_OVERRIDE = 1 << 26, S_WALL_UP = 1 << 27, S_COVER_POST = 1 << 28, S_SUPPORTED = 1 << 29;
    // word 3: block index (low 16 bits), horizontal facing (bits 16-17: N, E, S, W), the bottom face's cover of the wall
    // side test shapes (bits 20-23: N, E, S, W)
    // word 5: Accessibility's classes (A_*); keep in step with atlas_overlays.slang
    static final int A_CLEAR = 1, A_SOLIDTOP = 1 << 1, A_ROAD = 1 << 2, A_PROP = 1 << 3, A_LOG = 1 << 4, A_PROP_INDOOR = 1 << 5,
            A_SLAB = 1 << 6, A_WALLISH = 1 << 7, A_POLE = 1 << 8, A_POLETOP = 1 << 9, A_ARM = 1 << 10, A_PATHABLE = 1 << 11,
            A_COLLIDE = 1 << 12, A_HALF_BOTTOM = 1 << 13;
    private static final Direction[] NESW = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /**
     * The block table's words (atlas_ovl_set, which 0): a header (magic, count, the ids the kernels write: air, dirt,
     * coarse dirt, dirt path, spruce planks, water; words per id; the shape tables' offset and size), then per id
     * {@link #ID_WORDS} words: its flags (F_*), its transform word (a fence gate: the open gate; a fence: the open gate
     * of its wood facing east (low half) and south (high half)), its sweep bits (S_*), its block index, facing and
     * cover bits, the offset of its shape table (a fence or pane: 16 ids by connected sides N 1, E 2, S 4, W 8; a wall:
     * 162 ids by sides N + 3 E + 9 S + 27 W (none, low, tall) + 81 up; 0: none). Adds the states the transforms and
     * shape tables reach to the table first.
     */
    synchronized int[] blockWords() {
        int dirt = id(Blocks.DIRT.defaultBlockState()), coarse = id(Blocks.COARSE_DIRT.defaultBlockState()),
                path = id(Blocks.DIRT_PATH.defaultBlockState()), planks = id(Blocks.SPRUCE_PLANKS.defaultBlockState()),
                water = id(Blocks.WATER.defaultBlockState());
        int[] xform = new int[0], shapeOf = new int[0];
        List<int[]> shapes = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {  // the table grows while this runs (the transforms' and shapes' states)
            BlockState s = blocks.get(i);
            int x = 0;
            if (s.getBlock() instanceof FenceGateBlock) {
                x = idOr(s.setValue(FenceGateBlock.OPEN, true), i);
            } else if (s.getBlock() instanceof FenceBlock) {
                BlockState g = gateFor(s);
                x = idOr(g.setValue(FenceGateBlock.FACING, Direction.EAST).setValue(FenceGateBlock.OPEN, true), i)
                        | idOr(g.setValue(FenceGateBlock.FACING, Direction.SOUTH).setValue(FenceGateBlock.OPEN, true), i) << 16;
            }
            if (xform.length <= i) {
                xform = java.util.Arrays.copyOf(xform, Math.max(i + 1, xform.length * 2 + 64));
                shapeOf = java.util.Arrays.copyOf(shapeOf, xform.length);
            }
            xform[i] = x;
            int[] table = shapeTable(s);
            if (table != null) {
                shapes.add(table);
                shapeOf[i] = shapes.size();  // 1-based until the offsets are known
            }
        }
        int n = blocks.size();
        int shapeAt = HEADER + n * ID_WORDS, shapeWords = 0;
        int[] shapeOffset = new int[shapes.size()];
        for (int k = 0; k < shapes.size(); k++) {
            shapeOffset[k] = shapeAt + shapeWords;
            shapeWords += shapes.get(k).length;
        }
        int[] w = new int[shapeAt + shapeWords];
        w[0] = BLOCKS_MAGIC;
        w[1] = n;
        w[2] = 0;
        w[3] = dirt;
        w[4] = coarse;
        w[5] = path;
        w[6] = planks;
        w[7] = water;
        w[8] = ID_WORDS;
        w[9] = shapeAt;
        w[10] = shapeWords;
        Map<Block, Integer> blockIndex = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            BlockState s = blocks.get(i);
            int o = HEADER + i * ID_WORDS;
            w[o] = flags(s);
            w[o + 1] = xform[i];
            w[o + 2] = sweepBits(s);
            w[o + 3] = blockIndex.computeIfAbsent(s.getBlock(), k -> blockIndex.size() + 1) | facing(s) << 16 | cover(s) << 20;
            w[o + 4] = shapeOf[i] == 0 ? 0 : shapeOffset[shapeOf[i] - 1];
            w[o + 5] = accessBits(s);
            w[o + 6] = com.nirncraft.roads.RoadPaintGpu.bits(s);
        }
        for (int k = 0; k < shapes.size(); k++) System.arraycopy(shapes.get(k), 0, w, shapeOffset[k], shapes.get(k).length);
        return w;
    }

    /** The id of a state (added unless frozen), else {@code fallback} (a frozen table without it: the closest it has). */
    private int idOr(BlockState s, int fallback) {
        if (frozen) {
            int i = idIfPresent(s);
            return i >= 0 ? i : fallback;
        }
        return id(s);
    }

    /**
     * Every state the overlays may place, for the generator program's table (AtlasGenProgram.addBlocks): the given
     * states, the ids the kernels write, and the closure of transforms and shapes. Not frozen.
     */
    static List<BlockState> closure(java.util.Collection<BlockState> placed) {
        AtlasOverlayTapes t = new AtlasOverlayTapes();
        for (BlockState s : placed) t.id(s);
        t.blockWords();  // adds the kernels' ids, the gates and every fence / wall / pane shape
        return List.copyOf(t.blocks);
    }

    /** A connectable state's shapes (see blockWords), or null. */
    private int[] shapeTable(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof FenceBlock || b instanceof IronBarsBlock) {
            int[] t = new int[16];
            for (int m = 0; m < 16; m++)
                t[m] = idOr(s.setValue(CrossCollisionBlock.NORTH, (m & 1) != 0).setValue(CrossCollisionBlock.EAST, (m & 2) != 0)
                        .setValue(CrossCollisionBlock.SOUTH, (m & 4) != 0).setValue(CrossCollisionBlock.WEST, (m & 8) != 0), idIfPresent(s));
            return t;
        }
        if (b instanceof WallBlock) {
            WallSide[] sides = {WallSide.NONE, WallSide.LOW, WallSide.TALL};
            int[] t = new int[162];
            for (int m = 0; m < 162; m++) {
                int q = m;
                BlockState v = s;
                for (Direction d : NESW) {
                    v = v.setValue(WallBlock.PROPERTY_BY_DIRECTION.get(d), sides[q % 3]);
                    q /= 3;
                }
                t[m] = idOr(v.setValue(WallBlock.UP, q != 0), idIfPresent(s));
            }
            return t;
        }
        return null;
    }

    /** Sanity's and its connect step's classes of a state (S_*). */
    static int sweepBits(BlockState s) {
        Block b = s.getBlock();
        int v = 0;
        if (b instanceof DoorBlock) v |= s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? S_DOOR_LOWER : S_DOOR_UPPER;
        if (b instanceof BedBlock) {
            v |= S_BED;
            if (s.getValue(BedBlock.PART) == BedPart.HEAD) v |= S_BED_HEAD;
        }
        if (b instanceof TrapDoorBlock) v |= S_TRAPDOOR;
        if (b instanceof StairBlock) v |= S_STAIR;
        if (b instanceof CarpetBlock || b instanceof PressurePlateBlock) v |= S_CARPET_PLATE;
        if (b instanceof LanternBlock) {
            v |= S_LANTERN;
            if (s.getValue(BlockStateProperties.HANGING)) v |= S_HANGING;
        }
        if (b instanceof LadderBlock) v |= S_LADDER;
        if (b instanceof ChestBlock) v |= S_CHEST;
        if (b instanceof BarrelBlock) v |= S_BARREL;
        if (b instanceof FenceBlock) v |= S_FENCE;
        if (b instanceof WallBlock) v |= S_WALL;
        if (b instanceof IronBarsBlock) v |= S_BARS;
        // removeBlock leaves the state's fluid (a waterlogged chest: water)
        if (s.getFluidState().is(Fluids.WATER)) v |= S_WATERLOGGED;
        for (int k = 0; k < 4; k++)
            if (s.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, NESW[k])) v |= S_STURDY_N << k;
        if (Block.isExceptionForConnection(s)) v |= S_EXC;
        if (s.is(BlockTags.WOODEN_FENCES)) v |= S_WOODFENCE;
        if (s.is(BlockTags.FENCES)) v |= S_FENCETAG;
        if (b instanceof FenceGateBlock) {
            v |= S_GATE;
            if (s.getValue(FenceGateBlock.FACING).getAxis() == Direction.Axis.X) v |= S_GATE_AXIS_X;
        }
        if (s.is(BlockTags.WALLS)) v |= S_WALLS;
        if (s.is(BlockTags.WALL_POST_OVERRIDE)) v |= S_POST_OVERRIDE;
        if (b instanceof WallBlock && s.getValue(WallBlock.UP)) v |= S_WALL_UP;
        if (covered(s, wallTest(null))) v |= S_COVER_POST;
        if (!s.isAir() && s.getFluidState().isEmpty() && !s.canBeReplaced()) v |= S_SUPPORTED;
        return v;
    }

    /** Accessibility's block classes (its clear, solidTop, roadLike, isProp, isWall, remove and stair tests). */
    static int accessBits(BlockState s) {
        Block b = s.getBlock();
        int v = 0;
        boolean collide = !s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        if (collide) v |= A_COLLIDE;
        if (s.isAir() || s.canBeReplaced() || b instanceof CarpetBlock || b instanceof PressurePlateBlock || b instanceof DoorBlock
                || b instanceof FenceGateBlock || b instanceof TrapDoorBlock || !collide) v |= A_CLEAR;
        if (!s.isAir() && s.getFluidState().isEmpty() && !s.canBeReplaced() && collide) v |= A_SOLIDTOP;
        if (s.is(Blocks.DIRT_PATH) || s.is(Blocks.GRAVEL) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.MOSSY_COBBLESTONE)
                || s.is(Blocks.STONE_BRICKS) || s.is(Blocks.STONE) || s.is(Blocks.PACKED_MUD) || s.is(Blocks.MUD_BRICKS)
                || s.is(Blocks.SMOOTH_STONE) || s.is(Blocks.COBBLED_DEEPSLATE) || s.is(Blocks.POLISHED_ANDESITE)
                || s.is(Blocks.DEEPSLATE_BRICKS) || s.is(Blocks.BRICKS) || s.is(BlockTags.PLANKS)) v |= A_ROAD;
        if (b instanceof BedBlock || b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof FenceBlock
                || b instanceof net.minecraft.world.level.block.FlowerPotBlock || b instanceof net.minecraft.world.level.block.CandleBlock
                || b instanceof LanternBlock || b instanceof net.minecraft.world.level.block.AbstractCauldronBlock
                || b instanceof net.minecraft.world.level.block.AbstractFurnaceBlock || b instanceof net.minecraft.world.level.block.CraftingTableBlock
                || b instanceof net.minecraft.world.level.block.LecternBlock || b instanceof net.minecraft.world.level.block.CampfireBlock
                || b instanceof net.minecraft.world.level.block.CropBlock || b instanceof net.minecraft.world.level.block.LeavesBlock
                || b instanceof net.minecraft.world.level.block.HayBlock || b instanceof net.minecraft.world.level.block.LoomBlock
                || b instanceof net.minecraft.world.level.block.GrindstoneBlock || b instanceof net.minecraft.world.level.block.AnvilBlock
                || b instanceof net.minecraft.world.level.block.BrewingStandBlock || b instanceof net.minecraft.world.level.block.DecoratedPotBlock
                || b instanceof net.minecraft.world.level.block.SkullBlock || b instanceof net.minecraft.world.level.block.WallBannerBlock
                || b instanceof net.minecraft.world.level.block.StandingSignBlock || b instanceof net.minecraft.world.level.block.StemBlock
                || b instanceof net.minecraft.world.level.block.SweetBerryBushBlock
                || b instanceof com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlock
                || b instanceof com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureContainerBlock) v |= A_PROP;
        if (s.is(BlockTags.LOGS) && !s.is(BlockTags.PLANKS)) v |= A_LOG;
        if (s.is(Blocks.BOOKSHELF) || s.is(Blocks.CHISELED_BOOKSHELF) || s.is(Blocks.TARGET)) v |= A_PROP_INDOOR;
        if (b instanceof net.minecraft.world.level.block.SlabBlock) v |= A_SLAB;
        if (s.isSolidRender() || b instanceof StairBlock || b instanceof net.minecraft.world.level.block.SlabBlock || s.is(BlockTags.WALLS)
                || b instanceof IronBarsBlock || b instanceof net.minecraft.world.level.block.TransparentBlock) v |= A_WALLISH;
        if (b instanceof FenceBlock || s.is(BlockTags.WALLS)) v |= A_POLE;
        if (b instanceof FenceBlock || s.is(BlockTags.WALLS) || s.is(BlockTags.BANNERS) || s.is(BlockTags.ALL_SIGNS) || s.is(BlockTags.WOOL)
                || s.is(Blocks.GOLD_BLOCK) || s.is(com.nirncraft.worldgen.VanillaBlocks.LIGHTNING_ROD) || b instanceof LanternBlock) v |= A_POLETOP;
        if (b instanceof FenceBlock || s.is(BlockTags.BANNERS) || s.is(BlockTags.ALL_SIGNS) || s.is(BlockTags.WOOL)) v |= A_ARM;
        if (s.is(BlockTags.SUBSTRATE_OVERWORLD) || s.is(BlockTags.SAND) || s.is(Blocks.GRASS_BLOCK)) v |= A_PATHABLE;
        if (b instanceof StairBlock && s.getValue(StairBlock.HALF) == net.minecraft.world.level.block.state.properties.Half.BOTTOM) v |= A_HALF_BOTTOM;
        return v;
    }

    private static int facing(BlockState s) {
        if (!s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return 0;
        return java.util.Arrays.asList(NESW).indexOf(s.getValue(BlockStateProperties.HORIZONTAL_FACING));
    }

    /** Whether the state's bottom face covers each wall side's test shape (WallBlock.makeWallState: a tall side under it). */
    private static int cover(BlockState s) {
        int v = 0;
        for (int k = 0; k < 4; k++) if (covered(s, wallTest(NESW[k]))) v |= 1 << k;
        return v;
    }

    /** WallBlock.isCovered of the state's collision shape's bottom face. */
    private static boolean covered(BlockState above, VoxelShape test) {
        VoxelShape face = above.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).getFaceShape(Direction.DOWN);
        return !Shapes.joinIsNotEmpty(test, face, BooleanOp.ONLY_FIRST);
    }

    private static volatile Map<Direction, VoxelShape> wallTests;
    private static volatile VoxelShape wallPostTest;

    /** WallBlock's private test shapes (d null: the post's; else a side's), read once. */
    @SuppressWarnings("unchecked")
    private static VoxelShape wallTest(Direction d) {
        try {
            if (wallTests == null) {
                java.lang.reflect.Field post = WallBlock.class.getDeclaredField("TEST_SHAPE_POST");
                java.lang.reflect.Field sides = WallBlock.class.getDeclaredField("TEST_SHAPES_WALL");
                post.setAccessible(true);
                sides.setAccessible(true);
                wallPostTest = (VoxelShape) post.get(null);
                wallTests = (Map<Direction, VoxelShape>) sides.get(null);
            }
            return d == null ? wallPostTest : wallTests.get(d);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("WallBlock's test shapes", e);
        }
    }

    static int flags(BlockState s) {
        FluidState f = s.getFluidState();
        Block b = s.getBlock();
        int v = 0;
        if (s.isAir()) v |= F_AIR;
        if (!f.isEmpty()) v |= F_FLUID;
        if (Canvas.open(s)) v |= F_OPEN;
        if (Canvas.solidGround(s)) v |= F_SOLIDGROUND;
        if (s.is(BlockTags.FEATURES_CANNOT_REPLACE)) v |= F_FCR;
        if (b instanceof BedBlock) v |= F_BED;
        if (b instanceof DoorBlock) v |= F_DOOR;
        if (b instanceof SpreadingSnowyBlock) v |= F_SNOWY;
        // Canvas.smotherGrassBelow: what turns covered grass to dirt
        if (!s.isAir() && ((!f.isEmpty() && f.getAmount() == 8) || s.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.DOWN)))
            v |= F_SMOTHERS;
        if (!s.isAir() && Canvas.plant(s)) v |= F_PLANT;
        if (b instanceof CarpetBlock) v |= F_CARPET;
        // the street painter (StreetPaint.Painter: its natural, solidTop, isTallPole and clutter tests)
        if (s.canBeReplaced()) v |= F_REPL;
        if (s.is(BlockTags.LEAVES)) v |= F_LEAVES;
        if (s.is(BlockTags.SUBSTRATE_OVERWORLD) && !s.is(Blocks.DIRT_PATH) || s.is(Blocks.SAND) || s.is(Blocks.RED_SAND)
                || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.GRAVEL) || s.is(Blocks.FARMLAND) || s.is(Blocks.CLAY)) v |= F_SPNAT;
        if (b instanceof FenceBlock) v |= F_FENCE;
        if (b instanceof FenceGateBlock) v |= F_GATE;
        if (b instanceof FenceGateBlock && s.getValue(FenceGateBlock.OPEN)) v |= F_GATEOPEN;
        if (b instanceof LadderBlock) v |= F_LADDER;
        if (b instanceof FenceBlock || s.is(BlockTags.WALLS) || s.is(BlockTags.BANNERS) || s.is(BlockTags.ALL_SIGNS) || s.is(BlockTags.WOOL)
                || s.is(Blocks.GOLD_BLOCK)) v |= F_TALLPOLE;
        if (b instanceof FenceBlock || s.is(BlockTags.WALLS) || s.is(BlockTags.BANNERS) || s.is(BlockTags.ALL_SIGNS)) v |= F_WBS;
        if (s.isSolidRender()) v |= F_SOLIDRENDER;
        if (s.is(BlockTags.BASE_STONE_OVERWORLD)) v |= F_BASESTONE;
        if (s.is(BlockTags.OVERWORLD_NATURAL_LOGS)) v |= F_NATLOG;
        if (b instanceof LanternBlock) v |= F_LANTERN;
        if (s.is(Blocks.DIRT_PATH)) v |= F_DIRTPATH;
        return v;
    }

    /** StreetPaint.Painter.gateFor: the fence gate of a fence's wood (oak when there is none). */
    static BlockState gateFor(BlockState fence) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(fence.getBlock());
        Identifier g = Identifier.fromNamespaceAndPath(id.getNamespace(), id.getPath() + "_gate");
        Block b = BuiltInRegistries.BLOCK.getValue(g);
        if (b == Blocks.AIR || !(b instanceof FenceGateBlock)) b = Blocks.OAK_FENCE_GATE;
        return b.defaultBlockState();
    }

    // ---- tapes --------------------------------------------------------------------------------------------------

    /** Per layer, per chunk (key ChunkPos.pack) the ops of its 256 columns, in the server's order. */
    @SuppressWarnings("unchecked")
    private final Long2ObjectLinkedOpenHashMap<Cols>[] layers = new Long2ObjectLinkedOpenHashMap[] {new Long2ObjectLinkedOpenHashMap<Cols>(),
            new Long2ObjectLinkedOpenHashMap<Cols>(), new Long2ObjectLinkedOpenHashMap<Cols>()};
    /** The layer ops go to (recordRaw sets it for its structure's step). */
    private int layer = LAYER_SETTLEMENTS;

    /** A chunk's column lists, and where each list's last op starts (a run of plain writes merges into it). */
    private static final class Cols {
        final IntArrayList[] ops = new IntArrayList[256];
        final int[] last = new int[256];
        /** Per column the y range postProcess's sweeps look at (Sanity over the clip and the wide box): y0 | y1 << 16. */
        final int[] sweep = new int[256];
        /** The settlement pieces' boxes over this chunk (minX, minZ, maxX, maxZ, maxY: PlacementMask's structure mask). */
        final IntArrayList boxes = new IntArrayList();

        Cols() {
            java.util.Arrays.fill(sweep, NO_SWEEP);
        }
    }

    /** An empty sweep range (y0 > y1). */
    static final int NO_SWEEP = 0x7FFF | 0x8000 << 16;

    /** Only ops inside this box (x / z) are kept (null: all). */
    BoundingBox keep;
    long ops, pieces, failed, nanos;
    /** The first few pieces whose builder threw (dev reports). */
    final List<String> failures = new ArrayList<>();

    private Cols cols(int x, int z) {
        return cols(layer, x, z);
    }

    private Cols cols(int layer, int x, int z) {
        long k = net.minecraft.world.level.ChunkPos.pack(x >> 4, z >> 4);
        Cols c = layers[layer].get(k);
        if (c == null) layers[layer].put(k, c = new Cols());
        return c;
    }

    /** A raw write (recordRaw's): merged into the column's last op when it carries on a run of the same state. */
    private void raw(int x, int y, int z, BlockState s) {
        if (keep != null && (x < keep.minX() || x > keep.maxX() || z < keep.minZ() || z > keep.maxZ())) return;
        int sid = id(s);
        if (sid < 0) return;
        Cols cs = cols(x, z);
        int ci = (z & 15) << 4 | (x & 15);
        IntArrayList l = cs.ops[ci];
        if (l == null) cs.ops[ci] = l = new IntArrayList(8);
        ops++;
        int start = l.size(), w0 = RAW | sid << 16;
        if (start > 0 && cs.last[ci] == start - 2 && l.getInt(start - 2) == w0 && (l.getInt(start - 1) >> 16) == y - 1) {
            int y0 = (short) l.getInt(start - 1);
            l.set(start - 1, (y0 & 0xFFFF) | y << 16);
            return;
        }
        l.add(w0);
        l.add((y & 0xFFFF) | y << 16);
        cs.last[ci] = start;
    }

    /** The state wset would write at (x, y, z): the piece's charring filter (Canvas.wset). */
    static BlockState charred(Canvas c, int x, int y, int z, BlockState s) {
        return c.burn > 0 && !s.isAir() ? com.nirncraft.fire.aftermath.Charring.apply(s, x, y, z, y - c.oy, c.seed, c.burn) : s;
    }

    @Override
    public void op(Canvas c, int kind, int x, int y, int z, BlockState s, int a, int b) {
        if (keep != null && (x < keep.minX() || x > keep.maxX() || z < keep.minZ() || z > keep.maxZ())) return;
        Cols cs = cols(x, z);
        int ci = (z & 15) << 4 | (x & 15);
        IntArrayList l = cs.ops[ci];
        if (l == null) cs.ops[ci] = l = new IntArrayList(8);
        ops++;
        int start = l.size();
        switch (kind) {
            case W, OPEN, FURNITURE -> {
                BlockState w = charred(c, x, y, z, s);
                int flags = (c.okWorld(x, y - 1, z) ? 0 : OF_NOSMOTHER) | guard(s);
                int sid = id(w);
                if (sid < 0) return;
                int w0 = kind | flags << 4 | sid << 16;
                // a run of plain writes up a column (fill, walls) merges into one range
                if (kind == W && start > 0 && cs.last[ci] == start - 2 && l.getInt(start - 2) == w0 && (l.getInt(start - 1) >> 16) == y - 1) {
                    int y0 = (short) l.getInt(start - 1);
                    l.set(start - 1, (y0 & 0xFFFF) | y << 16);
                    return;
                }
                l.add(w0);
                l.add((y & 0xFFFF) | y << 16);
            }
            case NOT_AIR, PLANT -> {
                l.add(kind | id(Blocks.AIR.defaultBlockState()) << 16);
                l.add((y & 0xFFFF) | a << 16);
            }
            case ENTRANCE -> {
                // a = the outward 2D value (S 0, W 1, N 2, E 3) -> N 0, E 1, S 2, W 3
                int d = switch (a & 3) {
                    case 0 -> 2;
                    case 1 -> 3;
                    case 2 -> 0;
                    default -> 1;
                };
                l.add(kind | d << 4);
                l.add((y & 0xFFFF) | y << 16);
            }
            case BED -> {
                int sid = id(s);
                if (sid < 0) return;
                l.add(kind | sid << 16);
                l.add((y & 0xFFFF) | y << 16);
            }
            case FOUNDATION -> {
                // y = top, a = lowest y, b = the clip's top; per y the charred state (a burnt piece's posts vary)
                int n = y - a + 1;
                int[] st = new int[n];
                boolean var = false;
                for (int i = 0; i < n; i++) {
                    st[i] = id(charred(c, x, y - i, z, s));
                    if (st[i] < 0) return;
                    var |= st[i] != st[0];
                }
                int flags = (var ? OF_VAR : 0) | (c.okWorld(x, a - 1, z) ? 0 : OF_BOTTOMCLIP) | guard(s);
                l.add(kind | flags << 4 | st[0] << 16);
                l.add((y & 0xFFFF) | a << 16);
                l.add(b);
                if (var) for (int i = 0; i < n; i += 2) l.add(st[i] | (i + 1 < n ? st[i + 1] : 0) << 16);
            }
            default -> throw new IllegalArgumentException("op kind " + kind);
        }
        cs.last[ci] = start;
    }

    /**
     * Records the named settlement's pieces (the street plan's layout, as SettlementStructure adds them) over chunks
     * [cx0, cx1] x [cz0, cz1] of a window holding the terrain, in the server's order: chunk by chunk (z, then x), every
     * piece whose box meets the chunk in piece order, each through SettlementPiece.postProcess itself on the chunk's
     * region (its canvas clipped to the chunk and taped here; its sweeps painting the window untaped, so a later
     * builder's branch reads what the server's would). Returns the (piece, chunk) builds.
     */
    int recordSettlement(String placeId, Window w, int cx0, int cz0, int cx1, int cz1) {
        return record(placeId, pieces(placeId), w, cx0, cz0, cx1, cz1);
    }

    /** As recordSettlement, for any start's settlement pieces (a generic settlement, a working site: AtlasOverlayStarts). */
    int record(String placeId, List<SettlementPiece> list, Window w, int cx0, int cz0, int cx1, int cz1) {
        if (list.isEmpty()) return 0;
        int minY = w.heights.getMinY(), maxY = w.heights.getMaxY(), built = 0;
        for (int cz = cz0; cz <= cz1; cz++)
            for (int cx = cx0; cx <= cx1; cx++) {
                net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(cx, cz);
                BoundingBox chunkBox = new BoundingBox(cx << 4, minY + 1, cz << 4, (cx << 4) + 15, maxY, (cz << 4) + 15);
                WorldGenLevel level = w.region(cx, cz);
                for (SettlementPiece piece : list) {
                    if (!piece.getBoundingBox().intersects(chunkBox)) continue;
                    long t0 = System.nanoTime();
                    SettlementPiece.TAPE.set(this);
                    try {
                        piece.postProcess(level, null, null, RandomSource.create(0L), chunkBox, cp, BlockPos.ZERO);
                    } catch (RuntimeException e) {
                        failed++;
                        if (failures.size() < 8) failures.add(placeId + " #" + piece.index + " " + piece.spec.kind + ": " + e);
                    } finally {
                        SettlementPiece.TAPE.remove();
                    }
                    sweeps(piece.getBoundingBox(), cx, cz, minY, maxY);
                    box(piece.getBoundingBox(), cx, cz);
                    nanos += System.nanoTime() - t0;
                    pieces++;
                    built++;
                }
            }
        return built;
    }

    /**
     * Records a start's pieces as raw writes (a dungeon's: DungeonPiece.postProcess paints through a Painter that
     * reads the world as it goes), over chunks [cx0, cx1] x [cz0, cz1] of a window holding the terrain, in the
     * server's order (chunk by chunk, z then x; per chunk every piece meeting its writable area, StructureStart's
     * placeInChunk), every write the window takes kept in order per column, into the layer of the structure's step.
     * The writes are exact for the terrain the recording reads (the generator's own, before the decoration of the
     * structure's step). Spawns and block entities are left out (Painter.RECORDING). Returns the (piece, chunk) builds.
     */
    int recordRaw(String key, List<net.minecraft.world.level.levelgen.structure.StructurePiece> list, Window w, int cx0, int cz0, int cx1,
                  int cz1, int layer) {
        if (list.isEmpty()) return 0;
        int minY = w.heights.getMinY(), maxY = w.heights.getMaxY(), built = 0;
        BoundingBox first = list.get(0).getBoundingBox();
        BlockPos center = first.getCenter(), reference = new BlockPos(center.getX(), first.minY(), center.getZ());
        int was = this.layer;
        this.layer = layer;
        w.sink = (p, st) -> raw(p.getX(), p.getY(), p.getZ(), st);
        com.nirncraft.dungeons.world.Painter.RECORDING.set(Boolean.TRUE);
        try {
            for (int cz = cz0; cz <= cz1; cz++)
                for (int cx = cx0; cx <= cx1; cx++) {
                    net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(cx, cz);
                    BoundingBox chunkBox = new BoundingBox(cx << 4, minY + 1, cz << 4, (cx << 4) + 15, maxY, (cz << 4) + 15);
                    WorldGenLevel level = null;
                    for (net.minecraft.world.level.levelgen.structure.StructurePiece piece : list) {
                        if (!piece.getBoundingBox().intersects(chunkBox)) continue;
                        if (level == null) level = w.region(cx, cz);
                        long t0 = System.nanoTime();
                        try {
                            piece.postProcess(level, null, null, RandomSource.create(0L), chunkBox, cp, reference);
                        } catch (RuntimeException e) {
                            failed++;
                            if (failures.size() < 8) failures.add(key + " " + piece.getClass().getSimpleName() + ": " + e);
                        }
                        box(piece.getBoundingBox(), cx, cz);
                        nanos += System.nanoTime() - t0;
                        pieces++;
                        built++;
                    }
                }
        } finally {
            com.nirncraft.dungeons.world.Painter.RECORDING.remove();
            w.sink = null;
            this.layer = was;
        }
        return built;
    }

    /** The settlement's pieces as SettlementStructure adds them (OMIT left out; the index and seed kept). */
    static List<SettlementPiece> pieces(String placeId) {
        List<Spec> specs = StreetPlan.get().layout(placeId);
        Place place = Places.byId(placeId).orElse(null);
        List<SettlementPiece> out = new ArrayList<>();
        if (specs == null || place == null) return out;
        long seed = Cities.seed(place);
        for (int i = 0; i < specs.size(); i++) {
            Spec s = specs.get(i);
            if (s.has(Spec.OMIT)) continue;
            out.add(new SettlementPiece(s, seed ^ (i * 0x9E3779B97F4A7C15L), place.id(), i));
        }
        return out;
    }

    /**
     * The y ranges SettlementPiece.postProcess's Sanity sweeps cover for a piece box b in chunk (cx, cz) (the clip with
     * the foundations, then the box 7 wider and 2 below / 4 above, both within the chunk), joined into the columns'.
     */
    private void sweeps(BoundingBox b, int cx, int cz, int minY, int maxY) {
        int x0 = cx << 4, z0 = cz << 4, x1 = x0 + 15, z1 = z0 + 15;
        int cx0 = Math.max(x0, b.minX()), cx1 = Math.min(x1, b.maxX()), cz0 = Math.max(z0, b.minZ()), cz1 = Math.min(z1, b.maxZ());
        int cy0 = Math.max(minY + 1, b.minY() - SettlementPiece.FOUNDATION), cy1 = Math.min(maxY, b.maxY());
        if (cx0 > cx1 || cz0 > cz1 || cy0 > cy1) return;
        int top = maxY + 1 - 2;
        sweep(cx0, cz0, cx1, cz1, Math.max(cy0, minY + 1), Math.min(cy1, top));
        int wy0 = Math.max(minY + 1, cy0 - 2), wy1 = Math.min(maxY, cy1 + 4);
        sweep(Math.max(x0, cx0 - 7), Math.max(z0, cz0 - 7), Math.min(x1, cx1 + 7), Math.min(z1, cz1 + 7), Math.max(wy0, minY + 1),
                Math.min(wy1, top));
    }

    /** PlacementMask.collect's piece filter: no box wider than this counts (a long wall or street). */
    static final int PIECE_SPAN_MAX = 160;

    /** A piece's box over chunk (cx, cz), for the road painter's structure mask. */
    private void box(BoundingBox b, int cx, int cz) {
        if (b.getXSpan() > PIECE_SPAN_MAX || b.getZSpan() > PIECE_SPAN_MAX) return;
        if (keep != null && ((cx << 4) + 15 < keep.minX() || (cx << 4) > keep.maxX() || (cz << 4) + 15 < keep.minZ() || (cz << 4) > keep.maxZ()))
            return;
        Cols cs = cols(LAYER_SETTLEMENTS, cx << 4, cz << 4);
        cs.boxes.add(b.minX());
        cs.boxes.add(b.minZ());
        cs.boxes.add(b.maxX());
        cs.boxes.add(b.maxZ());
        cs.boxes.add(b.maxY());
    }

    private void sweep(int x0, int z0, int x1, int z1, int y0, int y1) {
        if (y0 > y1) return;
        for (int z = z0; z <= z1; z++)
            for (int x = x0; x <= x1; x++) {
                if (keep != null && (x < keep.minX() || x > keep.maxX() || z < keep.minZ() || z > keep.maxZ())) continue;
                Cols cs = cols(x, z);
                int ci = (z & 15) << 4 | (x & 15), r = cs.sweep[ci];
                int a = (short) r, c = r >> 16;
                if (a > c) {
                    a = y0;
                    c = y1;
                } else {
                    a = Math.min(a, y0);
                    c = Math.max(c, y1);
                }
                cs.sweep[ci] = (a & 0xFFFF) | c << 16;
            }
    }

    /** SettlementPiece.canvas: the piece's frame over the level, clipped to clip. */
    static Canvas canvas(SettlementPiece p, WorldGenLevel level, BoundingBox clip) {
        Spec s = p.spec;
        if (s.isLine()) return new Canvas(level, clip, 0, s.floor, 0, Direction.NORTH, p.seed);
        int[] o = SettlementPiece.origin(s);
        return new Canvas(level, clip, o[0], s.floor, o[1], s.facing, p.seed);
    }

    /** The chunks' tapes (ops in this table's ids, sweep ranges) to a cache: keys (ChunkPos.pack) of chunks this table has. */
    synchronized void write(java.io.DataOutputStream out, long[] keys) throws java.io.IOException {
        for (Long2ObjectLinkedOpenHashMap<Cols> chunks : layers) write(out, keys, chunks);
    }

    private static void write(java.io.DataOutputStream out, long[] keys, Long2ObjectLinkedOpenHashMap<Cols> chunks) throws java.io.IOException {
        int n = 0;
        for (long k : keys) if (chunks.containsKey(k)) n++;
        out.writeInt(n);
        for (long k : keys) {
            Cols c = chunks.get(k);
            if (c == null) continue;
            out.writeLong(k);
            for (int i = 0; i < 256; i++) out.writeInt(c.sweep[i]);
            for (int i = 0; i < 256; i++) {
                IntArrayList l = c.ops[i];
                int len = l == null ? 0 : l.size();
                out.writeInt(len);
                for (int j = 0; j < len; j++) out.writeInt(l.getInt(j));
            }
            out.writeInt(c.boxes.size());
            for (int j = 0; j < c.boxes.size(); j++) out.writeInt(c.boxes.getInt(j));
        }
    }

    /** Chunks' tapes from a cache (written by {@link #write} with this table's ids): they replace what this table had. */
    synchronized long[] read(java.io.DataInputStream in) throws java.io.IOException {
        it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet all = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
        for (Long2ObjectLinkedOpenHashMap<Cols> chunks : layers) for (long k : read(in, chunks)) all.add(k);
        return all.toLongArray();
    }

    private static long[] read(java.io.DataInputStream in, Long2ObjectLinkedOpenHashMap<Cols> chunks) throws java.io.IOException {
        int n = in.readInt();
        long[] keys = new long[n];
        for (int c = 0; c < n; c++) {
            long k = in.readLong();
            keys[c] = k;
            Cols cs = new Cols();
            for (int i = 0; i < 256; i++) cs.sweep[i] = in.readInt();
            for (int i = 0; i < 256; i++) {
                int len = in.readInt();
                if (len == 0) continue;
                IntArrayList l = new IntArrayList(len);
                for (int j = 0; j < len; j++) l.add(in.readInt());
                cs.ops[i] = l;
                cs.last[i] = -1;  // (no merging into a loaded list)
            }
            int nb = in.readInt();
            for (int j = 0; j < nb; j++) cs.boxes.add(in.readInt());
            chunks.put(k, cs);
        }
        return keys;
    }

    /** Chunks with ops (ChunkPos.pack keys). */
    long[] chunkKeys() {
        it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet all = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
        for (Long2ObjectLinkedOpenHashMap<Cols> chunks : layers) all.addAll(chunks.keySet());
        return all.toLongArray();
    }

    /**
     * The tapes' words (atlas_ovl_set, which 1) and each chunk's record offset (offsets: ChunkPos.pack -> word):
     * header (magic, index entries, the index's offset, the index's stride 4), the records, then the index (cx, cz,
     * record offset, layer per chunk and layer; offsets: the settlements' layer). A
     * record: 257 column starts (words from the record's op area), 256 column sweep ranges (y0 | y1 << 16, empty when
     * y0 > y1), then the ops.
     */
    int[] tapeWords(Map<Long, Integer> offsets) {
        IntArrayList w = new IntArrayList();
        for (int i = 0; i < HEADER; i++) w.add(0);
        w.set(0, TAPES_MAGIC);
        int entries = 0;
        for (Long2ObjectLinkedOpenHashMap<Cols> chunks : layers) entries += chunks.size();
        w.set(1, entries);
        w.set(3, INDEX_STRIDE);
        IntArrayList index = new IntArrayList();
        for (int layer = 0; layer < LAYERS; layer++)
        for (var e : layers[layer].long2ObjectEntrySet()) {
            int rec = w.size();
            if (layer == LAYER_SETTLEMENTS) offsets.put(e.getLongKey(), rec);
            index.add(net.minecraft.world.level.ChunkPos.getX(e.getLongKey()));
            index.add(net.minecraft.world.level.ChunkPos.getZ(e.getLongKey()));
            index.add(rec);
            index.add(layer);
            IntArrayList[] cols = e.getValue().ops;
            int at = 0;
            for (int c = 0; c < 256; c++) {
                w.add(at);
                at += cols[c] == null ? 0 : cols[c].size();
            }
            w.add(at);
            for (int c = 0; c < 256; c++) w.add(e.getValue().sweep[c]);
            for (int c = 0; c < 256; c++) if (cols[c] != null) w.addAll(cols[c]);
            // after the ops: the pieces' boxes (count, then minX, minZ, maxX, maxZ, maxY each)
            w.add(e.getValue().boxes.size() / 5);
            w.addAll(e.getValue().boxes);
        }
        w.set(2, w.size());
        w.addAll(index);
        return w.toIntArray();
    }

    // ---- the street painter's plan ------------------------------------------------------------------------------

    /**
     * The street plan as the painter reads it (atlas_ovl_set, which 2): header (magic, nodes, edges, plots, buckets,
     * and the offsets of: nodes (x, z, h as float bits, class), edges (a, b, width bits, class, surface), plots
     * (minX, minZ, maxX, maxZ, kind), buckets (edgeFrom, edgeTo, plotFrom, plotTo), the bucket edge list, the bucket
     * plot list; then the world's lowest and highest painted y, and the offset of the buckets' chunks (bx, bz each)).
     * A chunk's bucket is plan.bucket(cx, cz); buckets <= 0: all of the plan's.
     */
    static int[] streetWords(StreetPlan p, LevelHeightAccessor heights, int buckets) {
        int nn = p.nodes(), ne = p.edges(), np = p.plots();
        if (buckets <= 0) buckets = p.buckets();
        int edgeList = buckets == 0 ? 0 : p.edgeTo(buckets - 1), plotList = buckets == 0 ? 0 : p.plotTo(buckets - 1);
        int oN = HEADER, oE = oN + nn * 4, oP = oE + ne * 5, oB = oP + np * 5, oEL = oB + buckets * 4, oPL = oEL + edgeList,
                oK = oPL + plotList, end = oK + buckets * 2;
        int[] w = new int[end];
        w[0] = STREETS_MAGIC;
        w[1] = nn;
        w[2] = ne;
        w[3] = np;
        w[4] = buckets;
        w[5] = oN;
        w[6] = oE;
        w[7] = oP;
        w[8] = oB;
        w[9] = oEL;
        w[10] = oPL;
        w[11] = heights.getMinY() + 1;           // Painter.minY
        w[12] = (heights.getMaxY() + 1) - 4;     // Painter.maxY
        w[13] = oK;
        int[] keys = p.bucketChunks();
        System.arraycopy(keys, 0, w, oK, buckets * 2);
        for (int i = 0; i < nn; i++) {
            w[oN + i * 4] = Float.floatToRawIntBits(p.nx[i]);
            w[oN + i * 4 + 1] = Float.floatToRawIntBits(p.nz[i]);
            w[oN + i * 4 + 2] = Float.floatToRawIntBits(p.nh[i]);
            w[oN + i * 4 + 3] = p.nClass[i];
        }
        for (int i = 0; i < ne; i++) {
            w[oE + i * 5] = p.ea[i];
            w[oE + i * 5 + 1] = p.eb[i];
            w[oE + i * 5 + 2] = Float.floatToRawIntBits(p.ew[i]);
            w[oE + i * 5 + 3] = p.eClass[i];
            w[oE + i * 5 + 4] = p.eSurf[i];
        }
        for (int i = 0; i < np; i++) {
            w[oP + i * 5] = p.pMinX[i];
            w[oP + i * 5 + 1] = p.pMinZ[i];
            w[oP + i * 5 + 2] = p.pMaxX[i];
            w[oP + i * 5 + 3] = p.pMaxZ[i];
            w[oP + i * 5 + 4] = p.pKind[i];
        }
        for (int k = 0; k < buckets; k++) {
            w[oB + k * 4] = p.edgeFrom(k);
            w[oB + k * 4 + 1] = p.edgeTo(k);
            w[oB + k * 4 + 2] = p.plotFrom(k);
            w[oB + k * 4 + 3] = p.plotTo(k);
        }
        for (int j = 0; j < edgeList; j++) w[oEL + j] = p.edgeAt(j);
        for (int j = 0; j < plotList; j++) w[oPL + j] = p.plotAt(j);
        return w;
    }

    // ---- a level over proto chunks ------------------------------------------------------------------------------

    /**
     * Proto chunks holding a world's blocks (filled on first touch by {@code source}), seen as a WorldGenLevel: all of
     * them writable (the recording), or only the 3 x 3 around a chunk (a WorldGenRegion at the decoration step:
     * {@link #region}). Heightmaps kept by the proto chunks as they are written.
     */
    static final class Window {
        final LevelHeightAccessor heights;
        final LongFunction<ProtoChunk> source;
        final Long2ObjectLinkedOpenHashMap<ProtoChunk> chunks = new Long2ObjectLinkedOpenHashMap<>();
        final long seed;
        /** Every write the window takes, in order (recordRaw's), or null. */
        java.util.function.BiConsumer<BlockPos, BlockState> sink;

        Window(LevelHeightAccessor heights, long seed, LongFunction<ProtoChunk> source) {
            this.heights = heights;
            this.seed = seed;
            this.source = source;
        }

        ProtoChunk chunk(int cx, int cz) {
            long k = net.minecraft.world.level.ChunkPos.pack(cx, cz);
            ProtoChunk c = chunks.get(k);
            if (c == null && !chunks.containsKey(k)) {
                c = source.apply(k);
                chunks.put(k, c);
            }
            return c;
        }

        BlockState state(BlockPos p) {
            ProtoChunk c = chunk(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()));
            return c == null ? Blocks.AIR.defaultBlockState() : c.getBlockState(p);
        }

        /** Every chunk writable. */
        WorldGenLevel level() {
            return proxy(Integer.MIN_VALUE, 0);
        }

        /** Writable over the 3 x 3 around chunk (cx, cz). */
        WorldGenLevel region(int cx, int cz) {
            return proxy(cx, cz);
        }

        @SuppressWarnings("unchecked")
        private WorldGenLevel proxy(int cx, int cz) {
            RandomSource random = RandomSource.create(seed);
            Predicate<BlockPos> writable = cx == Integer.MIN_VALUE ? p -> p.getY() >= heights.getMinY() && p.getY() <= heights.getMaxY()
                    : p -> {
                        int dx = SectionPos.blockToSectionCoord(p.getX()) - cx, dz = SectionPos.blockToSectionCoord(p.getZ()) - cz;
                        return dx >= -1 && dx <= 1 && dz >= -1 && dz <= 1 && p.getY() >= heights.getMinY() && p.getY() <= heights.getMaxY();
                    };
            InvocationHandler h = (proxy, m, args) -> {
                int n = args == null ? 0 : args.length;
                switch (m.getName()) {
                    case "getBlockState":
                        if (n == 1 && args[0] instanceof BlockPos p) return state(p);
                        break;
                    case "getFluidState":
                        if (n == 1 && args[0] instanceof BlockPos p) return state(p).getFluidState();
                        break;
                    case "setBlock":
                        if (n >= 3 && args[0] instanceof BlockPos p && args[1] instanceof BlockState s) {
                            if (!writable.test(p)) return false;
                            ProtoChunk c = chunk(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()));
                            if (c == null) return false;
                            c.setBlockState(p, s, (int) args[2]);
                            if (sink != null) sink.accept(p, s);
                            return true;
                        }
                        break;
                    case "removeBlock":
                    case "destroyBlock":
                        if (n >= 1 && args[0] instanceof BlockPos p && writable.test(p)) {
                            ProtoChunk c = chunk(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()));
                            if (c != null) {
                                BlockState s = state(p).getFluidState().createLegacyBlock();
                                c.setBlockState(p, s, 3);
                                if (sink != null) sink.accept(p, s);
                            }
                            return c != null;
                        }
                        return false;
                    case "ensureCanWrite":
                        return n == 1 && args[0] instanceof BlockPos p && writable.test(p);
                    case "getChunk":
                        if (n >= 2 && args[0] instanceof Integer x && args[1] instanceof Integer z) return chunk(x, z);
                        if (n == 1 && args[0] instanceof BlockPos p)
                            return chunk(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()));
                        break;
                    case "getHeight":
                        if (n == 0) return heights.getHeight();
                        if (n == 3 && args[0] instanceof Heightmap.Types t) {
                            int x = (int) args[1], z = (int) args[2];
                            ProtoChunk c = chunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
                            return c == null ? heights.getMinY() : c.getHeight(t, x & 15, z & 15) + 1;
                        }
                        break;
                    case "getHeightmapPos":
                        if (n == 2 && args[0] instanceof Heightmap.Types t && args[1] instanceof BlockPos p) {
                            ProtoChunk c = chunk(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()));
                            int y = c == null ? heights.getMinY() : c.getHeight(t, p.getX() & 15, p.getZ() & 15) + 1;
                            return new BlockPos(p.getX(), y, p.getZ());
                        }
                        break;
                    case "getMinY":
                        return heights.getMinY();
                    case "getMaxY":
                        return heights.getMaxY();
                    case "getHeightAccessorForGeneration":
                        return heights;
                    case "hasChunk":
                        if (n == 2) return chunk((int) args[0], (int) args[1]) != null;
                        break;
                    case "hasChunksAt":
                        return true;
                    case "isStateAtPosition":
                        if (n == 2) return ((Predicate<BlockState>) args[1]).test(state((BlockPos) args[0]));
                        break;
                    case "isFluidAtPosition":
                        if (n == 2) return ((Predicate<FluidState>) args[1]).test(state((BlockPos) args[0]).getFluidState());
                        break;
                    case "getBlockEntity":
                        return n == 2 ? Optional.empty() : null;
                    case "getSeed":
                        return seed;
                    case "getRandom":
                        return random;
                    case "isClientSide":
                        return false;
                    case "getSeaLevel":
                        return 63;
                    case "scheduleTick":
                    case "setCurrentlyGenerating":
                    case "blockUpdated":
                    case "updateNeighborsAt":
                        return m.getReturnType() == boolean.class ? false : null;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    case "toString":
                        return "atlas overlay level";
                    default:
                        break;
                }
                if (m.isDefault()) return InvocationHandler.invokeDefault(proxy, m, args);
                return AtlasCapture.zero(m.getReturnType());
            };
            return (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] {WorldGenLevel.class}, h);
        }
    }
}
