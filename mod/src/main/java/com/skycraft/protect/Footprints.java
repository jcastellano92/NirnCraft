package com.skycraft.protect;

import com.skycraft.Skycraft;
import com.skycraft.roads.RoadCells;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import com.skycraft.tamriel.settlement.plan.Cities;
import com.skycraft.world.PlacementMask;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "What man-made thing stands here?": the footprint a block belongs to, from the data the world was built from
 * (named places and their layout reach, the road plan, the placement mask's sites and landmarks, the structure
 * pieces of the chunk). Server thread only; the per-chunk place lists are cached (places are static data).
 *
 * <p>Cost: a place-list lookup per chunk (built once), a road plan lookup, a placement mask grid read and a walk over
 * the chunk's structure references (usually none). Called a few times per player action, never per tick per block.</p>
 */
public final class Footprints {
    /** From the least to the most protected. */
    public enum Kind {
        NONE, SITE, STRUCTURE, ROAD, SETTLEMENT;

        public boolean built() {
            return this != NONE;
        }
    }

    /** Named places farther than this (plus margins) from a chunk cannot touch it. */
    private static final int REACH = 300;
    private static final int MAX_MARGIN = 64;
    private static final int SITE_RADIUS_MAX = 40, SITE_PAD = 4;
    /** Pieces wider than this are containers, not footprints (same rule as the placement mask). */
    private static final int PIECE_SPAN_MAX = 160;
    private static final int CACHE_CHUNKS = 1024;
    private static final int[] NO_PLACES = new int[0];

    /** chunk -> flat [x, z, rawRadius, isSettlement] groups of the places that may reach the chunk. */
    private static final Long2ObjectOpenHashMap<int[]> PLACES = new Long2ObjectOpenHashMap<>();
    private static long queries, placeBuilds;

    private Footprints() {}

    public static String stats() {
        return queries + " footprint queries, " + placeBuilds + " chunk place lists built";
    }

    /** The most protected footprint {@code pos} belongs to. */
    public static Kind at(ServerLevel level, BlockPos pos) {
        queries++;
        Kind best = Kind.NONE;
        boolean overworld = level.dimension() == Level.OVERWORLD;
        int x = pos.getX(), z = pos.getZ();
        if (overworld) {
            int margin = ProtectConfig.SETTLEMENT_MARGIN.get();
            int[] ps = places(x >> 4, z >> 4);
            for (int i = 0; i < ps.length; i += 4) {
                long dx = x - ps[i], dz = z - ps[i + 1];
                long d2 = dx * dx + dz * dz;
                if (ps[i + 3] == 1) {
                    long r = ps[i + 2] + margin;
                    if (d2 <= r * r) return Kind.SETTLEMENT;
                } else {
                    long r = Math.min(ps[i + 2], SITE_RADIUS_MAX) + SITE_PAD;
                    if (d2 <= r * r) best = Kind.SITE;
                }
            }
            // generated settlements that are not a named place, as the structures recorded them
            if (SettlementAnchors.near(level, pos, margin).filter(e -> !e.homestead()).isPresent()) return Kind.SETTLEMENT;
            int roadMargin = ProtectConfig.ROAD_MARGIN.get();
            if (RoadCells.isRoad(level, x, z, roadMargin)) return Kind.ROAD;
            if (PlacementMask.clearance(level, x, z) <= 0) {
                // a road's verge, a settlement core or the pad of a minor site / landmark
                if (RoadCells.isRoad(level, x, z, roadMargin + 6)) return Kind.ROAD;
                if (best.compareTo(Kind.SITE) < 0) best = Kind.SITE;
            }
        }
        if (best.compareTo(Kind.STRUCTURE) < 0 && inStructure(level, pos, 1)) best = Kind.STRUCTURE;
        return best;
    }

    /**
     * True if the column lies in a named settlement's footprint. Pure data (the place list and layout reach), so the
     * client can use it to predict a denial without asking the server.
     */
    public static boolean inNamedSettlement(int x, int z) {
        int margin;
        try {
            margin = ProtectConfig.SETTLEMENT_MARGIN.get();
        } catch (RuntimeException e) {
            margin = 6;
        }
        int[] ps = places(x >> 4, z >> 4);
        for (int i = 0; i < ps.length; i += 4) {
            if (ps[i + 3] != 1) continue;
            long dx = x - ps[i], dz = z - ps[i + 1];
            long r = ps[i + 2] + margin;
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    /** True if a piece of a structure start the chunk references contains the position (grown by {@code pad}). */
    public static boolean inStructure(ServerLevel level, BlockPos pos, int pad) {
        ChunkAccess chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.STRUCTURE_REFERENCES, false);
        if (chunk == null) return false;
        Map<Structure, LongSet> refs = chunk.getAllReferences();
        if (refs.isEmpty()) return false;
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        for (Map.Entry<Structure, LongSet> e : refs.entrySet()) {
            for (long startChunk : e.getValue()) {
                ChunkAccess sc = level.getChunk(ChunkPos.getX(startChunk), ChunkPos.getZ(startChunk), ChunkStatus.STRUCTURE_STARTS, false);
                if (sc == null) continue;
                StructureStart start = sc.getStartForStructure(e.getKey());
                if (start == null || !start.isValid() || !inside(start.getBoundingBox(), x, y, z, pad)) continue;
                for (StructurePiece piece : start.getPieces()) {
                    BoundingBox b = piece.getBoundingBox();
                    if (b.getXSpan() > PIECE_SPAN_MAX || b.getZSpan() > PIECE_SPAN_MAX) continue;
                    if (inside(b, x, y, z, pad)) return true;
                }
            }
        }
        return false;
    }

    private static boolean inside(BoundingBox b, int x, int y, int z, int pad) {
        return x >= b.minX() - pad && x <= b.maxX() + pad && y >= b.minY() - pad && y <= b.maxY() + pad
                && z >= b.minZ() - pad && z <= b.maxZ() + pad;
    }

    private static synchronized int[] places(int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        int[] cached = PLACES.get(key);
        if (cached != null) return cached;
        int x0 = cx << 4, z0 = cz << 4;
        List<int[]> list = new ArrayList<>();
        try {
            for (Place p : Places.overlapping(x0 - REACH, z0 - REACH, x0 + 15 + REACH, z0 + 15 + REACH)) {
                boolean settlement = p.type().isSettlement();
                if (settlement && "homestead".equals(p.variant())) continue; // open land for a player's homestead
                int r;
                if (settlement) {
                    try {
                        r = Math.max(Cities.reach(p), Cities.layoutRadius(p));
                    } catch (RuntimeException e) {
                        r = p.radius();
                    }
                } else {
                    r = Math.min(p.radius(), SITE_RADIUS_MAX);
                }
                long reach = (long) r + MAX_MARGIN + SITE_PAD;
                long dx = Math.max(0, Math.max(x0 - p.x(), p.x() - (x0 + 15)));
                long dz = Math.max(0, Math.max(z0 - p.z(), p.z() - (z0 + 15)));
                if (dx * dx + dz * dz > reach * reach) continue;
                list.add(new int[]{p.x(), p.z(), r, settlement ? 1 : 0});
            }
        } catch (RuntimeException e) {
            Skycraft.LOGGER.warn("Protect: could not list the places near chunk {} {}", cx, cz, e);
        }
        int[] flat = NO_PLACES;
        if (!list.isEmpty()) {
            flat = new int[list.size() * 4];
            for (int i = 0; i < list.size(); i++) System.arraycopy(list.get(i), 0, flat, i * 4, 4);
        }
        if (PLACES.size() >= CACHE_CHUNKS) PLACES.clear();
        PLACES.put(key, flat);
        placeBuilds++;
        return flat;
    }

    /** Drops the caches (level unload). */
    public static void clear() {
        PLACES.clear();
    }
}
