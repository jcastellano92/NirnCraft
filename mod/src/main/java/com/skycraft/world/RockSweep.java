package com.skycraft.world;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Orphan rock sweep, the stone counterpart of {@link OrphanSweep}. Roads, settlement grading and terrain changes cut
 * the ground out from under boulders, scree, spires and loose ore, and leave small islands of stone, dirt or ore
 * hanging in the air. This finds the connected components of terrain-like blocks near the top of each column and removes
 * every small one that is held by nothing: it touches no other solid block, no fluid-free support, no built block and no
 * edge of the writable box. Big components (more than {@link #LIMIT} blocks) are the land itself and are never touched.
 *
 * <p>Pure function of the blocks it reads; reads and writes stay inside the writable box and (when {@code loaded} is
 * given) inside loaded chunks, so it is safe in worldgen regions and on a live server.</p>
 */
public final class RockSweep {
    private static final int FLAGS = 2 | 16;
    /** A component bigger than this is the land (or a mountain), not a floating rock. */
    public static final int LIMIT = 1600;
    private static final String[] BUILT = {"plank", "brick", "door", "glass", "wool", "concrete", "polished", "smooth", "chiseled",
            "stripped", "tile", "thatch", "hay_block", "scaffolding", "bars", "cut_", "lantern", "barrel", "chest", "bookshelf",
            "charred", "scorched", "carpet", "bed", "fence", "torch", "campfire", "furnace", "anvil", "table"};

    private RockSweep() {}

    /**
     * Sweeps columns of {@code [tx1..tx2] x [tz1..tz2]} grown by {@code margin} (clamped to the writable box), looking
     * {@code depth} blocks down from each column's top.
     *
     * @param loaded chunk-coordinate test (cx, cz) for live levels, or null in worldgen regions
     * @param keep   positions that must never be removed (player-placed blocks), or null
     * @return the number of blocks removed
     */
    public static int sweep(LevelAccessor level, int tx1, int tz1, int tx2, int tz2, int margin, int wx1, int wz1, int wx2, int wz2,
                            int depth, BiPredicate<Integer, Integer> loaded, Predicate<BlockPos> keep) {
        int x1 = Math.max(wx1, tx1 - margin), x2 = Math.min(wx2, tx2 + margin);
        int z1 = Math.max(wz1, tz1 - margin), z2 = Math.min(wz2, tz2 + margin);
        if (x1 > x2 || z1 > z2) return 0;
        int minY = level.getMinBuildHeight() + 2, maxY = level.getMaxBuildHeight() - 2;
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayList doomed = new LongArrayList();
        LongArrayList comp = new LongArrayList();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                if (loaded != null && !loaded.test(x >> 4, z >> 4)) continue;
                int top = Math.min(maxY, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
                int bottom = Math.max(minY, top - depth);
                for (int y = top; y >= bottom; y--) {
                    BlockState s = level.getBlockState(p.set(x, y, z));
                    if (!member(s)) continue;
                    long key = BlockPos.asLong(x, y, z);
                    if (seen.contains(key)) continue;
                    // only blocks with nothing solid under them can start a floating component
                    BlockState below = level.getBlockState(q.set(x, y - 1, z));
                    if (below.blocksMotion() || !below.getFluidState().isEmpty()) continue;
                    comp.clear();
                    queue.clear();
                    seen.add(key);
                    queue.enqueue(key);
                    boolean grounded = false;
                    while (!queue.isEmpty() && !grounded) {
                        long k = queue.dequeueLong();
                        comp.add(k);
                        if (comp.size() > LIMIT) {
                            grounded = true;
                            break;
                        }
                        int bx = BlockPos.getX(k), by = BlockPos.getY(k), bz = BlockPos.getZ(k);
                        if (bx <= x1 || bx >= x2 || bz <= z1 || bz >= z2 || by <= minY || by >= maxY) {
                            grounded = true; // may continue out of the box
                            break;
                        }
                        if (loaded != null && (!loaded.test((bx - 1) >> 4, bz >> 4) || !loaded.test((bx + 1) >> 4, bz >> 4)
                                || !loaded.test(bx >> 4, (bz - 1) >> 4) || !loaded.test(bx >> 4, (bz + 1) >> 4))) {
                            grounded = true;
                            break;
                        }
                        for (Direction d : Direction.values()) {
                            BlockState ns = level.getBlockState(q.set(bx + d.getStepX(), by + d.getStepY(), bz + d.getStepZ()));
                            if (member(ns)) continue; // handled by the 26-neighbourhood below
                            if (ns.isAir() || !ns.blocksMotion()) continue;
                            // another solid block touches it: a log, a wall, planks, a built block: held (or built on)
                            if (!ns.is(BlockTags.LEAVES)) {
                                grounded = true;
                                break;
                            }
                        }
                        if (grounded) break;
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dy = -1; dy <= 1; dy++) {
                                for (int dz = -1; dz <= 1; dz++) {
                                    if ((dx | dy | dz) == 0) continue;
                                    long n = BlockPos.asLong(bx + dx, by + dy, bz + dz);
                                    if (seen.contains(n)) continue;
                                    BlockState ns = level.getBlockState(q.set(bx + dx, by + dy, bz + dz));
                                    if (!member(ns)) continue;
                                    seen.add(n);
                                    queue.enqueue(n);
                                }
                            }
                        }
                    }
                    if (grounded || comp.isEmpty()) continue;
                    if (touchesBuilt(level, comp, q)) continue;
                    if (keep != null) {
                        boolean prot = false;
                        for (int i = 0; i < comp.size() && !prot; i++) prot = keep.test(BlockPos.of(comp.getLong(i)));
                        if (prot) continue;
                    }
                    doomed.addAll(comp);
                }
            }
        }
        BlockState air = Blocks.AIR.defaultBlockState();
        int n = 0;
        for (int i = 0; i < doomed.size(); i++) {
            BlockPos pos = BlockPos.of(doomed.getLong(i));
            if (level.setBlock(pos, air, FLAGS)) n++;
            // grass, flowers, double plants, vines and snow that sat on it would hang in the air
            for (int dy = 1; dy <= 2; dy++) {
                BlockPos up = pos.above(dy);
                BlockState u = level.getBlockState(up);
                if (isFloatingCover(u)) {
                    level.setBlock(up, air, FLAGS);
                }
            }
        }
        return n;
    }

    private static boolean isFloatingCover(BlockState s) {
        if (s.isAir()) return false;
        Block b = s.getBlock();
        return b instanceof net.minecraft.world.level.block.BushBlock
                || b instanceof com.skycraft.crafting.arcane.block.IngredientPlantBlock
                || b instanceof net.minecraft.world.level.block.DoublePlantBlock
                || b instanceof net.minecraft.world.level.block.TallGrassBlock
                || b instanceof net.minecraft.world.level.block.VineBlock
                || b instanceof net.minecraft.world.level.block.SnowLayerBlock
                || s.is(BlockTags.FLOWERS)
                || s.is(BlockTags.SAPLINGS)
                || s.is(Blocks.MOSS_CARPET)
                || s.is(Blocks.SNOW);
    }

    /** Terrain-like material: stone and its relatives, soil, sand, gravel, ores, loose cobble and the slabs/walls of rock. */
    static boolean member(BlockState s) {
        if (s.isAir()) return false;
        if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.TERRACOTTA)
                || s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COPPER_ORES) || s.is(BlockTags.GOLD_ORES)
                || s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES) || s.is(BlockTags.EMERALD_ORES) || s.is(BlockTags.DIAMOND_ORES)) {
            return true;
        }
        Block b = s.getBlock();
        if (b == Blocks.GRAVEL || b == Blocks.COBBLESTONE || b == Blocks.MOSSY_COBBLESTONE || b == Blocks.CLAY || b == Blocks.CALCITE
                || b == Blocks.DRIPSTONE_BLOCK || b == Blocks.SNOW_BLOCK || b == Blocks.PACKED_ICE || b == Blocks.BLACKSTONE
                || b == Blocks.BASALT || b == Blocks.SMOOTH_BASALT || b == Blocks.COBBLED_DEEPSLATE || b == Blocks.AMETHYST_BLOCK) {
            return true;
        }
        if (b instanceof SlabBlock || b instanceof StairBlock || b instanceof WallBlock) {
            String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
            return path.startsWith("cobblestone") || path.startsWith("mossy_cobblestone") || path.startsWith("andesite")
                    || path.startsWith("granite") || path.startsWith("diorite") || path.startsWith("stone_") || path.startsWith("blackstone")
                    || path.startsWith("cobbled_deepslate") || path.startsWith("tuff") || path.startsWith("sandstone")
                    || path.startsWith("red_sandstone") || path.startsWith("mud");
        }
        return false;
    }

    /** True when some member of the component touches a block a builder placed (a floating wall piece is not a rock). */
    private static boolean touchesBuilt(LevelAccessor level, LongArrayList comp, BlockPos.MutableBlockPos q) {
        for (int i = 0; i < comp.size(); i++) {
            long k = comp.getLong(i);
            for (Direction d : Direction.values()) {
                long n = BlockPos.offset(k, d);
                BlockState s = level.getBlockState(q.set(BlockPos.getX(n), BlockPos.getY(n), BlockPos.getZ(n)));
                if (s.isAir() || member(s)) continue;
                String path = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
                for (String b : BUILT) if (path.contains(b)) return true;
            }
        }
        return false;
    }
}
