package com.nirncraft.roads;

import com.nirncraft.NirnCraft;
import com.nirncraft.world.LocationTags;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;

import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Orchestrates the road network on the server: settlement detection from loaded chunks, road planning on a
 * background thread, and feeding loaded road chunks to the {@link Paver}. Only the overworld has roads.
 *
 * <p>The chunk load event only records what it saw in thread-safe queues; all world access happens in the level
 * tick on the server thread.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class RoadNetwork {
    /** Legacy alias of {@code #nirncraft:towns} (quests use it); see {@link LocationTags}. */
    public static final TagKey<Structure> SETTLEMENTS =
            TagKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "settlements"));

    private record FoundStart(Structure structure, long key, BoundingBox box) {
    }

    private static final ConcurrentLinkedQueue<FoundStart> FOUND = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Long> LOADED = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<RoadPlanner.Result> RESULTS = new ConcurrentLinkedQueue<>();
    private static ExecutorService planner;
    private static final List<Future<?>> RUNNING = new ArrayList<>();
    /** Areas of the links being planned now (pair -> minX, minZ, maxX, maxZ). */
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<int[]> IN_FLIGHT_BOXES = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static long tick;
    /** Copy of the road cells handed to plan jobs; rebuilt only after roads were added. */
    @org.jetbrains.annotations.Nullable
    private static LongOpenHashSet cellsSnapshot;
    /** Planner statistics since the server started (for /nirncraft-roads stats and the log). */
    static long plannedRoads, plannedNanos, plannedSamples, failedPlans, applyNanosMax;
    /** Server-thread cost of the roads tick (paving, results, traffic) since the server started. */
    static long tickNanosMax, tickNanosTotal, ticks;

    private RoadNetwork() {}

    static boolean isRoadLevel(Level level) {
        return level instanceof ServerLevel && level.dimension() == Level.OVERWORLD;
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        if (!RoadsConfig.ENABLED.get()) return;
        LOADED.add(chunk.getPos().pack());
        Map<Structure, StructureStart> starts = chunk.getAllStarts();
        if (starts.isEmpty()) return;
        for (Map.Entry<Structure, StructureStart> e : starts.entrySet()) {
            StructureStart start = e.getValue();
            if (start == null || !start.isValid()) continue;
            FOUND.add(new FoundStart(e.getKey(), start.getChunkPos().pack(), start.getBoundingBox()));
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        tick++;
        if (!RoadsConfig.ENABLED.get()) {
            FOUND.clear();
            LOADED.clear();
            return;
        }
        RoadsData data = RoadsData.get(level);
        long tickStart = System.nanoTime();
        try {
            if (!data.classified) reclassify(level, data);
            WorldPlan.tick(level, data, tick);
            drainFound(level, data);
            drainLoaded(level, data);
            long t0 = System.nanoTime();
            applyResults(level, data);
            applyNanosMax = Math.max(applyNanosMax, System.nanoTime() - t0);
            if (tick % RoadsConfig.PLAN_INTERVAL_TICKS.get() == 0) startPlanning(level, data);
            Paver.tick(level, data, tick);
            Travelers.tick(level, data, tick);
            if (tick % 100 == 13) clearRoadSnow(level, data);
            if (tick % 200 == 17) RoadsPackets.sendMarkers(level, data);
            if (tick % 100 == 29) RoadsPackets.sendNetworkIfChanged(level, data);
        } catch (RuntimeException e) {
            NirnCraft.LOGGER.error("NirnCraft roads tick failed", e);
        }
        long spent = System.nanoTime() - tickStart;
        tickNanosMax = Math.max(tickNanosMax, spent);
        tickNanosTotal += spent;
        ticks++;
    }

    /**
     * Clears snow settling on active road chunks near players so the road surface is always visible. Snow layers are
     * not motion blocking, so the top block comes from the WORLD_SURFACE heightmap.
     */
    private static void clearRoadSnow(ServerLevel level, RoadsData data) {
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            ChunkPos cp = player.chunkPosition();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    long c = ChunkPos.pack(cp.x() + dx, cp.z() + dz);
                    if (data.paved.contains(c)) {
                        LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x() + dx, cp.z() + dz);
                        if (chunk == null) continue;
                        for (int bx = 0; bx < 16; bx += 2) {
                            for (int bz = 0; bz < 16; bz += 2) {
                                int x = (cp.x() + dx) * 16 + bx;
                                int z = (cp.z() + dz) * 16 + bz;
                                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                                net.minecraft.core.BlockPos above = new net.minecraft.core.BlockPos(x, y - 1, z);
                                if (level.getBlockState(above).is(net.minecraft.world.level.block.Blocks.SNOW)) {
                                    net.minecraft.world.level.block.state.BlockState ground = level.getBlockState(above.below());
                                    if (Paver.isRoadMaterial(ground)) {
                                        level.setBlock(above, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (planner != null) {
            planner.shutdownNow();
            planner = null;
        }
        RUNNING.clear();
        IN_FLIGHT_BOXES.clear();
        cellsSnapshot = null;
        WorldPlan.clear();
        FOUND.clear();
        LOADED.clear();
        RESULTS.clear();
        Paver.clear();
        Travelers.clear();
        RoadsPackets.clear();
    }

    // ------------------------------------------------------------------ settlements

    private static void drainFound(ServerLevel level, RoadsData data) {
        // wait for the world plan, so the named places' own starts are recognised as the planned destinations
        if (FOUND.isEmpty() || data.planVersion < WorldPlan.VERSION) return;
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        FoundStart f;
        while ((f = FOUND.poll()) != null) {
            if (data.settlementByKey.containsKey(f.key())) continue;
            BoundingBox box = f.box();
            if (WorldPlan.plannedAt(data, (box.minX() + box.maxX()) / 2, (box.minZ() + box.maxZ()) / 2) != null) continue;
            Optional<Holder.Reference<Structure>> holder = registry.getResourceKey(f.structure()).flatMap(registry::get);
            if (holder.isEmpty()) continue;
            // only explicit tags decide: towns get people, other destinations only a spur road
            boolean town = holder.get().is(LocationTags.TOWNS);
            if (!town && !holder.get().is(LocationTags.ROAD_DESTINATIONS)) continue;
            int cx = (box.minX() + box.maxX()) / 2, cz = (box.minZ() + box.maxZ()) / 2;
            if (!town && Landmass.near(cx, cz, 96) <= Landmass.SEA) continue;
            register(level, data, f, holder.get(), town);
        }
    }

    /** Checks settlements loaded from an older save against the current tags (towns vs. other destinations). */
    private static void reclassify(ServerLevel level, RoadsData data) {
        data.classified = true;
        for (Settlement s : new ArrayList<>(data.settlements)) {
            Identifier id = Identifier.tryParse(s.structure);
            if (id == null) continue;
            // not in any tag any more (e.g. outposts registered by the old name heuristic): keep its roads, no people
            data.setTown(s, LocationTags.is(level.registryAccess(), id, LocationTags.TOWNS));
        }
        data.rebuildLinks();
    }

    private static void register(ServerLevel level, RoadsData data, FoundStart f, Holder<Structure> holder, boolean town) {
        Identifier structure = holder.unwrapKey().map(ResourceKey::identifier).orElseThrow();
        BoundingBox box = f.box();
        ChunkPos startChunk = ChunkPos.unpack(f.key());
        String locationId = SettlementNames.locationId(level.dimension(), structure, startChunk);
        String name = SettlementNames.name(level.dimension(), holder, structure, locationId);
        int cx = (box.minX() + box.maxX()) / 2;
        int cz = (box.minZ() + box.maxZ()) / 2;
        if (!town && Landmass.near(cx, cz, 96) <= Landmass.SEA) return;
        int cy = (box.minY() + box.maxY()) / 2;
        Settlement s = new Settlement(data.nextSettlementId++, f.key(), cx, cy, cz, box.minX(), box.minZ(), box.maxX(),
                box.maxZ(), name, structure.toString(), locationId, false);
        s.town = town;
        data.addSettlement(s);
        resolveY(level, s);
        NirnCraft.LOGGER.debug("NirnCraft roads: settlement {} ({}) at {}, {}", s.name, structure, s.x, s.z);
        planConnections(data, s);
    }

    /** Returns true if the height was resolved now. */
    private static boolean resolveY(ServerLevel level, Settlement s) {
        if (s.yResolved || level.getChunkSource().getChunkNow(s.x >> 4, s.z >> 4) == null) return false;
        s.y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, s.x, s.z);
        s.yResolved = true;
        return true;
    }

    /**
     * Queues roads for a new destination. A town is linked to its nearest towns (or the nearest one at all if it
     * would be isolated), its road group is bridged to the nearest other group, and dungeons near it that still lack
     * a road get their spur. A dungeon gets one spur road to its nearest town. All lookups use the spatial grid and
     * the incremental union-find, so the cost does not grow with the number of known settlements.
     */
    static int planConnections(RoadsData data, Settlement s) {
        int maxDist = RoadsConfig.MAX_DISTANCE.get();
        if (!s.town) return 0; // dungeons get a trail from the nearest road (WorldPlan)
        // farmsteads and roadside inns hang off the network with one road; villages and towns get the configured count
        int wanted = s.structure.contains("farmstead") ? 1 : RoadsConfig.CONNECTIONS.get();
        int minDist = data.zoneRadius() * 2;
        List<Settlement> near = data.near(s.x, s.z, maxDist * 2.0, o -> o != s && o.town
                && Landmass.connected(s.x, s.z, o.x, o.z));
        int linked = 0, queued = 0;
        for (Settlement o : near) {
            if (linked >= wanted) break;
            double d = Math.sqrt(s.distSq(o.x + 0.5, o.z + 0.5));
            if (d > maxDist) break;
            if (data.failed.contains(RoadsData.pairKey(s.id, o.id))) continue; // unroutable: try the next one
            linked++;
            if (d < minDist) continue; // practically the same place
            if (queue(data, s, o)) queued++;
        }
        if (linked == 0) {
            for (Settlement o : near) {
                if (data.failed.contains(RoadsData.pairKey(s.id, o.id))) continue;
                if (queue(data, s, o)) queued++;
                break;
            }
        }
        queued += bridgeComponents(data, s, maxDist * 3.0);
        return queued;
    }

    /**
     * No orphan towns: if {@code s}'s connected group does not reach every known town, links {@code s} to the nearest
     * town of another group (within {@code limit} blocks).
     */
    private static int bridgeComponents(RoadsData data, Settlement s, double limit) {
        if (data.componentSize(s.id) >= data.townCount()) return 0;
        int mine = data.find(s.id);
        for (Settlement o : data.near(s.x, s.z, limit, o -> o.town && o != s && Landmass.connected(s.x, s.z, o.x, o.z))) {
            if (data.find(o.id) == mine || data.failed.contains(RoadsData.pairKey(s.id, o.id))) continue;
            return queue(data, s, o) ? 1 : 0;
        }
        return 0;
    }

    private static boolean queue(RoadsData data, Settlement a, Settlement b) {
        long key = RoadsData.pairKey(a.id, b.id);
        if (data.failed.contains(key) || !data.pairs.add(key)) return false;
        // generic settlements hang off the network with lesser roads: lanes to farmsteads, roads to the rest
        if (!data.links.containsKey(key)) {
            boolean farm = a.structure.contains("farmstead") || b.structure.contains("farmstead");
            data.links.put(key, new RoadsData.Link(farm ? Road.LANE : Road.ROAD, "", RoadGraph.DEFAULT_BRIDGE,
                    Integer.MIN_VALUE, Integer.MIN_VALUE, 0));
        }
        data.pending.add(key);
        data.link(a.id, b.id);
        data.setDirty();
        return true;
    }

    // ------------------------------------------------------------------ chunks

    private static void drainLoaded(ServerLevel level, RoadsData data) {
        Long c;
        while ((c = LOADED.poll()) != null) {
            long chunk = c;
            if (data.needsPaving(chunk)) Paver.enqueue(chunk);
            List<Settlement> centers = data.settlementByCenterChunk.get(chunk);
            if (centers != null) {
                for (Settlement s : centers) {
                    if (resolveY(level, s)) data.setDirty();
                }
            }
        }
    }

    /** Re-queues every loaded chunk that still needs paving (after roads changed). */
    static void queueLoaded(ServerLevel level, RoadsData data, LongOpenHashSet chunks) {
        boolean changed = false;
        for (long c : chunks) {
            changed |= data.paved.remove(c);
            if (level.getChunkSource().getChunkNow(ChunkPos.getX(c), ChunkPos.getZ(c)) != null) Paver.enqueue(c);
        }
        if (changed) data.setDirty();
    }


    // ------------------------------------------------------------------ planning

    private static ExecutorService planner() {
        if (planner == null) {
            int threads = RoadsConfig.PLANNER_THREADS.get();
            planner = Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "NirnCraft Road Planner");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
        }
        return planner;
    }

    /** Runs background work (graph building, site prediction) on the planner threads. */
    static void submit(Runnable task) {
        RUNNING.add(planner().submit(task));
    }

    static boolean planning() {
        RUNNING.removeIf(Future::isDone);
        return !RUNNING.isEmpty();
    }

    /** The settlement at one end of a link; the virtual end (negative id) of a gate lane is its town itself. */
    @org.jetbrains.annotations.Nullable
    private static Settlement endOf(RoadsData data, long pair, int id) {
        Settlement s = data.settlement(id);
        if (s != null || id >= 0) return s;
        return data.settlement(RoadsData.pairA(pair) == id ? RoadsData.pairB(pair) : RoadsData.pairA(pair));
    }

    /**
     * The waiting pair to plan next: the one whose midpoint is nearest to a player (or to the world spawn), so the
     * roads around the players are ready first.
     */
    private static long nextPair(ServerLevel level, RoadsData data) {
        List<double[]> anchors = new ArrayList<>();
        for (net.minecraft.server.level.ServerPlayer p : level.players()) anchors.add(new double[]{p.getX(), p.getZ()});
        if (anchors.isEmpty()) anchors.add(new double[]{level.getRespawnData().pos().getX(), level.getRespawnData().pos().getZ()});
        long best = -1;
        double bestD = Double.MAX_VALUE;
        for (long pair : data.pending) {
            Settlement a = endOf(data, pair, RoadsData.pairA(pair)), b = endOf(data, pair, RoadsData.pairB(pair));
            if (a == null || b == null) return pair; // dropped by the caller
            // never two neighbouring roads at once: the second must see the first to share its way
            if (overlapsInFlight(box(data, pair, a, b, null, null))) continue;
            double mx = (a.x + b.x) * 0.5, mz = (a.z + b.z) * 0.5;
            for (double[] p : anchors) {
                double d = (mx - p[0]) * (mx - p[0]) + (mz - p[1]) * (mz - p[1]);
                if (d < bestD) {
                    bestD = d;
                    best = pair;
                }
            }
        }
        return best;
    }

    /** Area a link's planning may touch (endpoints plus a margin). */
    private static int[] box(RoadsData data, long pair, Settlement a, Settlement b, int[] ea, int[] eb) {
        int m = 160;
        RoadsData.Link link = data.link(pair);
        int ax = a.x, az = a.z, bx = b.x, bz = b.z;
        if (link.trail()) { // from the destination to the road point it joins
            Settlement from = link.from() == b.id ? b : a;
            ax = from.x;
            az = from.z;
            bx = link.tx();
            bz = link.tz();
            m = 48;
        }
        if (ea != null) {
            ax = ea[0];
            az = ea[1];
        }
        if (eb != null) {
            bx = eb[0];
            bz = eb[1];
        }
        return new int[]{Math.min(ax, bx) - m, Math.min(az, bz) - m, Math.max(ax, bx) + m, Math.max(az, bz) + m};
    }

    private static boolean overlapsInFlight(int[] box) {
        for (int[] o : IN_FLIGHT_BOXES.values()) {
            if (box[0] <= o[2] && box[2] >= o[0] && box[1] <= o[3] && box[3] >= o[1]) return true;
        }
        return false;
    }

    /** Links taken from the road plan per planning call (applying one indexes it and lays out its signs). */
    private static final int PLAN_ROUTES_PER_CALL = 12;

    private static void startPlanning(ServerLevel level, RoadsData data) {
        RUNNING.removeIf(Future::isDone);
        int threads = RoadsConfig.PLANNER_THREADS.get();
        int fromPlanNow = 0;
        while (RUNNING.size() < threads && !data.pending.isEmpty()) {
            long pair = nextPair(level, data);
            if (pair == -1) break; // everything waiting overlaps a road being planned
            data.pending.remove(pair);
            Settlement a = endOf(data, pair, RoadsData.pairA(pair));
            Settlement b = endOf(data, pair, RoadsData.pairB(pair));
            int virtual = RoadsData.pairA(pair) < 0 ? RoadsData.pairA(pair) : 0; // a gate lane's second end
            if (a == null || b == null) {
                data.pairs.remove(pair);
                data.rebuildLinks();
                data.setDirty();
                continue;
            }
            RoadsData.Link link = data.link(pair);
            // a trail runs from its destination (the non-town end) to the road point it joins
            if (link.trail() && link.from() == b.id) {
                Settlement t = a;
                a = b;
                b = t;
            }
            int[] ea = null;
            if (virtual < 0) { // the gate this lane leaves from
                java.util.List<int[]> gates = a.place().map(GateEnds::usable).orElse(java.util.List.of());
                if (-virtual - 1 < gates.size()) ea = gates.get(-virtual - 1);
            }
            if (ea == null) ea = end(data, a, link.trail() ? Integer.MIN_VALUE : b.id, link.trail() ? link.tx() : b.x, link.trail() ? link.tz() : b.z);
            int[] eb = link.trail() ? new int[]{link.tx(), link.tz()} : end(data, b, a.id, a.x, a.z);
            // the baked road plan has the polyline of every named link: the network takes it instead of searching
            RoadPlanner.Result fromPlan = PlanRoute.route(data.generation, a.id, virtual < 0 ? virtual : b.id, a.placeId, b.placeId,
                    virtual < 0 ? -virtual - 1 : -1, link.trail(), ea, eb);
            if (fromPlan != null) {
                data.inFlight.add(pair);
                RESULTS.add(fromPlan);
                if (++fromPlanNow >= PLAN_ROUTES_PER_CALL) break;
                continue;
            }
            if (!data.legacyPaving()) {
                // no plan link (a generic settlement, a trail the plan did not make) and no runtime road planner here
                data.pairs.remove(pair);
                data.failed.add(pair);
                data.rebuildLinks();
                data.setDirty();
                continue;
            }
            data.inFlight.add(pair);
            IN_FLIGHT_BOXES.put(pair, box(data, pair, a, b, ea, eb));
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            RandomState random = level.getChunkSource().randomState();
            int bridge = link.maxBridge() > RoadGraph.DEFAULT_BRIDGE ? link.maxBridge()
                    : Math.min(link.maxBridge(), RoadsConfig.MAX_BRIDGE_LENGTH.get());
            int longWater = Math.max(1, (int) Math.round(bridge / RoadPlanner.SPACING));
            if (cellsSnapshot == null) cellsSnapshot = new LongOpenHashSet(data.roadCells);
            RoadPlanner.Job job = new RoadPlanner.Job(data.generation, a.id, virtual < 0 ? virtual : b.id, ea[0], ea[1], eb[0], eb[1], level.getSeaLevel(),
                    RoadsConfig.MAX_EXPANSIONS.get(), longWater, cellsSnapshot, bridge, avoid(data, ea, eb));
            Settlement fa = a, fb = b;
            RUNNING.add(planner().submit(() -> {
                long t0 = System.nanoTime();
                RoadPlanner.Result r;
                try {
                    r = RoadPlanner.plan(job, generator, random, level);
                } catch (Throwable t) {
                    NirnCraft.LOGGER.warn("NirnCraft roads: planning {} -> {} failed", fa.name, fb.name, t);
                    r = new RoadPlanner.Result(job.generation(), job.a(), job.b(), new int[0], new int[0], new byte[0], true, 0, 0);
                }
                synchronized (RoadNetwork.class) {
                    plannedNanos += System.nanoTime() - t0;
                    plannedSamples += r.samples();
                }
                RESULTS.add(r);
            }));
        }
    }

    /**
     * Where the road to {@code otherId} (a settlement at ox, oz; or a trail's target point, otherId MIN_VALUE) meets
     * {@code s}: the gate of its plan that faces that neighbour (see {@link GateEnds}), else the old per-type guess, and
     * for a destination found in the world its centre.
     */
    static int[] end(RoadsData data, Settlement s, int otherId, int ox, int oz) {
        java.util.Optional<com.nirncraft.tamriel.Place> place = s.place();
        if (place.isEmpty()) return new int[]{s.x, s.z};
        com.nirncraft.tamriel.Place p = place.get();
        if (s.town && p.type().isSettlement()) {
            int[] g = GateEnds.pick(p, otherId, ox, oz, neighbours(data, s));
            if (g != null) return g;
        }
        return RoadGraph.endpoint(p);
    }

    /** The settlements linked to {@code s} by planned (non-trail) roads, as {id, x, z} sorted by id. */
    private static int[][] neighbours(RoadsData data, Settlement s) {
        List<int[]> out = new ArrayList<>();
        for (long pair : data.pairs) {
            int a = RoadsData.pairA(pair), b = RoadsData.pairB(pair);
            if (a != s.id && b != s.id) continue;
            if (data.failed.contains(pair) || data.link(pair).trail()) continue;
            Settlement o = data.settlement(a == s.id ? b : a);
            if (o != null) out.add(new int[]{o.id, o.x, o.z});
        }
        out.sort(java.util.Comparator.comparingInt(r -> r[0]));
        return out.toArray(new int[0][]);
    }

    /** Walled cities near the link (centre and radius triples): the road goes round them to the gate. */
    private static int[] avoid(RoadsData data, int[] a, int[] b) {
        int x = (a[0] + b[0]) / 2, z = (a[1] + b[1]) / 2;
        double reach = Math.sqrt((double) (a[0] - b[0]) * (a[0] - b[0]) + (double) (a[1] - b[1]) * (a[1] - b[1])) / 2 + 400;
        it.unimi.dsi.fastutil.ints.IntArrayList out = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (Settlement s : data.near(x, z, reach, s -> s.town && !s.placeId.isEmpty())) {
            s.place().ifPresent(p -> {
                boolean city = p.type() == com.nirncraft.tamriel.PlaceType.CAPITAL || p.type() == com.nirncraft.tamriel.PlaceType.CITY;
                if (!city && !p.type().isSettlement()) return;
                int core = GateEnds.coreRadius(p);
                // a road never crosses a town: it goes round to the gate (a city also keeps clear of its outer ground)
                boolean own = Math.hypot(a[0] - p.x(), a[1] - p.z()) <= Math.max(p.radius(), core) + 60
                        || Math.hypot(b[0] - p.x(), b[1] - p.z()) <= Math.max(p.radius(), core) + 60;
                int r = own ? core - 8 : city ? p.radius() : core;
                if (r < 16) return;
                out.add(p.x());
                out.add(p.z());
                out.add(r);
            });
        }
        return out.toIntArray();
    }

    private static void applyResults(ServerLevel level, RoadsData data) {
        RoadPlanner.Result r;
        while ((r = RESULTS.poll()) != null) {
            if (r.generation() != data.generation) continue;
            long pair = RoadsData.pairKey(r.a(), r.b());
            data.inFlight.remove(pair);
            IN_FLIGHT_BOXES.remove(pair);
            Settlement a = endOf(data, pair, r.a()), b = endOf(data, pair, r.b());
            if (a == null || b == null || r.xs().length < 2) {
                data.pairs.remove(pair);
                data.rebuildLinks();
                data.setDirty();
                if (r.aborted() && a != null && b != null) {
                    // interrupted or crashed: plan it again later
                    data.pairs.add(pair);
                    data.pending.add(pair);
                    data.link(a.id, b.id);
                } else if (a != null && b != null) {
                    RoadsData.Link link = data.link(pair);
                    if (!link.trail() && link.maxBridge() < RETRY_BRIDGE) {
                        // a lake island (Goldenglow Estate) or a broad river: try once more with a longer bridge
                        data.links.put(pair, new RoadsData.Link(link.kind(), link.name(), RETRY_BRIDGE, link.tx(), link.tz(), link.from()));
                        data.pairs.add(pair);
                        data.pending.add(pair);
                        data.link(a.id, b.id);
                        continue;
                    }
                    // no acceptable path (cliffs, open sea...): never retry this pair
                    data.failed.add(pair);
                    failedPlans++;
                    NirnCraft.LOGGER.info("NirnCraft roads: no path {} -> {} ({} expansions)", a.name, b.name, r.expansions());
                    // link other neighbours instead, but only for a destination left without any road (no cascades)
                    if (!link.trail()) {
                        if (isolated(data, a)) planConnections(data, a);
                        if (isolated(data, b)) planConnections(data, b);
                    } else if (r.a() >= 0 && link.from() != 0) {
                        data.trailChecked.remove(link.from()); // WorldPlan tries another road point (limited)
                    }
                }
                continue;
            }
            RoadsData.Link link = data.link(pair);
            Road road = data.addRoad(r.a(), r.b(), r.xs(), r.zs(), r.flags(), link.kind(), link.name(),
                    r.expansions() == PlanRoute.FROM_PLAN);
            LongOpenHashSet touched = RoadLayout.layout(data, road); // before indexing: junctions see only other roads
            touched.addAll(data.indexRoad(road));
            cellsSnapshot = null;
            plannedRoads++;
            queueLoaded(level, data, touched);
            RoadsPackets.roadAdded(road);
            NirnCraft.LOGGER.debug("NirnCraft roads: road {} -> {} planned ({} points, {} m, {} expansions)", a.name, b.name,
                    road.size(), Math.round(road.length()), r.expansions());
            if (data.pending.isEmpty() && data.inFlight.isEmpty()) logStats(data);
        }
    }

    /** Longest bridge of a link's second attempt. */
    private static final int RETRY_BRIDGE = 160;
    /** A destination stops asking for replacement links after this many failed ones. */
    private static final int MAX_FAILURES = 3;

    /** True if a town has no road and nothing queued, and has not failed too often to be worth another try. */
    private static boolean isolated(RoadsData data, Settlement s) {
        if (!s.town || !data.roadsAt(s.id).isEmpty()) return false;
        int failures = 0;
        for (long p : data.failed) if (RoadsData.pairA(p) == s.id || RoadsData.pairB(p) == s.id) failures++;
        if (failures >= MAX_FAILURES) return false;
        for (long p : data.pending) if (RoadsData.pairA(p) == s.id || RoadsData.pairB(p) == s.id) return false;
        for (long p : data.inFlight) if (RoadsData.pairA(p) == s.id || RoadsData.pairB(p) == s.id) return false;
        return true;
    }

    /** Logs the network size and the planner's cost so far. */
    static void logStats(RoadsData data) {
        NirnCraft.LOGGER.info("NirnCraft roads: {}", stats(data));
    }

    static String stats(RoadsData data) {
        double[] km = new double[4];
        int[] count = new int[4];
        for (Road road : data.roads.values()) {
            int k = Math.max(0, Math.min(3, road.kind));
            km[k] += road.length() / 1000.0;
            count[k]++;
        }
        int towns = 0, dests = 0;
        for (Settlement s : data.settlements) {
            if (s.town) towns++;
            else dests++;
        }
        return String.format(java.util.Locale.ROOT,
                "network: %d towns, %d other destinations, %d roads (%.1f km): %d highways %.1f km, %d roads %.1f km, %d lanes %.1f km, "
                        + "%d trails %.1f km; %d pending, %d failed; planner %.1f s over %d plans (%d height samples), "
                        + "slowest result apply %.2f ms; roads tick avg %.3f ms, max %.2f ms; generic sites predicted in %d regions: "
                        + "%d towns, %d dungeons",
                towns, dests, data.roads.size(), km[0] + km[1] + km[2] + km[3], count[0], km[0], count[1], km[1], count[2], km[2],
                count[3], km[3], pendingPlans(data), data.failed.size(), plannedNanos / 1e9, plannedRoads, plannedSamples,
                applyNanosMax / 1e6, ticks == 0 ? 0.0 : tickNanosTotal / 1e6 / ticks, tickNanosMax / 1e6,
                WorldPlan.scannedRegions, WorldPlan.predictedTowns, WorldPlan.predictedDungeons);
    }

    /** Forgets every road and plans the network again: the named network (WorldPlan), then the known generic towns. */
    static int replan(ServerLevel level, RoadsData data) {
        for (Future<?> f : RUNNING) f.cancel(true);
        RUNNING.clear();
        IN_FLIGHT_BOXES.clear();
        RESULTS.clear();
        Paver.clear();
        WorldPlan.clear();
        cellsSnapshot = null;
        data.clearRoads();
        int queued = 0;
        for (Settlement s : data.settlements) if (s.town && s.placeId.isEmpty()) queued += planConnections(data, s);
        RoadsPackets.networkReset();
        return queued;
    }

    /** Clears the paved state of every road chunk so loaded ones are paved again (e.g. after a config change). */
    static int repave(ServerLevel level, RoadsData data) {
        LongOpenHashSet all = new LongOpenHashSet(data.chunkIndex.keySet());
        all.addAll(data.featuresByChunk.keySet());
        data.paved.clear();
        int loaded = 0;
        for (long c : all) {
            if (level.getChunkSource().getChunkNow(ChunkPos.getX(c), ChunkPos.getZ(c)) != null) {
                Paver.enqueue(c);
                loaded++;
            }
        }
        data.setDirty();
        return loaded;
    }

    static int pendingPlans(RoadsData data) {
        return data.pending.size() + data.inFlight.size();
    }
}
