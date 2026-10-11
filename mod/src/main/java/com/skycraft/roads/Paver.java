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
    /** Squared half widths of the paved band per road class (highway, road, lane, trail), and of bridge decks. */
    private static final float[] HALF_SQ = {4.0f, 2.5f, 1.3f, 0.6f};
    /** Squared outer half widths: highways get edge stones out to here, bridges their rails. */
    private static final float[] RAIL_SQ = {6.25f, 2.5f, 1.3f, 0.6f};
    private static final float BRIDGE_HALF_SQ = 2.25f, BRIDGE_RAIL_SQ = 4.0f;
    private static final int MAX_PILLAR = 24;

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
        /** The building culture of the chunk's land (road style and bridge kind). */
        final com.skycraft.tamriel.settlement.Culture culture;
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
            culture = RoadStyle.cultureAt((cx << 4) + 8, (cz << 4) + 8);
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
            // per column: the road whose band covers it most centrally (distance relative to that road's half width)
            float[] best = new float[256];
            boolean[] edgeOf = new boolean[256];
            float[] d2Of = new float[256];
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
                    int kind = Math.max(0, Math.min(3, r.kind));
                    boolean water = r.water(i) || (i > 0 && r.water(i - 1)) || (i + 1 < r.size() && r.water(i + 1));
                    float half = water ? Math.max(HALF_SQ[kind], BRIDGE_HALF_SQ) : HALF_SQ[kind];
                    float rail = water ? Math.max(RAIL_SQ[kind], BRIDGE_RAIL_SQ) : RAIL_SQ[kind];
                    for (int dx = -RoadsData.FOOTPRINT; dx <= RoadsData.FOOTPRINT; dx++) {
                        int x = px + dx;
                        if ((x >> 4) != cx) continue;
                        for (int dz = -RoadsData.FOOTPRINT; dz <= RoadsData.FOOTPRINT; dz++) {
                            int z = pz + dz;
                            if ((z >> 4) != cz) continue;
                            float d2 = dx * dx + dz * dz;
                            if (d2 > rail) continue;
                            float q = d2 / half;
                            int li = ((z - bz) << 4) | (x - bx);
                            if (q < best[li]) {
                                best[li] = q;
                                d2Of[li] = d2;
                                edgeOf[li] = d2 > half;
                                road[li] = r.id;
                                idx[li] = i;
                            }
                        }
                    }
                }
            }
            int n = 0;
            for (float b : best) if (b != Float.MAX_VALUE) n++;
            List<RoadFeature> feats = data.featuresByChunk.get(chunk);
            Task t = new Task(chunk, n, feats == null ? List.of() : new ArrayList<>(feats), pieces(level, cx, cz), claims(level, cx, cz));
            int j = 0;
            for (int li = 0; li < 256; li++) {
                if (best[li] == Float.MAX_VALUE) continue;
                t.colX[j] = bx + (li & 15);
                t.colZ[j] = bz + (li >> 4);
                t.colRoad[j] = road[li];
                t.colIdx[j] = idx[li];
                t.colEdge[j] = edgeOf[li];
                t.colCenter[j] = d2Of[li] < 0.5f;
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
                return switch (feature.type) {
                    case RoadFeature.SIGNPOST -> placeSignpost(level, this, data, feature, false);
                    case RoadFeature.MILESTONE -> placeSignpost(level, this, data, feature, true);
                    case RoadFeature.SHRINE -> placeShrine(level, this, feature);
                    default -> placeLantern(level, this, feature);
                };
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
            return bridge(level, task, road, idx, top, ts, edge, center);
        }
        // a bridge deck we built (one above the water surface): leave it be
        if (isBridgeDeck(level, task, top, ts)) return 0;
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

        int roll = roll(x, z);
        BlockState want = edge ? RoadStyle.edge(task.culture, roll) : RoadStyle.surface(task.culture, road.kind, roll);
        if (want.getBlock() instanceof FallingBlock && !supported(level, top)) want = Blocks.COARSE_DIRT.defaultBlockState();
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
        if (ts.getBlock() != want.getBlock()) {
            level.setBlock(top, want, QUIET);
            changes++;
        }
        return changes;
    }

    /** True if this column's top is one of our raised bridge decks (deck material over water). */
    private static boolean isBridgeDeck(ServerLevel level, Task task, BlockPos top, BlockState ts) {
        if (!ts.is(RoadStyle.deck(task.culture).getBlock()) && !ts.is(RoadStyle.deckSlab(task.culture).getBlock())) return false;
        return !level.getBlockState(top.below()).getFluidState().isEmpty();
    }

    /**
     * One column of a bridge. The deck stands one block above the water surface, so boats and swimmers pass under it,
     * and its first and last columns are slabs: half steps up from the banks, walkable without jumping. Stone cultures
     * build stone decks with wall parapets and masonry piers; the others plank decks with fence rails and log piles.
     * Piers stand every 8 blocks down to the river bed; lanterns light every second pier.
     */
    private static int bridge(ServerLevel level, Task task, Road road, int idx, BlockPos waterTop, BlockState waterState,
                              boolean edge, boolean center) {
        if (!waterState.is(Blocks.WATER) && !(waterState.getBlock() instanceof BushBlock) && !waterState.canBeReplaced()) {
            return 0; // waterlogged stairs, kelp-covered player builds...
        }
        if (PlacedBlocks.isPlayerPlaced(level, waterTop)) return 0;
        BlockPos deckPos = waterTop.above();
        BlockState d0 = level.getBlockState(deckPos);
        if (!clearable(d0) && !d0.is(RoadStyle.deck(task.culture).getBlock()) && !d0.is(RoadStyle.deckSlab(task.culture).getBlock())) {
            return 0;
        }
        com.skycraft.tamriel.settlement.Culture c = task.culture;
        boolean stone = RoadStyle.stoneBridge(c);
        // a slab where the road reaches the shore within one point, so the step up from the bank is a half block
        boolean end = !waterAt(level, road, idx - 1) || !waterAt(level, road, idx + 1);
        BlockState deck = end ? RoadStyle.deckSlab(c) : RoadStyle.deck(c);
        int changes = 0;
        if (level.setBlock(deckPos, deck, QUIET)) changes++;
        BlockPos above = deckPos.above();
        BlockState a1 = level.getBlockState(above);
        if (edge && !end) {
            BlockState rail = Block.updateFromNeighbourShapes(RoadStyle.rail(c), level, above);
            if (level.setBlock(above, rail, CONNECT)) changes++;
            if (idx % 8 == 0 && level.getBlockState(above.above()).isAir()) {
                level.setBlock(above.above(), Blocks.LANTERN.defaultBlockState(), QUIET);
                changes++;
            }
        } else if (!a1.isAir() && clearable(a1)) {
            level.setBlock(above, Blocks.AIR.defaultBlockState(), QUIET);
            changes++;
        }
        if (idx % 4 == 0 && (center || (stone && edge))) changes += pillar(level, waterTop, stone ? RoadStyle.pillar(c) : log(c));
        return changes;
    }

    private static BlockState log(com.skycraft.tamriel.settlement.Culture c) {
        return RoadStyle.pillar(c).is(BlockTags.LOGS) ? RoadStyle.pillar(c) : Blocks.SPRUCE_LOG.defaultBlockState();
    }

    /** True if the road point is over water in the world (outside the loaded area counts as water: no slab). */
    private static boolean waterAt(ServerLevel level, Road road, int i) {
        if (i < 0 || i >= road.size()) return false;
        int x = road.xs[i], z = road.zs[i];
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return true;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockState s = level.getBlockState(new BlockPos(x, y, z));
        if (!s.getFluidState().isEmpty()) return true;
        // our own deck: the water is one below
        return !level.getBlockState(new BlockPos(x, y - 1, z)).getFluidState().isEmpty();
    }

    /** A pier from the water surface down to the river bed, if the bed is close enough. */
    private static int pillar(ServerLevel level, BlockPos waterTop, BlockState material) {
        BlockPos.MutableBlockPos p = waterTop.mutable();
        int depth = 0;
        while (depth < MAX_PILLAR && p.getY() > level.getMinBuildHeight()) {
            BlockState s = level.getBlockState(p);
            if (!s.is(Blocks.WATER) && !(s.getBlock() instanceof BushBlock && !s.getFluidState().isEmpty())
                    && !s.is(Blocks.KELP) && !s.is(Blocks.KELP_PLANT) && !s.is(Blocks.SEAGRASS) && !s.is(Blocks.TALL_SEAGRASS)) break;
            p.move(Direction.DOWN);
            depth++;
        }
        if (depth == 0 || depth >= MAX_PILLAR) return 0;
        if (!level.getBlockState(p).isFaceSturdy(level, p, Direction.UP)) return 0;
        BlockState pier = material.hasProperty(RotatedPillarBlock.AXIS) ? material.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y) : material;
        for (int i = 0; i < depth; i++) level.setBlock(waterTop.below(i), pier, QUIET);
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

    /** Ground the world generator made: soil, sand, stone, gravel, snow, clay, ash, volcanic rock... */
    static boolean isNaturalGround(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.TERRACOTTA)
                || s.is(Blocks.GRAVEL) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.CALCITE)
                || s.is(Blocks.CLAY) || s.is(Blocks.MUD) || s.is(Blocks.PACKED_ICE) || s.is(Blocks.SANDSTONE)
                || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.BASALT)
                || isTamrielGround(s);
    }

    private static boolean isTamrielGround(BlockState s) {
        var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(s.getBlock());
        if (id == null || !com.skycraft.Skycraft.MODID.equals(id.getNamespace())) return false;
        return switch (id.getPath()) {
            case "ash_block", "volcanic_rock", "cracked_volcanic_rock", "dune_sand", "coral_stone", "frosted_stone" -> true;
            default -> false;
        };
    }

    static boolean isRoadMaterial(BlockState s) {
        return s.is(Blocks.DIRT_PATH) || s.is(Blocks.SPRUCE_PLANKS) || RoadStyle.isMaterial(s);
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

    static int roll(int x, int z) {
        int h = x * 73428767 ^ z * 912931;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 0x7fffffff) % 100;
    }

    // ------------------------------------------------------------------ features

    private static boolean isPostMaterial(BlockState s) {
        return s.is(Blocks.SPRUCE_FENCE) || s.is(Blocks.STRIPPED_SPRUCE_LOG) || s.is(Blocks.LANTERN)
                || s.is(Blocks.MOSSY_STONE_BRICKS) || s.is(Blocks.CHISELED_STONE_BRICKS) || s.getBlock() instanceof WallSignBlock;
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
     * can be read from either direction), capped with a fence. A milestone is the same in stone: a mossy stone-brick
     * post with a wall cap. Rebuilding just rewrites the signs.
     */
    private static int placeSignpost(ServerLevel level, Task task, RoadsData data, RoadFeature f, boolean milestone) {
        if (f.dests.isEmpty()) return 0;
        BlockPos g = ground(level, f.x, f.z);
        if (g == null || PlacedBlocks.isPlayerPlaced(level, g) || task.reserved(level, f.x, g.getY(), f.z)) return 0;
        int k = Math.min(f.dests.size(), RoadFeature.MAX_DESTINATIONS);
        BlockPos base = g.above();
        for (int i = 0; i <= k + 1; i++) {
            if (!freeFor(level, base.above(i))) return 0;
        }
        int changes = 0;
        BlockState post = milestone ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState()
                : Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        BlockState cap = milestone ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState() : Blocks.SPRUCE_FENCE.defaultBlockState();
        for (int i = 0; i <= k; i++) {
            if (level.setBlock(base.above(i), post, QUIET)) changes++;
        }
        if (level.setBlock(base.above(k + 1), cap, QUIET)) changes++;
        for (int i = 0; i < k; i++) {
            RoadFeature.Dest d = f.dests.get(i);
            Settlement s = data.settlement(d.settlement());
            if (s == null) continue;
            BlockPos level0 = base.above(i + 1);
            // signs on the two faces perpendicular to the main direction, so the arrow points along the road
            boolean eastWest = Math.abs(d.dirX()) >= Math.abs(d.dirZ());
            Direction[] faces = eastWest ? new Direction[]{Direction.NORTH, Direction.SOUTH}
                    : new Direction[]{Direction.EAST, Direction.WEST};
            for (Direction face : faces) changes += placeSign(level, level0.relative(face), face, s.displayName(), d, milestone);
        }
        return changes;
    }

    private static int placeSign(ServerLevel level, BlockPos pos, Direction face, Component name, RoadFeature.Dest d,
                                 boolean stone) {
        BlockState existing = level.getBlockState(pos);
        if (!(existing.getBlock() instanceof WallSignBlock) && !clearable(existing)) return 0;
        if (PlacedBlocks.isPlayerPlaced(level, pos)) return 0;
        int changes = 0;
        BlockState sign = (stone ? Blocks.DARK_OAK_WALL_SIGN : Blocks.SPRUCE_WALL_SIGN).defaultBlockState().setValue(WallSignBlock.FACING, face);
        if (level.setBlock(pos, sign, QUIET)) changes++;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SignBlockEntity sbe)) return changes;
        // the reader stands in front of the sign looking at it (towards -face); their right hand is:
        Direction readerRight = face.getOpposite().getClockWise();
        boolean right = d.dirX() * readerRight.getStepX() + d.dirZ() * readerRight.getStepZ() >= 0;
        String distance = formatDistance(d.distance());
        // the historic road's name (the Gold Road...) heads the sign, in each reader's language
        Component road = d.roadName().isEmpty() ? Component.empty()
                : Component.translatable("road.skycraft." + d.roadName()).withStyle(net.minecraft.ChatFormatting.ITALIC);
        SignText text = new SignText()
                .setMessage(0, road)
                .setMessage(1, name)
                .setMessage(2, Component.literal(right ? distance + " →" : "← " + distance))
                .setMessage(3, Component.empty());
        if (stone) text = text.setColor(net.minecraft.world.item.DyeColor.WHITE);
        sbe.setText(text, true);
        sbe.setText(text, false);
        sbe.setWaxed(true);
        sbe.setChanged();
        level.sendBlockUpdated(pos, sign, sign, Block.UPDATE_CLIENTS);
        return changes + 1;
    }

    /**
     * A wayside shrine: the Divine's shrine on a dressed-stone plinth facing the road, between two lantern posts.
     */
    private static int placeShrine(ServerLevel level, Task task, RoadFeature f) {
        BlockPos g = ground(level, f.x, f.z);
        if (g == null || PlacedBlocks.isPlayerPlaced(level, g) || task.reserved(level, f.x, g.getY(), f.z)) return 0;
        Block shrine = Wayshrines.block(f.variant);
        BlockPos at = g.above();
        if (level.getBlockState(at).is(shrine)) return 0; // already built
        RoadFeature.Dest d = f.dests.isEmpty() ? null : f.dests.get(0);
        Direction facing = d == null ? Direction.SOUTH : Direction.getNearest(d.dirX(), 0, d.dirZ());
        Direction side = facing.getClockWise();
        BlockPos[] spots = {at, at.relative(side, 2), at.relative(side, -2)};
        for (BlockPos p : spots) {
            if (!freeFor(level, p) || !freeFor(level, p.above()) || !freeFor(level, p.above(2))) return 0;
            BlockState below = level.getBlockState(p.below());
            if (!isNaturalGround(below) && !isRoadMaterial(below)) return 0;
        }
        int changes = 0;
        for (int s = -2; s <= 2; s++) {
            BlockPos p = g.relative(side, s);
            BlockState below = level.getBlockState(p);
            if ((isNaturalGround(below) || isRoadMaterial(below)) && !PlacedBlocks.isPlayerPlaced(level, p)
                    && level.getBlockState(p.above()).canBeReplaced()) {
                level.setBlock(p, (s == 0 ? Blocks.CHISELED_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState(), QUIET);
                changes++;
            }
        }
        BlockState state = shrine.defaultBlockState();
        if (state.hasProperty(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)) {
            state = state.setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, facing);
        }
        if (level.setBlock(at, state, Block.UPDATE_CLIENTS)) changes++;
        for (int s : new int[]{2, -2}) {
            BlockPos p = at.relative(side, s);
            level.setBlock(p, Blocks.STONE_BRICK_WALL.defaultBlockState(), QUIET);
            level.setBlock(p.above(), Blocks.LANTERN.defaultBlockState(), QUIET);
            changes += 2;
        }
        return changes;
    }

    static String formatDistance(int blocks) {
        if (blocks >= 1000) return String.format(java.util.Locale.ROOT, "%.1f km", blocks / 1000.0);
        return (Math.max(10, Math.round(blocks / 10.0f) * 10)) + " m";
    }
}
