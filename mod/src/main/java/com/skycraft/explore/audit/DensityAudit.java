package com.skycraft.explore.audit;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.skycraft.Skycraft;
import com.skycraft.explore.MinorSites;
import com.skycraft.explore.SiteKind;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.terrain.Beyond;
import com.skycraft.world.LocationTags;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The exploration density audit: is Nirn "Skyrim-dense"? It samples 2048x2048-block areas in every region (or tiles
 * the whole Tamriel raster) and, using only placement math plus the structures' own cheap acceptance checks (the
 * same {@code findValidGenerationPoint} the game calls, which reads noise heights but generates nothing), lists the
 * points of interest around them:
 * <ul>
 *     <li><b>settlement</b>: named settlements and generic towns, villages, hamlets, farmsteads, taverns;</li>
 *     <li><b>dungeon</b>: named dungeons and forts and random caves, ruins, barrows, forts;</li>
 *     <li><b>landmark</b>: standing stones, word walls, dragon mounds, shrines, named landmarks, random surface sites;</li>
 *     <li><b>small</b>: the minor sites ({@link MinorSites}), checked against the real terrain for water;</li>
 *     <li><b>feature</b>: rarity-filtered site features (wayside shrines, bandit camps, shrines), estimated as a
 *     seeded Bernoulli per chunk from their placement rarity and the biome's feature list.</li>
 * </ul>
 * For every land sample point (32-block grid) it measures the distance to the nearest notable POI (any of the above),
 * to the nearest major site (a settlement, dungeon or landmark: what Skyrim marks on the map), and the same without
 * the minor sites ("before").
 * Results are written as CSV for {@code tools/explore/density_audit.py}, which draws the heatmap and the report.
 */
public final class DensityAudit {
    public static final double WALK_SPEED = 4.3;
    public static final int AREA = 2048;
    public static final int STEP = 32;
    public static final int MARGIN = 640;
    public static final int TARGET_NOTABLE = 250;
    public static final int TARGET_MAJOR = 600;

    enum Cat { SETTLEMENT, DUNGEON, LANDMARK, SMALL, FEATURE }

    record Poi(int x, int z, Cat cat, String what) {
    }

    record Area(String label, int cx, int cz) {
    }

    /** Rarity of the site features we count, with a guess at how often their own ground checks pass. */
    private static final Map<String, Double> SITE_FEATURES = Map.of(
            "skycraft:wayside_shrine", 0.5,
            "skycraft:bandit_camp", 0.35,
            "skycraft:standing_stone_shrine", 0.5,
            "skycraft:word_wall_shrine", 0.5,
            "skycraft:word_wall_shrine_highlands", 0.5);
    private static final String[] FORAGE = {"patch", "nirnroot", "snowberr", "berries", "moon_sugar", "deathbell",
            "nightshade", "mirriam", "mushroom", "lavender", "roses", "grape", "reeds", "lichen", "parasol"};

    private final ServerLevel level;
    private final RegistryAccess access;
    private final ChunkGenerator generator;
    private final BiomeSource biomes;
    private final RandomState randomState;
    private final StructureTemplateManager templates;
    private final ChunkGeneratorStructureState structureState;
    private final LevelHeightAccessor height;
    private final long seed;
    private final Consumer<String> progress;

    private final Map<Long, List<Poi>> structureCache = new ConcurrentHashMap<>();
    private final Map<Holder<Biome>, double[]> biomeCache = new ConcurrentHashMap<>();
    private final List<Holder<PlacedFeature>> siteFeatures = new ArrayList<>();
    private final List<Double> siteAccept = new ArrayList<>();
    private final List<Double> siteRate = new ArrayList<>();
    private final List<Holder<PlacedFeature>> forage = new ArrayList<>();
    private final List<Double> forageRate = new ArrayList<>();

    public DensityAudit(ServerLevel level, Consumer<String> progress) {
        this.level = level;
        this.access = level.registryAccess();
        this.generator = level.getChunkSource().getGenerator();
        this.biomes = generator.getBiomeSource();
        this.randomState = level.getChunkSource().randomState();
        this.templates = level.getStructureManager();
        this.structureState = level.getChunkSource().getGeneratorState();
        this.height = level;
        this.seed = level.getSeed();
        this.progress = progress;
        Registry<PlacedFeature> placed = access.registryOrThrow(Registries.PLACED_FEATURE);
        placed.holders().forEach(h -> {
            String id = h.key().location().toString();
            double rate = perChunk(h.value());
            if (SITE_FEATURES.containsKey(id)) {
                siteFeatures.add(h);
                siteAccept.add(SITE_FEATURES.get(id));
                siteRate.add(rate);
            } else if (h.key().location().getNamespace().equals(Skycraft.MODID)) {
                for (String f : FORAGE) {
                    if (id.contains(f)) {
                        forage.add(h);
                        forageRate.add(rate);
                        break;
                    }
                }
            }
        });
    }

    /** Expected placements per chunk from a placed feature's count and rarity modifiers. */
    static double perChunk(PlacedFeature f) {
        double rate = 1;
        for (PlacementModifier m : f.placement()) {
            JsonElement json = PlacementModifier.CODEC.encodeStart(JsonOps.INSTANCE, m).result().orElse(null);
            if (json == null || !json.isJsonObject()) continue;
            var o = json.getAsJsonObject();
            String type = o.has("type") ? o.get("type").getAsString() : "";
            if (type.endsWith("rarity_filter") && o.has("chance")) rate /= Math.max(1, o.get("chance").getAsInt());
            else if (type.endsWith(":count") && o.has("count")) rate *= average(o.get("count"));
        }
        return rate;
    }

    private static double average(JsonElement provider) {
        if (provider.isJsonPrimitive()) return provider.getAsDouble();
        var o = provider.getAsJsonObject();
        if (o.has("value") && o.get("value").isJsonPrimitive()) return o.get("value").getAsDouble();
        if (o.has("min_inclusive") && o.has("max_inclusive")) return (o.get("min_inclusive").getAsDouble() + o.get("max_inclusive").getAsDouble()) / 2;
        if (o.has("min") && o.has("max")) return (o.get("min").getAsDouble() + o.get("max").getAsDouble()) / 2;
        return 1;
    }

    // ================================================================== areas

    /** Up to {@code perRegion} area centres in every authored land region, plus the foreign lands and realms. */
    public List<Area> regionAreas(int perRegion, int realms) {
        Map<Integer, List<int[]>> cells = new HashMap<>();
        MapSample s = new MapSample();
        int c = TamrielMap.CELL;
        for (int cz = 2; cz < TamrielMap.HEIGHT_CELLS - 2; cz += 2) {
            for (int cx = 2; cx < TamrielMap.WIDTH_CELLS - 2; cx += 2) {
                int x = TamrielMap.MIN_X + cx * c + c / 2, z = TamrielMap.MIN_Z + cz * c + c / 2;
                TamrielMap.sample(x, z, s);
                if (s.coastDistance < 40 || s.inWater()) continue;
                cells.computeIfAbsent(s.region, k -> new ArrayList<>()).add(new int[]{x, z});
            }
        }
        List<Area> out = new ArrayList<>();
        for (Map.Entry<Integer, List<int[]>> e : cells.entrySet()) {
            Region r = Regions.byIndex(e.getKey());
            if (r == null || "sea".equals(r.kind()) || "sea".equals(r.province())) continue;
            List<int[]> pts = e.getValue();
            long sx = 0, sz = 0;
            for (int[] p : pts) {
                sx += p[0];
                sz += p[1];
            }
            int mx = (int) (sx / pts.size()), mz = (int) (sz / pts.size());
            List<int[]> chosen = new ArrayList<>();
            chosen.add(nearest(pts, mx, mz));
            while (chosen.size() < perRegion && chosen.size() < pts.size()) {
                int[] best = null;
                long bestD = -1;
                for (int[] p : pts) {
                    long d = Long.MAX_VALUE;
                    for (int[] q : chosen) d = Math.min(d, sq(p[0] - q[0]) + sq(p[1] - q[1]));
                    if (d > bestD) {
                        bestD = d;
                        best = p;
                    }
                }
                if (best == null || bestD < sq(AREA / 2)) break;
                chosen.add(best);
            }
            for (int i = 0; i < chosen.size(); i++) out.add(new Area(r.id() + "#" + i, chosen.get(i)[0], chosen.get(i)[1]));
        }
        // the fixed foreign lands, by their lore centres
        int[][] foreign = {{0, -11500}, {-1500, -11000}, {17600, 2800}, {17600, 5200}, {16800, 600}, {22200, 5200},
                {-19800, 6300}, {-19800, 4000}, {-18300, 11300}, {-19300, 20600}, {-15200, 17000}};
        for (int[] f : foreign) {
            int[] land = landNear(f[0], f[1], 2400);
            if (land != null) out.add(new Area(TamrielMap.region(land[0], land[1]).id() + "#f", land[0], land[1]));
        }
        // the endless lands: one area on each landmass of a ring of super-cells
        int added = 0;
        for (int ring = 3; ring <= 6 && added < realms; ring++) {
            for (int[] d : new int[][]{{ring, 0}, {-ring, 0}, {0, ring}, {0, -ring}, {ring, ring}, {-ring, -ring}, {ring, -ring}, {-ring, ring}}) {
                if (added >= realms) break;
                int x = d[0] * Beyond.SUPER + Beyond.SUPER / 2, z = d[1] * Beyond.SUPER + Beyond.SUPER / 2;
                int[] land = landNear(x, z, Beyond.SUPER / 2);
                if (land == null) continue;
                Region r = TamrielMap.region(land[0], land[1]);
                if (!"realm".equals(r.province())) continue;
                out.add(new Area(r.id() + "#r", land[0], land[1]));
                added++;
            }
        }
        return out;
    }

    /** Every 2048-block tile of the Tamriel raster with some land. */
    public List<Area> tamrielTiles() {
        List<Area> out = new ArrayList<>();
        MapSample s = new MapSample();
        for (int z = TamrielMap.MIN_Z; z < TamrielMap.MAX_Z; z += AREA) {
            for (int x = TamrielMap.MIN_X; x < TamrielMap.MAX_X; x += AREA) {
                int land = 0;
                for (int dz = 64; dz < AREA; dz += 256) {
                    for (int dx = 64; dx < AREA; dx += 256) {
                        TamrielMap.sample(x + dx, z + dz, s);
                        if (s.coastDistance > 0 && !s.inWater()) land++;
                    }
                }
                if (land > 0) out.add(new Area("tile_" + x + "_" + z, x + AREA / 2, z + AREA / 2));
            }
        }
        return out;
    }

    private static int[] landNear(int x, int z, int radius) {
        MapSample s = new MapSample();
        int[] best = null;
        double bestCoast = 0;
        for (int dz = -radius; dz <= radius; dz += Math.max(64, radius / 12)) {
            for (int dx = -radius; dx <= radius; dx += Math.max(64, radius / 12)) {
                TamrielMap.sample(x + dx, z + dz, s);
                if (s.inWater() || s.coastDistance < 60) continue;
                // prefer inland points near the given centre
                double score = Math.min(s.coastDistance, 600) - Math.sqrt(dx * dx + dz * dz) * 0.15;
                if (best == null || score > bestCoast) {
                    best = new int[]{x + dx, z + dz};
                    bestCoast = score;
                }
            }
        }
        return best;
    }

    private static int[] nearest(List<int[]> pts, int x, int z) {
        int[] best = pts.get(0);
        long bd = Long.MAX_VALUE;
        for (int[] p : pts) {
            long d = sq(p[0] - x) + sq(p[1] - z);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private static long sq(long v) {
        return v * v;
    }

    // ================================================================== POIs

    /** Every POI in the block box. */
    List<Poi> poisIn(int x0, int z0, int x1, int z1) {
        List<Poi> out = new ArrayList<>();
        for (Place p : Places.overlapping(x0, z0, x1, z1)) {
            if (p.x() < x0 || p.x() > x1 || p.z() < z0 || p.z() > z1) continue;
            Cat c = switch (p.type()) {
                case CAPITAL, CITY, TOWN, VILLAGE, FARM -> Cat.SETTLEMENT;
                case DUNGEON_FIXED, FORT -> Cat.DUNGEON;
                default -> Cat.LANDMARK;
            };
            out.add(new Poi(p.x(), p.z(), c, "place:" + p.type().id()));
        }
        // random-spread structure sets (cached per start chunk across areas)
        Registry<StructureSet> sets = access.registryOrThrow(Registries.STRUCTURE_SET);
        List<Poi> structures = new ArrayList<>();
        sets.holders().forEach(set -> {
            if (!(set.value().placement() instanceof RandomSpreadStructurePlacement rs)) return;
            int spacing = rs.spacing();
            int c0x = Math.floorDiv(x0 >> 4, spacing), c1x = Math.floorDiv(x1 >> 4, spacing);
            int c0z = Math.floorDiv(z0 >> 4, spacing), c1z = Math.floorDiv(z1 >> 4, spacing);
            for (int cx = c0x; cx <= c1x; cx++) {
                for (int cz = c0z; cz <= c1z; cz++) {
                    ChunkPos chunk = rs.getPotentialStructureChunk(seed, cx * spacing, cz * spacing);
                    int bx = chunk.getMiddleBlockX(), bz = chunk.getMiddleBlockZ();
                    if (bx < x0 || bx > x1 || bz < z0 || bz > z1) continue;
                    long key = chunk.toLong() * 31 + set.key().location().hashCode();
                    List<Poi> found = structureCache.computeIfAbsent(key, k -> startAt(set, rs, chunk));
                    structures.addAll(found);
                }
            }
        });
        out.addAll(structures);
        // minor sites
        int s0x = Math.floorDiv(x0, MinorSites.CELL), s1x = Math.floorDiv(x1, MinorSites.CELL);
        int s0z = Math.floorDiv(z0, MinorSites.CELL), s1z = Math.floorDiv(z1, MinorSites.CELL);
        for (int cx = s0x; cx <= s1x; cx++) {
            for (int cz = s0z; cz <= s1z; cz++) {
                MinorSites.Site s = MinorSites.at(seed, cx, cz);
                if (s == null || s.x() < x0 || s.x() > x1 || s.z() < z0 || s.z() > z1) continue;
                boolean waterSite = s.kind() == SiteKind.FISHING_DOCK || s.kind() == SiteKind.SHIPWRECK_DEBRIS;
                if (!waterSite && water(s.x(), s.z())) continue;
                boolean pinned = s.unique() != null || s.kind() == SiteKind.TREASURE_CACHE;
                if (!pinned && nearSurfaceStart(structures, s.x(), s.z())) continue;
                out.add(new Poi(s.x(), s.z(), Cat.SMALL, s.kind().id));
            }
        }
        // rarity-filtered site features, estimated per chunk (biome read once per 4x4 chunks, cached across areas)
        for (int gx = (x0 >> 6); gx <= (x1 >> 6); gx++) {
            for (int gz = (z0 >> 6); gz <= (z1 >> 6); gz++) {
                for (Poi p : featureBlock(gx, gz)) {
                    if (p.x() >= x0 && p.x() <= x1 && p.z() >= z0 && p.z() <= z1) out.add(p);
                }
            }
        }
        return out;
    }

    private final Map<Long, List<Poi>> featureCache = new ConcurrentHashMap<>();
    private final Map<Long, Holder<Biome>> biomeGrid = new ConcurrentHashMap<>();

    /** The biome of the 64-block grid cell holding (x, z), sampled at its centre. */
    private Holder<Biome> biomeCell(int x, int z) {
        int gx = x >> 6, gz = z >> 6;
        return biomeGrid.computeIfAbsent(ChunkPos.asLong(gx, gz), k -> biomeAt((gx << 6) + 32, (gz << 6) + 32, true));
    }

    private List<Poi> featureBlock(int gx, int gz) {
        return featureCache.computeIfAbsent(ChunkPos.asLong(gx, gz), k -> {
            List<Poi> out = new ArrayList<>(2);
            Holder<Biome> biome = biomeCell(gx << 6, gz << 6);
            for (int cx = gx << 2; cx < (gx << 2) + 4; cx++) {
                for (int cz = gz << 2; cz < (gz << 2) + 4; cz++) {
                    for (int i = 0; i < siteFeatures.size(); i++) {
                        Holder<PlacedFeature> f = siteFeatures.get(i);
                        if (!biome.value().getGenerationSettings().hasFeature(f.value())) continue;
                        long h = MinorSitesHash.of(seed, cx, cz, i);
                        double p = siteRate.get(i) * siteAccept.get(i);
                        if (MinorSitesHash.unit(h, 0) >= p) continue;
                        int bx = (cx << 4) + (int) (MinorSitesHash.unit(h, 1) * 16), bz = (cz << 4) + (int) (MinorSitesHash.unit(h, 2) * 16);
                        if (water(bx, bz)) continue;
                        out.add(new Poi(bx, bz, Cat.FEATURE, f.unwrapKey().map(r -> r.location().getPath()).orElse("feature")));
                    }
                }
            }
            return out;
        });
    }

    private static boolean nearSurfaceStart(List<Poi> structures, int x, int z) {
        for (Poi p : structures) {
            if (p.cat() == Cat.DUNGEON && !p.what().contains("fort")) continue;
            long dx = p.x() - x, dz = p.z() - z;
            if (dx * dx + dz * dz < 40 * 40) return true;
        }
        return false;
    }

    /** The start a random-spread set makes in {@code chunk}, if any (vanilla's weighted try-in-turn selection). */
    private List<Poi> startAt(Holder<StructureSet> set, RandomSpreadStructurePlacement rs, ChunkPos chunk) {
        try {
            if (!rs.isStructureChunk(structureState, chunk.x, chunk.z)) return List.of();
            List<StructureSet.StructureSelectionEntry> entries = new ArrayList<>(set.value().structures());
            WorldgenRandom pick = new WorldgenRandom(new LegacyRandomSource(0L));
            pick.setLargeFeatureSeed(seed, chunk.x, chunk.z);
            int total = 0;
            for (var e : entries) total += e.weight();
            while (!entries.isEmpty()) {
                StructureSet.StructureSelectionEntry entry;
                if (entries.size() == 1) {
                    entry = entries.get(0);
                } else {
                    int roll = pick.nextInt(total);
                    int i = 0;
                    for (var e : entries) {
                        roll -= e.weight();
                        if (roll < 0) break;
                        i++;
                    }
                    entry = entries.get(Math.min(i, entries.size() - 1));
                }
                Structure structure = entry.structure().value();
                java.util.Optional<Structure.GenerationStub> stub = java.util.Optional.empty();
                for (int attempt = 0; attempt < 4; attempt++) {
                    try {
                        // vanilla jigsaw templates cache their palettes in a plain HashMap: retry when two threads race
                        stub = structure.findValidGenerationPoint(new Structure.GenerationContext(access, generator, biomes,
                                randomState, templates, seed, chunk, height, structure.biomes()::contains));
                        break;
                    } catch (java.util.ConcurrentModificationException e) {
                        Thread.onSpinWait();
                    }
                }
                if (stub.isPresent()) {
                    var pos = stub.get().position();
                    Holder<Structure> h = entry.structure();
                    if (h.is(LocationTags.IGNORED_LANDMARKS) || hidden(h)) return List.of();
                    Cat cat = h.is(LocationTags.TOWNS) ? Cat.SETTLEMENT : h.is(LocationTags.DUNGEONS) ? Cat.DUNGEON : Cat.LANDMARK;
                    String id = h.unwrapKey().map(k -> k.location().getPath()).orElse("?");
                    return List.of(new Poi(pos.getX(), pos.getZ(), cat, id));
                }
                entries.remove(entry);
                total -= entry.weight();
            }
        } catch (RuntimeException e) {
            Skycraft.LOGGER.debug("Density audit: start check failed at {}", chunk, e);
        }
        return List.of();
    }

    /**
     * Structures that don't count: every vanilla structure. The buried and sunken ones (mineshafts, strongholds,
     * ancient cities, buried treasure, ocean ruins) are never found from the surface, and the visible ones (ruined
     * portals, pillager outposts) are not Skyrim content; they still generate, but the audit judges Skycraft's own.
     */
    private static boolean hidden(Holder<Structure> h) {
        return h.unwrapKey().map(k -> k.location().getNamespace().equals("minecraft")).orElse(false);
    }

    private boolean water(int x, int z) {
        int land = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, height, randomState);
        int top = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, randomState);
        if (top > land) return true;
        MapSample s = TamrielMap.sampleScratch(x, z);
        return s.inWater() && land < s.waterLevel;
    }

    private Holder<Biome> biomeAt(int x, int z, boolean surface) {
        int y = surface ? Math.max(generator.getSeaLevel(), (int) TamrielMap.elevation(x, z)) : generator.getSeaLevel();
        return biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), randomState.sampler());
    }

    /** Expected forage patches per chunk in a biome. */
    private double foragePerChunk(Holder<Biome> biome) {
        return biomeCache.computeIfAbsent(biome, b -> {
            double sum = 0;
            for (int i = 0; i < forage.size(); i++) {
                if (b.value().getGenerationSettings().hasFeature(forage.get(i).value())) sum += forageRate.get(i);
            }
            return new double[]{sum};
        })[0];
    }

    // ================================================================== measuring

    static final class RegionStats {
        final String region, province;
        int points;
        long[] cat = new long[Cat.values().length];
        double forage;
        int road, inTown;
        final List<Integer> dNotable = new ArrayList<>(), dBefore = new ArrayList<>(), dMajor = new ArrayList<>();
        /** Without the estimated site features: named places and structures only, and those plus the minor sites. */
        final List<Integer> dCore = new ArrayList<>(), dCoreSmall = new ArrayList<>();
        final Map<String, Integer> kinds = new LinkedHashMap<>();

        RegionStats(String region, String province) {
            this.region = region;
            this.province = province;
        }
    }

    /** Runs the audit over {@code areas} and writes the CSVs into {@code dir}; returns a short summary. */
    public String run(List<Area> areas, Path dir, String tag) throws IOException {
        Files.createDirectories(dir);
        Map<String, RegionStats> stats = new LinkedHashMap<>();
        Path pointsFile = dir.resolve("audit_" + tag + "_points.csv");
        try (PrintWriter pts = new PrintWriter(Files.newBufferedWriter(pointsFile, StandardCharsets.UTF_8))) {
            pts.println("x,z,region,d_notable,d_notable_before,d_major,d_small,d_core,d_core_small");
            // gathering the POIs is the expensive part (structure checks, biomes): do it in parallel batches
            java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
            try {
                for (int b = 0; b < areas.size(); b += 16) {
                    List<Area> batch = areas.subList(b, Math.min(areas.size(), b + 16));
                    List<List<Poi>> found = pool.submit(() -> batch.parallelStream().map(a -> poisIn(a.cx() - AREA / 2 - MARGIN,
                            a.cz() - AREA / 2 - MARGIN, a.cx() + AREA / 2 + MARGIN, a.cz() + AREA / 2 + MARGIN)).toList()).join();
                    for (int i = 0; i < batch.size(); i++) measure(batch.get(i), found.get(i), stats, pts);
                    String msg = "Density audit (" + tag + "): " + Math.min(areas.size(), b + 16) + "/" + areas.size() + " areas";
                    Skycraft.LOGGER.info(msg);
                    progress.accept(msg);
                }
            } finally {
                pool.shutdown();
            }
        }
        writeRegions(stats, dir.resolve("audit_" + tag + "_regions.csv"));
        writeKinds(stats, dir.resolve("audit_" + tag + "_kinds.csv"));
        int ok = 0, total = 0;
        for (RegionStats r : stats.values()) {
            if (r.points < 50) continue;
            total++;
            if (pctWithin(r.dCoreSmall, TARGET_NOTABLE) >= 90 && pctWithin(r.dMajor, TARGET_MAJOR) >= 90) ok++;
        }
        return String.format(Locale.ROOT, "%d areas, %d regions measured, %d meet the targets; CSV in %s", areas.size(), total, ok, dir);
    }

    private void measure(Area a, List<Poi> pois, Map<String, RegionStats> stats, PrintWriter pts) {
        int x0 = a.cx() - AREA / 2, z0 = a.cz() - AREA / 2, x1 = x0 + AREA, z1 = z0 + AREA;
        int n = pois.size();
        int[] px = new int[n], pz = new int[n];
        Cat[] pc = new Cat[n];
        for (int i = 0; i < n; i++) {
            px[i] = pois.get(i).x();
            pz[i] = pois.get(i).z();
            pc[i] = pois.get(i).cat();
        }
        MapSample s = new MapSample();
        for (int z = z0 + STEP / 2; z < z1; z += STEP) {
            for (int x = x0 + STEP / 2; x < x1; x += STEP) {
                TamrielMap.sample(x, z, s);
                if (s.coastDistance <= 0 || s.inWater()) continue;
                Region r = TamrielMap.region(x, z);
                if (r == null || "sea".equals(r.kind())) continue;
                RegionStats st = stats.computeIfAbsent(r.id(), k -> new RegionStats(r.id(), r.province()));
                long bn = Long.MAX_VALUE, bb = Long.MAX_VALUE, bm = Long.MAX_VALUE, bs = Long.MAX_VALUE, bcs = Long.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    long d = sq(px[i] - x) + sq(pz[i] - z);
                    if (d < bn) bn = d;
                    switch (pc[i]) {
                        case SMALL -> {
                            if (d < bs) bs = d;
                            if (d < bcs) bcs = d;
                        }
                        case SETTLEMENT, DUNGEON, LANDMARK -> {
                            if (d < bm) bm = d;
                            if (d < bb) bb = d;
                            if (d < bcs) bcs = d;
                        }
                        default -> {
                            if (d < bb) bb = d;
                        }
                    }
                }
                int dn = dist(bn), db = dist(bb), dm = dist(bm), ds = dist(bs);
                st.points++;
                st.dNotable.add(dn);
                st.dBefore.add(db);
                st.dMajor.add(dm);
                st.dCore.add(dm);
                st.dCoreSmall.add(dist(bcs));
                if (s.road > 0.5f) st.road++;
                st.forage += foragePerChunk(biomeCell(x, z));
                if (((x - x0) / STEP) % 2 == 0 && ((z - z0) / STEP) % 2 == 0) {
                    pts.println(x + "," + z + "," + r.id() + "," + dn + "," + db + "," + dm + "," + ds + "," + dm + "," + dist(bcs));
                }
            }
        }
        // POIs inside the area count towards the region they stand in
        for (Poi p : pois) {
            if (p.x() < x0 || p.x() >= x1 || p.z() < z0 || p.z() >= z1) continue;
            Region r = TamrielMap.region(p.x(), p.z());
            RegionStats st = stats.get(r.id());
            if (st == null) continue;
            st.cat[p.cat().ordinal()]++;
            st.kinds.merge(p.cat().name().toLowerCase(Locale.ROOT) + ":" + p.what(), 1, Integer::sum);
            if (p.cat() == Cat.SETTLEMENT) st.inTown++;
        }
    }

    private static int dist(long sq) {
        return sq == Long.MAX_VALUE ? 99999 : (int) Math.round(Math.sqrt(sq));
    }

    static double pctWithin(List<Integer> d, int limit) {
        if (d.isEmpty()) return 0;
        int c = 0;
        for (int v : d) if (v <= limit) c++;
        return 100.0 * c / d.size();
    }

    static int percentile(List<Integer> d, double p) {
        if (d.isEmpty()) return -1;
        int[] a = d.stream().mapToInt(Integer::intValue).toArray();
        Arrays.sort(a);
        return a[Math.min(a.length - 1, (int) Math.floor(p * (a.length - 1)))];
    }

    private void writeRegions(Map<String, RegionStats> stats, Path file) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            w.println("region,province,land_km2,settlements_km2,dungeons_km2,landmarks_km2,small_sites_km2,site_features_km2,"
                    + "forage_patches_km2,encounters_per_km,notable_p50,notable_p90,notable_p99,walk_s_p50,walk_s_p90,"
                    + "pct_notable_250,pct_major_600,before_p50,before_p90,before_pct_250,core_p50,core_p90,core_pct_250,"
                    + "core_small_p50,core_small_p90,core_small_pct_250,verdict");
            for (RegionStats r : stats.values()) {
                if (r.points == 0) continue;
                double km2 = r.points * (double) STEP * STEP / 1e6;
                double forage = r.forage / r.points * (1e6 / 256.0);
                // wilderness encounters: one roll every ~270 s outside towns; road travellers every ~40 s near roads
                double perKm = 1000.0 / WALK_SPEED;
                double enc = perKm / 270.0 * 0.85 + (r.road / (double) r.points) * perKm / 40.0 * 0.5;
                int p50 = percentile(r.dNotable, 0.5), p90 = percentile(r.dNotable, 0.9), p99 = percentile(r.dNotable, 0.99);
                double pn = pctWithin(r.dNotable, TARGET_NOTABLE), pm = pctWithin(r.dMajor, TARGET_MAJOR);
                // judged without the estimated site features: named places, structures and minor sites must do it alone
                double pcs = pctWithin(r.dCoreSmall, TARGET_NOTABLE);
                String verdict = pcs >= 90 && pm >= 90 ? "ok" : pcs >= 90 ? "major_gaps" : "notable_gaps";
                w.printf(Locale.ROOT, "%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.1f,%.2f,%d,%d,%d,%.0f,%.0f,%.1f,%.1f,%d,%d,%.1f,%d,%d,%.1f,%d,%d,%.1f,%s%n",
                        r.region, r.province, km2,
                        r.cat[Cat.SETTLEMENT.ordinal()] / km2, r.cat[Cat.DUNGEON.ordinal()] / km2, r.cat[Cat.LANDMARK.ordinal()] / km2,
                        r.cat[Cat.SMALL.ordinal()] / km2, r.cat[Cat.FEATURE.ordinal()] / km2, forage, enc,
                        p50, p90, p99, p50 / WALK_SPEED, p90 / WALK_SPEED, pn, pm,
                        percentile(r.dBefore, 0.5), percentile(r.dBefore, 0.9), pctWithin(r.dBefore, TARGET_NOTABLE),
                        percentile(r.dCore, 0.5), percentile(r.dCore, 0.9), pctWithin(r.dCore, TARGET_NOTABLE),
                        percentile(r.dCoreSmall, 0.5), percentile(r.dCoreSmall, 0.9), pcs, verdict);
            }
        }
    }

    private void writeKinds(Map<String, RegionStats> stats, Path file) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            w.println("region,kind,count");
            for (RegionStats r : stats.values()) {
                for (var e : r.kinds.entrySet()) w.println(r.region + "," + e.getKey() + "," + e.getValue());
            }
        }
    }

    /** Small seeded hash for the per-chunk feature estimate (independent of the game's decoration seeds). */
    static final class MinorSitesHash {
        static long of(long seed, int cx, int cz, int i) {
            long z = seed ^ (cx * 0x9E3779B97F4A7C15L) ^ (cz * 0xC2B2AE3D27D4EB4FL) ^ (i * 0x165667B19E3779F9L);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        static double unit(long h, int k) {
            long z = h + k * 0x9E3779B97F4A7C15L;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return ((z ^ (z >>> 31)) >>> 11) * 0x1.0p-53;
        }
    }

    public ResourceLocation dimension() {
        return level.dimension().location();
    }
}
