package com.skycraft.worldplan.tile;

import com.skycraft.Skycraft;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.settlement.plan.Archetype;
import com.skycraft.worldplan.PlanGround;
import com.skycraft.worldplan.RoadPlan;
import com.skycraft.worldplan.scene.ScenePlan;
import com.skycraft.worldplan.street.StreetPlan;
import net.minecraft.core.Direction;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Seed-dependent 4,096 x 4,096 block tile planner and off-thread cache for Endless Lands beyond the authored map
 * (pipeline stage P7, see {@code docs/WORLDGEN-PIPELINE.md} section 7).
 *
 * <p>Endless Lands are dormant under the outer-ocean policy. Runtime {@link #getIfReady(int, int)} returns null even
 * for a previously saved or cached plan and schedules no expansion work. Explicit commands and tests may inspect
 * the preserved planner using {@link #planSync(long, int, int)} and a thread-confined {@link #previewForTools()} scope.</p>
 *
 * <p><b>Boundary seam contract:</b> crossing points on any tile edge are computed by a pure function of
 * {@code (worldSeed, isHorizontal, borderLineCoord, spanIndex)} via {@link #computeEdgePoints}, so two adjacent tiles
 * planned in any order produce identical {@link TilePlan.EdgePoint}s on their shared border without reading each other.</p>
 */
public final class TilePlanner {
    private static final int VERTEX_SPACING = 4;
    private static final String[] SCENE_KINDS = {
            "road_camp", "wayshrine", "crossroads_sign", "bridge_watch", "ruin_outpost", "settlement_marker",
            "campfire", "crate", "barrel", "bench", "lantern", "cart", ScenePlan.SIGN, ScenePlan.NOTE
    };

    private static final String[] PLACE_PREFIXES = {
            "North", "High", "Salt", "Drift", "Storm", "Iron", "Frost", "Sun",
            "Cinder", "Mist", "Crag", "Deep", "Wind", "Grey", "Red", "Star"
    };
    private static final String[] SETTLEMENT_SUFFIXES = {
            "haven", "stead", "watch", "crossing", "harbour", "ford", "hold", "wick"
    };
    private static final String[] DELVE_SUFFIXES = {
            "Barrow", "Vault", "Redoubt", "Cavern", "Holdfast", "Cairn", "Hollow", "Crypt"
    };
    private static final String[] SHRINE_SUFFIXES = {
            "Wayshrine", "Standing Stone", "Beacon", "Altar", "Sanctum", "Obelisk"
    };

    /** Session identity is independent of seed: two saves may legitimately share a seed. */
    private static final class Session {
        final long seed;
        final Object owner;
        final PersistentLoader loader;
        final SessionSink sink;
        final Runnable beforeDetach;
        final BooleanSupplier ownerThread;
        volatile boolean closing;
        final ConcurrentHashMap<Long, TilePlan> cache = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Long, CompletableFuture<TilePlan>> pending = new ConcurrentHashMap<>();

        Session(long seed, Object owner, PersistentLoader loader, SessionSink sink, Runnable beforeDetach,
                BooleanSupplier ownerThread) {
            this.seed = seed;
            this.owner = owner;
            this.loader = loader;
            this.sink = sink;
            this.beforeDetach = beforeDetach;
            this.ownerThread = ownerThread;
        }
    }

    private static volatile Session activeSession;
    private static final ThreadLocal<Integer> TOOL_PREVIEW = ThreadLocal.withInitial(() -> 0);

    /** Explicit thread-confined inspection of dormant expansion plans; never enables runtime world generation. */
    public static final class ToolPreview implements AutoCloseable {
        private final Thread thread = Thread.currentThread();
        private final int depth = TOOL_PREVIEW.get();
        private boolean closed;
        private ToolPreview() { TOOL_PREVIEW.set(depth + 1); }
        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != thread || TOOL_PREVIEW.get() != depth + 1)
                throw new IllegalStateException("Tile tool preview must close on its original thread in scope order");
            if (depth == 0) TOOL_PREVIEW.remove(); else TOOL_PREVIEW.set(depth);
            closed = true;
        }
    }

    public static ToolPreview previewForTools() { return new ToolPreview(); }

    @FunctionalInterface
    interface SessionSink {
        void accept(Object token, long key, TilePlan plan);
    }

    @FunctionalInterface
    public interface PersistentLoader {
        TilePlan load(long worldSeed, int tileX, int tileZ);
    }

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "skycraft-tile-planner-" + THREAD_COUNTER.getAndIncrement());
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static final ThreadLocal<RoadPlan.Hit> HIT_SCRATCH = ThreadLocal.withInitial(RoadPlan.Hit::new);

    private TilePlanner() {
    }

    /** Selects a seed for standalone planning. Runtime worlds use {@link #attach}. */
    public static synchronized void setWorldSeed(long worldSeed) {
        Session old = activeSession;
        if (old == null || old.seed != worldSeed) {
            replaceSession(new Session(worldSeed, null, null, null, null, null));
        }
    }

    public static long getWorldSeed() {
        Session session = activeSession;
        return session == null ? 0L : session.seed;
    }

    /**
     * An immutable generation prerequisite tied to its original save and cache epoch. This is groundwork for
     * first-consumer integration; optional render/lookahead queries continue using {@link #getIfReady}.
     */
    public static final class GenerationSnapshot {
        private final Session session;
        private final TilePlan tile;

        private GenerationSnapshot(Session session, TilePlan tile) {
            this.session = session;
            this.tile = tile;
        }

        public long worldSeed() { return session.seed; }
        public boolean isCurrent() { return session == activeSession && !session.closing; }

        /** A stale prerequisite cannot silently become a query against the next world's plan. */
        public TilePlan tile() {
            if (!isCurrent()) throw new java.util.concurrent.CancellationException("Stale tile generation prerequisite");
            return tile;
        }
    }

    /**
     * Required worker-side tile resolution: never returns pending-as-absent and never joins a queued planner job.
     * A cold tile is loaded from immutable saved bytes or computed directly on this caller. A duplicate pure
     * computation is intentional when the background pool is occupied: this avoids a worldgen/pool wait cycle.
     * Publication still uses the original session and owning-thread SavedData queue.
     *
     * <p>Not installed as a default lookup: all first consumers, density memoisation and locate preflight must be
     * integrated together. Calling on the owning server thread fails fast, even for a resident tile; this API
     * must not introduce an unpredictable cold solve into a tick or a player-facing exception path.</p>
     */
    public static GenerationSnapshot requireTileForWorker(long worldSeed, int tileX, int tileZ) {
        Session session = activeSession;
        if (session == null || session.closing || session.seed != worldSeed) {
            throw new java.util.concurrent.CancellationException("Missing or stale tile generation session");
        }
        if (session.ownerThread == null) throw new IllegalStateException("Tile generation prerequisite has no owner-thread binding");
        if (session.ownerThread.getAsBoolean()) throw new IllegalStateException("Required tile resolution must run on a worldgen worker");
        TilePlan tile = getOrCompute(session, tileX, tileZ);
        GenerationSnapshot snapshot = new GenerationSnapshot(session, tile);
        snapshot.tile(); // Completion may have raced with detach while reading immutable saved bytes.
        return snapshot;
    }

    /** Atomically attaches standalone test/tool callbacks to a particular owner. */
    public static synchronized void attach(long seed, Object owner, PersistentLoader loader,
                                           BiConsumer<Long, TilePlan> sink) {
        attachQueued(seed, owner, loader, guardedSink(sink), null);
    }

    /** Runtime persistence receives its immutable token before serialization, never the next world's callbacks. */
    static synchronized void attachQueued(long seed, Object owner, PersistentLoader loader,
                                           SessionSink sink, Runnable beforeDetach) {
        attachQueued(seed, owner, loader, sink, beforeDetach, null);
    }

    static synchronized void attachQueued(long seed, Object owner, PersistentLoader loader,
                                           SessionSink sink, Runnable beforeDetach, BooleanSupplier ownerThread) {
        if (owner == null) throw new IllegalArgumentException("Missing tile-plan save owner");
        Session old = activeSession;
        if (old != null && !old.closing && old.owner == owner && old.seed == seed) return;
        replaceSession(new Session(seed, owner, loader, sink, beforeDetach, ownerThread));
    }

    private static SessionSink guardedSink(BiConsumer<Long, TilePlan> sink) {
        return sink == null ? null : (token, key, plan) -> publish(token, () -> sink.accept(key, plan));
    }

    /** Installs standalone test/tool persistence callbacks as a fresh session. */
    public static synchronized void setPersistence(PersistentLoader loader, BiConsumer<Long, TilePlan> sink) {
        replaceSession(new Session(getWorldSeed(), null, loader, guardedSink(sink), null, null));
    }

    static synchronized Object tokenFor(Object owner) {
        Session session = activeSession;
        return session != null && !session.closing && session.owner == owner ? session : null;
    }

    /** Queue insertion and stop share one short lock. No serialization/server waits happen under it. */
    static synchronized boolean enqueuePublication(Object token, Runnable enqueue) {
        Session session = activeSession;
        if (session == null || session != token || session.closing) return false;
        enqueue.run();
        return true;
    }

    /** Only owning-thread queue drains call this; closing still permits bytes already accepted before stop. */
    static synchronized boolean publish(Object token, Runnable publication) {
        if (activeSession == null || activeSession != token) return false;
        publication.run();
        return true;
    }

    private static void replaceSession(Session next) {
        Session old = activeSession;
        activeSession = next;
        if (old != null) {
            old.pending.values().forEach(f -> f.cancel(false));
            old.pending.clear();
            old.cache.clear();
        }
    }

    /** Drain completed owning-thread publications, then invalidate work; never wait for planner workers. */
    public static synchronized void detach() {
        Session session = activeSession;
        if (session == null) return;
        session.closing = true;
        try {
            if (session.beforeDetach != null) session.beforeDetach.run();
        } finally {
            replaceSession(null);
        }
    }

    /** Clears cached plans and invalidates outstanding work without changing the selected save. */
    public static synchronized void clearCache() {
        Session old = activeSession;
        replaceSession(old == null ? null : new Session(old.seed, old.owner, old.loader, old.sink, old.beforeDetach, old.ownerThread));
    }

    public static boolean isCached(int tileX, int tileZ) {
        Session session = activeSession;
        return session != null && session.cache.containsKey(TilePlan.key(tileX, tileZ));
    }

    /**
     * Runtime expansion is dormant: normal queries return no tile, even when historical saved plans are cached.
     * Within an explicit {@link #previewForTools()} scope this retains the old non-blocking tool behavior.
     * If the tile containing {@code (blockX, blockZ)} is cached, returns it immediately; otherwise schedules
     * off-thread planning for the tile and its 4 cardinal neighbours and returns {@code null}.
     */
    public static TilePlan getIfReady(int blockX, int blockZ) {
        if (!com.skycraft.tamriel.terrain.Beyond.ENDLESS_LANDS_ENABLED && TOOL_PREVIEW.get() == 0) return null;
        Session session = activeSession;
        if (session == null || session.closing) {
            return null;
        }
        int tx = TilePlan.tileCoord(blockX);
        int tz = TilePlan.tileCoord(blockZ);
        long k = TilePlan.key(tx, tz);
        TilePlan plan = session.cache.get(k);
        if (plan != null) {
            return plan;
        }
        ensurePlannedAsync(session, tx, tz);
        return null;
    }

    /** Convenience helper returning the cached {@link RoadPlan} at {@code (blockX, blockZ)}, or {@code null}. */
    public static RoadPlan roadPlanAt(int blockX, int blockZ) {
        TilePlan tp = getIfReady(blockX, blockZ);
        return tp != null ? tp.roadPlan() : null;
    }

    /** Convenience helper returning the cached {@link StreetPlan} at {@code (blockX, blockZ)}, or {@code null}. */
    public static StreetPlan streetPlanAt(int blockX, int blockZ) {
        TilePlan tp = getIfReady(blockX, blockZ);
        return tp != null ? tp.streetPlan() : null;
    }

    /** Convenience helper returning the cached {@link ScenePlan} at {@code (blockX, blockZ)}, or {@code null}. */
    public static ScenePlan scenePlanAt(int blockX, int blockZ) {
        TilePlan tp = getIfReady(blockX, blockZ);
        return tp != null ? tp.scenePlan() : null;
    }

    /**
     * Computes the exact road bed height at {@code (blockX, blockZ)} from a cached tile plan without touching
     * the authored {@link PlanGround} singleton cache.
     */
    public static double bedHeightAt(double x, double z) {
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        RoadPlan rp = roadPlanAt(ix, iz);
        if (rp == null) return Double.NaN;
        RoadPlan.Hit hit = HIT_SCRATCH.get();
        if (!rp.at(x, z, hit)) return Double.NaN;
        if (hit.distance > hit.halfWidth + 1.0f) return Double.NaN;
        return hit.height;
    }

    /**
     * Schedules asynchronous off-thread planning for {@code (tileX, tileZ)} and its 4 cardinal neighbours.
     * Returns immediately (`0 ms` server stall) with the future for the centre tile.
     */
    public static synchronized CompletableFuture<TilePlan> ensurePlannedAsync(long worldSeed, int tileX, int tileZ) {
        Session session = activeSession;
        if (session == null) {
            setWorldSeed(worldSeed);
            session = activeSession;
        }
        if (session.seed != worldSeed) return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("Stale tile seed"));
        return ensurePlannedAsync(session, tileX, tileZ);
    }

    private static CompletableFuture<TilePlan> ensurePlannedAsync(Session session, int tileX, int tileZ) {
        CompletableFuture<TilePlan> center = submitSingleAsync(session, tileX, tileZ);
        submitSingleAsync(session, tileX - 1, tileZ);
        submitSingleAsync(session, tileX + 1, tileZ);
        submitSingleAsync(session, tileX, tileZ - 1);
        submitSingleAsync(session, tileX, tileZ + 1);
        return center;
    }

    private static synchronized CompletableFuture<TilePlan> submitSingleAsync(Session session, int tileX, int tileZ) {
        if (session != activeSession || session.closing) return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("Stale tile session"));
        if (isEntirelyInsideTamriel(tileX, tileZ)) return CompletableFuture.completedFuture(null);
        long key = TilePlan.key(tileX, tileZ);
        TilePlan existing = session.cache.get(key);
        if (existing != null) return CompletableFuture.completedFuture(existing);
        CompletableFuture<TilePlan> pending = session.pending.get(key);
        if (pending != null) return pending;
        CompletableFuture<TilePlan> future = new CompletableFuture<>();
        session.pending.put(key, future);
        POOL.execute(() -> {
            try {
                if (!future.isCancelled()) future.complete(getOrCompute(session, tileX, tileZ));
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            } finally {
                session.pending.remove(key, future);
            }
        });
        return future;
    }

    /** Synchronous planning is for explicit developer tools, never a server-thread generation prerequisite. */
    public static TilePlan planSync(long worldSeed, int tileX, int tileZ) {
        Session session;
        synchronized (TilePlanner.class) {
            setWorldSeed(worldSeed);
            session = activeSession;
        }
        if (isEntirelyInsideTamriel(tileX, tileZ)) return getOrCompute(session, tileX, tileZ);
        return submitSingleAsync(session, tileX, tileZ).join();
    }

    private static TilePlan getOrCompute(Session session, int tileX, int tileZ) {
        if (session != activeSession || session.closing) throw new java.util.concurrent.CancellationException("Stale tile session");
        long key = TilePlan.key(tileX, tileZ);
        TilePlan existing = session.cache.get(key);
        if (existing != null) return existing;
        TilePlan loaded = session.loader == null ? null : session.loader.load(session.seed, tileX, tileZ);
        if (session != activeSession || session.closing) throw new java.util.concurrent.CancellationException("Stale tile session");
        long start = System.nanoTime();
        TilePlan built = loaded == null ? buildTilePlan(session.seed, tileX, tileZ) : loaded;
        if (built.worldSeed() != session.seed || built.tileX() != tileX || built.tileZ() != tileZ) {
            throw new IllegalStateException("Persistent tile identity mismatch");
        }
        // Runtime sinks serialize without holding the session lock, then enqueue with their original token.
        if (session != activeSession || session.closing) throw new java.util.concurrent.CancellationException("Stale tile session");
        if (loaded == null && session.sink != null) session.sink.accept(session, key, built);
        synchronized (TilePlanner.class) {
            if (session != activeSession || session.closing) throw new java.util.concurrent.CancellationException("Stale tile session");
            session.cache.put(key, built);
        }
        if (loaded == null) Skycraft.LOGGER.debug("[tileplan] planned tile ({}, {}) in {} ms: {} places, {} segments, {} scenes",
                tileX, tileZ, (System.nanoTime() - start) / 1_000_000L,
                built.places().size(), built.roadPlan().segments(), built.scenePlan().scenes());
        return built;
    }

    /** True only if all 4 corners of the tile lie strictly inside the authored Tamriel raster. */
    public static boolean isEntirelyInsideTamriel(int tileX, int tileZ) {
        int x0 = tileX << TilePlan.TILE_SHIFT;
        int z0 = tileZ << TilePlan.TILE_SHIFT;
        int x1 = x0 + TilePlan.TILE_SIZE - 1;
        int z1 = z0 + TilePlan.TILE_SIZE - 1;
        return TamrielMap.inTamriel(x0, z0)
                && TamrielMap.inTamriel(x1, z0)
                && TamrielMap.inTamriel(x0, z1)
                && TamrielMap.inTamriel(x1, z1);
    }

    // ------------------------------------------------------------------ deterministic tile builder

    public static TilePlan buildTilePlan(long worldSeed, int tileX, int tileZ) {
        TileSheetGenerator.TileSheet sheet = TileSheetGenerator.generate(worldSeed, tileX, tileZ);

        EnumMap<TilePlan.Edge, List<TilePlan.EdgePoint>> edgePoints = new EnumMap<>(TilePlan.Edge.class);
        for (TilePlan.Edge edge : TilePlan.Edge.values()) {
            edgePoints.put(edge, computeEdgePoints(worldSeed, tileX, tileZ, edge));
        }

        List<TilePlan.EdgePoint> authoredSeam = computeAuthoredSeamPoints(tileX, tileZ);
        List<TilePlan.RiverSegment> rivers = buildRivers(worldSeed, tileX, tileZ, edgePoints);
        List<TilePlan.TilePlace> places = buildPlaces(worldSeed, tileX, tileZ, sheet, rivers);

        RoadPlan roadPlan = buildRoadPlan(worldSeed, tileX, tileZ, edgePoints, authoredSeam, places, rivers);
        StreetPlan streetPlan = buildStreetPlan(worldSeed, tileX, tileZ, places, roadPlan);
        ScenePlan scenePlan = buildScenePlan(worldSeed, tileX, tileZ, sheet, places, roadPlan);

        return new TilePlan(
                worldSeed,
                tileX,
                tileZ,
                sheet,
                edgePoints,
                authoredSeam,
                places,
                rivers,
                roadPlan,
                streetPlan,
                scenePlan
        );
    }

    /**
     * Pure symmetric function computing the shared boundary {@link TilePlan.EdgePoint}s on {@code edge} of
     * {@code (tileX, tileZ)}.
     *
     * <p>Because {@code EAST} of {@code (tx, tz)} and {@code WEST} of {@code (tx + 1, tz)} map to the exact same
     * canonical border {@code (horizontal=false, lineX=(tx+1)<<12, spanIndex=tz)}, and {@code SOUTH} of
     * {@code (tx, tz)} and {@code NORTH} of {@code (tx, tz + 1)} map to {@code (horizontal=true, lineZ=(tz+1)<<12, spanIndex=tx)},
     * adjacent tiles always agree 100% on shared boundary positions, heights, and road/river types.</p>
     */
    public static List<TilePlan.EdgePoint> computeEdgePoints(long worldSeed, int tileX, int tileZ, TilePlan.Edge edge) {
        boolean horizontal = (edge == TilePlan.Edge.NORTH || edge == TilePlan.Edge.SOUTH);
        int lineCoord = switch (edge) {
            case NORTH -> tileZ << TilePlan.TILE_SHIFT;
            case SOUTH -> (tileZ + 1) << TilePlan.TILE_SHIFT;
            case WEST -> tileX << TilePlan.TILE_SHIFT;
            case EAST -> (tileX + 1) << TilePlan.TILE_SHIFT;
        };
        int spanIndex = horizontal ? tileX : tileZ;
        return computeCanonicalBorder(worldSeed, horizontal, lineCoord, spanIndex);
    }

    private static List<TilePlan.EdgePoint> computeCanonicalBorder(
            long worldSeed,
            boolean horizontal,
            int lineCoord,
            int spanIndex
    ) {
        List<TilePlan.EdgePoint> out = new ArrayList<>(3);
        int spanStart = spanIndex << TilePlan.TILE_SHIFT;

        // First: if this border coincides with an authored map boundary (e.g. MAX_Z = 16384), include any authored
        // road vertices that terminate on or cross this boundary segment.
        RoadPlan authored = RoadPlan.get();
        if (authored != null && authored.vertices() > 0) {
            for (int s = 0; s < authored.segments(); s++) {
                for (int v = authored.segStart[s]; v < authored.segEnd[s]; v++) {
                    int vx = Math.round(authored.vx[v]);
                    int vz = Math.round(authored.vz[v]);
                    int distToLine = horizontal ? Math.abs(vz - lineCoord) : Math.abs(vx - lineCoord);
                    int along = horizontal ? vx : vz;
                    if (distToLine <= 24 && along >= spanStart + 64 && along <= spanStart + TilePlan.TILE_SIZE - 64) {
                        int px = horizontal ? vx : lineCoord;
                        int pz = horizontal ? lineCoord : vz;
                        if (isFarFromExisting(out, px, pz, 128)) {
                            out.add(new TilePlan.EdgePoint(px, pz, quant16(authored.vh[v]), authored.segKind[s], false));
                        }
                        break;
                    }
                }
            }
        }

        long borderSeed = TileSheetGenerator.mix(worldSeed
                ^ (horizontal ? 0x517CC1B727220A95L : 0x9E3779B97F4A7C15L)
                ^ ((long) lineCoord * 0xBF58476D1CE4E5B9L)
                ^ ((long) spanIndex * 0x94D049BB133111EBL));

        MapSample col = new MapSample();
        int bestRoadAlong1 = -1;
        float bestRoadScore1 = Float.MAX_VALUE;
        float bestRoadHeight1 = Float.NaN;

        int bestRoadAlong2 = -1;
        float bestRoadScore2 = Float.MAX_VALUE;
        float bestRoadHeight2 = Float.NaN;

        int bestRiverAlong = -1;
        float bestRiverElev = Float.MAX_VALUE;

        // Sample every 64 blocks along the interior [512 .. 3584] of the 4,096-block border segment.
        for (int offset = 512; offset <= TilePlan.TILE_SIZE - 512; offset += 64) {
            int along = spanStart + offset;
            int x = horizontal ? along : lineCoord;
            int z = horizontal ? lineCoord : along;
            if (TamrielMap.inTamriel(x, z)) {
                continue;
            }
            TamrielMap.sample(x, z, col);
            if (col.coastDistance > 20f && col.elevation >= TamrielMap.SEA_LEVEL + 2f) {
                float jitter = ((TileSheetGenerator.mix(borderSeed ^ offset) & 0xFFFFL) / 65535.0f) * 6.0f;
                float targetElev = TamrielMap.SEA_LEVEL + 18f;
                float score = Math.abs(col.elevation - targetElev) + col.roughness * 2.5f + col.crag * 20f + jitter;
                if (offset <= TilePlan.TILE_SIZE / 2) {
                    if (score < bestRoadScore1) {
                        bestRoadScore1 = score;
                        bestRoadAlong1 = along;
                        bestRoadHeight1 = quant16(col.elevation + 1.0f);
                    }
                } else {
                    if (score < bestRoadScore2) {
                        bestRoadScore2 = score;
                        bestRoadAlong2 = along;
                        bestRoadHeight2 = quant16(col.elevation + 1.0f);
                    }
                }
                if (col.elevation < bestRiverElev && col.elevation <= TamrielMap.SEA_LEVEL + 28f) {
                    bestRiverElev = col.elevation;
                    bestRiverAlong = along;
                }
            }
        }

        if (bestRoadAlong1 != -1) {
            int x = horizontal ? bestRoadAlong1 : lineCoord;
            int z = horizontal ? lineCoord : bestRoadAlong1;
            if (isFarFromExisting(out, x, z, 256)) {
                out.add(new TilePlan.EdgePoint(x, z, bestRoadHeight1, (byte) 1, false));
            }
        }
        if (bestRoadAlong2 != -1 && ((borderSeed & 3L) != 0L || out.isEmpty())) {
            int x = horizontal ? bestRoadAlong2 : lineCoord;
            int z = horizontal ? lineCoord : bestRoadAlong2;
            if (isFarFromExisting(out, x, z, 512)) {
                byte kind = (byte) ((borderSeed & 1L) == 0L ? 1 : 2);
                out.add(new TilePlan.EdgePoint(x, z, bestRoadHeight2, kind, false));
            }
        }
        if (bestRiverAlong != -1) {
            int x = horizontal ? bestRiverAlong : lineCoord;
            int z = horizontal ? lineCoord : bestRiverAlong;
            if (isFarFromExisting(out, x, z, 192)) {
                float rh = quant16(Math.max((float) TamrielMap.SEA_LEVEL, bestRiverElev - 1.0f));
                out.add(new TilePlan.EdgePoint(x, z, rh, (byte) 3, true));
            }
        }

        out.sort(Comparator.comparingInt((TilePlan.EdgePoint p) -> horizontal ? p.x() : p.z()));
        return out;
    }

    /**
     * Finds authored {@link RoadPlan} vertices that approach the Tamriel raster border inside or adjacent to
     * {@code (tileX, tileZ)} so tile roads connect seamlessly to Tamriel's road network even when the raster border
     * falls inside a 4,096-block tile (`MIN_X = -15360`, `MAX_X = 12800`, `MIN_Z = -5120`, `MAX_Z = 16384`).
     */
    public static List<TilePlan.EdgePoint> computeAuthoredSeamPoints(int tileX, int tileZ) {
        List<TilePlan.EdgePoint> seam = new ArrayList<>();
        int minX = (tileX << TilePlan.TILE_SHIFT) - 64;
        int maxX = ((tileX + 1) << TilePlan.TILE_SHIFT) + 64;
        int minZ = (tileZ << TilePlan.TILE_SHIFT) - 64;
        int maxZ = ((tileZ + 1) << TilePlan.TILE_SHIFT) + 64;

        boolean touchesBorder = (minX <= TamrielMap.MIN_X && TamrielMap.MIN_X <= maxX)
                || (minX <= TamrielMap.MAX_X && TamrielMap.MAX_X <= maxX)
                || (minZ <= TamrielMap.MIN_Z && TamrielMap.MIN_Z <= maxZ)
                || (minZ <= TamrielMap.MAX_Z && TamrielMap.MAX_Z <= maxZ);
        if (!touchesBorder) {
            return seam;
        }

        RoadPlan authored = RoadPlan.get();
        if (authored == null || authored.segments() == 0) {
            return seam;
        }

        for (int s = 0; s < authored.segments(); s++) {
            int bestV = -1;
            int bestBorderDist = Integer.MAX_VALUE;
            for (int v = authored.segStart[s]; v < authored.segEnd[s]; v++) {
                int vx = Math.round(authored.vx[v]);
                int vz = Math.round(authored.vz[v]);
                if (vx < minX || vx > maxX || vz < minZ || vz > maxZ) continue;
                int dWest = Math.abs(vx - TamrielMap.MIN_X);
                int dEast = Math.abs(vx - TamrielMap.MAX_X);
                int dNorth = Math.abs(vz - TamrielMap.MIN_Z);
                int dSouth = Math.abs(vz - TamrielMap.MAX_Z);
                int d = Math.min(Math.min(dWest, dEast), Math.min(dNorth, dSouth));
                if (d <= 64 && d < bestBorderDist) {
                    bestBorderDist = d;
                    bestV = v;
                }
            }
            if (bestV != -1) {
                int vx = Math.round(authored.vx[bestV]);
                int vz = Math.round(authored.vz[bestV]);
                if (isFarFromExisting(seam, vx, vz, 64)) {
                    seam.add(new TilePlan.EdgePoint(vx, vz, quant16(authored.vh[bestV]), authored.segKind[s], false));
                }
            }
        }
        seam.sort(Comparator.comparingInt(TilePlan.EdgePoint::x).thenComparingInt(TilePlan.EdgePoint::z));
        return seam;
    }

    private static boolean isFarFromExisting(List<TilePlan.EdgePoint> list, int x, int z, int minDist) {
        long minD2 = (long) minDist * minDist;
        for (TilePlan.EdgePoint p : list) {
            long dx = p.x() - x;
            long dz = p.z() - z;
            if (dx * dx + dz * dz < minD2) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ tile-scale hydrology

    private static List<TilePlan.RiverSegment> buildRivers(
            long worldSeed,
            int tileX,
            int tileZ,
            Map<TilePlan.Edge, List<TilePlan.EdgePoint>> edgePoints
    ) {
        List<TilePlan.RiverSegment> rivers = new ArrayList<>(4);
        int x0 = tileX << TilePlan.TILE_SHIFT;
        int z0 = tileZ << TilePlan.TILE_SHIFT;

        List<TilePlan.EdgePoint> riverEdges = new ArrayList<>(4);
        for (TilePlan.Edge e : TilePlan.Edge.values()) {
            for (TilePlan.EdgePoint p : edgePoints.getOrDefault(e, List.of())) {
                if (p.river()) riverEdges.add(p);
            }
        }

        // Find highest interior basin point and lowest coastal/outfall point.
        MapSample col = new MapSample();
        int highX = x0 + 2048, highZ = z0 + 2048;
        float highElev = -1f;
        int coastX = -1, coastZ = -1;

        for (int gx = 1; gx <= 7; gx++) {
            int sx = x0 + gx * 512;
            for (int gz = 1; gz <= 7; gz++) {
                int sz = z0 + gz * 512;
                if (TamrielMap.inTamriel(sx, sz)) continue;
                TamrielMap.sample(sx, sz, col);
                if (col.coastDistance > 40f && col.elevation > highElev) {
                    highElev = col.elevation;
                    highX = sx;
                    highZ = sz;
                }
                if (col.coastDistance >= -16f && col.coastDistance <= 24f && coastX == -1) {
                    coastX = sx;
                    coastZ = sz;
                }
            }
        }

        if (highElev < TamrielMap.SEA_LEVEL + 6f) {
            return rivers;
        }

        float sourceH = Math.max(TamrielMap.SEA_LEVEL + 4f, highElev - 4f);
        if (!riverEdges.isEmpty()) {
            for (int i = 0; i < Math.min(2, riverEdges.size()); i++) {
                TilePlan.EdgePoint ep = riverEdges.get(i);
                float hStart = Math.max(sourceH, ep.height() + 2f);
                rivers.add(new TilePlan.RiverSegment(highX, highZ, hStart, ep.x(), ep.z(), ep.height(), 4.5f));
            }
        } else if (coastX != -1) {
            rivers.add(new TilePlan.RiverSegment(highX, highZ, sourceH, coastX, coastZ, TamrielMap.SEA_LEVEL, 4.5f));
        }
        return rivers;
    }

    // ------------------------------------------------------------------ procedural places (half Tamriel density)

    private static List<TilePlan.TilePlace> buildPlaces(
            long worldSeed,
            int tileX,
            int tileZ,
            TileSheetGenerator.TileSheet sheet,
            List<TilePlan.RiverSegment> rivers
    ) {
        List<TilePlan.TilePlace> places = new ArrayList<>(8);
        if (sheet.landFraction() < 0.04f) {
            return places;
        }

        long seed = TileSheetGenerator.mix(worldSeed ^ ((long) tileX * 0x517CC1B727220A95L) ^ ((long) tileZ * 0x9E3779B97F4A7C15L));
        int x0 = tileX << TilePlan.TILE_SHIFT;
        int z0 = tileZ << TilePlan.TILE_SHIFT;

        record Candidate(int x, int z, float elev, float slope, float coastDist) {
        }
        List<Candidate> dryCandidates = new ArrayList<>(36);
        MapSample c0 = new MapSample();
        MapSample cx = new MapSample();
        MapSample cz = new MapSample();

        for (int gx = 0; gx < 6; gx++) {
            for (int gz = 0; gz < 6; gz++) {
                long cellHash = TileSheetGenerator.mix(seed ^ (gx * 31L + gz));
                int jitterX = (int) Math.floorMod(cellHash, 240) - 120;
                int jitterZ = (int) Math.floorMod(cellHash >>> 16, 240) - 120;
                int px = x0 + 480 + gx * 620 + jitterX;
                int pz = z0 + 480 + gz * 620 + jitterZ;
                if (TamrielMap.inTamriel(px, pz)) continue;
                TamrielMap.sample(px, pz, c0);
                if (c0.coastDistance < 32f || c0.elevation < TamrielMap.SEA_LEVEL + 2f) continue;
                if (nearRiver(rivers, px, pz, 24f)) continue;
                TamrielMap.sample(px + 16, pz, cx);
                TamrielMap.sample(px, pz + 16, cz);
                float slope = (Math.abs(cx.elevation - c0.elevation) + Math.abs(cz.elevation - c0.elevation)) / 16f;
                dryCandidates.add(new Candidate(px, pz, c0.elevation, slope, c0.coastDistance));
            }
        }

        if (dryCandidates.isEmpty()) {
            return places;
        }

        // Sort flattest well-drained benches first for settlements, steeper crags later for delves/shrines.
        dryCandidates.sort(Comparator.comparingDouble(c -> c.slope * 20.0 + Math.abs(c.elev - (TamrielMap.SEA_LEVEL + 20.0))));

        int maxSettlements = sheet.landFraction() >= 0.35f ? 2 : 1;
        int maxDelves = sheet.landFraction() >= 0.45f ? 2 : 1;
        int maxShrines = sheet.landFraction() >= 0.30f ? 2 : 1;

        int settlementsAdded = 0;
        for (Candidate c : dryCandidates) {
            if (settlementsAdded >= maxSettlements) break;
            if (!farFromPlaces(places, c.x, c.z, 700)) continue;
            PlaceType type = (settlementsAdded == 0) ? PlaceType.VILLAGE : PlaceType.FARM;
            int radius = (type == PlaceType.VILLAGE) ? 28 : 20;
            String id = sheet.id() + "_settlement_" + settlementsAdded;
            String name = placeName(seed, settlementsAdded, SETTLEMENT_SUFFIXES);
            float pad = quant16(Math.max(TamrielMap.SEA_LEVEL + 2f, c.elev));
            List<String> why = List.of(
                    sheet.fieldId("L8", "hearth_compounds"),
                    sheet.fieldId("L5", "frontier_artisans")
            );
            places.add(new TilePlan.TilePlace(id, name, type, c.x, c.z, radius, pad, why));
            settlementsAdded++;
        }

        // Sort rougher candidates for delves/ruins
        List<Candidate> delveCandidates = new ArrayList<>(dryCandidates);
        delveCandidates.sort(Comparator.comparingDouble((Candidate c) -> -c.elev - c.slope * 15.0));
        int delvesAdded = 0;
        for (Candidate c : delveCandidates) {
            if (delvesAdded >= maxDelves) break;
            if (!farFromPlaces(places, c.x, c.z, 550)) continue;
            PlaceType type = (delvesAdded % 2 == 0) ? PlaceType.FORT : PlaceType.DUNGEON_FIXED;
            String id = sheet.id() + "_delve_" + delvesAdded;
            String name = placeName(seed ^ 0x12345678L, delvesAdded + 4, DELVE_SUFFIXES);
            float pad = quant16(Math.max(TamrielMap.SEA_LEVEL + 2f, c.elev));
            List<String> why = List.of(
                    sheet.fieldId("L11", "abandoned_watchposts"),
                    sheet.fieldId("L9", "outlaw_redoubts")
            );
            places.add(new TilePlan.TilePlace(id, name, type, c.x, c.z, 16, pad, why));
            delvesAdded++;
        }

        // Shrines & camps
        int shrinesAdded = 0;
        for (int i = dryCandidates.size() - 1; i >= 0 && shrinesAdded < maxShrines; i--) {
            Candidate c = dryCandidates.get(i);
            if (!farFromPlaces(places, c.x, c.z, 450)) continue;
            PlaceType type = (shrinesAdded == 0) ? PlaceType.STANDING_STONE : PlaceType.LANDMARK;
            String id = sheet.id() + "_site_" + shrinesAdded;
            String name = placeName(seed ^ 0x87654321L, shrinesAdded + 8, SHRINE_SUFFIXES);
            float pad = quant16(Math.max(TamrielMap.SEA_LEVEL + 2f, c.elev));
            List<String> why = (type == PlaceType.STANDING_STONE)
                    ? List.of(sheet.fieldId("L8", "wayshrine_precincts"), sheet.fieldId("L11", "expedition_cairns"))
                    : List.of(sheet.fieldId("L7", "charter_wardens"), sheet.fieldId("L5", "quarry_and_timber"));
            places.add(new TilePlan.TilePlace(id, name, type, c.x, c.z, 12, pad, why));
            shrinesAdded++;
        }

        return places;
    }

    private static boolean nearRiver(List<TilePlan.RiverSegment> rivers, int x, int z, float minDist) {
        for (TilePlan.RiverSegment r : rivers) {
            float dx = r.x1() - r.x0();
            float dz = r.z1() - r.z0();
            float len2 = dx * dx + dz * dz;
            float t = len2 < 1e-6f ? 0f : ((x - r.x0()) * dx + (z - r.z0()) * dz) / len2;
            if (t < 0f) t = 0f;
            else if (t > 1f) t = 1f;
            float qx = r.x0() + dx * t - x;
            float qz = r.z0() + dz * t - z;
            if (qx * qx + qz * qz < (minDist + r.halfWidth()) * (minDist + r.halfWidth())) return true;
        }
        return false;
    }

    private static boolean farFromPlaces(List<TilePlan.TilePlace> places, int x, int z, int minDist) {
        long minD2 = (long) minDist * minDist;
        for (TilePlan.TilePlace p : places) {
            long dx = p.x() - x;
            long dz = p.z() - z;
            if (dx * dx + dz * dz < minD2) return false;
        }
        return true;
    }

    private static String placeName(long seed, int idx, String[] suffixes) {
        long h = TileSheetGenerator.mix(seed + idx * 0x9E3779B97F4A7C15L);
        String prefix = PLACE_PREFIXES[(int) Math.floorMod(h, PLACE_PREFIXES.length)];
        String suffix = suffixes[(int) Math.floorMod(h >>> 8, suffixes.length)];
        return suffix.startsWith(" ") || Character.isUpperCase(suffix.charAt(0))
                ? prefix + " " + suffix
                : prefix + suffix;
    }

    // ------------------------------------------------------------------ RoadPlan builder

    private record AnchorNode(String id, int x, int z, float h, byte preferredKind, boolean isSite) {
    }

    private record RawSpan(byte type, int fromLocal, int toLocal, float deck, float[] piers) {
    }

    private record RawSegment(
            int linkIdx,
            byte kind,
            float halfWidth,
            int joinA,
            int joinB,
            float[] vx,
            float[] vz,
            float[] vh,
            float[] vg,
            float[] vw,
            byte[] vspan,
            List<RawSpan> spans
    ) {
    }

    private static RoadPlan buildRoadPlan(
            long worldSeed,
            int tileX,
            int tileZ,
            Map<TilePlan.Edge, List<TilePlan.EdgePoint>> edgePoints,
            List<TilePlan.EdgePoint> authoredSeam,
            List<TilePlan.TilePlace> places,
            List<TilePlan.RiverSegment> rivers
    ) {
        List<AnchorNode> hubs = new ArrayList<>();
        List<AnchorNode> spokes = new ArrayList<>();

        for (TilePlan.TilePlace p : places) {
            if (p.type().isSettlement()) {
                hubs.add(new AnchorNode(p.id(), p.x(), p.z(), p.padHeight(), (byte) 1, true));
            } else {
                byte k = (p.type() == PlaceType.LANDMARK || p.type() == PlaceType.STANDING_STONE || p.type() == PlaceType.DAEDRIC_SHRINE) ? (byte) 2 : (byte) 3;
                spokes.add(new AnchorNode(p.id(), p.x(), p.z(), p.padHeight(), k, true));
            }
        }

        List<AnchorNode> borderNodes = new ArrayList<>();
        for (TilePlan.Edge e : TilePlan.Edge.values()) {
            int idx = 0;
            for (TilePlan.EdgePoint ep : edgePoints.getOrDefault(e, List.of())) {
                if (!ep.river()) {
                    borderNodes.add(new AnchorNode(
                            "edge_" + e.name().toLowerCase(Locale.ROOT) + "_" + (idx++),
                            ep.x(), ep.z(), ep.height(), ep.kind(), false));
                }
            }
        }
        int seamIdx = 0;
        for (TilePlan.EdgePoint sp : authoredSeam) {
            if (!sp.river()) {
                borderNodes.add(new AnchorNode(
                        "seam_" + (seamIdx++),
                        sp.x(), sp.z(), sp.height(), sp.kind(), false));
            }
        }

        record PlannedLink(AnchorNode a, AnchorNode b, byte kind, String name) {
        }
        List<PlannedLink> links = new ArrayList<>();

        if (!hubs.isEmpty()) {
            // Connect hubs to each other
            for (int i = 0; i + 1 < hubs.size(); i++) {
                AnchorNode a = hubs.get(i);
                AnchorNode b = hubs.get(i + 1);
                links.add(new PlannedLink(a, b, (byte) 1, a.id + "_to_" + b.id));
            }
            // Connect every border/seam road point to its nearest settlement hub
            for (AnchorNode bn : borderNodes) {
                AnchorNode nearest = findNearest(bn, hubs);
                if (nearest != null) {
                    links.add(new PlannedLink(bn, nearest, bn.preferredKind, bn.id + "_to_" + nearest.id));
                }
            }
            // Connect every spoke (delve, camp, shrine) to its nearest hub
            for (AnchorNode sp : spokes) {
                AnchorNode nearest = findNearest(sp, hubs);
                if (nearest != null) {
                    links.add(new PlannedLink(nearest, sp, sp.preferredKind, nearest.id + "_to_" + sp.id));
                }
            }
        } else {
            // No settlement hubs: connect border nodes across the tile or to spokes
            List<AnchorNode> all = new ArrayList<>(borderNodes);
            all.addAll(spokes);
            for (int i = 0; i + 1 < all.size(); i++) {
                AnchorNode a = all.get(i);
                AnchorNode b = all.get(i + 1);
                byte kind = (byte) Math.max(a.preferredKind, b.preferredKind);
                links.add(new PlannedLink(a, b, kind, a.id + "_to_" + b.id));
            }
        }

        List<RawSegment> segments = new ArrayList<>(links.size());
        int[] linkBridges = new int[links.size()];

        for (int l = 0; l < links.size(); l++) {
            PlannedLink pl = links.get(l);
            RawSegment seg = routeSegment(worldSeed, l, pl.a(), pl.b(), pl.kind(), rivers);
            segments.add(seg);
            int bridges = 0;
            for (RawSpan sp : seg.spans()) {
                if (sp.type() == RoadPlan.BRIDGE) bridges++;
            }
            linkBridges[l] = bridges;
        }

        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(32 * 1024);
            try (DataOutputStream out = new DataOutputStream(bo)) {
                out.writeInt(RoadPlan.MAGIC);
                out.writeInt(RoadPlan.VERSION);

                out.writeInt(places.size());
                for (TilePlan.TilePlace p : places) {
                    out.writeUTF(p.id());
                    out.writeByte(p.type().ordinal());
                    out.writeInt(p.x());
                    out.writeInt(p.z());
                    out.writeShort(p.radius());
                    out.writeShort(q16(p.padHeight()));
                }

                out.writeInt(links.size());
                for (int i = 0; i < links.size(); i++) {
                    PlannedLink pl = links.get(i);
                    out.writeUTF(pl.a().id());
                    out.writeUTF(pl.b().id());
                    out.writeUTF(pl.name());
                    out.writeByte(pl.kind());
                    out.writeByte(RoadPlan.LINK_OK);
                    out.writeShort(linkBridges[i]);
                }

                out.writeInt(segments.size());
                for (RawSegment seg : segments) {
                    out.writeInt(seg.linkIdx());
                    out.writeByte(seg.kind());
                    out.writeByte(Math.round(seg.halfWidth() * 4f));
                    out.writeInt(seg.joinA());
                    out.writeInt(seg.joinB());
                    int n = seg.vx().length;
                    out.writeInt(n);
                    int lx = 0, lz = 0;
                    for (int i = 0; i < n; i++) {
                        int qx = Math.round(seg.vx()[i] * 16f);
                        int qz = Math.round(seg.vz()[i] * 16f);
                        if (i == 0) {
                            out.writeInt(qx);
                            out.writeInt(qz);
                        } else {
                            out.writeShort(qx - lx);
                            out.writeShort(qz - lz);
                        }
                        lx = qx;
                        lz = qz;
                    }
                    for (int i = 0; i < n; i++) out.writeShort(q16(seg.vh()[i]));
                    for (int i = 0; i < n; i++) out.writeShort(q16(seg.vg()[i]));
                    for (int i = 0; i < n; i++) {
                        out.writeShort(Float.isNaN(seg.vw()[i]) ? Short.MIN_VALUE : q16(seg.vw()[i]));
                    }
                    for (int i = 0; i < n; i++) out.writeByte(seg.vspan()[i]);
                    List<RawSpan> spans = seg.spans();
                    out.writeShort(spans.size());
                    for (RawSpan sp : spans) {
                        out.writeByte(sp.type());
                        out.writeInt(sp.fromLocal());
                        out.writeInt(sp.toLocal());
                        out.writeShort(Float.isNaN(sp.deck()) ? Short.MIN_VALUE : q16(sp.deck()));
                        out.writeShort(sp.piers().length);
                        for (float pier : sp.piers()) {
                            out.writeShort(q16(pier));
                        }
                    }
                }
            }
            return RoadPlan.read(bo.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialise procedural tile RoadPlan", e);
        }
    }

    private static AnchorNode findNearest(AnchorNode from, List<AnchorNode> candidates) {
        AnchorNode best = null;
        long bestD2 = Long.MAX_VALUE;
        for (AnchorNode c : candidates) {
            long dx = c.x() - from.x();
            long dz = c.z() - from.z();
            long d2 = dx * dx + dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = c;
            }
        }
        return best;
    }

    private static RawSegment routeSegment(
            long worldSeed,
            int linkIdx,
            AnchorNode a,
            AnchorNode b,
            byte kind,
            List<TilePlan.RiverSegment> rivers
    ) {
        float dx = b.x() - a.x();
        float dz = b.z() - a.z();
        float chordLen = (float) Math.hypot(dx, dz);
        int steps = Math.max(4, Math.round(chordLen / VERTEX_SPACING));
        int n = steps + 1;

        float[] vx = new float[n];
        float[] vz = new float[n];
        float[] vh = new float[n];
        float[] vg = new float[n];
        float[] vw = new float[n];
        byte[] vspan = new byte[n];

        // Orthogonal unit vector for gentle contour-seeking deflection in the middle of the route
        float nx = chordLen > 1e-4f ? -dz / chordLen : 0f;
        float nz = chordLen > 1e-4f ? dx / chordLen : 0f;
        long routeHash = TileSheetGenerator.mix(worldSeed ^ ((long) a.x() * 31 + a.z()) ^ ((long) b.x() * 131 + b.z()));
        float maxSway = Math.min(36f, chordLen * 0.06f) * (((routeHash & 1L) == 0L) ? 1f : -1f);

        MapSample col = new MapSample();
        for (int i = 0; i < n; i++) {
            float t = i / (float) steps;
            float sway = (float) Math.sin(t * Math.PI) * maxSway;
            // Quantize to 1/16th block
            vx[i] = quant16(a.x() + dx * t + nx * sway);
            vz[i] = quant16(a.z() + dz * t + nz * sway);
            if (i == 0) {
                vx[i] = a.x();
                vz[i] = a.z();
            } else if (i == n - 1) {
                vx[i] = b.x();
                vz[i] = b.z();
            }

            int ix = Math.round(vx[i]);
            int iz = Math.round(vz[i]);
            TamrielMap.sample(ix, iz, col);
            sampleRivers(rivers, ix, iz, col);

            vg[i] = quant16(col.elevation);
            boolean wet = (col.coastDistance <= 2f)
                    || (col.elevation < TamrielMap.SEA_LEVEL + 0.5f)
                    || (col.riverDistance <= col.riverHalfWidth + 3f && !Float.isNaN(col.waterLevel));

            if (wet && i > 0 && i < n - 1) {
                float waterSurf = Float.isNaN(col.waterLevel)
                        ? (float) TamrielMap.SEA_LEVEL
                        : Math.max((float) TamrielMap.SEA_LEVEL, col.waterLevel);
                vw[i] = quant16(waterSurf);
                vspan[i] = RoadPlan.BRIDGE;
                vh[i] = quant16(waterSurf + 3.5f);
            } else {
                vw[i] = Float.NaN;
                vspan[i] = RoadPlan.NORMAL;
                vh[i] = quant16(Math.max((float) TamrielMap.SEA_LEVEL + 1.5f, col.elevation + 0.5f));
            }
        }

        // Pin exact endpoint heights so tile edge crossings and settlement pads have 0 vertical step
        vh[0] = quant16(a.h());
        vh[n - 1] = quant16(b.h());

        // Enforce grade cap via forward + backward relaxation while keeping bridge decks above water
        float maxGrade = switch (kind) {
            case 0, 1 -> 0.115f; // <= 1:8 road grade cap
            case 2 -> 0.185f;    // <= 1:5 lane grade cap
            default -> 0.22f;    // <= 1 block per 4-block vertex step on trails
        };

        for (int pass = 0; pass < 3; pass++) {
            for (int i = 1; i < n; i++) {
                float segDist = (float) Math.hypot(vx[i] - vx[i - 1], vz[i] - vz[i - 1]);
                float maxDelta = Math.max(0.125f, segDist * maxGrade);
                if (vh[i] > vh[i - 1] + maxDelta) vh[i] = vh[i - 1] + maxDelta;
                if (vh[i] < vh[i - 1] - maxDelta) vh[i] = vh[i - 1] - maxDelta;
                if (vspan[i] == RoadPlan.BRIDGE && !Float.isNaN(vw[i])) {
                    vh[i] = Math.max(vh[i], vw[i] + 2.5f);
                }
            }
            vh[n - 1] = quant16(b.h());
            for (int i = n - 2; i >= 0; i--) {
                float segDist = (float) Math.hypot(vx[i + 1] - vx[i], vz[i + 1] - vz[i]);
                float maxDelta = Math.max(0.125f, segDist * maxGrade);
                if (vh[i] > vh[i + 1] + maxDelta) vh[i] = vh[i + 1] + maxDelta;
                if (vh[i] < vh[i + 1] - maxDelta) vh[i] = vh[i + 1] - maxDelta;
                if (vspan[i] == RoadPlan.BRIDGE && !Float.isNaN(vw[i])) {
                    vh[i] = Math.max(vh[i], vw[i] + 2.5f);
                }
            }
            vh[0] = quant16(a.h());
        }

        // Final pass to guarantee endpoints and quantize to 1/16th block without exceeding 1-block drop
        for (int i = 0; i < n; i++) {
            vh[i] = quant16(vh[i]);
        }

        // Build contiguous spans (BRIDGE, CUT, FILL)
        List<RawSpan> spans = new ArrayList<>();
        int i = 0;
        while (i < n) {
            if (vspan[i] == RoadPlan.BRIDGE) {
                int start = i;
                float deck = vh[i];
                float arcLen = 0f;
                while (i + 1 < n && vspan[i + 1] == RoadPlan.BRIDGE) {
                    arcLen += (float) Math.hypot(vx[i + 1] - vx[i], vz[i + 1] - vz[i]);
                    i++;
                    if (vh[i] > deck) deck = vh[i];
                }
                int end = i;
                int nPiers = Math.max(0, (int) (arcLen / 12f));
                float[] piers = new float[nPiers];
                for (int p = 0; p < nPiers; p++) {
                    piers[p] = quant16((p + 1) * (arcLen / (nPiers + 1)));
                }
                spans.add(new RawSpan(RoadPlan.BRIDGE, start, end, quant16(deck), piers));
            } else {
                float diff = vh[i] - vg[i];
                if (diff <= -2.5f) {
                    vspan[i] = RoadPlan.CUT;
                } else if (diff >= 2.5f) {
                    vspan[i] = RoadPlan.FILL;
                }
            }
            i++;
        }

        float halfWidth = switch (kind) {
            case 0 -> 3.5f;
            case 1 -> 2.5f;
            case 2 -> 2.0f;
            default -> 1.25f;
        };
        int joinA = a.isSite() ? RoadPlan.JOIN_SITE : RoadPlan.JOIN_NONE;
        int joinB = b.isSite() ? RoadPlan.JOIN_SITE : RoadPlan.JOIN_NONE;

        return new RawSegment(linkIdx, kind, halfWidth, joinA, joinB, vx, vz, vh, vg, vw, vspan, spans);
    }

    private static void sampleRivers(List<TilePlan.RiverSegment> rivers, int x, int z, MapSample out) {
        for (TilePlan.RiverSegment r : rivers) {
            float dx = r.x1() - r.x0();
            float dz = r.z1() - r.z0();
            float len2 = dx * dx + dz * dz;
            float t = len2 < 1e-6f ? 0f : ((x - r.x0()) * dx + (z - r.z0()) * dz) / len2;
            if (t < 0f) t = 0f;
            else if (t > 1f) t = 1f;
            float qx = r.x0() + dx * t - x;
            float qz = r.z0() + dz * t - z;
            float dist = (float) Math.sqrt(qx * qx + qz * qz);
            if (dist < out.riverDistance) {
                out.riverDistance = dist;
                out.riverHalfWidth = r.halfWidth();
                out.waterLevel = Math.max((float) TamrielMap.SEA_LEVEL, r.h0() + (r.h1() - r.h0()) * t);
            }
        }
    }

    // ------------------------------------------------------------------ StreetPlan builder

    private static StreetPlan buildStreetPlan(
            long worldSeed,
            int tileX,
            int tileZ,
            List<TilePlan.TilePlace> places,
            RoadPlan roadPlan
    ) {
        List<TilePlan.TilePlace> settlements = new ArrayList<>();
        for (TilePlan.TilePlace p : places) {
            if (p.type().isSettlement()) {
                settlements.add(p);
            }
        }

        int ns = settlements.size();
        int[] sNodeOff = new int[ns + 1];
        int[] sEdgeOff = new int[ns + 1];
        int[] sDoorOff = new int[ns + 1];
        int[] sPlotOff = new int[ns + 1];
        int[] sSpecOff = new int[ns + 1];

        List<Float> nx = new ArrayList<>(), nz = new ArrayList<>(), nh = new ArrayList<>();
        List<Byte> nClass = new ArrayList<>();
        List<Integer> nJoinSeg = new ArrayList<>(), nJoinVert = new ArrayList<>();

        List<Integer> ea = new ArrayList<>(), eb = new ArrayList<>();
        List<Float> ew = new ArrayList<>();
        List<Byte> eClass = new ArrayList<>(), eSurf = new ArrayList<>();

        List<Integer> dSpec = new ArrayList<>(), dx = new ArrayList<>(), dy = new ArrayList<>(), dz = new ArrayList<>(), dNode = new ArrayList<>();
        List<Byte> dFacing = new ArrayList<>();
        List<Boolean> dReach = new ArrayList<>();

        List<Integer> pSpec = new ArrayList<>(), pMinX = new ArrayList<>(), pMinZ = new ArrayList<>(), pMaxX = new ArrayList<>(), pMaxZ = new ArrayList<>(), pPad = new ArrayList<>();
        List<Byte> pKind = new ArrayList<>();

        List<Integer> specInts = new ArrayList<>();
        List<String> specNames = new ArrayList<>();

        for (int s = 0; s < ns; s++) {
            TilePlan.TilePlace settlement = settlements.get(s);
            sNodeOff[s] = nx.size();
            sEdgeOff[s] = ea.size();
            sDoorOff[s] = dSpec.size();
            sPlotOff[s] = pSpec.size();
            sSpecOff[s] = specNames.size();

            int cx = settlement.x();
            int cz = settlement.z();
            float padH = settlement.padHeight();
            int floorY = Math.round(padH);

            // Find road vertex joining this settlement if present
            int joinSeg = -1, joinVert = -1;
            float gateX = cx, gateZ = cz - settlement.radius();
            float gateH = padH;
            float bestD2 = Float.MAX_VALUE;
            for (int seg = 0; seg < roadPlan.segments(); seg++) {
                int v0 = roadPlan.segStart[seg];
                int v1 = roadPlan.segEnd[seg] - 1;
                for (int v : new int[]{v0, v1}) {
                    float rdx = roadPlan.vx[v] - cx;
                    float rdz = roadPlan.vz[v] - cz;
                    float d2 = rdx * rdx + rdz * rdz;
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        joinSeg = seg;
                        joinVert = v;
                        gateX = roadPlan.vx[v];
                        gateZ = roadPlan.vz[v];
                        gateH = roadPlan.vh[v];
                    }
                }
            }

            // Node 0: central plaza
            int nPlaza = nx.size();
            nx.add((float) cx);
            nz.add((float) cz);
            nh.add(padH);
            nClass.add(StreetPlan.N_PLAZA);
            nJoinSeg.add(-1);
            nJoinVert.add(-1);

            // Node 1: gate node joining RoadPlan
            int nGate = nx.size();
            nx.add(gateX);
            nz.add(gateZ);
            nh.add(gateH);
            nClass.add(StreetPlan.N_GATE);
            nJoinSeg.add(joinSeg);
            nJoinVert.add(joinVert);

            // Edge Plaza <-> Gate (MAIN)
            ea.add(nGate);
            eb.add(nPlaza);
            ew.add(4.0f);
            eClass.add(StreetPlan.MAIN);
            eSurf.add(StreetPlan.PAVED);

            // 3 building plots around the plaza with street & door nodes
            int[][] offsets = {{-14, -6}, {14, -6}, {0, 14}};
            Direction[] facings = {Direction.EAST, Direction.WEST, Direction.NORTH};
            for (int b = 0; b < offsets.length; b++) {
                int bx = cx + offsets[b][0];
                int bz = cz + offsets[b][1];
                Direction facing = facings[b];
                int doorX = bx + facing.getStepX() * 4;
                int doorZ = bz + facing.getStepZ() * 4;
                int streetX = bx + facing.getStepX() * 7;
                int streetZ = bz + facing.getStepZ() * 7;

                int nStreet = nx.size();
                nx.add((float) streetX);
                nz.add((float) streetZ);
                nh.add(padH);
                nClass.add(StreetPlan.N_STREET);
                nJoinSeg.add(-1);
                nJoinVert.add(-1);

                int nDoor = nx.size();
                nx.add((float) doorX);
                nz.add((float) doorZ);
                nh.add(padH);
                nClass.add(StreetPlan.N_DOOR);
                nJoinSeg.add(-1);
                nJoinVert.add(-1);

                // Edge Plaza -> Street node (LANE)
                ea.add(nPlaza);
                eb.add(nStreet);
                ew.add(3.0f);
                eClass.add(StreetPlan.LANE);
                eSurf.add(StreetPlan.BEATEN);

                // Edge Street node -> Door node (PATH)
                ea.add(nStreet);
                eb.add(nDoor);
                ew.add(1.5f);
                eClass.add(StreetPlan.PATH);
                eSurf.add(StreetPlan.STONE);

                int specIdx = specNames.size();
                specNames.add(settlement.id() + "_hall_" + b);
                int[] encoded = new int[20];
                encoded[0] = Archetype.HOUSE.ordinal();
                encoded[3] = bx;
                encoded[4] = bz;
                encoded[7] = 7;
                encoded[8] = 7;
                encoded[9] = 5;
                encoded[10] = facing.get2DDataValue();
                encoded[11] = floorY;
                encoded[12] = floorY;
                for (int v : encoded) specInts.add(v);

                dSpec.add(specIdx);
                dx.add(doorX);
                dy.add(floorY);
                dz.add(doorZ);
                dNode.add(nDoor);
                dFacing.add((byte) facing.get2DDataValue());
                dReach.add(true);

                pSpec.add(specIdx);
                pMinX.add(bx - 3);
                pMinZ.add(bz - 3);
                pMaxX.add(bx + 3);
                pMaxZ.add(bz + 3);
                pPad.add(floorY);
                pKind.add(StreetPlan.PLOT_BUILDING);
            }
        }
        sNodeOff[ns] = nx.size();
        sEdgeOff[ns] = ea.size();
        sDoorOff[ns] = dSpec.size();
        sPlotOff[ns] = pSpec.size();
        sSpecOff[ns] = specNames.size();

        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(8192);
            try (DataOutputStream o = new DataOutputStream(bo)) {
                o.writeInt(StreetPlan.MAGIC);
                o.writeInt(StreetPlan.VERSION);
                o.writeInt(ns);
                for (TilePlan.TilePlace s : settlements) {
                    o.writeUTF(s.id());
                    o.writeInt(s.x());
                    o.writeInt(s.z());
                    o.writeInt(s.radius() + 16);
                }
                writeInts(o, sNodeOff);
                writeInts(o, sEdgeOff);
                writeInts(o, sDoorOff);
                writeInts(o, sPlotOff);
                writeInts(o, sSpecOff);
                writeFloats(o, nx);
                writeFloats(o, nz);
                writeFloats(o, nh);
                writeBytes(o, nClass);
                writeInts(o, nJoinSeg);
                writeInts(o, nJoinVert);
                writeInts(o, ea);
                writeInts(o, eb);
                writeFloats(o, ew);
                writeBytes(o, eClass);
                writeBytes(o, eSurf);
                writeInts(o, dSpec);
                writeInts(o, dx);
                writeInts(o, dy);
                writeInts(o, dz);
                writeInts(o, dNode);
                writeBytes(o, dFacing);
                o.writeInt(dReach.size());
                for (boolean r : dReach) o.writeBoolean(r);
                writeInts(o, pSpec);
                writeInts(o, pMinX);
                writeInts(o, pMinZ);
                writeInts(o, pMaxX);
                writeInts(o, pMaxZ);
                writeInts(o, pPad);
                writeBytes(o, pKind);
                writeInts(o, specInts);
                o.writeInt(specNames.size());
                for (String name : specNames) o.writeUTF(name);
            }
            return StreetPlan.read(bo.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialise procedural tile StreetPlan", e);
        }
    }

    // ------------------------------------------------------------------ ScenePlan builder

    private record RawScene(
            int kindIdx,
            int x,
            int z,
            float h,
            byte facing,
            byte anchor,
            String ref,
            String reason,
            String clue,
            String note,
            int pathX,
            int pathZ,
            short radius,
            int[] propKinds,
            byte[] propDx,
            byte[] propDz
    ) {
    }

    private static ScenePlan buildScenePlan(
            long worldSeed,
            int tileX,
            int tileZ,
            TileSheetGenerator.TileSheet sheet,
            List<TilePlan.TilePlace> places,
            RoadPlan roadPlan
    ) {
        List<RawScene> scenes = new ArrayList<>();

        // 1. Place-anchored scenes (shrines, camps, ruins, settlements)
        for (TilePlan.TilePlace p : places) {
            String reason = p.why().isEmpty() ? sheet.fieldId("L8", "wayshrine_precincts") : p.why().get(0);
            int sx = p.x() + p.radius() + 6;
            int sz = p.z();
            byte anchor = switch (p.type()) {
                case STANDING_STONE, DAEDRIC_SHRINE, WORD_WALL -> ScenePlan.SHRINE_SITE;
                case LANDMARK -> ScenePlan.CAMP_SITE;
                case FORT, DUNGEON_FIXED, DRAGON_MOUND -> ScenePlan.RUIN_EDGE;
                default -> ScenePlan.SETTLEMENT_EDGE;
            };
            int kindIdx = (anchor == ScenePlan.SHRINE_SITE) ? 1
                    : (anchor == ScenePlan.CAMP_SITE) ? 0
                    : (anchor == ScenePlan.RUIN_EDGE) ? 4 : 5;
            scenes.add(new RawScene(
                    kindIdx,
                    sx,
                    sz,
                    p.padHeight(),
                    (byte) 1,
                    anchor,
                    p.id(),
                    reason,
                    "Wayfarer mark of " + p.name(),
                    "Charter survey log for " + p.name() + " (" + sheet.name() + ")",
                    p.x(),
                    p.z(),
                    (short) 6,
                    new int[]{6, 7, 10, 12},
                    new byte[]{0, 2, -2, 1},
                    new byte[]{0, 1, -1, 2}
            ));
        }

        // 2. Road-anchored scenes (midpoint road bends & bridge ends)
        for (int s = 0; s < roadPlan.segments(); s++) {
            int start = roadPlan.segStart[s];
            int end = roadPlan.segEnd[s];
            int mid = (start + end) >>> 1;
            if (end - start >= 8) {
                int rx = Math.round(roadPlan.vx[mid]);
                int rz = Math.round(roadPlan.vz[mid]);
                int sx = rx + 6;
                int sz = rz + 6;
                scenes.add(new RawScene(
                        2, // crossroads_sign
                        sx,
                        sz,
                        roadPlan.vh[mid],
                        (byte) 2,
                        ScenePlan.ROAD_BEND,
                        "seg_" + s,
                        sheet.fieldId("L6", "wayfarer_tracks"),
                        "Direction post along " + roadPlan.linkName[roadPlan.segLink[s]],
                        "",
                        rx,
                        rz,
                        (short) 5,
                        new int[]{10, 12},
                        new byte[]{0, 1},
                        new byte[]{0, -1}
                ));
            }
            for (int k = roadPlan.spanOff[s]; k < roadPlan.spanOff[s + 1]; k++) {
                if (roadPlan.spanType[k] == RoadPlan.BRIDGE) {
                    int v = roadPlan.spanFrom[k];
                    int bx = Math.round(roadPlan.vx[v]);
                    int bz = Math.round(roadPlan.vz[v]);
                    scenes.add(new RawScene(
                            3, // bridge_watch
                            bx + 5,
                            bz - 5,
                            roadPlan.vh[v],
                            (byte) 0,
                            ScenePlan.BRIDGE_END,
                            "bridge_" + s + "_" + k,
                            sheet.fieldId("L7", "charter_wardens"),
                            "Bridge warden post",
                            "",
                            bx,
                            bz,
                            (short) 5,
                            new int[]{7, 8, 10},
                            new byte[]{0, 2, -1},
                            new byte[]{0, 1, 1}
                    ));
                }
            }
        }

        float landKm2 = Math.max(0.1f, sheet.landFraction() * 16.777216f);
        float[] roadKm = new float[4];
        for (int k = 0; k < 4; k++) {
            roadKm[k] = (float) (roadPlan.length(k) / 1000.0);
        }

        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(8192);
            try (DataOutputStream out = new DataOutputStream(bo)) {
                out.writeInt(ScenePlan.MAGIC);
                out.writeInt(ScenePlan.VERSION);
                out.writeFloat(landKm2);
                out.writeByte(roadKm.length);
                for (float v : roadKm) out.writeFloat(v);
                out.writeInt(SCENE_KINDS.length);
                for (String k : SCENE_KINDS) out.writeUTF(k);
                out.writeInt(scenes.size());
                for (RawScene sc : scenes) {
                    out.writeShort(sc.kindIdx());
                    out.writeInt(sc.x());
                    out.writeInt(sc.z());
                    out.writeShort(q16(sc.h()));
                    out.writeByte(sc.facing());
                    out.writeByte(sc.anchor());
                    out.writeUTF(sc.ref());
                    out.writeUTF(sc.reason());
                    out.writeUTF(sc.clue());
                    out.writeUTF(sc.note());
                    out.writeInt(sc.pathX());
                    out.writeInt(sc.pathZ());
                    out.writeShort(sc.radius());
                    int m = sc.propKinds().length;
                    out.writeByte(m);
                    for (int p = 0; p < m; p++) {
                        out.writeShort(sc.propKinds()[p]);
                        out.writeByte(sc.propDx()[p]);
                        out.writeByte(sc.propDz()[p]);
                    }
                }
            }
            return ScenePlan.read(bo.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialise procedural tile ScenePlan", e);
        }
    }

    private static short q16(float v) {
        return (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, Math.round(v * 16f)));
    }

    private static float quant16(float v) {
        return Math.round(v * 16f) / 16f;
    }

    private static void writeInts(DataOutputStream o, int[] a) throws IOException {
        o.writeInt(a.length);
        for (int v : a) o.writeInt(v);
    }

    private static void writeInts(DataOutputStream o, List<Integer> a) throws IOException {
        o.writeInt(a.size());
        for (int v : a) o.writeInt(v);
    }

    private static void writeFloats(DataOutputStream o, List<Float> a) throws IOException {
        o.writeInt(a.size());
        for (float v : a) o.writeFloat(v);
    }

    private static void writeBytes(DataOutputStream o, List<Byte> a) throws IOException {
        o.writeInt(a.size());
        for (byte v : a) o.writeByte(v);
    }
}
