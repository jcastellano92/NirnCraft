package com.skycraft.roads;

import com.skycraft.Skycraft;
import com.skycraft.dig.PlacedBlocks;
import com.skycraft.homestead.HomesteadClaim;
import com.skycraft.homestead.HomesteadData;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Paves road chunks incrementally. A chunk is paved only while it and its 8 neighbours are loaded (so no block
 * update can ever reach into an unloaded chunk), never from inside the chunk load event, and with a per-tick block
 * and time budget. Every operation is idempotent, so partially paved chunks are simply paved again later.
 * Roads are only laid on natural ground: never on logs, roofs or other mods' floors, never inside a structure piece
 * or a homestead claim, never over player-placed blocks.
 */
final class Paver {
    /** Blocks placed without neighbour shape updates (no cascades, no drops). */
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    /** Fences must update their neighbours so rails connect. */
    private static final int CONNECT = Block.UPDATE_CLIENTS;
    private static final float ROAD_HALF_WIDTH_SQ = 4.0f;  // 2.0^2: wider 4-5 block road band
    private static final float RAIL_SQ = 6.25f;            // 2.5^2: edge stones and bridge rails
    private static final int MAX_PILLAR = 16;

    private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();
    private static final LongOpenHashSet WAITING = new LongOpenHashSet();
    private static Task current;

    private Paver() {}

    static void enqueue(long chunk) {
        QUEUE.add(chunk);
    }

    static int queued() {
        return QUEUE.size() + WAITING.size() + (current != null ? 1 : 0);
    }

    static void clear() {
        QUEUE.clear();
        WAITING.clear();
        current = null;
    }

    static void tick(ServerLevel level, RoadsData data, long tick) {
        if (tick % 40 == 0 && !WAITING.isEmpty()) {
            for (long c : WAITING) QUEUE.add(c);
            WAITING.clear();
        }
        if (current == null && QUEUE.isEmpty()) return;
        int budget = RoadsConfig.PAVE_BLOCKS_PER_TICK.get();
        long deadline = System.nanoTime() + (long) (RoadsConfig.PAVE_MILLIS_PER_TICK.get() * 1_000_000L);
        int steps = 0;
        while (budget > 0) {
            if ((++steps & 15) == 0 && System.nanoTime() > deadline) break;
            if (current == null) {
                current = nextTask(level, data);
                if (current == null) break;
            }
            if (current.tick != tick) {
                current.tick = tick;
                if (!areaLoaded(level, current.cx, current.cz)) {
                    // unloaded meanwhile: it will be queued again by the next chunk load
                    current = null;
                    continue;
                }
            }
            int used;
            try {
                used = current.step(level, data);
            } catch (RuntimeException e) {
                Skycraft.LOGGER.warn("Skycraft roads: paving chunk {} failed", new ChunkPos(current.chunk), e);
                if (data.paved.add(current.chunk)) data.setDirty();
                current = null;
                continue;
            }
            if (used < 0) {
                if (data.paved.add(current.chunk)) data.setDirty();
                current = null;
                continue;
            }
            budget -= Math.max(1, used);
        }
    }

    private static Task nextTask(ServerLevel level, RoadsData data) {
        while (!QUEUE.isEmpty()) {
            long c = QUEUE.removeFirstLong();
            if (!data.needsPaving(c)) continue;
            int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
            if (level.getChunkSource().getChunkNow(cx, cz) == null) continue;
            if (!areaLoaded(level, cx, cz)) {
                WAITING.add(c);
                continue;
            }
            return Task.build(level, data, c);
        }
        return null;
    }

    /** The chunk and its 8 neighbours are fully loaded (checked without loading anything). */
    private static boolean areaLoaded(ServerLevel level, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) == null) return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ task

    private static final class Task {
        final long chunk;
        final int cx, cz;
        final int[] colX, colZ, colRoad, colIdx;
        final boolean[] colEdge, colCenter;
        final List<RoadFeature> features;
        /** Pieces of every structure reaching into this chunk (other mods' buildings, trees, ruins...). */
        final List<BoundingBox> pieces;
        /** Homestead claims overlapping this chunk. */
        final List<HomesteadClaim> claims;
        int cursor;
        long tick = Long.MIN_VALUE;

        private Task(long chunk, int n, List<RoadFeature> features, List<BoundingBox> pieces, List<HomesteadClaim> claims) {
            this.chunk = chunk;
            this.cx = ChunkPos.getX(chunk);
            this.cz = ChunkPos.getZ(chunk);
            colX = new int[n];
            colZ = new int[n];
            colRoad = new int[n];
            colIdx = new int[n];
            colEdge = new boolean[n];
            colCenter = new boolean[n];
            this.features = features;
            this.pieces = pieces;
            this.claims = claims;
        }

        /** True if a structure piece covers (x, z) around ground level y (1 block margin), or a homestead claims it. */
        boolean reserved(ServerLevel level, int x, int y, int z) {
            for (BoundingBox b : pieces) {
                if (x >= b.minX() - 1 && x <= b.maxX() + 1 && z >= b.minZ() - 1 && z <= b.maxZ() + 1
                        && b.maxY() >= y - 1 && b.minY() <= y + 4) return true;
            }
            if (!claims.isEmpty()) {
                BlockPos pos = new BlockPos(x, y, z);
                for (HomesteadClaim c : claims) if (c.isHorizontallyInside(level, pos)) return true;
            }
            return false;
        }

        /** Structure pieces reaching into the chunk, from start chunks that are already in memory (never loads any). */
        private static List<BoundingBox> pieces(ServerLevel level, int cx, int cz) {
            LevelChunk lc = level.getChunkSource().getChunkNow(cx, cz);
            if (lc == null || !lc.hasAnyStructureReferences()) return List.of();
            int bx = cx << 4, bz = cz << 4;
            List<BoundingBox> out = new ArrayList<>();
            for (Map.Entry<Structure, LongSet> e : lc.getAllReferences().entrySet()) {
                for (long startChunk : e.getValue()) {
                    ChunkAccess sc = level.getChunkSource().getChunk(ChunkPos.getX(startChunk), ChunkPos.getZ(startChunk),
                            ChunkStatus.STRUCTURE_STARTS, false);
                    if (sc == null) continue;
                    StructureStart start = sc.getStartForStructure(e.getKey());
                    if (start == null || !start.isValid()) continue;
                    for (StructurePiece piece : start.getPieces()) {
                        BoundingBox b = piece.getBoundingBox();
                        if (b.maxX() < bx - 1 || b.minX() > bx + 16 || b.maxZ() < bz - 1 || b.minZ() > bz + 16) continue;
                        out.add(b);
                    }
                }
            }
            return out;
        }

        private static List<HomesteadClaim> claims(ServerLevel level, int cx, int cz) {
            List<HomesteadClaim> out = new ArrayList<>(0);
            int mx = (cx << 4) + 8, mz = (cz << 4) + 8;
            for (HomesteadClaim c : HomesteadData.get(level.getServer()).allClaims()) {
                int reach = c.getRadius() + 12;
                if (Math.abs(c.getCenter().getX() - mx) <= reach && Math.abs(c.getCenter().getZ() - mz) <= reach) out.add(c);
            }
            return out;
        }

        static Task build(ServerLevel level, RoadsData data, long chunk) {
            int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
            int bx = cx << 4, bz = cz << 4;
            float[] best = new float[256];
            int[] road = new int[256];
            int[] idx = new int[256];
            java.util.Arrays.fill(best, Float.MAX_VALUE);
            LongArrayList entries = data.chunkIndex.get(chunk);
            if (entries != null) {
                List<Settlement> zones = data.zonesNearChunk(cx, cz);
                int radius = data.zoneRadius();
                for (int k = 0; k < entries.size(); k++) {
                    long e = entries.getLong(k);
                    Road r = data.road(RoadsData.entryRoad(e));
                    int i = RoadsData.entryIndex(e);
                    if (r == null || i < 0 || i >= r.size()) continue;
                    int px = r.xs[i], pz = r.zs[i];
                    boolean skip = false;
                    for (Settlement s : zones) {
                        if (s.inZone(px, pz, radius)) {
                            skip = true;
                            break;
                        }
                    }
                    if (skip) continue;
                    for (int dx = -RoadsData.FOOTPRINT; dx <= RoadsData.FOOTPRINT; dx++) {
                        int x = px + dx;
                        if ((x >> 4) != cx) continue;
                        for (int dz = -RoadsData.FOOTPRINT; dz <= RoadsData.FOOTPRINT; dz++) {
                            int z = pz + dz;
                            if ((z >> 4) != cz) continue;
                            float d2 = dx * dx + dz * dz;
                            if (d2 > RAIL_SQ) continue;
                            int li = ((z - bz) << 4) | (x - bx);
                            if (d2 < best[li]) {
                                best[li] = d2;
                                road[li] = r.id;
                                idx[li] = i;
                            }
                        }
                    }
                }
            }
            int n = 0;
            for (float b : best) if (b <= RAIL_SQ) n++;
            List<RoadFeature> feats = data.featuresByChunk.get(chunk);
            Task t = new Task(chunk, n, feats == null ? List.of() : new ArrayList<>(feats), pieces(level, cx, cz), claims(level, cx, cz));
            int j = 0;
            for (int li = 0; li < 256; li++) {
                if (best[li] > RAIL_SQ) continue;
                t.colX[j] = bx + (li & 15);
                t.colZ[j] = bz + (li >> 4);
                t.colRoad[j] = road[li];
                t.colIdx[j] = idx[li];
                t.colEdge[j] = best[li] > ROAD_HALF_WIDTH_SQ;
                t.colCenter[j] = best[li] < 0.5f;
                j++;
            }
            return t;
        }

        /** Processes one column or feature; returns the number of block changes, or -1 when the chunk is done. */
        int step(ServerLevel level, RoadsData data) {
            int columns = colX.length;
            if (cursor < columns) {
                int i = cursor++;
                Road r = data.road(colRoad[i]);
                if (r == null) return 0;
                return paveColumn(level, this, r, colIdx[i], colX[i], colZ[i], colEdge[i], colCenter[i]);
            }
            int f = cursor - columns;
            if (f < features.size()) {
                cursor++;
                RoadFeature feature = features.get(f);
                return feature.type == RoadFeature.SIGNPOST ? placeSignpost(level, this, data, feature) : placeLantern(level, this, feature);
            }
            return -1;
        }
    }

    // ------------------------------------------------------------------ road surface

    private static int paveColumn(ServerLevel level, Task task, Road road, int idx, int x, int z, boolean edge, boolean center) {
        BlockPos top = naturalTop(level, x, z);
        if (top == null) return 0;
        BlockState ts = level.getBlockState(top);
        if (task.reserved(level, x, top.getY(), z)) return 0; // another mod's building, a tree structure, a homestead
        FluidState fluid = ts.getFluidState();
        if (!fluid.isEmpty()) {
            if (!fluid.is(FluidTags.WATER) || road.longWater(idx)) return 0;
            return bridge(level, top, ts, edge, center && idx % 4 == 0);
        }
        // only ever on natural ground (or our own road): never on roofs, logs or other mods' floors
        if (!isNaturalGround(ts) && !isRoadMaterial(ts)) return 0;
        BlockPos above = top.above();
        BlockPos above2 = above.above();
        BlockPos above3 = above2.above();
        BlockState a1 = level.getBlockState(above);
        BlockState a2 = level.getBlockState(above2);
        BlockState a3 = level.getBlockState(above3);
        if (!clearable(a1)) return 0;
        if (PlacedBlocks.isPlayerPlaced(level, top) || (!a1.isAir() && PlacedBlocks.isPlayerPlaced(level, above))) return 0;

        if (edge) {
            if (isRoadMaterial(ts)) return 0;
            BlockState edgeBlock = edgeFor(level, top, ts, x, z);
            if (edgeBlock == null) return 0;
            if (!a1.isAir() && clearable(a1) && !PlacedBlocks.isPlayerPlaced(level, above)) level.setBlock(above, Blocks.AIR.defaultBlockState(), QUIET);
            if (!a2.isAir() && clearable(a2) && !PlacedBlocks.isPlayerPlaced(level, above2)) level.setBlock(above2, Blocks.AIR.defaultBlockState(), QUIET);
            if (!a3.isAir() && clearable(a3) && !PlacedBlocks.isPlayerPlaced(level, above3)) level.setBlock(above3, Blocks.AIR.defaultBlockState(), QUIET);
            level.setBlock(top, edgeBlock, QUIET);
            return 2;
        }

        if (isRoadMaterial(ts)) {
            if (a1.is(Blocks.SNOW) || (clearable(a1) && !PlacedBlocks.isPlayerPlaced(level, above))) {
                level.setBlock(above, Blocks.AIR.defaultBlockState(), QUIET);
                if (!a2.isAir() && clearable(a2) && !PlacedBlocks.isPlayerPlaced(level, above2)) level.setBlock(above2, Blocks.AIR.defaultBlockState(), QUIET);
                if (!a3.isAir() && clearable(a3) && !PlacedBlocks.isPlayerPlaced(level, above3)) level.setBlock(above3, Blocks.AIR.defaultBlockState(), QUIET);
                return 1;
            }
            return 0;
        }
        BlockState surface = surfaceFor(level, top, ts, x, z);
        if (surface == null) return 0;
        int changes = 0;
        if (!a3.isAir() && clearable(a3) && !PlacedBlocks.isPlayerPlaced(level, above3)) {
            level.setBlock(above3, Blocks.AIR.defaultBlockState(), QUIET);
            changes++;
        }
        if (!a2.isAir() && clearable(a2) && !PlacedBlocks.isPlayerPlaced(level, above2)) {
            level.setBlock(above2, Blocks.AIR.defaultBlockState(), QUIET);
            changes++;
        }
        if (!a1.isAir() && clearable(a1) && !PlacedBlocks.isPlayerPlaced(level, above)) {
            level.setBlock(above, Blocks.AIR.defaultBlockState(), QUIET);
            changes++;
        }
        level.setBlock(top, surface, QUIET);
        return changes + 1;
    }

    private static int bridge(ServerLevel level, BlockPos waterTop, BlockState waterState, boolean edge, boolean pillar) {
        // the deck replaces the water surface, so it is flush with the banks
        if (!waterState.is(Blocks.WATER) && !(waterState.getBlock() instanceof BushBlock) && !waterState.canBeReplaced()) {
            return 0; // waterlogged stairs, kelp-covered player builds...
        }
        if (PlacedBlocks.isPlayerPlaced(level, waterTop)) return 0;
        BlockPos above = waterTop.above();
        BlockState a1 = level.getBlockState(above);
        if (!clearable(a1)) return 0;
        int changes = 0;
        level.setBlock(waterTop, Blocks.SPRUCE_PLANKS.defaultBlockState(), QUIET);
        changes++;
        if (edge) {
            BlockState fence = Block.updateFromNeighbourShapes(Blocks.SPRUCE_FENCE.defaultBlockState(), level, above);
            level.setBlock(above, fence, CONNECT);
            changes++;
        } else if (!a1.isAir()) {
            level.setBlock(above, Blocks.AIR.defaultBlockState(), QUIET);
            changes++;
        }
        if (pillar) changes += pillar(level, waterTop.below());
        return changes;
    }

    /** A log pillar from below the deck down to the river bed, if the bed is close enough. */
    private static int pillar(ServerLevel level, BlockPos from) {
        BlockPos.MutableBlockPos p = from.mutable();
        int depth = 0;
        while (depth < MAX_PILLAR && p.getY() > level.getMinBuildHeight()) {
            BlockState s = level.getBlockState(p);
            if (!s.is(Blocks.WATER) && !(s.getBlock() instanceof BushBlock && !s.getFluidState().isEmpty())) break;
            p.move(Direction.DOWN);
            depth++;
        }
        if (depth == 0 || depth >= MAX_PILLAR) return 0;
        if (!level.getBlockState(p).isFaceSturdy(level, p, Direction.UP)) return 0;
        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState();
        for (int i = 0; i < depth; i++) level.setBlock(from.below(i), log, QUIET);
        return depth;
    }

    /**
     * The top solid block of a column after walking down through leaves, plants and snow layers, or null if that walk
     * ends on a log (a tree trunk or branch: never paved over or cut) or goes on for too long. Water is returned as is.
     */
    @Nullable
    private static BlockPos naturalTop(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y, z);
        for (int guard = 0; guard < 24; guard++) {
            if (p.getY() <= level.getMinBuildHeight()) return null;
            BlockState s = level.getBlockState(p);
            if (s.is(BlockTags.LOGS)) return null;
            if (!s.getFluidState().isEmpty()) return p.immutable();
            boolean passable = s.isAir() || s.is(Blocks.SNOW) || s.is(BlockTags.LEAVES) || s.getBlock() instanceof BushBlock
                    || s.canBeReplaced();
            if (!passable) return p.immutable();
            p.move(Direction.DOWN);
        }
        return null;
    }

    /** Ground the world generator made: soil, sand, stone, gravel, snow, clay... */
    static boolean isNaturalGround(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.TERRACOTTA)
                || s.is(Blocks.GRAVEL) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.CALCITE)
                || s.is(Blocks.CLAY) || s.is(Blocks.MUD) || s.is(Blocks.PACKED_ICE) || s.is(Blocks.SANDSTONE)
                || s.is(Blocks.RED_SANDSTONE);
    }

    static boolean isRoadMaterial(BlockState s) {
        return s.is(Blocks.DIRT_PATH) || s.is(Blocks.GRAVEL) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.COBBLESTONE)
                || s.is(Blocks.MOSSY_COBBLESTONE) || s.is(Blocks.SMOOTH_SANDSTONE) || s.is(Blocks.SMOOTH_RED_SANDSTONE)
                || s.is(Blocks.PACKED_MUD) || s.is(Blocks.SPRUCE_PLANKS) || s.is(Blocks.ANDESITE) || s.is(Blocks.STONE_BRICKS);
    }

    /** The road block for a natural surface, or null to leave the column alone. Cobble & path mix. */
    @Nullable
    private static BlockState surfaceFor(ServerLevel level, BlockPos pos, BlockState s, int x, int z) {
        int roll = roll(x, z);
        if (s.is(BlockTags.DIRT)) {
            if (roll < 20) return Blocks.COBBLESTONE.defaultBlockState();
            if (roll < 35) return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            if (roll < 45 && supported(level, pos)) return Blocks.GRAVEL.defaultBlockState();
            return Blocks.DIRT_PATH.defaultBlockState();
        }
        if (s.is(BlockTags.SAND)) {
            if (s.is(Blocks.RED_SAND)) return Blocks.SMOOTH_RED_SANDSTONE.defaultBlockState();
            return roll < 20 ? Blocks.COBBLESTONE.defaultBlockState() : (roll < 35 ? Blocks.PACKED_MUD.defaultBlockState() : Blocks.SMOOTH_SANDSTONE.defaultBlockState());
        }
        if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.SNOW_BLOCK)
                || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.CALCITE) || s.is(Blocks.CLAY)) {
            if (roll < 25 && supported(level, pos)) return Blocks.GRAVEL.defaultBlockState();
            if (roll < 60) return Blocks.COBBLESTONE.defaultBlockState();
            if (roll < 80) return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            return Blocks.STONE_BRICKS.defaultBlockState();
        }
        if (isNaturalGround(s)) {
            if (roll < 30 && supported(level, pos)) return Blocks.GRAVEL.defaultBlockState();
            return roll < 70 ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        }
        return null; // not ours to pave
    }

    /** Edge stones lining the border of the road (natural ground only). */
    @Nullable
    private static BlockState edgeFor(ServerLevel level, BlockPos pos, BlockState s, int x, int z) {
        if (!isNaturalGround(s)) return null;
        int roll = roll(x, z);
        if (roll < 40) return Blocks.COBBLESTONE.defaultBlockState();
        if (roll < 65) return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        if (roll < 85) return Blocks.ANDESITE.defaultBlockState();
        return Blocks.STONE_BRICKS.defaultBlockState();
    }

    /** Gravel would fall into air/fluid below. */
    private static boolean supported(ServerLevel level, BlockPos pos) {
        return !FallingBlock.isFree(level.getBlockState(pos.below()));
    }

    /** Plants, snow layers, leaves and other replaceable clutter that may be removed above a road (never logs). */
    static boolean clearable(BlockState s) {
        if (s.isAir()) return true;
        if (!s.getFluidState().isEmpty()) return false;
        if (s.is(Blocks.SNOW)) return true;
        if (s.getBlock() instanceof BushBlock) return true;
        if (s.is(BlockTags.LEAVES) || s.is(BlockTags.FLOWERS)) return true;
        return s.canBeReplaced();
    }

    private static int roll(int x, int z) {
        int h = x * 73428767 ^ z * 912931;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 0x7fffffff) % 100;
    }

    // ------------------------------------------------------------------ features

    private static boolean isPostMaterial(BlockState s) {
        return s.is(Blocks.SPRUCE_FENCE) || s.is(Blocks.STRIPPED_SPRUCE_LOG) || s.is(Blocks.LANTERN)
                || s.getBlock() instanceof WallSignBlock;
    }

    /** The natural ground block under a (possibly already built) post, or null if unsuitable. */
    private static BlockPos ground(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y, z);
        int guard = 0;
        while (p.getY() > level.getMinBuildHeight() && isPostMaterial(level.getBlockState(p)) && guard++ < 8) {
            p.move(Direction.DOWN);
        }
        BlockState s = level.getBlockState(p);
        if (s.is(Blocks.SNOW)) {
            p.move(Direction.DOWN);
            s = level.getBlockState(p);
        }
        if (!s.getFluidState().isEmpty() || !s.isFaceSturdy(level, p, Direction.UP) || s.is(BlockTags.LEAVES)
                || s.is(BlockTags.LOGS)) return null;
        if (!isNaturalGround(s) && !isRoadMaterial(s)) return null; // roofs, floors and walls of other builds
        return p.immutable();
    }

    private static boolean freeFor(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return (clearable(s) || isPostMaterial(s)) && !PlacedBlocks.isPlayerPlaced(level, pos);
    }

    private static int placeLantern(ServerLevel level, Task task, RoadFeature f) {
        BlockPos g = ground(level, f.x, f.z);
        if (g == null || PlacedBlocks.isPlayerPlaced(level, g) || task.reserved(level, f.x, g.getY(), f.z)) return 0;
        BlockState gs = level.getBlockState(g);
        if (gs.is(Blocks.DIRT_PATH)) return 0; // don't block another road
        BlockPos p1 = g.above(), p2 = p1.above(), p3 = p2.above();
        if (level.getBlockState(p3).is(Blocks.LANTERN)) return 0; // already built
        if (!freeFor(level, p1) || !freeFor(level, p2) || !freeFor(level, p3)) return 0;
        BlockState fence = Blocks.SPRUCE_FENCE.defaultBlockState();
        level.setBlock(p1, fence, QUIET);
        level.setBlock(p2, fence, QUIET);
        level.setBlock(p3, Blocks.LANTERN.defaultBlockState(), QUIET);
        return 3;
    }

    /**
     * A signpost: a stripped spruce post with one level of wall signs per destination (both sides of the post, so it
     * can be read from either direction), capped with a fence. Rebuilding just rewrites the signs.
     */
    private static int placeSignpost(ServerLevel level, Task task, RoadsData data, RoadFeature f) {
        if (f.dests.isEmpty()) return 0;
        BlockPos g = ground(level, f.x, f.z);
        if (g == null || PlacedBlocks.isPlayerPlaced(level, g) || task.reserved(level, f.x, g.getY(), f.z)) return 0;
        int k = Math.min(f.dests.size(), RoadFeature.MAX_DESTINATIONS);
        BlockPos base = g.above();
        for (int i = 0; i <= k + 1; i++) {
            if (!freeFor(level, base.above(i))) return 0;
        }
        int changes = 0;
        BlockState post = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        for (int i = 0; i <= k; i++) {
            if (level.setBlock(base.above(i), post, QUIET)) changes++;
        }
        if (level.setBlock(base.above(k + 1), Blocks.SPRUCE_FENCE.defaultBlockState(), QUIET)) changes++;
        for (int i = 0; i < k; i++) {
            RoadFeature.Dest d = f.dests.get(i);
            Settlement s = data.settlement(d.settlement());
            if (s == null) continue;
            BlockPos level0 = base.above(i + 1);
            // signs on the two faces perpendicular to the main direction, so the arrow points along the road
            boolean eastWest = Math.abs(d.dirX()) >= Math.abs(d.dirZ());
            Direction[] faces = eastWest ? new Direction[]{Direction.NORTH, Direction.SOUTH}
                    : new Direction[]{Direction.EAST, Direction.WEST};
            for (Direction face : faces) changes += placeSign(level, level0.relative(face), face, s.name, d);
        }
        return changes;
    }

    private static int placeSign(ServerLevel level, BlockPos pos, Direction face, String name, RoadFeature.Dest d) {
        BlockState existing = level.getBlockState(pos);
        if (!(existing.getBlock() instanceof WallSignBlock) && !clearable(existing)) return 0;
        if (PlacedBlocks.isPlayerPlaced(level, pos)) return 0;
        int changes = 0;
        BlockState sign = Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, face);
        if (level.setBlock(pos, sign, QUIET)) changes++;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SignBlockEntity sbe)) return changes;
        // the reader stands in front of the sign looking at it (towards -face); their right hand is:
        Direction readerRight = face.getOpposite().getClockWise();
        boolean right = d.dirX() * readerRight.getStepX() + d.dirZ() * readerRight.getStepZ() >= 0;
        String distance = formatDistance(d.distance());
        SignText text = new SignText()
                .setMessage(0, Component.empty())
                .setMessage(1, Component.literal(name))
                .setMessage(2, Component.literal(right ? distance + " →" : "← " + distance))
                .setMessage(3, Component.empty());
        sbe.setText(text, true);
        sbe.setWaxed(true);
        sbe.setChanged();
        level.sendBlockUpdated(pos, sign, sign, Block.UPDATE_CLIENTS);
        return changes + 1;
    }

    static String formatDistance(int blocks) {
        if (blocks >= 1000) return String.format(java.util.Locale.ROOT, "%.1f km", blocks / 1000.0);
        return (Math.max(10, Math.round(blocks / 10.0f) * 10)) + " m";
    }
}
