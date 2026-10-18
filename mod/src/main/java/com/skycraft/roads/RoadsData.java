package com.skycraft.roads;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-dimension road network (only the overworld is used): settlements, roads, roadside features and the set of
 * chunks that are already paved. Spatial indexes and the connectivity union-find are transient and rebuilt on load.
 * Road polylines (the bulk of the data) are stored in per-region {@link RoadBucket} files, so adding a road rewrites
 * only its region; this file holds the small, frequently changing state.
 */
public final class RoadsData extends SavedData {
    public static final String NAME = "skycraft_roads";
    /** Road points are indexed into every chunk their footprint (this many blocks around the point) touches. */
    static final int FOOTPRINT = 5;
    /** Size (log2 blocks) of the regions used to look up settlement zones. */
    private static final int ZONE_SHIFT = 8;

    /** Size (log2 blocks) of the spatial grid used for nearest-settlement queries. */
    private static final int GRID_SHIFT = 9;

    /** Every road destination: towns and dungeons. */
    final List<Settlement> settlements = new ArrayList<>();
    /** The towns among {@link #settlements} (what other modules see). */
    private final List<Settlement> towns = new ArrayList<>();
    private final Long2ObjectOpenHashMap<List<Settlement>> grid = new Long2ObjectOpenHashMap<>();
    final Int2ObjectOpenHashMap<Settlement> settlementById = new Int2ObjectOpenHashMap<>();
    final Long2ObjectOpenHashMap<Settlement> settlementByKey = new Long2ObjectOpenHashMap<>();
    final Long2ObjectOpenHashMap<List<Settlement>> settlementByCenterChunk = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<List<Settlement>> zoneIndex = new Long2ObjectOpenHashMap<>();
    private int zoneIndexRadius = -1;

    final Int2ObjectLinkedOpenHashMap<Road> roads = new Int2ObjectLinkedOpenHashMap<>();
    final List<RoadFeature> features = new ArrayList<>();
    final Long2ObjectOpenHashMap<List<RoadFeature>> featuresByChunk = new Long2ObjectOpenHashMap<>();
    /** chunk -> encoded (roadId << 32 | pointIndex) for every point whose footprint touches the chunk. */
    final Long2ObjectOpenHashMap<LongArrayList> chunkIndex = new Long2ObjectOpenHashMap<>();
    /** Coarse (8-block) planner cells that already carry a road: new roads are encouraged to merge into them. */
    final LongOpenHashSet roadCells = new LongOpenHashSet();
    final LongOpenHashSet paved = new LongOpenHashSet();
    /** Settlement pairs that are connected or waiting to be planned. */
    final LongOpenHashSet pairs = new LongOpenHashSet();
    /** Pairs waiting to be planned (FIFO). */
    final ArrayDeque<Long> pending = new ArrayDeque<>();
    /** Pairs the planner found no acceptable path for: never queued again (until a replan). */
    final LongOpenHashSet failed = new LongOpenHashSet();
    /** Pairs currently being planned on the background threads; saved back into pending. */
    final LongOpenHashSet inFlight = new LongOpenHashSet();
    /** How the planned (pending or built) link of a pair is to be built; pairs without one are ordinary roads. */
    final Long2ObjectOpenHashMap<Link> links = new Long2ObjectOpenHashMap<>();
    /** Named places registered as destinations, by place id. Not saved (rebuilt from the settlements). */
    final java.util.Map<String, Settlement> byPlace = new java.util.HashMap<>();
    /** Roads ending at each destination. Not saved. */
    final Int2ObjectOpenHashMap<it.unimi.dsi.fastutil.ints.IntArrayList> roadsAt = new Int2ObjectOpenHashMap<>();
    /** Version of the world plan applied to this save (0 = roads only from explored settlements). */
    int planVersion;
    /**
     * True for a world created with the plan-first pipeline (roads painted at generation from the baked road plan,
     * docs/WORLDGEN-PIPELINE.md P3); false for a save that predates it, whose roads the legacy {@link Paver} still
     * paves when {@code roads.legacyPaver} is {@code auto}.
     */
    boolean planEra = true;
    /** Regions ({@link WorldPlan#SCAN_SHIFT}) whose generic settlements and dungeons were predicted and registered. */
    final LongOpenHashSet scanned = new LongOpenHashSet();
    /** Trail destinations already checked for a nearby road (whether or not they got a trail). */
    final IntOpenHashSet trailChecked = new IntOpenHashSet();
    int nextSettlementId = 1;
    int nextRoadId = 1;
    /** Bumped by replanning so results from older plans are discarded. Not saved. */
    int generation;
    /** Settlements loaded from disk were checked against the current structure tags. Not saved. */
    boolean classified;

    /** Union-find over towns joined by a road or a queued pair (path compression + union by rank). Not saved. */
    private final Int2IntOpenHashMap parent = new Int2IntOpenHashMap();
    private final Int2IntOpenHashMap rank = new Int2IntOpenHashMap();
    private final Int2IntOpenHashMap componentSize = new Int2IntOpenHashMap();
    /** Non-town destinations that already have (or wait for) their spur road. Not saved. */
    private final IntOpenHashSet spurred = new IntOpenHashSet();

    /** Region files holding the road polylines; {@link #storage} is set once the buckets are attached. */
    private final Long2ObjectOpenHashMap<RoadBucket> buckets = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet bucketKeys = new LongOpenHashSet();
    @Nullable
    private ServerLevel storage;
    /** Roads read from an old single-file save: moved into region buckets when attached. */
    private boolean legacyRoads;

    public static RoadsData get(ServerLevel level) {
        RoadsData data = com.skycraft.platform.SavedDataTypes.get(level, NAME, RoadsData::load, RoadsData::new, d -> d.save(new CompoundTag()));
        if (data.storage == null) data.attach(level);
        return data;
    }

    /** Loads the road region files listed in the main file (and migrates roads from old saves into them). */
    private void attach(ServerLevel storage) {
        this.storage = storage;
        for (long key : bucketKeys.toLongArray()) {
            RoadBucket b = com.skycraft.platform.SavedDataTypes.getIfPresent(storage, RoadBucket.name(key), RoadBucket::load, RoadBucket::new, d -> d.save(new CompoundTag()));
            if (b == null) {
                bucketKeys.remove(key);
                continue;
            }
            buckets.put(key, b);
            for (Road road : b.roads.values()) {
                if (roads.containsKey(road.id)) continue;
                putRoad(road);
            }
        }
        if (legacyRoads) {
            for (Road road : roads.values()) bucket(RoadBucket.keyOf(road)).roads.put(road.id, road);
            legacyRoads = false;
            setDirty();
        }
        rebuildLinks();
    }

    private RoadBucket bucket(long key) {
        RoadBucket b = buckets.get(key);
        if (b == null) {
            b = storage != null ? com.skycraft.platform.SavedDataTypes.get(storage, RoadBucket.name(key), RoadBucket::load, RoadBucket::new, d -> d.save(new CompoundTag())) : new RoadBucket();
            buckets.put(key, b);
            if (bucketKeys.add(key)) setDirty();
        }
        b.setDirty();
        return b;
    }

    private void putRoad(Road road) {
        roads.put(road.id, road);
        indexEnds(road);
        pairs.add(pairKey(road.a, road.b));
        indexRoad(road);
        nextRoadId = Math.max(nextRoadId, road.id + 1);
    }

    public RoadsData() {
    }

    /**
     * How a planned link is built: road class, historic name, longest bridge, and for trails the road point it joins
     * ({@code tx}, {@code tz}; {@link Integer#MIN_VALUE} when the link runs between the two destinations' ends) and the
     * destination it starts from ({@code from}; 0 for ordinary links).
     */
    record Link(byte kind, String name, int maxBridge, int tx, int tz, int from) {
        static final Link DEFAULT = new Link(Road.ROAD, "", RoadGraph.DEFAULT_BRIDGE, Integer.MIN_VALUE, Integer.MIN_VALUE, 0);

        boolean trail() {
            return tx != Integer.MIN_VALUE;
        }

        CompoundTag save(long pair) {
            CompoundTag t = new CompoundTag();
            t.putLong("p", pair);
            t.putByte("k", kind);
            if (!name.isEmpty()) t.putString("n", name);
            t.putInt("b", maxBridge);
            if (trail()) t.putIntArray("t", new int[]{tx, tz});
            if (from != 0) t.putInt("fr", from);
            return t;
        }

        static Link load(CompoundTag t) {
            int[] target = t.getIntArray("t").orElseGet(() -> new int[0]);
            return new Link(t.getByteOr("k", (byte) 0), t.getStringOr("n", ""), t.contains("b") ? t.getIntOr("b", 0) : RoadGraph.DEFAULT_BRIDGE,
                    target.length == 2 ? target[0] : Integer.MIN_VALUE, target.length == 2 ? target[1] : Integer.MIN_VALUE,
                    t.getIntOr("fr", 0));
        }
    }

    Link link(long pair) {
        Link l = links.get(pair);
        return l != null ? l : Link.DEFAULT;
    }

    @Nullable
    Settlement byPlace(String placeId) {
        return byPlace.get(placeId);
    }

    /** Ids of the roads ending at a destination. */
    it.unimi.dsi.fastutil.ints.IntArrayList roadsAt(int settlement) {
        it.unimi.dsi.fastutil.ints.IntArrayList l = roadsAt.get(settlement);
        return l != null ? l : new it.unimi.dsi.fastutil.ints.IntArrayList(0);
    }

    private void indexEnds(Road road) {
        roadsAt.computeIfAbsent(road.a, k -> new it.unimi.dsi.fastutil.ints.IntArrayList(2)).add(road.id);
        roadsAt.computeIfAbsent(road.b, k -> new it.unimi.dsi.fastutil.ints.IntArrayList(2)).add(road.id);
    }

    // ------------------------------------------------------------------ keys

    static long pairKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }

    static int pairA(long key) {
        return (int) (key >> 32);
    }

    static int pairB(long key) {
        return (int) key;
    }

    static long entry(int roadId, int index) {
        return ((long) roadId << 32) | (index & 0xffffffffL);
    }

    static int entryRoad(long e) {
        return (int) (e >> 32);
    }

    static int entryIndex(long e) {
        return (int) e;
    }

    static long cellKey(int blockX, int blockZ) {
        return ChunkPos.pack(Math.floorDiv(blockX, RoadPlanner.CELL), Math.floorDiv(blockZ, RoadPlanner.CELL));
    }

    // ------------------------------------------------------------------ settlements

    Settlement addSettlement(Settlement s) {
        settlements.add(s);
        if (s.town) towns.add(s);
        settlementById.put(s.id, s);
        settlementByKey.put(s.key, s);
        if (!s.placeId.isEmpty()) byPlace.put(s.placeId, s);
        settlementByCenterChunk.computeIfAbsent(ChunkPos.pack(s.x >> 4, s.z >> 4), k -> new ArrayList<>()).add(s);
        grid.computeIfAbsent(ChunkPos.pack(s.x >> GRID_SHIFT, s.z >> GRID_SHIFT), k -> new ArrayList<>(2)).add(s);
        if (zoneIndexRadius >= 0) indexZone(s, zoneIndexRadius);
        setDirty();
        return s;
    }

    /** Changes whether a destination is a town (structure tags changed since it was saved). */
    void setTown(Settlement s, boolean town) {
        if (s.town == town) return;
        s.town = town;
        if (town) towns.add(s);
        else towns.remove(s);
        setDirty();
    }

    @Nullable
    public Settlement settlement(int id) {
        return settlementById.get(id);
    }

    /** The known towns ({@code #skycraft:towns}); dungeons the roads lead to are not included. */
    public List<Settlement> settlements() {
        return Collections.unmodifiableList(towns);
    }

    int townCount() {
        return towns.size();
    }

    /**
     * Destinations within {@code radius} blocks of (x, z) matching {@code filter}, nearest first. Uses the spatial grid,
     * so the cost depends on the radius, not on the number of settlements.
     */
    List<Settlement> near(int x, int z, double radius, java.util.function.Predicate<Settlement> filter) {
        List<Settlement> out = new ArrayList<>();
        double r2 = radius * radius;
        int r = (int) Math.ceil(radius);
        for (int gx = (x - r) >> GRID_SHIFT; gx <= (x + r) >> GRID_SHIFT; gx++) {
            for (int gz = (z - r) >> GRID_SHIFT; gz <= (z + r) >> GRID_SHIFT; gz++) {
                List<Settlement> list = grid.get(ChunkPos.pack(gx, gz));
                if (list == null) continue;
                for (Settlement s : list) {
                    if (s.distSq(x + 0.5, z + 0.5) <= r2 && filter.test(s)) out.add(s);
                }
            }
        }
        out.sort(java.util.Comparator.comparingDouble(s -> s.distSq(x + 0.5, z + 0.5)));
        return out;
    }

    // ------------------------------------------------------------------ connectivity

    /** Root of a town's road-connected group. */
    int find(int id) {
        int root = id;
        while (true) {
            int p = parent.getOrDefault(root, root);
            if (p == root) break;
            root = p;
        }
        // path compression
        int cur = id;
        while (cur != root) {
            int next = parent.getOrDefault(cur, cur);
            parent.put(cur, root);
            cur = next;
        }
        return root;
    }

    int componentSize(int id) {
        return componentSize.getOrDefault(find(id), 1);
    }

    /** Records that a road (built or queued) joins a and b. */
    void link(int a, int b) {
        Settlement sa = settlement(a), sb = settlement(b);
        if (sa == null || sb == null) return;
        if (!sa.town || !sb.town) {
            spurred.add(sa.town ? b : a);
            return;
        }
        int ra = find(a), rb = find(b);
        if (ra == rb) return;
        int rka = rank.getOrDefault(ra, 0), rkb = rank.getOrDefault(rb, 0);
        if (rka < rkb) {
            int t = ra;
            ra = rb;
            rb = t;
        }
        parent.put(rb, ra);
        if (rka == rkb) rank.put(ra, rka + 1);
        componentSize.put(ra, componentSize.getOrDefault(ra, 1) + componentSize.getOrDefault(rb, 1));
        componentSize.remove(rb);
    }

    boolean hasSpur(int id) {
        return spurred.contains(id);
    }

    /** Rebuilds the union-find from the known pairs (after load, or after a pair was dropped). */
    void rebuildLinks() {
        parent.clear();
        rank.clear();
        componentSize.clear();
        spurred.clear();
        for (long p : pairs) link(pairA(p), pairB(p));
    }

    private void indexZone(Settlement s, int radius) {
        int x0 = s.zoneMinX(radius) >> ZONE_SHIFT, x1 = s.zoneMaxX(radius) >> ZONE_SHIFT;
        int z0 = s.zoneMinZ(radius) >> ZONE_SHIFT, z1 = s.zoneMaxZ(radius) >> ZONE_SHIFT;
        for (int rx = x0; rx <= x1; rx++) {
            for (int rz = z0; rz <= z1; rz++) {
                zoneIndex.computeIfAbsent(ChunkPos.pack(rx, rz), k -> new ArrayList<>(2)).add(s);
            }
        }
    }

    private void ensureZoneIndex() {
        int radius = RoadsConfig.SETTLEMENT_RADIUS.get();
        if (radius == zoneIndexRadius) return;
        zoneIndex.clear();
        zoneIndexRadius = radius;
        for (Settlement s : settlements) indexZone(s, radius);
    }

    /** The town whose zone contains this column, if any (dungeon destinations are not towns). */
    @Nullable
    public Settlement zoneAt(int x, int z) {
        return zoneAt(x, z, true);
    }

    /** The destination (town or dungeon) whose zone (no-paving area) contains this column, if any. */
    @Nullable
    Settlement anyZoneAt(int x, int z) {
        return zoneAt(x, z, false);
    }

    /** The destination whose no-road ground (zone, inside the gates of a planned town) contains this column, if any. */
    @Nullable
    Settlement noRoadZoneAt(int x, int z) {
        ensureZoneIndex();
        List<Settlement> list = zoneIndex.get(ChunkPos.pack(x >> ZONE_SHIFT, z >> ZONE_SHIFT));
        if (list == null) return null;
        for (Settlement s : list) if (s.noRoadZone(x, z, zoneIndexRadius)) return s;
        return null;
    }

    @Nullable
    private Settlement zoneAt(int x, int z, boolean townsOnly) {
        ensureZoneIndex();
        List<Settlement> list = zoneIndex.get(ChunkPos.pack(x >> ZONE_SHIFT, z >> ZONE_SHIFT));
        if (list == null) return null;
        for (Settlement s : list) if ((s.town || !townsOnly) && s.inZone(x, z, zoneIndexRadius)) return s;
        return null;
    }

    /** Settlements whose zone may overlap the given chunk. */
    List<Settlement> zonesNearChunk(int cx, int cz) {
        ensureZoneIndex();
        List<Settlement> out = new ArrayList<>(2);
        int bx = cx << 4, bz = cz << 4;
        int r0x = bx >> ZONE_SHIFT, r1x = (bx + 15) >> ZONE_SHIFT;
        int r0z = bz >> ZONE_SHIFT, r1z = (bz + 15) >> ZONE_SHIFT;
        for (int rx = r0x; rx <= r1x; rx++) {
            for (int rz = r0z; rz <= r1z; rz++) {
                List<Settlement> list = zoneIndex.get(ChunkPos.pack(rx, rz));
                if (list == null) continue;
                for (Settlement s : list) {
                    if (out.contains(s)) continue;
                    if (s.zoneMaxX(zoneIndexRadius) < bx || s.zoneMinX(zoneIndexRadius) > bx + 15
                            || s.zoneMaxZ(zoneIndexRadius) < bz || s.zoneMinZ(zoneIndexRadius) > bz + 15) continue;
                    out.add(s);
                }
            }
        }
        return out;
    }

    int zoneRadius() {
        ensureZoneIndex();
        return zoneIndexRadius;
    }

    // ------------------------------------------------------------------ roads

    Road addRoad(int a, int b, int[] xs, int[] zs, byte[] flags, byte kind, String name) {
        return addRoad(a, b, xs, zs, flags, kind, name, false);
    }

    /** {@code plan}: the polyline was taken from the baked road plan (painted at generation, not by the paver). */
    Road addRoad(int a, int b, int[] xs, int[] zs, byte[] flags, byte kind, String name, boolean plan) {
        Road road = new Road(nextRoadId++, a, b, xs, zs, flags, kind, name);
        road.plan = plan;
        roads.put(road.id, road);
        indexEnds(road);
        pairs.add(pairKey(a, b));
        link(a, b);
        bucket(RoadBucket.keyOf(road)).roads.put(road.id, road);
        setDirty();
        return road;
    }

    @Nullable
    public Road road(int id) {
        return roads.get(id);
    }

    /** Adds the road's points to the chunk index and returns every chunk they touch. */
    LongOpenHashSet indexRoad(Road road) {
        LongOpenHashSet touched = new LongOpenHashSet();
        for (int i = 0; i < road.size(); i++) {
            int x = road.xs[i], z = road.zs[i];
            int cx0 = (x - FOOTPRINT) >> 4, cx1 = (x + FOOTPRINT) >> 4;
            int cz0 = (z - FOOTPRINT) >> 4, cz1 = (z + FOOTPRINT) >> 4;
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    long c = ChunkPos.pack(cx, cz);
                    chunkIndex.computeIfAbsent(c, k -> new LongArrayList()).add(entry(road.id, i));
                    touched.add(c);
                }
            }
            roadCells.add(cellKey(x, z));
        }
        return touched;
    }

    /** Registers a feature and returns its chunk. */
    long addFeature(RoadFeature f) {
        features.add(f);
        long c = ChunkPos.pack(f.x >> 4, f.z >> 4);
        featuresByChunk.computeIfAbsent(c, k -> new ArrayList<>(1)).add(f);
        setDirty();
        return c;
    }

    /** The nearest signpost within {@code radius} blocks, if any. */
    @Nullable
    RoadFeature signpostNear(int x, int z, int radius) {
        RoadFeature best = null;
        long bestD = (long) radius * radius;
        for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++) {
            for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++) {
                List<RoadFeature> list = featuresByChunk.get(ChunkPos.pack(cx, cz));
                if (list == null) continue;
                for (RoadFeature f : list) {
                    if (f.type != RoadFeature.SIGNPOST) continue;
                    long dx = f.x - x, dz = f.z - z;
                    long d = dx * dx + dz * dz;
                    if (d <= bestD) {
                        bestD = d;
                        best = f;
                    }
                }
            }
        }
        return best;
    }

    /**
     * True if the legacy paver still lays road blocks in this world ({@code roads.legacyPaver}: {@code true} always,
     * {@code false} never, {@code auto} only in saves that predate the plan-first generation).
     */
    boolean legacyPaving() {
        String mode = RoadsConfig.LEGACY_PAVER.get();
        return "true".equals(mode) || ("auto".equals(mode) && !planEra);
    }

    /** True if this chunk has road points or features that are not paved yet. */
    boolean needsPaving(long chunk) {
        return !paved.contains(chunk) && (chunkIndex.containsKey(chunk) || featuresByChunk.containsKey(chunk));
    }

    /** Forgets all roads, features and paving state (settlements are kept). Placed blocks stay in the world. */
    void clearRoads() {
        roads.clear();
        features.clear();
        featuresByChunk.clear();
        chunkIndex.clear();
        roadCells.clear();
        paved.clear();
        pairs.clear();
        pending.clear();
        failed.clear();
        inFlight.clear();
        links.clear();
        roadsAt.clear();
        trailChecked.clear();
        planVersion = 0;
        generation++;
        for (RoadBucket b : buckets.values()) {
            if (b.roads.isEmpty()) continue;
            b.roads.clear();
            b.setDirty();
        }
        rebuildLinks();
        setDirty();
    }

    // ------------------------------------------------------------------ persistence

    public CompoundTag save(CompoundTag tag) {
        ListTag s = new ListTag();
        for (Settlement st : settlements) s.add(st.save());
        tag.put("settlements", s);
        if (storage == null) {
            // never attached to its region files (should not happen): keep the polylines here rather than lose them
            ListTag r = new ListTag();
            for (Road road : roads.values()) r.add(road.save());
            tag.put("roads", r);
        }
        tag.putLongArray("buckets", bucketKeys.toLongArray());
        tag.putLongArray("failed", failed.toLongArray());
        ListTag f = new ListTag();
        for (RoadFeature feature : features) f.add(feature.save());
        tag.put("features", f);
        tag.putLongArray("paved", paved.toLongArray());
        LongArrayList pend = new LongArrayList();
        for (long p : inFlight) pend.add(p);
        for (Long p : pending) pend.add(p.longValue());
        tag.putLongArray("pending", pend.toLongArray());
        ListTag l = new ListTag();
        for (Long2ObjectMap.Entry<Link> e : links.long2ObjectEntrySet()) l.add(e.getValue().save(e.getLongKey()));
        tag.put("links", l);
        tag.putInt("planVersion", planVersion);
        tag.putBoolean("planEra", planEra);
        tag.putLongArray("scanned", scanned.toLongArray());
        tag.putIntArray("trailChecked", trailChecked.toIntArray());
        tag.putInt("nextSettlement", nextSettlementId);
        tag.putInt("nextRoad", nextRoadId);
        return tag;
    }

    public static RoadsData load(CompoundTag tag) {
        RoadsData data = new RoadsData();
        data.nextSettlementId = Math.max(1, tag.getIntOr("nextSettlement", 0));
        data.nextRoadId = Math.max(1, tag.getIntOr("nextRoad", 0));
        ListTag s = tag.getListOrEmpty("settlements");
        for (int i = 0; i < s.size(); i++) {
            Settlement st = Settlement.load(s.getCompoundOrEmpty(i));
            if (st == null || data.settlementByKey.containsKey(st.key)) continue;
            data.addSettlement(st);
            data.nextSettlementId = Math.max(data.nextSettlementId, st.id + 1);
        }
        // roads live in region files (attached by get()); older saves kept them all here
        ListTag r = tag.getListOrEmpty("roads");
        for (int i = 0; i < r.size(); i++) {
            Road road = Road.load(r.getCompoundOrEmpty(i));
            if (road == null) continue;
            data.putRoad(road);
            data.legacyRoads = true;
        }
        for (long k : tag.getLongArray("buckets").orElseGet(() -> new long[0])) data.bucketKeys.add(k);
        for (long p : tag.getLongArray("failed").orElseGet(() -> new long[0])) data.failed.add(p);
        ListTag f = tag.getListOrEmpty("features");
        for (int i = 0; i < f.size(); i++) data.addFeature(RoadFeature.load(f.getCompoundOrEmpty(i)));
        for (long c : tag.getLongArray("paved").orElseGet(() -> new long[0])) data.paved.add(c);
        for (long p : tag.getLongArray("pending").orElseGet(() -> new long[0])) {
            if (data.pairs.add(p)) data.pending.add(p);
        }
        ListTag l = tag.getListOrEmpty("links");
        for (int i = 0; i < l.size(); i++) data.links.put(l.getCompoundOrEmpty(i).getLongOr("p", 0L), Link.load(l.getCompoundOrEmpty(i)));
        data.planVersion = tag.getIntOr("planVersion", 0);
        data.planEra = tag.contains("planEra") && tag.getBooleanOr("planEra", false);
        for (long k : tag.getLongArray("scanned").orElseGet(() -> new long[0])) data.scanned.add(k);
        for (int k : tag.getIntArray("trailChecked").orElseGet(() -> new int[0])) data.trailChecked.add(k);
        data.setDirty(false);
        return data;
    }
}
