package com.skycraft.world;

import com.skycraft.Skycraft;
import com.skycraft.explore.MinorSites;
import com.skycraft.landmarks.LandmarkSite;
import com.skycraft.landmarks.Landmarks;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.terrain.MapRaster;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;

/**
 * Plan-first vegetation mask (world-generation pipeline stage S10, docs/WORLDGEN-PIPELINE.md): where trees, huge
 * trees, ground cover, rocks and props must <em>not</em> be placed because a road, street, plot, pad, site or path
 * will be there. Vegetation features ask {@link #clear} before they grow; a masked candidate is skipped whole (never
 * shrink-wrapped), so nothing has to be cut afterwards and no crown is left hanging.
 *
 * <p>The mask is a per-chunk byte grid: for every column the distance (blocks, capped at {@link #CAP}) to the nearest
 * padded footprint, 0 inside one. A feature with a crown (or footprint) of radius {@code r} may stand at a column
 * when its clearance is greater than {@code r}. Grids are built lazily, once per chunk and thread, from sources that
 * need no neighbour chunk to be generated:</p>
 * <ul>
 *   <li><b>structure pieces</b>: every structure start the generation region already holds (settlement streets,
 *   plots and pads, dungeon mouths, forts, camps, cabins...): each piece's box plus {@value #STRUCT_PAD} blocks, pieces
 *   buried below the ground ignored;</li>
 *   <li><b>settlement cores</b> of the named places ({@link Places}), half the place radius, so a town is clear even
 *   where its pieces are not known;</li>
 *   <li><b>minor sites and landmarks</b> (their deterministic grids), capped footprint;</li>
 *   <li><b>road corridors</b> of the authored map's road channel ({@link MapRaster#roadDistance}): the planner
 *   routes along them. Half width of a highway plus {@value #ROAD_PAD_WIDTH} blocks of verge and planner slack;</li>
 *   <li>any {@link Source} registered by another module: the baked road plan (roads/worldplan) plugs in here so its
 *   exact polylines, class half widths and plot pads join the mask without touching the features.</li>
 * </ul>
 *
 * <p>Cost: a query is one thread-local lookup plus one array read (no allocation); a chunk's grid is built once
 * (see {@link #stats}). Thread-safe: state is per thread, sources are immutable data.</p>
 */
public final class PlacementMask {
    /** Largest clearance reported, blocks. Sources influence columns up to this far away. */
    public static final int CAP = 63;
    /** Verge kept around a structure piece. */
    private static final int STRUCT_PAD = 2;
    /** Highway half width (2.5) + the two blocks the paver clears beside the band + slack for the planner's detours. */
    private static final float ROAD_PAD_WIDTH = 7f;
    /** A named settlement's guaranteed-clear core, as a fraction of its radius, plus its verge. */
    private static final float CORE_FRACTION = 0.5f;
    private static final int CORE_PAD = 4;
    /** Site footprints are clipped to this many blocks. */
    private static final int SITE_RADIUS_MAX = 24, SITE_PAD = 2;
    /** Pieces wider than this are containers, not footprints (a whole-region ward box); they are ignored. */
    private static final int PIECE_SPAN_MAX = 160;
    /** Start chunks scanned around the chunk (references reach further; the region holds starts up to 8 away). */
    private static final int START_RING = 3;
    private static final int CACHE_CHUNKS = 192;

    /** A mask contribution from another system. Must be fast, allocation free and thread safe. */
    public interface Source {
        /**
         * Lowers {@code out[(z & 15) << 4 | (x & 15)]} (columns of chunk {@code chunkX, chunkZ}) with
         * {@link PlacementMask#lower} to the padded distance to the source's geometry (0 or less inside).
         */
        void contribute(long worldSeed, int chunkX, int chunkZ, byte[] out);
    }

    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();
    /** The authored map's road channel is a stand-in until the baked road plan is registered as a source. */
    private static volatile boolean mapRoadChannel = true;

    private static final class Cache {
        ServerLevel owner;
        final Long2ObjectOpenHashMap<byte[]> chunks = new Long2ObjectOpenHashMap<>();
        long lastKey = Long.MIN_VALUE;
        byte[] last;
        int tick;
    }

    private static final ThreadLocal<Cache> CACHE = ThreadLocal.withInitial(Cache::new);

    // statistics
    private static final LongAdder QUERIES = new LongAdder(), SKIPS = new LongAdder(), BUILDS = new LongAdder(),
            BUILD_NANOS = new LongAdder(), QUERY_SAMPLES = new LongAdder(), QUERY_NANOS = new LongAdder(),
            MASKED_COLUMNS = new LongAdder();

    private PlacementMask() {}

    /** Logs the totals when a server stops (the benchmark numbers of the generation that just ran). */
    @net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
    public static final class Log {
        private Log() {}

        @net.neoforged.bus.api.SubscribeEvent
        public static void onStop(net.neoforged.neoforge.event.server.ServerStoppingEvent e) {
            Skycraft.LOGGER.info(stats());
        }
    }

    /** Registers another module's footprints (the baked road plan). Chunk grids built earlier are not revised. */
    public static void register(Source source) {
        SOURCES.add(source);
    }

    /**
     * Switches the approximate road corridors of the map's road channel off (the baked road plan registered an exact
     * {@link Source}, so trees no longer keep clear of corridors where no road was planned).
     */
    public static void useMapRoadChannel(boolean on) {
        mapRoadChannel = on;
    }

    /** For {@link Source}s: lowers the clearance at a column index to {@code distance} (clamped to 0..{@link #CAP}). */
    public static void lower(byte[] out, int index, double distance) {
        int d = distance <= 0 ? 0 : distance >= CAP ? CAP : (int) distance;
        if (d < out[index]) out[index] = (byte) d;
    }

    // ================================================================================================ queries

    /**
     * True if a plant, tree or prop of footprint radius {@code radius} (crown radius for trees) may stand at the
     * column: nothing masked within that many blocks. Counts the call, and the skip when it answers false.
     */
    public static boolean clear(WorldGenLevel level, int x, int z, int radius) {
        boolean ok = clearance(level, x, z) > radius;
        QUERIES.increment();
        if (!ok) SKIPS.increment();
        return ok;
    }

    public static boolean clear(WorldGenLevel level, BlockPos pos, int radius) {
        return clear(level, pos.getX(), pos.getZ(), radius);
    }

    /** Blocks from the column to the nearest padded footprint: 0 inside one, {@link #CAP} when none is near. */
    public static int clearance(WorldGenLevel level, int x, int z) {
        return lookup(level, x, z, 0);
    }

    /**
     * Like {@link #clear} but ignoring the pads of minor sites and landmarks: for a site's own builder, which must
     * not be refused by its own footprint but should still give way to a road, a street or a structure.
     */
    public static boolean clearOfPlans(WorldGenLevel level, int x, int z, int radius) {
        boolean ok = lookup(level, x, z, 256) > radius;
        QUERIES.increment();
        if (!ok) SKIPS.increment();
        return ok;
    }

    private static int lookup(WorldGenLevel level, int x, int z, int offset) {
        Cache c = CACHE.get();
        boolean timed = (++c.tick & 15) == 0, built = false;
        long t0 = timed ? System.nanoTime() : 0L;
        ServerLevel owner = level.getLevel();
        if (c.owner != owner) {
            c.owner = owner;
            c.chunks.clear();
            c.lastKey = Long.MIN_VALUE;
            c.last = null;
        }
        long key = ChunkPos.pack(x >> 4, z >> 4);
        byte[] m;
        if (key == c.lastKey) {
            m = c.last;
        } else {
            m = c.chunks.get(key);
            if (m == null) {
                m = build(level, owner, x >> 4, z >> 4);
                built = true;
                if (c.chunks.size() >= CACHE_CHUNKS) c.chunks.clear();
                c.chunks.put(key, m);
            }
            c.lastKey = key;
            c.last = m;
        }
        int v = m[offset + ((z & 15) << 4 | (x & 15))];
        if (timed && !built) {
            QUERY_SAMPLES.increment();
            QUERY_NANOS.add(System.nanoTime() - t0);
        }
        return v;
    }

    /** A one-line summary for the log and the dev commands. */
    public static String stats() {
        long b = BUILDS.sum(), q = QUERIES.sum(), qs = QUERY_SAMPLES.sum();
        return String.format("PlacementMask: %d queries, %d skipped; %d chunk grids built (avg %.1f us, %.1f%% of columns inside a mask); "
                        + "query avg %.0f ns (sampled, grid already built)", q, SKIPS.sum(), b, b == 0 ? 0.0 : BUILD_NANOS.sum() / 1000.0 / b,
                b == 0 ? 0.0 : 100.0 * MASKED_COLUMNS.sum() / (256.0 * b), qs == 0 ? 0.0 : (double) QUERY_NANOS.sum() / qs);
    }

    // ================================================================================================ building

    private static byte[] build(WorldGenLevel level, ServerLevel owner, int cx, int cz) {
        long t0 = System.nanoTime();
        // the plans (roads, streets, pads, structure pieces, registered sources) first; the site pads on top
        byte[] p = new byte[256];
        Arrays.fill(p, (byte) CAP);
        int x0 = cx << 4, z0 = cz << 4;
        long seed = owner.getSeed();
        boolean overworld = owner.dimension() == Level.OVERWORLD;
        try {
            if (overworld) {
                if (mapRoadChannel) roads(p, x0, z0);
                places(p, x0, z0);
            }
            structures(level, p, cx, cz);
            for (Source s : SOURCES) s.contribute(seed, cx, cz, p);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.warn("PlacementMask: could not build the plan grid of chunk {} {}", cx, cz, e);
        }
        byte[] m = new byte[512];
        System.arraycopy(p, 0, m, 0, 256);
        System.arraycopy(p, 0, m, 256, 256);
        try {
            if (overworld) sites(m, seed, x0, z0);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.warn("PlacementMask: could not add the site pads of chunk {} {}", cx, cz, e);
        }
        int inside = 0, min = CAP;
        for (int i = 0; i < 256; i++) {
            if (m[i] == 0) inside++;
            if (m[i] < min) min = m[i];
        }
        dump(cx, cz, min, inside);
        MASKED_COLUMNS.add(inside);
        BUILD_NANOS.add(System.nanoTime() - t0);
        BUILDS.increment();
        long n = BUILDS.sum();
        if (n % 2000 == 0) Skycraft.LOGGER.info(stats());
        return m;
    }

    /** Developer aid: {@code -Dskycraft.mask.dump=<file>} appends "chunkX chunkZ minClearance maskedColumns" per grid. */
    private static final String DUMP = System.getProperty("skycraft.mask.dump");

    private static void dump(int cx, int cz, int min, int inside) {
        if (DUMP == null) return;
        synchronized (PlacementMask.class) {
            try (java.io.FileWriter w = new java.io.FileWriter(DUMP, true)) {
                w.write(cx + " " + cz + " " + min + " " + inside + "\n");
            } catch (java.io.IOException e) {
                // diagnostics only
            }
        }
    }

    /** Road corridors of the authored map's road channel. */
    private static void roads(byte[] m, int x0, int z0) {
        if (!MapRaster.covers(x0 + 8, z0 + 8)) return;
        MapRaster r = MapRaster.get();
        if (!r.loaded()) return;
        // most chunks lie beyond the 64 block reach of every road: nine samples tell
        boolean any = false;
        for (int i = 0; i <= 16 && !any; i += 8) {
            for (int j = 0; j <= 16; j += 8) {
                if (r.roadDistance(x0 + i, z0 + j) < MapRaster.ROAD_REACH) {
                    any = true;
                    break;
                }
            }
        }
        if (!any) return;
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                float d = r.roadDistance(x0 + dx, z0 + dz);
                if (d < MapRaster.ROAD_REACH) lower(m, dz << 4 | dx, d - ROAD_PAD_WIDTH);
            }
        }
    }

    /** The core of every named settlement near the chunk (the pieces themselves come from the structure starts). */
    private static void places(byte[] m, int x0, int z0) {
        for (Place p : Places.overlapping(x0 - CAP, z0 - CAP, x0 + 15 + CAP, z0 + 15 + CAP)) {
            if (!p.type().isSettlement()) continue;
            circle(m, x0, z0, p.x(), p.z(), p.radius() * CORE_FRACTION + CORE_PAD);
        }
    }

    /** Minor sites and landmarks: deterministic grids, so no neighbour chunk is needed. */
    private static void sites(byte[] m, long seed, int x0, int z0) {
        int cx = x0 + 8, cz = z0 + 8, reach = 8 + CAP + SITE_RADIUS_MAX;
        for (MinorSites.Site s : MinorSites.within(seed, cx, cz, reach)) {
            circle(m, x0, z0, s.x(), s.z(), Math.min(s.kind().radius, SITE_RADIUS_MAX) + SITE_PAD);
        }
        for (LandmarkSite s : Landmarks.within(seed, cx, cz, reach)) {
            circle(m, x0, z0, s.x(), s.z(), Math.min(s.kind().radius(), SITE_RADIUS_MAX) + SITE_PAD);
        }
    }

    private static void circle(byte[] m, int x0, int z0, int px, int pz, double radius) {
        if (px + radius + CAP < x0 || px - radius - CAP > x0 + 15 || pz + radius + CAP < z0 || pz - radius - CAP > z0 + 15) return;
        for (int dz = 0; dz < 16; dz++) {
            double ez = z0 + dz - pz;
            for (int dx = 0; dx < 16; dx++) {
                double ex = x0 + dx - px;
                lower(m, dz << 4 | dx, Math.sqrt(ex * ex + ez * ez) - radius);
            }
        }
    }

    /**
     * Every piece of every structure start the generation region holds near the chunk: the chunk's own references
     * (starts whose box reaches it, up to 8 chunks away) plus the start chunks within {@link #START_RING}.
     */
    private static void structures(WorldGenLevel level, byte[] m, int cx, int cz) {
        structures(level, m, cx, cz, STRUCT_PAD);
    }

    /**
     * For the road painter: the clearance of every column of chunk (cx, cz) to the pieces of the structure starts the
     * region holds, 0 inside a piece's box grown by {@code pad}, {@link #CAP} when none is near. No road, pad or site
     * contribution (the painter must not be refused by its own road). Allocates; call once per chunk.
     */
    public static byte[] structureGrid(WorldGenLevel level, int cx, int cz, int pad) {
        byte[] m = new byte[256];
        Arrays.fill(m, (byte) CAP);
        try {
            structures(level, m, cx, cz, pad);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.warn("PlacementMask: could not read the structure pieces of chunk {} {}", cx, cz, e);
        }
        return m;
    }

    private static void structures(WorldGenLevel level, byte[] m, int cx, int cz, int pad) {
        if (!(level instanceof WorldGenRegion region)) return;
        int x0 = cx << 4, z0 = cz << 4;
        // pieces that can matter: horizontally within the cap of the chunk
        List<BoundingBox> boxes = null;
        LongSet seen = new LongOpenHashSet();
        ChunkAccess own = region.getChunk(cx, cz, ChunkStatus.STRUCTURE_REFERENCES, false);
        if (own != null) {
            for (Map.Entry<Structure, LongSet> e : own.getAllReferences().entrySet()) {
                for (long pos : e.getValue()) {
                    if (seen.add(pos)) boxes = collect(region, boxes, ChunkPos.getX(pos), ChunkPos.getZ(pos), x0, z0);
                }
            }
        }
        for (int dx = -START_RING; dx <= START_RING; dx++) {
            for (int dz = -START_RING; dz <= START_RING; dz++) {
                long pos = ChunkPos.pack(cx + dx, cz + dz);
                if (seen.add(pos)) boxes = collect(region, boxes, cx + dx, cz + dz, x0, z0);
            }
        }
        if (boxes == null) return;
        // ground height per column: the decorated chunk's own heightmap is ready; neighbours use the macro map
        boolean own16 = region.getCenter().x() == cx && region.getCenter().z() == cz;
        int macro = own16 ? 0 : (int) TamrielMap.elevation(x0 + 8, z0 + 8);
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int idx = dz << 4 | dx, x = x0 + dx, z = z0 + dz;
                if (m[idx] == 0) continue;
                int ground = own16 ? region.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) : macro;
                int best = m[idx];
                for (int i = 0; i < boxes.size(); i++) {
                    BoundingBox b = boxes.get(i);
                    if (b.maxY() < ground - 4) continue; // buried (dungeon halls, cellars)
                    int ex = Math.max(Math.max(b.minX() - x, x - b.maxX()), 0);
                    int ez = Math.max(Math.max(b.minZ() - z, z - b.maxZ()), 0);
                    if (ex > best + pad || ez > best + pad) continue;
                    double d = Math.sqrt((double) ex * ex + (double) ez * ez) - pad;
                    if (d < best) best = d <= 0 ? 0 : (int) d;
                    if (best == 0) break;
                }
                if (best < m[idx]) m[idx] = (byte) best;
            }
        }
    }

    /** Adds the piece boxes of the start (if any) in chunk (sx, sz) that come near the chunk at (x0, z0). */
    private static List<BoundingBox> collect(WorldGenRegion region, List<BoundingBox> out, int sx, int sz, int x0, int z0) {
        ChunkAccess ca = region.getChunk(sx, sz, ChunkStatus.STRUCTURE_STARTS, false);
        if (ca == null) return out;
        Map<Structure, StructureStart> starts = ca.getAllStarts();
        if (starts.isEmpty()) return out;
        int near = CAP + STRUCT_PAD;
        for (StructureStart start : starts.values()) {
            if (start == null || !start.isValid()) continue;
            BoundingBox sb = start.getBoundingBox();
            if (sb.maxX() < x0 - near || sb.minX() > x0 + 15 + near || sb.maxZ() < z0 - near || sb.minZ() > z0 + 15 + near) continue;
            for (StructurePiece piece : start.getPieces()) {
                BoundingBox b = piece.getBoundingBox();
                if (b.maxX() < x0 - near || b.minX() > x0 + 15 + near || b.maxZ() < z0 - near || b.minZ() > z0 + 15 + near) continue;
                if (b.getXSpan() > PIECE_SPAN_MAX || b.getZSpan() > PIECE_SPAN_MAX) continue;
                if (out == null) out = new ArrayList<>();
                out.add(b);
            }
        }
        return out;
    }
}
