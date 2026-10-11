package com.skycraft.roads;

import com.skycraft.Skycraft;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.settlement.SettlementStructure;
import com.skycraft.world.LocationTags;
import com.skycraft.world.WorldData;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The pre-planned road network. On the first start of a world (or the first load of a save from before the plan
 * existed) every named settlement and landmark of Tamriel is registered as a road destination and the links of the
 * {@link RoadGraph} are queued for the background {@link RoadPlanner}, nearest to the players first. Once the main
 * network around a landmark is planned, the landmark gets a signposted trail from the nearest road (within
 * {@link #TRAIL_RANGE} blocks).
 *
 * <p>Generic settlements and dungeons (random-spread structure sets) are predicted the same way the chunk generator
 * places them, without loading chunks: for each region near a player the set's placement picks its candidate chunks
 * and the structure's own {@code findValidGenerationPoint} (noise heights only) decides whether it will generate. The
 * predicted towns join the network with lesser roads; dungeons become trail destinations. Regions are scanned lazily
 * within {@link #SCAN_RADIUS} blocks of players, so the endless lands cost nothing until someone goes there.</p>
 */
final class WorldPlan {
    /** Bump to re-plan the named network of existing saves (roads already planned stay). */
    static final int VERSION = 1;
    static final int TRAIL_RANGE = 300;
    private static final int TRAIL_MIN = 20;
    /** Generic-site prediction regions: 1024 blocks. */
    static final int SCAN_SHIFT = 10;
    static final int SCAN_RADIUS = 3000;
    /** Trail destinations closer than this to a planned destination are part of it (no trail of their own). */
    private static final int MERGE = 48;

    private record Site(Holder<Structure> structure, ResourceLocation id, long chunk, int x, int y, int z, int radius,
                        boolean town) {
    }

    private record Scan(long region, List<Site> sites) {
    }

    private static final ConcurrentLinkedQueue<RoadGraph.Graph> GRAPHS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Scan> SCANS = new ConcurrentLinkedQueue<>();
    private static final LongOpenHashSet SCANNING = new LongOpenHashSet();
    private static boolean building;
    /** Generic sites predicted since the server started (for the stats). */
    static int predictedTowns, predictedDungeons, scannedRegions;
    /** The last graph built (for the stats and the network render). */
    @Nullable
    static volatile RoadGraph.Graph lastGraph;

    private WorldPlan() {}

    static void clear() {
        GRAPHS.clear();
        SCANS.clear();
        SCANNING.clear();
        building = false;
    }

    static void tick(ServerLevel level, RoadsData data, long tick) {
        if (data.planVersion < VERSION && !building) {
            building = true;
            RoadNetwork.submit(() -> {
                try {
                    Landmass.warm();
                    Corridors.size();
                    GRAPHS.add(RoadGraph.build());
                } catch (Throwable t) {
                    Skycraft.LOGGER.error("Skycraft roads: planning the road graph failed", t);
                }
            });
        }
        RoadGraph.Graph g;
        while ((g = GRAPHS.poll()) != null) {
            building = false;
            apply(level, data, g);
        }
        Scan scan;
        while ((scan = SCANS.poll()) != null) applyScan(level, data, scan);
        if (data.planVersion >= VERSION) {
            if (tick % 5 == 0) trails(level, data);
            if (tick % 100 == 41) scanAround(level, data);
        }
    }

    // ------------------------------------------------------------------ named network

    /** Registers a named place as a destination (or adopts the settlement found there before the plan existed). */
    static Settlement registerPlace(ServerLevel level, RoadsData data, Place p, boolean town) {
        Settlement s = data.byPlace(p.id());
        if (s != null) return s;
        long key = ChunkPos.asLong(p.x() >> 4, p.z() >> 4);
        s = data.settlementByKey.get(key);
        if (s != null) {
            s.placeId = p.id();
            data.byPlace.put(p.id(), s);
            data.setTown(s, town);
            data.setDirty();
            return s;
        }
        int r = Math.max(8, p.radius());
        int y = Math.round(TamrielMap.elevation(p.x(), p.z()));
        s = new Settlement(data.nextSettlementId++, key, p.x(), y, p.z(), p.x() - r, p.z() - r, p.x() + r, p.z() + r,
                p.name().getString(), "skycraft:place/" + p.type().id(), WorldData.placeLocId(p.id()), false);
        s.town = town;
        s.placeId = p.id();
        data.addSettlement(s);
        return s;
    }

    private static void apply(ServerLevel level, RoadsData data, RoadGraph.Graph g) {
        lastGraph = g;
        long t0 = System.nanoTime();
        int queued = 0;
        for (Place p : g.nodes()) registerPlace(level, data, p, true);
        for (Place p : Places.all()) {
            // roadside inns beside the highways: towns with a short path from the road
            if (p.type().isSettlement() && RoadGraph.isSpur(p) && Landmass.near(p.x(), p.z(), 64) > Landmass.SEA) registerPlace(level, data, p, true);
        }
        int landmarks = 0;
        for (Place p : Places.all()) {
            if (!RoadGraph.isTrailDestination(p.type())) continue;
            if (insidePlanned(data, p.x(), p.z()) != null) continue; // part of a town (a temple, a keep...)
            registerPlace(level, data, p, false);
            landmarks++;
        }
        for (RoadGraph.Edge e : g.edges()) {
            Settlement a = data.byPlace(e.a().id()), b = data.byPlace(e.b().id());
            if (a == null || b == null) continue;
            long pair = RoadsData.pairKey(a.id, b.id);
            data.links.put(pair, new RoadsData.Link(e.kind(), e.name(), e.maxBridge(), Integer.MIN_VALUE, Integer.MIN_VALUE, 0));
            if (data.pairs.contains(pair)) continue; // already built or queued by the explored network
            data.failed.remove(pair);
            data.pairs.add(pair);
            data.pending.add(pair);
            data.link(a.id, b.id);
            queued++;
        }
        data.planVersion = VERSION;
        data.setDirty();
        double km = 0;
        for (RoadGraph.Edge e : g.edges()) km += e.length() / 1000.0;
        Skycraft.LOGGER.info("Skycraft roads: world plan: {} settlements, {} trail destinations, {} links ({} km straight-line), {} queued ({} ms on the server thread); {}",
                g.nodes().size(), landmarks, g.edges().size(), Math.round(km), queued, (System.nanoTime() - t0) / 1_000_000,
                RoadGraph.componentSummary(g));
    }

    /** The planned town or city whose area contains (x, z), if any. */
    @Nullable
    static Settlement insidePlanned(RoadsData data, int x, int z) {
        for (Settlement s : data.near(x, z, 260, s -> s.town)) {
            int r = s.place().map(Place::radius).orElse(Math.max(s.maxX - s.minX, s.maxZ - s.minZ) / 2);
            if (s.distSq(x + 0.5, z + 0.5) <= (double) (r + 8) * (r + 8)) return s;
        }
        return null;
    }

    /** The planned destination this structure start belongs to (a place's anchor, district or site), if any. */
    @Nullable
    static Settlement plannedAt(RoadsData data, int x, int z) {
        Settlement town = insidePlanned(data, x, z);
        if (town != null) return town;
        List<Settlement> near = data.near(x, z, MERGE, s -> !s.town);
        return near.isEmpty() ? null : near.get(0);
    }

    // ------------------------------------------------------------------ trails

    /**
     * Gives a few unchecked trail destinations their trail per call: once the network near them is planned (nothing
     * else is waiting), the nearest road point within {@link #TRAIL_RANGE} is found and a trail is queued to it.
     */
    private static void trails(ServerLevel level, RoadsData data) {
        int mainWaiting = 0;
        for (long p : data.pending) if (!data.link(p).trail()) mainWaiting++;
        for (long p : data.inFlight) if (!data.link(p).trail()) mainWaiting++;
        if (mainWaiting > 0) return;
        int budget = 3;
        for (Settlement s : data.settlements) {
            boolean spur = s.town && s.place().map(RoadGraph::isSpur).orElse(false);
            if ((s.town && !spur) || data.trailChecked.contains(s.id)) continue;
            if (budget-- <= 0) break;
            data.trailChecked.add(s.id);
            data.setDirty();
            if (spur ? !data.roadsAt(s.id).isEmpty() : data.hasSpur(s.id)) continue;
            long hit = nearestRoadPoint(data, s.x, s.z, TRAIL_RANGE);
            if (hit == -1) continue;
            Road road = data.road(RoadsData.entryRoad(hit));
            int idx = RoadsData.entryIndex(hit);
            if (road == null) continue;
            double d = Math.sqrt(s.distSq(road.xs[idx] + 0.5, road.zs[idx] + 0.5));
            if (d < TRAIL_MIN) continue; // right beside the road: the junction sign of a neighbour will do
            int end = road.cum[idx] <= road.length() / 2 ? road.a : road.b;
            long pair = RoadsData.pairKey(s.id, end);
            if (data.pairs.contains(pair)) {
                end = end == road.a ? road.b : road.a;
                pair = RoadsData.pairKey(s.id, end);
                if (data.pairs.contains(pair)) continue;
            }
            data.links.put(pair, new RoadsData.Link(Road.TRAIL, "", 24, road.xs[idx], road.zs[idx], s.id));
            data.pairs.add(pair);
            data.pending.add(pair);
            data.link(s.id, end);
        }
    }

    /** The road point nearest to (x, z) within {@code range} (encoded as in the chunk index), or -1. */
    static long nearestRoadPoint(RoadsData data, int x, int z, int range) {
        long best = -1;
        long bestD = (long) range * range;
        int r = (range >> 4) + 1;
        int cx0 = x >> 4, cz0 = z >> 4;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                LongArrayList entries = data.chunkIndex.get(ChunkPos.asLong(cx0 + dx, cz0 + dz));
                if (entries == null) continue;
                for (int k = 0; k < entries.size(); k++) {
                    long e = entries.getLong(k);
                    Road road = data.road(RoadsData.entryRoad(e));
                    int i = RoadsData.entryIndex(e);
                    if (road == null || i < 0 || i >= road.size() || road.kind == Road.TRAIL || road.water(i)) continue;
                    long ddx = road.xs[i] - x, ddz = road.zs[i] - z;
                    long d = ddx * ddx + ddz * ddz;
                    if (d < bestD) {
                        bestD = d;
                        best = e;
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ generic sites

    private static void scanAround(ServerLevel level, RoadsData data) {
        if (!SCANNING.isEmpty()) return; // one region at a time keeps the planner free for roads
        int r = SCAN_RADIUS >> SCAN_SHIFT;
        long best = Long.MIN_VALUE;
        double bestD = Double.MAX_VALUE;
        List<net.minecraft.core.BlockPos> anchors = new ArrayList<>();
        anchors.add(level.getSharedSpawnPos()); // where new players arrive
        for (ServerPlayer player : level.players()) anchors.add(player.blockPosition());
        for (net.minecraft.core.BlockPos anchor : anchors) {
            int px = anchor.getX() >> SCAN_SHIFT, pz = anchor.getZ() >> SCAN_SHIFT;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    long key = ChunkPos.asLong(px + dx, pz + dz);
                    if (data.scanned.contains(key)) continue;
                    double d = dx * dx + dz * dz;
                    if (d > (r + 0.5) * (r + 0.5) || d >= bestD) continue;
                    bestD = d;
                    best = key;
                }
            }
        }
        if (best == Long.MIN_VALUE) return;
        long region = best;
        SCANNING.add(region);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState random = level.getChunkSource().randomState();
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        RegistryAccess access = level.registryAccess();
        StructureTemplateManager templates = level.getServer().getStructureManager();
        RoadNetwork.submit(() -> {
            List<Site> sites = new ArrayList<>();
            try {
                sites = predict(region, generator, random, state, access, templates, level);
            } catch (Throwable t) {
                Skycraft.LOGGER.warn("Skycraft roads: predicting the sites of region {} failed", new ChunkPos(region), t);
            }
            SCANS.add(new Scan(region, sites));
        });
    }

    /**
     * True for the random-spread Skycraft sets holding towns or road destinations (a mixed set such as the wilds,
     * where only some structures are destinations, is predicted whole and the others are dropped afterwards).
     */
    private static boolean relevant(StructureSet set) {
        if (!(set.placement() instanceof RandomSpreadStructurePlacement)) return false;
        boolean any = false;
        for (StructureSet.StructureSelectionEntry e : set.structures()) {
            Optional<ResourceKey<Structure>> key = e.structure().unwrapKey();
            if (key.isEmpty() || !Skycraft.MODID.equals(key.get().location().getNamespace())) return false;
            any |= destination(e.structure());
        }
        return any;
    }

    private static boolean destination(Holder<Structure> h) {
        return h.is(LocationTags.TOWNS) || h.is(LocationTags.ROAD_DESTINATIONS);
    }

    /**
     * Where the generic towns and dungeons of a region will generate: the same placement test and generation-point
     * search the chunk generator runs (vanilla's weighted pick for sets with several structures), on noise only.
     */
    private static List<Site> predict(long region, ChunkGenerator generator, RandomState random,
                                      ChunkGeneratorStructureState state, RegistryAccess access,
                                      StructureTemplateManager templates, ServerLevel level) {
        List<Site> out = new ArrayList<>();
        int cx0 = ChunkPos.getX(region) << (SCAN_SHIFT - 4), cz0 = ChunkPos.getZ(region) << (SCAN_SHIFT - 4);
        int span = 1 << (SCAN_SHIFT - 4);
        long seed = state.getLevelSeed();
        for (Holder<StructureSet> holder : state.possibleStructureSets()) {
            StructureSet set = holder.value();
            if (!relevant(set)) continue;
            RandomSpreadStructurePlacement placement = (RandomSpreadStructurePlacement) set.placement();
            int spacing = placement.spacing();
            for (int gx = Math.floorDiv(cx0, spacing); gx <= Math.floorDiv(cx0 + span - 1, spacing); gx++) {
                for (int gz = Math.floorDiv(cz0, spacing); gz <= Math.floorDiv(cz0 + span - 1, spacing); gz++) {
                    if (Thread.currentThread().isInterrupted()) return out;
                    ChunkPos pot = placement.getPotentialStructureChunk(seed, gx * spacing, gz * spacing);
                    if (pot.x < cx0 || pot.x >= cx0 + span || pot.z < cz0 || pot.z >= cz0 + span) continue;
                    if (!placement.isStructureChunk(state, pot.x, pot.z)) continue;
                    Site site = pick(set, pot, seed, generator, random, access, templates, level);
                    if (site != null && destination(site.structure())) out.add(site);
                }
            }
        }
        return out;
    }

    @Nullable
    private static Site pick(StructureSet set, ChunkPos pot, long seed, ChunkGenerator generator, RandomState random,
                             RegistryAccess access, StructureTemplateManager templates, ServerLevel level) {
        List<StructureSet.StructureSelectionEntry> entries = new ArrayList<>(set.structures());
        if (entries.size() == 1) return tryAt(entries.get(0).structure(), pot, seed, generator, random, access, templates, level);
        WorldgenRandom r = new WorldgenRandom(new LegacyRandomSource(0L));
        r.setLargeFeatureSeed(seed, pot.x, pot.z);
        int total = 0;
        for (StructureSet.StructureSelectionEntry e : entries) total += e.weight();
        while (!entries.isEmpty()) {
            int roll = r.nextInt(total);
            int i = 0;
            for (StructureSet.StructureSelectionEntry e : entries) {
                roll -= e.weight();
                if (roll < 0) break;
                i++;
            }
            StructureSet.StructureSelectionEntry chosen = entries.get(Math.min(i, entries.size() - 1));
            Site s = tryAt(chosen.structure(), pot, seed, generator, random, access, templates, level);
            if (s != null) return s;
            entries.remove(chosen);
            total -= chosen.weight();
        }
        return null;
    }

    @Nullable
    private static Site tryAt(Holder<Structure> holder, ChunkPos pot, long seed, ChunkGenerator generator, RandomState random,
                              RegistryAccess access, StructureTemplateManager templates, ServerLevel level) {
        Structure structure = holder.value();
        Structure.GenerationContext ctx = new Structure.GenerationContext(access, generator, generator.getBiomeSource(),
                random, templates, seed, pot, level, structure.biomes()::contains);
        Optional<Structure.GenerationStub> stub;
        try {
            stub = structure.findValidGenerationPoint(ctx);
        } catch (RuntimeException e) {
            return null;
        }
        if (stub.isEmpty()) return null;
        boolean town = holder.is(LocationTags.TOWNS);
        int radius = structure instanceof SettlementStructure ss ? ss.size.radius() : 24;
        var pos = stub.get().position();
        ResourceLocation id = holder.unwrapKey().map(ResourceKey::location).orElse(null);
        if (id == null) return null;
        return new Site(holder, id, pot.toLong(), pos.getX(), pos.getY(), pos.getZ(), radius, town);
    }

    private static void applyScan(ServerLevel level, RoadsData data, Scan scan) {
        SCANNING.remove(scan.region());
        data.scanned.add(scan.region());
        data.setDirty();
        int towns = 0, dungeons = 0;
        for (Site site : scan.sites()) {
            if (data.settlementByKey.containsKey(site.chunk())) continue;
            if (plannedAt(data, site.x(), site.z()) != null) continue;
            ChunkPos start = new ChunkPos(site.chunk());
            String loc = SettlementNames.locationId(Level.OVERWORLD, site.id(), start);
            String name = SettlementNames.name(Level.OVERWORLD, site.structure(), site.id(), loc);
            int r = site.radius();
            Settlement s = new Settlement(data.nextSettlementId++, site.chunk(), site.x(), site.y(), site.z(),
                    site.x() - r, site.z() - r, site.x() + r, site.z() + r, name, site.id().toString(), loc, false);
            s.town = site.town();
            data.addSettlement(s);
            if (s.town) {
                towns++;
                RoadNetwork.planConnections(data, s);
            } else {
                dungeons++;
            }
        }
        predictedTowns += towns;
        predictedDungeons += dungeons;
        scannedRegions++;
        if (towns + dungeons > 0) {
            Skycraft.LOGGER.debug("Skycraft roads: region {} predicts {} generic towns and {} dungeons", new ChunkPos(scan.region()), towns, dungeons);
        }
    }

    /** Is this a place type the plan registers (settlements and trail destinations)? */
    static boolean planned(PlaceType t) {
        return t.isSettlement() || RoadGraph.isTrailDestination(t);
    }
}
