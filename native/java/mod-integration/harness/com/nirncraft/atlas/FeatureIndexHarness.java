package com.nirncraft.atlas;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.nirncraft.explore.MinorSites;
import com.nirncraft.landmarks.LandmarkSite;
import com.nirncraft.landmarks.Landmarks;
import com.nirncraft.scatter.Discoveries;
import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.Regions;
import com.nirncraft.tamriel.TamrielBiomeSource;
import com.nirncraft.tamriel.TamrielMap;
import com.nirncraft.tamriel.terrain.TamrielChannel;
import com.nirncraft.tamriel.terrain.TamrielHeight;
import com.nirncraft.worldgen.WorldDensitySampler;
import com.nirncraft.worldplan.scene.ScenePlan;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * The generated-feature index for the test harness, outside Minecraft: for a seed, representative spots of every
 * generated feature type over the whole map, so every type gets visual tests (tools/harness/features.py drives this,
 * tools/harness/feature_scenes.py turns the index into scenes).
 * <ul>
 *   <li><b>biomes</b>: the world's own biome source (TamrielBiomeSource with the seeded climate: the router's
 *   temperature / vegetation noises, the Tamriel channels and the seeded height for depth) at the surface of a coarse
 *   grid; per biome up to N spots, interior first (the same biome around), spread apart and across regions, on land
 *   unless the biome is aquatic;</li>
 *   <li><b>landmarks</b>: the seeded natural landmarks (Landmarks.inRect, every tier) and the formations of
 *   Discoveries, per kind;</li>
 *   <li><b>sites</b>: the explore module's minor sites, the hidden stashes, and the baked scene plan's scenes per kind
 *   and per anchor (waysides, camps, shrines, fords ...).</li>
 * </ul>
 * <pre>  $env:HARNESS_JAVA_OPTS='-Dharness.per=6 -Dharness.step=32'
 *   run_exact_harness.ps1 -Main FeatureIndexHarness -Out features.json -Seed 1234 -Threads 8</pre>
 * Output: {"_seed", "biomes": {id: [spot]}, "landmarks": {kind: [spot]}, "sites": {kind: [spot]}, "_found": counts};
 * spot = {x, z, ground, region, water, ...}: biome spots add "interior" (0..1) and "yaw" (the bearing along which the
 * biome runs furthest, from the grid); landmark and site spots add "radius" and "views": {"approach" | "aerial": [x, z, height above
 * the surface, yaw, pitch]} (a visit pose with a clear line of sight to the spot on the seeded terrain).
 */
public final class FeatureIndexHarness {
    private FeatureIndexHarness() {}

    static final int SEA = TamrielMap.SEA_LEVEL;
    /** The whole map: Tamriel's raster and the fixed foreign lands with their seas (Beyond's core box); columns
     * outside Beyond.fixedWorldFootprint are open ocean (the endless lands are off) and are skipped. */
    static final int MAP_X0 = -27000, MAP_X1 = 26000, MAP_Z0 = -18500, MAP_Z1 = 26500;

    public static void main(String[] a) {
        try {
            run(a);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    static double r1(double v) { return Math.round(v * 10) / 10.0; }

    // ------------------------------------------------------------------------------------------ the biome source
    /** Biome holders by key, only for biomes that exist as data (as the game's registry would have them). */
    static final class Biomes implements HolderGetter<Biome> {
        final Map<ResourceKey<Biome>, Optional<Holder.Reference<Biome>>> refs = new ConcurrentHashMap<>();

        @Override
        public Optional<Holder.Reference<Biome>> get(ResourceKey<Biome> key) {
            return refs.computeIfAbsent(key, k -> {
                Identifier id = k.identifier();
                String res = "/data/" + id.getNamespace() + "/worldgen/biome/" + id.getPath() + ".json";
                return FeatureIndexHarness.class.getResource(res) == null ? Optional.empty()
                        : Optional.of(Holder.Reference.createStandAlone(this, k));
            });
        }

        @Override
        public Optional<HolderSet.Named<Biome>> get(TagKey<Biome> tag) {
            return Optional.empty();
        }
    }

    static Noise vanillaNoise(PositionalRandomFactory r, String name) throws Exception {
        try (InputStream in = FeatureIndexHarness.class.getResourceAsStream("/data/minecraft/worldgen/noise/" + name + ".json")) {
            if (in == null) throw new IllegalStateException("noise minecraft:" + name + " not on the classpath");
            NormalNoise def = NormalNoise.DIRECT_CODEC.parse(JsonOps.INSTANCE,
                    JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))).getOrThrow();
            return def.create(r.fromHashOf(Identifier.fromNamespaceAndPath("minecraft", name)));
        }
    }

    static DensitySampler.Bound bound(WorldDensitySampler s) {
        return new DensitySampler.Bound(s, SamplerContext.EMPTY_UNCACHED);
    }

    /**
     * The overworld's climate sampler as the noise router (data/nirncraft/worldgen/noise_settings/tamriel.json) makes
     * it: temperature and vegetation are vanilla's shifted noises (xz scale 0.25, shift_x / shift_z from the offset
     * noise), continentalness / erosion / ridges the Tamriel channels, depth (height - y) / 128.
     */
    static Climate.Sampler climate(long seed, TamrielHeight height) throws Exception {
        PositionalRandomFactory r = WorldgenRandom.Algorithm.XOROSHIRO.newInstance(seed).forkPositional();
        Noise temperature = vanillaNoise(r, "temperature"), vegetation = vanillaNoise(r, "vegetation"),
                offset = vanillaNoise(r, "offset");
        TamrielChannel cont = new TamrielChannel(TamrielChannel.Channel.CONTINENTALNESS);
        TamrielChannel erosion = new TamrielChannel(TamrielChannel.Channel.EROSION);
        TamrielChannel ridges = new TamrielChannel(TamrielChannel.Channel.RIDGES);
        WorldDensitySampler t = (c, x, y, z) -> shifted(temperature, offset, x, z);
        WorldDensitySampler h = (c, x, y, z) -> shifted(vegetation, offset, x, z);
        WorldDensitySampler cs = (c, x, y, z) -> (float) cont.compute(x, y, z);
        WorldDensitySampler es = (c, x, y, z) -> (float) erosion.compute(x, y, z);
        WorldDensitySampler ds = (c, x, y, z) -> (float) (0.0078125 * (height.compute(x, y, z) - y));
        WorldDensitySampler ws = (c, x, y, z) -> (float) ridges.compute(x, y, z);
        return new Climate.Sampler(bound(t), bound(h), bound(cs), bound(es), bound(ds), bound(ws));
    }

    /** minecraft:noise with shift_x / shift_z (shift_a, shift_b of the offset noise: x 4, at a quarter scale). */
    static float shifted(Noise noise, Noise offset, int x, int z) {
        double sx = offset.get(x * 0.25, 0, z * 0.25) * 4.0;
        double sz = offset.get(z * 0.25, x * 0.25, 0) * 4.0;
        return noise.get(x * 0.25 + sx, 0, z * 0.25 + sz);
    }

    // ------------------------------------------------------------------------------------------ spots
    record Spot(String kind, int x, int z, int radius, String extraKey, String extra) {}

    static String regionAt(int x, int z) {
        Region r = TamrielMap.region(x, z);
        return r == null ? "" : r.id();
    }

    /** Up to n of the given spots, spread apart (at least minSep where possible) and across regions. */
    static List<Spot> spread(List<Spot> all, int n, int minSep) {
        List<Spot> pool = new ArrayList<>(all);
        pool.sort(Comparator.comparingLong(s -> (long) s.x() * 73856093L ^ (long) s.z() * 19349663L));
        List<Spot> out = new ArrayList<>();
        java.util.Set<String> regions = new java.util.HashSet<>();
        while (out.size() < n && !pool.isEmpty()) {
            Spot best = null;
            double bestScore = -1;
            for (Spot s : pool) {
                double d = Double.MAX_VALUE;
                for (Spot o : out) d = Math.min(d, Math.hypot(s.x() - o.x(), s.z() - o.z()));
                if (!out.isEmpty() && d < minSep) continue;
                double score = (out.isEmpty() ? 1 : Math.min(d, 4000) / 4000) + (regions.contains(regionAt(s.x(), s.z())) ? 0 : 0.5);
                if (score > bestScore) {
                    bestScore = score;
                    best = s;
                }
            }
            if (best == null) {
                if (minSep <= 96) break;
                minSep /= 2;  // a compact kind: closer together rather than fewer
                continue;
            }
            out.add(best);
            regions.add(regionAt(best.x(), best.z()));
            pool.remove(best);
        }
        return out;
    }

    /**
     * Visit poses towards a spot: the camera at distance d on the bearing (of 12) with the clearest line of sight from
     * height h above the surface to a point a little above the spot; [x, z, h, yaw, pitch].
     */
    static JsonArray view(AtlasGround g, int sx, int sz, double groundAt, double d, double h, double rise) {
        double ty = groundAt + rise;
        double bestScore = -1;
        double[] best = null;
        int start = Math.floorMod(sx * 31 + sz * 17, 12);
        for (int k = 0; k < 12; k++) {
            double ang = Math.PI * 2 * ((start + k) % 12) / 12.0;
            double cx = sx + Math.cos(ang) * d, cz = sz + Math.sin(ang) * d;
            double cg = g.surface((int) Math.floor(cx), (int) Math.floor(cz));
            double ey = cg + h + 1.6;
            int clear = 0, steps = 16;
            for (int i = 1; i < steps; i++) {
                double t = i / (double) steps;
                double px = cx + (sx - cx) * t, pz = cz + (sz - cz) * t, py = ey + (ty - ey) * t;
                if (g.surface((int) Math.floor(px), (int) Math.floor(pz)) < py - 0.5) clear++;
            }
            double score = clear / (double) (steps - 1) - 0.002 * Math.max(0, cg - groundAt);  // prefer looking up less
            if (score > bestScore + 1e-9) {
                bestScore = score;
                best = new double[] {cx, cz, cg};
            }
        }
        double cx = best[0], cz = best[1], ey = best[2] + h + 1.6;
        double yaw = Math.toDegrees(Math.atan2(-(sx - cx), sz - cz));
        double pitch = Math.toDegrees(Math.atan2(ey - ty, Math.hypot(sx - cx, sz - cz)));
        JsonArray v = new JsonArray();
        v.add((int) Math.round(cx));
        v.add((int) Math.round(cz));
        v.add((int) Math.round(h));
        v.add(r1(yaw));
        v.add(r1(pitch));
        return v;
    }

    static JsonObject spotJson(AtlasGround g, Spot s, boolean views) {
        JsonObject o = new JsonObject();
        o.addProperty("x", s.x());
        o.addProperty("z", s.z());
        double ground = g.terrain(s.x(), s.z()), surface = g.surface(s.x(), s.z());
        o.addProperty("ground", r1(ground));
        o.addProperty("region", regionAt(s.x(), s.z()));
        o.addProperty("water", surface > ground + 0.5);
        if (s.extraKey() != null) o.addProperty(s.extraKey(), s.extra());
        if (views) {
            int r = Math.max(4, s.radius());
            o.addProperty("radius", r);
            JsonObject v = new JsonObject();
            double rise = Math.min(r * 0.5, 20) + 2;
            v.add("approach", view(g, s.x(), s.z(), surface, Math.max(22, r * 2.2 + 14), Math.max(3, Math.min(14, 3 + r * 0.15)), rise));
            v.add("aerial", view(g, s.x(), s.z(), surface, 60 + r * 1.5, Math.min(110, 55 + r), rise));
            o.add("views", v);
        }
        return o;
    }

    static JsonObject group(AtlasGround g, ForkJoinPool pool, Map<String, List<Spot>> byKind, int per, int minSep,
                            JsonObject found) throws Exception {
        TreeMap<String, List<Spot>> sorted = new TreeMap<>(byKind);
        List<String> keys = new ArrayList<>(sorted.keySet());
        JsonArray[] out = new JsonArray[keys.size()];
        pool.submit(() -> IntStream.range(0, keys.size()).parallel().forEach(i -> {
            JsonArray arr = new JsonArray();
            for (Spot s : spread(sorted.get(keys.get(i)), per, minSep)) arr.add(spotJson(g, s, true));
            out[i] = arr;
        })).get();
        JsonObject o = new JsonObject();
        for (int i = 0; i < keys.size(); i++) {
            o.add(keys.get(i), out[i]);
            found.addProperty(keys.get(i), sorted.get(keys.get(i)).size());
        }
        return o;
    }

    // ------------------------------------------------------------------------------------------ biomes
    static JsonObject biomes(long seed, TamrielHeight height, AtlasGround g, ForkJoinPool pool, int per, int step,
                             JsonObject found) throws Exception {
        TamrielBiomeSource source = new TamrielBiomeSource(new Biomes());
        Climate.Sampler sampler = climate(seed, height);
        int nx = (MAP_X1 - MAP_X0) / step, nz = (MAP_Z1 - MAP_Z0) / step;
        int[] biome = new int[nx * nz];
        java.util.Arrays.fill(biome, -1);
        boolean[] wet = new boolean[nx * nz];
        Map<String, Integer> ids = new ConcurrentHashMap<>();
        List<String> names = java.util.Collections.synchronizedList(new ArrayList<>());
        ThreadLocal<BiomeResolver> resolver = ThreadLocal.withInitial(() -> source.createResolver(sampler));
        // -Dharness.probe=x,z;x,z: what the biome source picks there (as /skybiome prints it), then the full index
        for (String p : System.getProperty("harness.probe", "").split(";")) {
            if (p.isBlank()) continue;
            String[] v = p.split(",");
            int px = Integer.parseInt(v[0].trim()), pz = Integer.parseInt(v[1].trim());
            int py = (int) Math.floor(height.compute(px, 0, pz));
            System.out.printf("probe %d %d (ground %d): %s%n", px, pz, py, source.describe(px, py, pz, sampler));
        }
        pool.submit(() -> IntStream.range(0, nz).parallel().forEach(j -> {
            MapSample s = new MapSample();
            BiomeResolver r = resolver.get();
            for (int i = 0; i < nx; i++) {
                int x = MAP_X0 + i * step + step / 2, z = MAP_Z0 + j * step + step / 2;
                if (!com.nirncraft.tamriel.terrain.Beyond.fixedWorldFootprint(x, z)) continue;
                TamrielMap.sample(x, z, s);
                double h = height.height(x, z, s);
                Holder<Biome> b = r.getNoiseBiome(x >> 2, ((int) Math.floor(h)) >> 2, z >> 2);
                String key = b.unwrapKey().map(k -> k.identifier().toString()).orElse("?");
                int id = ids.computeIfAbsent(key, k -> {
                    synchronized (names) {
                        names.add(k);
                        return names.size() - 1;
                    }
                });
                biome[j * nx + i] = id;
                float w = AtlasExactSource.waterAt(h, s);
                wet[j * nx + i] = (!Float.isNaN(w) && w > h + 0.5) || h < SEA - 0.5 || s.inWater();
            }
        })).get();
        // per biome: interior score (the share of the same biome within about 96 blocks) and wet share
        int nb = names.size();
        int rad = Math.max(1, Math.round(96f / step));
        int around = (2 * rad + 1) * (2 * rad + 1) - 1;
        List<List<Integer>> cells = new ArrayList<>();
        for (int b = 0; b < nb; b++) cells.add(new ArrayList<>());
        int[] interior = new int[nx * nz];
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                int c = j * nx + i, b = biome[c], same = 0;
                if (b < 0) continue;
                for (int dj = -rad; dj <= rad; dj++) {
                    for (int di = -rad; di <= rad; di++) {
                        if ((di | dj) == 0) continue;
                        int ii = i + di, jj = j + dj;
                        if (ii >= 0 && jj >= 0 && ii < nx && jj < nz && biome[jj * nx + ii] == b) same++;
                    }
                }
                interior[c] = same;
                cells.get(b).add(c);
            }
        }
        String[] keys = names.toArray(new String[0]);
        JsonArray[] out = new JsonArray[nb];
        int[][] dirs = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
        pool.submit(() -> IntStream.range(0, nb).parallel().forEach(b -> {
            List<Integer> all = cells.get(b);
            long wetN = all.stream().filter(c -> wet[c]).count();
            boolean aquatic = wetN * 2 > all.size();
            List<Integer> cand = new ArrayList<>();
            for (int c : all) if (wet[c] == aquatic) cand.add(c);
            if (cand.isEmpty()) cand = all;
            cand.sort(Comparator.comparingInt((Integer c) -> -interior[c]).thenComparingInt(c -> c));
            int top = interior[cand.get(0)];
            // interior first: the best interior spots, widening the bar until there are enough far-apart candidates
            List<Spot> picked = List.of();
            for (int bar = top; ; bar = Math.max(0, bar - Math.max(1, around / 6))) {
                List<Spot> pool2 = new ArrayList<>();
                for (int c : cand) {
                    if (interior[c] < bar) break;
                    if (pool2.size() >= 3000) break;
                    int i = c % nx, j = c / nx;
                    pool2.add(new Spot(keys[b], MAP_X0 + i * step + step / 2, MAP_Z0 + j * step + step / 2, 0, null, null));
                }
                picked = spread(pool2, per, 512);
                if (picked.size() >= per || bar <= 0) break;
            }
            JsonArray arr = new JsonArray();
            for (Spot s : picked) {
                int i = (s.x() - MAP_X0) / step, j = (s.z() - MAP_Z0) / step, c = j * nx + i;
                JsonObject o = spotJson(g, s, false);
                o.addProperty("interior", Math.round(interior[c] / (double) around * 100) / 100.0);
                o.addProperty("aquatic", aquatic);
                // look across the biome: the bearing along which it runs furthest (up to 640 blocks out)
                int bestRun = -1;
                int[] bestDir = dirs[0];
                for (int[] d : dirs) {
                    int run = 0;
                    for (int k = 1; k <= Math.max(10, 640 / step); k++) {
                        int ii = i + d[0] * k, jj = j + d[1] * k;
                        if (ii < 0 || jj < 0 || ii >= nx || jj >= nz || biome[jj * nx + ii] != b) break;
                        run++;
                    }
                    if (run > bestRun) {
                        bestRun = run;
                        bestDir = d;
                    }
                }
                o.addProperty("yaw", r1(Math.toDegrees(Math.atan2(-bestDir[0], bestDir[1]))));
                arr.add(o);
            }
            out[b] = arr;
        })).get();
        TreeMap<String, Integer> order = new TreeMap<>();
        for (int b = 0; b < nb; b++) order.put(keys[b], b);
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Integer> e : order.entrySet()) {
            String id = e.getKey().startsWith("nirncraft:") ? e.getKey().substring("nirncraft:".length()) : e.getKey();
            o.add(id, out[e.getValue()]);
            found.addProperty(id, cells.get(e.getValue()).size());
        }
        return o;
    }

    // ------------------------------------------------------------------------------------------ main
    static void run(String[] a) throws Exception {
        Path outPath = Path.of(a[2]);
        long seed = Long.parseLong(a[3]);
        int threads = Integer.parseInt(a[7]);
        int per = Integer.getInteger("harness.per", 6), step = Integer.getInteger("harness.step", 32);
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        TamrielHeight height = HarnessSeed.height(seed);
        AtlasGround world = new AtlasGround(height);
        ForkJoinPool pool = new ForkJoinPool(threads);
        int x0 = MAP_X0, z0 = MAP_Z0, x1 = MAP_X1 - 1, z1 = MAP_Z1 - 1;
        JsonObject found = new JsonObject(), fb = new JsonObject(), fl = new JsonObject(), fs = new JsonObject();
        found.add("biomes", fb);
        found.add("landmarks", fl);
        found.add("sites", fs);

        long t0 = System.nanoTime();
        JsonObject biomes = biomes(seed, height, world, pool, per, step, fb);
        double tb = (System.nanoTime() - t0) / 1e9;

        // natural landmarks (every tier), in strips aligned to the largest cell so no cell is split
        long t1 = System.nanoTime();
        int strip = 896 * 2;
        int s0 = Math.floorDiv(x0, strip);
        int strips = Math.floorDiv(x1, strip) - s0 + 1;
        ConcurrentLinkedQueue<Spot> lm = new ConcurrentLinkedQueue<>();
        pool.submit(() -> IntStream.range(0, strips).parallel().forEach(k -> {
            int a0 = Math.max(x0, (s0 + k) * strip), a1 = Math.min(x1, (s0 + k + 1) * strip - 1);
            for (LandmarkSite s : Landmarks.inRect(seed, a0, z0, a1, z1)) {
                lm.add(new Spot(s.kind().id(), s.x(), s.z(), s.kind().radius(), "tier", s.tier().name()));
            }
        })).get();
        // the rare formations of the scatter module (great arches, spire groves, dragon bones ...), and the stashes
        ConcurrentLinkedQueue<Spot> st = new ConcurrentLinkedQueue<>();
        int fc0 = Math.floorDiv(x0, Discoveries.FORMATION_CELL), fc1 = Math.floorDiv(x1, Discoveries.FORMATION_CELL);
        pool.submit(() -> IntStream.rangeClosed(fc0, fc1).parallel().forEach(cx -> {
            for (int cz = Math.floorDiv(z0, Discoveries.FORMATION_CELL); cz <= Math.floorDiv(z1, Discoveries.FORMATION_CELL); cz++) {
                Discoveries.Find f = Discoveries.formation(seed, cx, cz);
                if (f != null) lm.add(new Spot("formation/" + f.type().kind, f.x(), f.z(), f.type().radius, null, null));
            }
        })).get();
        int sc0 = Math.floorDiv(x0, Discoveries.STASH_CELL), sc1 = Math.floorDiv(x1, Discoveries.STASH_CELL);
        pool.submit(() -> IntStream.rangeClosed(sc0, sc1).parallel().forEach(cx -> {
            for (int cz = Math.floorDiv(z0, Discoveries.STASH_CELL); cz <= Math.floorDiv(z1, Discoveries.STASH_CELL); cz++) {
                Discoveries.Find f = Discoveries.stash(seed, cx, cz);
                if (f != null) st.add(new Spot("stash", f.x(), f.z(), f.type().radius, null, null));
            }
        })).get();
        Map<String, List<Spot>> lmBy = new ConcurrentHashMap<>();
        for (Spot s : lm) lmBy.computeIfAbsent(s.kind(), k -> new ArrayList<>()).add(s);
        JsonObject landmarks = group(world, pool, lmBy, per, 768, fl);
        double tl = (System.nanoTime() - t1) / 1e9;

        // minor sites, stashes, the baked scene plan (per scene kind and per anchor)
        long t2 = System.nanoTime();
        int mc0 = Math.floorDiv(x0, MinorSites.CELL), mc1 = Math.floorDiv(x1, MinorSites.CELL);
        pool.submit(() -> IntStream.rangeClosed(mc0, mc1).parallel().forEach(cx -> {
            for (int cz = Math.floorDiv(z0, MinorSites.CELL); cz <= Math.floorDiv(z1, MinorSites.CELL); cz++) {
                MinorSites.Site s = MinorSites.at(seed, cx, cz);
                if (s != null && s.x() >= x0 && s.x() <= x1 && s.z() >= z0 && s.z() <= z1) {
                    st.add(new Spot("minor/" + s.kind().id, s.x(), s.z(), s.kind().radius, null, null));
                }
            }
        })).get();
        ScenePlan plan = ScenePlan.get();
        for (int s = 0; s < plan.scenes(); s++) {
            String kind = plan.kindOf(s).replace(ScenePlan.LANDMARK_PREFIX, "lm-");
            int r = plan.radius[s];
            String anchor = plan.anchor[s] >= 0 && plan.anchor[s] < ScenePlan.ANCHOR_NAMES.length
                    ? ScenePlan.ANCHOR_NAMES[plan.anchor[s]].toLowerCase(java.util.Locale.ROOT) : "unknown";
            st.add(new Spot("scene/" + kind, plan.x[s], plan.z[s], r, "anchor", anchor));
            st.add(new Spot("anchor/" + anchor, plan.x[s], plan.z[s], r, "scene", kind));
        }
        Map<String, List<Spot>> stBy = new ConcurrentHashMap<>();
        for (Spot s : st) stBy.computeIfAbsent(s.kind(), k -> new ArrayList<>()).add(s);
        JsonObject sites = group(world, pool, stBy, per, 512, fs);
        double ts = (System.nanoTime() - t2) / 1e9;

        JsonObject out = new JsonObject();
        out.addProperty("_seed", seed);
        out.addProperty("_step", step);
        out.addProperty("_per", per);
        out.add("biomes", biomes);
        out.add("landmarks", landmarks);
        out.add("sites", sites);
        out.add("_found", found);
        Files.writeString(outPath, new GsonBuilder().disableHtmlEscaping().create().toJson(out), StandardCharsets.UTF_8);
        System.out.printf("feature index: %d biomes (%.1f s), %d landmark kinds from %d sites (%.1f s), %d site kinds from %d (%.1f s); seed %d -> %s%n",
                biomes.size(), tb, landmarks.size(), lm.size(), tl, sites.size(), st.size(), ts, seed, outPath);
    }
}
