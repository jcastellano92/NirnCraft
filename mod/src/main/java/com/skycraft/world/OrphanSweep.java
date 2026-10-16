package com.skycraft.world;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.function.Predicate;

/**
 * Orphan vegetation sweep. Settlement flattening, road clearing and dungeon carving cut trunks and leave crowns,
 * branch stubs and trunk fragments hanging in the air (persistent leaves never decay). This finds the connected
 * log+leaf components in an area and removes every one that has no ground under any of its logs: crowns without a
 * trunk, trunk stubs in mid-air, leaf scraps that lost their log. Components that touch the edge of the writable
 * box (their other half may be unloaded), that rest on or lean against a built block, or that the caller protects
 * (player blocks) are left alone. Bounded: columns are scanned top-down only as far as the vegetation reaches.
 */
public final class OrphanSweep {
    private static final byte LOG = 1, LEAF = 2;
    private static final int FLAGS = 2 | 16;
    private static final int MAX_COMPONENT = 20000;
    /** Leaf-only clumps this small sitting on the ground are bushes and hedges, not fallen crowns. */
    private static final int BUSH = 40;
    private static final String[] BUILT = {"plank", "brick", "slab", "stair", "wall", "fence", "door", "glass", "wool",
            "concrete", "polished", "smooth", "chiseled", "stripped", "tile", "thatch", "hay_block", "scaffolding",
            "bars", "cut_", "cobble", "lantern", "barrel", "chest", "bookshelf", "terracotta"};

    private OrphanSweep() {}

    /**
     * Sweeps the area {@code [tx1..tx2] x [tz1..tz2]} grown by {@code margin}, reading and writing only inside the
     * writable box {@code [wx1..wx2] x [wz1..wz2]} (must be loaded / inside the generation region).
     *
     * @param keep positions that must never be removed (player-placed blocks); may be null
     * @return the number of blocks removed
     */
    public static int sweep(LevelAccessor level, int tx1, int tz1, int tx2, int tz2, int margin,
                            int wx1, int wz1, int wx2, int wz2, Predicate<BlockPos> keep) {
        int x1 = Math.max(wx1, tx1 - margin), x2 = Math.min(wx2, tx2 + margin);
        int z1 = Math.max(wz1, tz1 - margin), z2 = Math.min(wz2, tz2 + margin);
        if (x1 > x2 || z1 > z2) return 0;
        int minY = level.getMinBuildHeight() + 1, maxY = level.getMaxBuildHeight() - 2;
        Long2ByteOpenHashMap veg = new Long2ByteOpenHashMap();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                int top = Math.min(maxY, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
                int lastVeg = Integer.MIN_VALUE;
                for (int y = top; y >= minY; y--) {
                    BlockState s = level.getBlockState(p.set(x, y, z));
                    if (s.isAir()) {
                        if (lastVeg != Integer.MIN_VALUE && lastVeg - y > 12) break;
                        continue;
                    }
                    if (s.is(BlockTags.LEAVES)) {
                        veg.put(BlockPos.asLong(x, y, z), LEAF);
                        lastVeg = y;
                    } else if (s.is(BlockTags.LOGS)) {
                        veg.put(BlockPos.asLong(x, y, z), LOG);
                        lastVeg = y;
                    } else if (lastVeg == Integer.MIN_VALUE) {
                        if (top - y > 4) break; // nothing but roofs and rock down to here: no crown in this column
                    } else if (lastVeg - y > 12) {
                        break;
                    }
                }
            }
        }
        if (veg.isEmpty()) return 0;
        LongOpenHashSet seen = new LongOpenHashSet(veg.size() * 2);
        LongArrayList doomed = new LongArrayList();
        LongArrayList comp = new LongArrayList();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        for (long start : veg.keySet()) {
            if (!seen.add(start)) continue;
            comp.clear();
            queue.enqueue(start);
            boolean grounded = false, hasLog = false, ground4Leaf = false, abort = false;
            while (!queue.isEmpty()) {
                long k = queue.dequeueLong();
                comp.add(k);
                if (comp.size() > MAX_COMPONENT) abort = true;
                int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
                boolean log = veg.get(k) == LOG;
                if (log) hasLog = true;
                if (x <= x1 || x >= x2 || z <= z1 || z >= z2 || y <= minY || y >= maxY) grounded = true; // may continue out of the box
                if (y > minY) {
                    BlockState below = level.getBlockState(p.set(x, y - 1, z));
                    if (isGround(below)) {
                        if (log) grounded = true;
                        else ground4Leaf = true;
                    }
                }
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    if ((dx | dy | dz) == 0) continue;
                    long n = BlockPos.asLong(x + dx, y + dy, z + dz);
                    if (veg.containsKey(n) && seen.add(n)) queue.enqueue(n);
                }
            }
            if (abort || grounded) continue;
            if (!hasLog && ground4Leaf && comp.size() <= BUSH) continue;
            if (leansOnBuilt(level, comp, veg, p)) continue;
            if (keep != null) {
                boolean prot = false;
                for (int i = 0; i < comp.size() && !prot; i++) prot = keep.test(BlockPos.of(comp.getLong(i)));
                if (prot) continue;
            }
            doomed.addAll(comp);
        }
        BlockState air = Blocks.AIR.defaultBlockState();
        int n = 0;
        for (int i = 0; i < doomed.size(); i++) {
            BlockPos pos = BlockPos.of(doomed.getLong(i));
            if (level.setBlock(pos, air, FLAGS)) n++;
            // Clear any flora, double plants, vines, or snow layers resting on top of the removed log/leaf
            for (int dy = 1; dy <= 2; dy++) {
                BlockPos up = pos.above(dy);
                BlockState u = level.getBlockState(up);
                if (isFloatingCover(u)) {
                    level.setBlock(up, air, FLAGS);
                }
            }
            // vines clinging to a removed block
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos v = pos.relative(d);
                for (int g = 0; g < 16; g++) {
                    if (!(level.getBlockState(v).getBlock() instanceof VineBlock)) break;
                    if (v.getX() < wx1 || v.getX() > wx2 || v.getZ() < wz1 || v.getZ() > wz2) break;
                    level.setBlock(v, air, FLAGS);
                    v = v.below();
                }
            }
        }
        return n;
    }

    private static boolean isFloatingCover(BlockState s) {
        if (s.isAir()) return false;
        net.minecraft.world.level.block.Block b = s.getBlock();
        return b instanceof net.minecraft.world.level.block.BushBlock
                || b instanceof com.skycraft.crafting.arcane.block.IngredientPlantBlock
                || b instanceof net.minecraft.world.level.block.DoublePlantBlock
                || b instanceof net.minecraft.world.level.block.TallGrassBlock
                || b instanceof VineBlock
                || b instanceof net.minecraft.world.level.block.SnowLayerBlock
                || s.is(BlockTags.FLOWERS)
                || s.is(BlockTags.SAPLINGS)
                || s.is(Blocks.MOSS_CARPET)
                || s.is(Blocks.SNOW);
    }

    /** Ground a trunk may stand on: any solid block that is not itself vegetation. */
    private static boolean isGround(BlockState s) {
        return !s.isAir() && s.blocksMotion() && !s.is(BlockTags.LEAVES) && !s.is(BlockTags.LOGS);
    }

    /** True if some member of the component touches a block that a builder placed (planks, bricks, stairs...). */
    private static boolean leansOnBuilt(LevelAccessor level, LongArrayList comp, Long2ByteOpenHashMap veg, BlockPos.MutableBlockPos p) {
        for (int i = 0; i < comp.size(); i++) {
            long k = comp.getLong(i);
            if (veg.get(k) == LOG) {
                String self = BuiltInRegistries.BLOCK.getKey(level.getBlockState(p.set(BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k))).getBlock()).getPath();
                if (self.startsWith("stripped")) return true; // a builder's beam
            }
            for (Direction d : Direction.values()) {
                long n = BlockPos.offset(k, d);
                if (veg.containsKey(n)) continue;
                BlockState s = level.getBlockState(p.set(BlockPos.getX(n), BlockPos.getY(n), BlockPos.getZ(n)));
                if (s.isAir() || !s.blocksMotion()) continue;
                String path = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
                for (String b : BUILT) if (path.contains(b)) return true;
            }
        }
        return false;
    }
}
