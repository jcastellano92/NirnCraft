package com.nirncraft.atlas;

import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.TamrielMap;
import com.nirncraft.tamriel.terrain.MapWaterFeature;
import com.nirncraft.testing.BoundedServerProvider;
import com.nirncraft.worldgen.FeaturePlaceContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Client generation parity (docs/ATLAS-CLIENT-GENERATION.md, stage 3): chunks across varied Tamriel terrain made by
 * Minecraft's own NoiseBasedChunkGenerator (biomes, doFill, buildSurface, then the mod's map water) against the GPU
 * generator (atlas_gen, Vulkan) and its C++ reference, block for block. A dev tool, off by default (the owner's lean
 * suite): {@code NIRNCRAFT_GEN_PARITY=1}, {@code NIRNCRAFT_ATLAS_LIB=<atlas_core.dll>}, optional
 * {@code NIRNCRAFT_GEN_PARITY_OUT=<report.txt>}, {@code NIRNCRAFT_GEN_PARITY_N=<chunks per kind>}.
 */
@EnabledIfEnvironmentVariable(named = "NIRNCRAFT_GEN_PARITY", matches = "1")
@ExtendWith(BoundedServerProvider.class)
class AtlasGenParityTest {
    static final long SEED = 1234L;

    @Test
    void gpuChunksMatchJava(MinecraftServer server) throws Exception {
        RegistryAccess ra = server.registryAccess();
        WorldDimensions dims = ra.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions();
        LevelStem stem = dims.dimensions().get(LevelStem.OVERWORLD);
        NoiseBasedChunkGenerator gen = (NoiseBasedChunkGenerator) stem.generator();
        NoiseGeneratorSettings settings = gen.generatorSettings().value();
        RandomState rs = RandomState.create(ra.lookupOrThrow(Registries.NOISE), SEED, settings);
        DimensionType dt = stem.type().value();
        LevelHeightAccessor heights = LevelHeightAccessor.create(dt.minY(), dt.height());
        StringBuilder report = new StringBuilder();
        long t0 = System.nanoTime();
        AtlasGenProgram prog = AtlasGenProgram.compile(gen, settings, rs, heights, SEED, s -> 0);
        report.append(String.format("program: %d words, %d column fields, %d interpolated, %d registers, %d ore veins, %d blocks, %d biomes (%.0f ms)%n",
                prog.words.length, prog.fields.size(), prog.interpolated, prog.registers, prog.oreVeins, prog.blocks.size(),
                prog.biomes.size(), (System.nanoTime() - t0) / 1e6));
        for (int f = 0; f < prog.fields.size(); f++) report.append("  field ").append(f).append(": ").append(prog.fields.get(f)).append('\n');

        // ---- the sample: chunks of each kind of terrain ----
        int per = Integer.parseInt(System.getenv().getOrDefault("NIRNCRAFT_GEN_PARITY_N", "3"));
        Map<String, List<ChunkPos>> kinds = pick(per);
        List<ChunkPos> all = new ArrayList<>();
        kinds.values().forEach(all::addAll);
        report.append("chunks: ").append(all.size()).append(' ').append(kinds).append('\n');

        // ---- Java ----
        Map<ChunkPos, ProtoChunk> biomeChunks = new HashMap<>();
        Map<ChunkPos, ProtoChunk> reference = new LinkedHashMap<>();
        PalettedContainerFactory containers = PalettedContainerFactory.create(ra);
        StructureManager structures = new StructureManager(null, null, null) {
            @Override
            public List<StructureStart> startsForStructure(int x, int z, Predicate<Structure> m) {
                return List.of();
            }
        };
        long jt = System.nanoTime();
        for (ChunkPos pos : all) reference.put(pos, javaChunk(pos, gen, rs, heights, containers, structures, biomeChunks));
        double javaMs = (System.nanoTime() - jt) / 1e6;
        Map<String, Long> histogram = new TreeMap<>();
        BlockPos.MutableBlockPos hp = new BlockPos.MutableBlockPos();
        for (var e : reference.entrySet())
            for (int y = -64; y < 576; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        histogram.merge(name(e.getValue().getBlockState(hp.set(e.getKey().getMinBlockX() + x, y, e.getKey().getMinBlockZ() + z))), 1L, Long::sum);
        report.append("java blocks: ");
        histogram.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> report.append(e.getKey()).append(' ').append(e.getValue()).append(", "));
        report.append(System.lineSeparator());
        report.append(String.format("java: %.0f ms (%.1f ms a chunk, biomes for the 3 x 3 included)%n", javaMs, javaMs / all.size()));

        // ---- native ----
        Path lib = Path.of(System.getenv("NIRNCRAFT_ATLAS_LIB"));
        AtlasGenNative nat = AtlasGenNative.load(lib);
        if (nat == null) throw new IllegalStateException("no generator in " + lib);
        AtlasGenPages pages = new AtlasGenPages(prog, rs, gen.getBiomeSource(), heights);
        int[] xz = new int[all.size() * 2];
        for (int i = 0; i < all.size(); i++) {
            xz[i * 2] = all.get(i).x();
            xz[i * 2 + 1] = all.get(i).z();
        }
        for (int backend : new int[]{AtlasGenNative.BACKEND_VULKAN, AtlasGenNative.BACKEND_CPU}) {
            try (AtlasGenNative.Gen g = nat.create(backend)) {
                if (g == null) {
                    report.append("backend ").append(backend).append(": unavailable\n");
                    continue;
                }
                g.setProgram(prog.words);
                long pt = System.nanoTime();
                int[] miss = g.missing(xz);
                for (int i = 0; i < miss.length; i += 3) g.putPage(miss[i], miss[i + 1], miss[i + 2] != 0, pages.page(miss[i], miss[i + 1], miss[i + 2] != 0));
                double pageMs = (System.nanoTime() - pt) / 1e6;
                int[] ids = new int[all.size() * AtlasGenNative.CHUNK_WORDS];
                int[] flags = new int[all.size()];
                AtlasGenNative.Times t = g.chunks(xz, 0, ids, flags);
                // a second run: the steady cost (pipelines warm, pages resident)
                AtlasGenNative.Times t2 = g.chunks(xz, 0, ids, flags);
                report.append(String.format("%n== %s: pages %d in %.0f ms (Java), generate %.1f ms (%.2f ms a chunk; kernels %.2f ms: corners %.2f, aquifer %.2f, fill %.2f, tops %.2f, surface %.2f, pack %.2f; readback %.2f)%n",
                        g.device(), miss.length / 3, pageMs, t2.totalMs(), t2.totalMs() / all.size(), t2.kernelMs(),
                        t2.passMs()[0], t2.passMs()[1], t2.passMs()[2], t2.passMs()[3], t2.passMs()[4], t2.passMs()[5], t2.readbackMs()));
                report.append(String.format("   first run %.1f ms%n", t.totalMs()));
                compare(report, kinds, all, reference, prog, ids, flags);
                if (backend == AtlasGenNative.BACKEND_VULKAN) feed(report, AtlasClientGen.detached(prog, pages, g, gen, rs, SEED), all, reference, ids);
                if (backend == AtlasGenNative.BACKEND_VULKAN) {
                    // the control: every GPU chunk against the next chunk's Java blocks must disagree a lot
                    List<ChunkPos> rotated = new ArrayList<>(all.subList(1, all.size()));
                    rotated.add(all.getFirst());
                    Map<ChunkPos, ProtoChunk> shifted = new LinkedHashMap<>();
                    for (int i = 0; i < all.size(); i++) shifted.put(all.get(i), reference.get(rotated.get(i)));
                    StringBuilder control = new StringBuilder();
                    compare(control, kinds, all, shifted, prog, ids, flags);
                    report.append("   control (each chunk against another's Java blocks): ")
                            .append(control.toString().lines().findFirst().orElse("")).append(System.lineSeparator());
                }
            }
        }
        if ("1".equals(System.getenv("NIRNCRAFT_GEN_DEBUG"))) debugBackends(report, nat, prog, pages, all, reference);
        String text = report.toString();
        String out = System.getenv("NIRNCRAFT_GEN_PARITY_OUT");
        if (out != null) Files.writeString(Path.of(out), text);
        System.out.println(text);
    }

    /**
     * The near window after a jump (stage 2's measure): a square of W x W chunks around a camera, then the camera
     * 600 blocks east; the pages the new window lacks are computed in Java on all cores, the chunks on the GPU.
     * NIRNCRAFT_GEN_WINDOW = W (default 32: 512 blocks, a 16-chunk view radius).
     */
    @Test
    void windowAfterJump(MinecraftServer server) throws Exception {
        RegistryAccess ra = server.registryAccess();
        LevelStem stem = ra.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value()
                .createWorldDimensions().dimensions().get(LevelStem.OVERWORLD);
        NoiseBasedChunkGenerator gen = (NoiseBasedChunkGenerator) stem.generator();
        NoiseGeneratorSettings settings = gen.generatorSettings().value();
        RandomState rs = RandomState.create(ra.lookupOrThrow(Registries.NOISE), SEED, settings);
        DimensionType dt = stem.type().value();
        LevelHeightAccessor heights = LevelHeightAccessor.create(dt.minY(), dt.height());
        AtlasGenProgram prog = AtlasGenProgram.compile(gen, settings, rs, heights, SEED, null);
        AtlasGenPages pages = new AtlasGenPages(prog, rs, gen.getBiomeSource(), heights);
        AtlasNative atlas = AtlasNative.load(Path.of(System.getenv("NIRNCRAFT_ATLAS_LIB")));
        AtlasGenNative nat = AtlasGenNative.load(Path.of(System.getenv("NIRNCRAFT_ATLAS_LIB")));
        int w = Integer.parseInt(System.getenv().getOrDefault("NIRNCRAFT_GEN_WINDOW", "32"));
        StringBuilder report = new StringBuilder();
        try (AtlasGenNative.Gen g = nat.create(AtlasGenNative.BACKEND_VULKAN); AtlasNative.Context ctx = atlas.create(AtlasGenNative.BACKEND_VULKAN)) {
            g.setProgram(prog.words);
            report.append(String.format("window %d x %d chunks, %s, %d threads for pages%n", w, w, g.device(),
                    java.util.concurrent.ForkJoinPool.commonPool().getParallelism() + 1));
            // Whiterun, then 600 blocks east (37.5 chunks: a new window that overlaps the old by a sliver)
            for (int[] cam : new int[][]{{0, 0}, {600, 0}}) {
                int ccx = cam[0] >> 4, ccz = cam[1] >> 4;
                int[] xz = new int[w * w * 2];
                int n = 0;
                // nearest first, as the near field asks
                List<int[]> order = new ArrayList<>();
                for (int dz = -w / 2; dz < w / 2; dz++)
                    for (int dx = -w / 2; dx < w / 2; dx++) order.add(new int[]{ccx + dx, ccz + dz});
                order.sort(java.util.Comparator.comparingInt(c -> (c[0] - ccx) * (c[0] - ccx) + (c[1] - ccz) * (c[1] - ccz)));
                for (int[] c : order) {
                    xz[n++] = c[0];
                    xz[n++] = c[1];
                }
                long t0 = System.nanoTime();
                int[] miss = g.missing(xz);
                int[][] made = new int[miss.length / 3][];
                java.util.stream.IntStream.range(0, made.length).parallel()
                        .forEach(i -> made[i] = pages.page(miss[i * 3], miss[i * 3 + 1], miss[i * 3 + 2] != 0));
                double pageMs = (System.nanoTime() - t0) / 1e6;
                for (int i = 0; i < made.length; i++) g.putPage(miss[i * 3], miss[i * 3 + 1], miss[i * 3 + 2] != 0, made[i]);
                double putMs = (System.nanoTime() - t0) / 1e6 - pageMs;
                long t1 = System.nanoTime();
                int[] ids = new int[w * w * AtlasGenNative.CHUNK_WORDS];
                AtlasGenNative.Times t = g.chunks(xz, 0, ids, null);
                double genMs = (System.nanoTime() - t1) / 1e6;
                long sections = 0;
                for (int c = 0; c < w * w; c++)
                    for (int sy = 0; sy < 40; sy++) {
                        int base = c * AtlasGenNative.CHUNK_WORDS + sy * 2048;
                        boolean any = false;
                        for (int k = 0; k < 2048 && !any; k++) any = ids[base + k] != 0;
                        if (any) sections++;
                    }
                report.append(String.format("camera %d, %d: %d pages in %.0f ms (+ %.0f ms to the device), %d chunks generated in %.0f ms "
                                + "(kernels %.1f ms: corners %.1f, aquifer %.1f, fill %.1f, tops %.1f, surface %.1f, pack %.1f; readback %.1f); "
                                + "%d non-air sections (%.4f ms of kernel a section); total %.0f ms%n",
                        cam[0], cam[1], made.length, pageMs, putMs, w * w, genMs, t.kernelMs(), t.passMs()[0], t.passMs()[1],
                        t.passMs()[2], t.passMs()[3], t.passMs()[4], t.passMs()[5], t.readbackMs(), sections,
                        t.kernelMs() / Math.max(1, sections), (System.nanoTime() - t0) / 1e6));
                // into an atlas context: generate (pages resident now) + the atlas_upload_section path, then its build
                int[] secs = new int[1];
                long t2 = System.nanoTime();
                AtlasGenNative.Times ti = g.into(ctx.handle(), xz, null, secs);
                double intoMs = (System.nanoTime() - t2) / 1e6;
                long t3 = System.nanoTime();
                ctx.update(cam[0], 80, cam[1], 0);
                double updMs = (System.nanoTime() - t3) / 1e6;
                report.append(String.format("   into the context: %d sections in %.0f ms (generate %.0f, ingest %.0f); atlas_update to settle %.0f ms%n",
                        secs[0], intoMs, ti.totalMs() - ti.ingestMs(), ti.ingestMs(), updMs));
            }
        }
        String out = System.getenv("NIRNCRAFT_GEN_PARITY_OUT");
        if (out != null) Files.writeString(Path.of(out + ".window.txt"), report.toString());
        System.out.println(report);
    }

    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Against the real server (stage 4): with NIRNCRAFT_TEST_WORLD=tamriel the JUnit server's overworld is Tamriel (seed
     * 1234, structures on); its fully generated chunks (terrain, features, structures, in its own order) against the
     * GPU's terrain alone and the GPU's terrain plus the client's decoration (AtlasGenFeatures over the 3 x 3).
     */
    @Test
    void realWorld(MinecraftServer server) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("tamriel".equals(System.getenv("NIRNCRAFT_TEST_WORLD")), "the flat test world");
        if (server.overworld() == null) {
            // the JUnit server loads no levels: create them as a dedicated server does (on its thread)
            server.submit(() -> {
                try {
                    java.lang.reflect.Method m = MinecraftServer.class.getDeclaredMethod("createLevels");
                    m.setAccessible(true);
                    m.invoke(server);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }).join();
        }
        net.minecraft.server.level.ServerLevel ow = server.overworld();
        NoiseBasedChunkGenerator gen = (NoiseBasedChunkGenerator) ow.getChunkSource().getGenerator();
        RandomState rs = ow.getChunkSource().randomState();
        long seed = ow.getSeed();
        AtlasGenProgram prog = AtlasGenProgram.compile(gen, gen.generatorSettings().value(), rs, ow, seed, null);
        AtlasGenPages pages = new AtlasGenPages(prog, rs, gen.getBiomeSource(), ow);
        AtlasGenFeatures features = new AtlasGenFeatures(gen, ow, server.registryAccess(), ow, seed, prog, true);
        int per = Integer.parseInt(System.getenv().getOrDefault("NIRNCRAFT_GEN_PARITY_N", "2"));
        Map<String, List<ChunkPos>> kinds = pick(per);
        List<ChunkPos> all = new ArrayList<>();
        kinds.values().forEach(all::addAll);
        StringBuilder report = new StringBuilder(String.format("real world: seed %d, %d chunks%n", seed, all.size()));
        long t0 = System.nanoTime();
        Map<ChunkPos, ChunkAccess> real = new LinkedHashMap<>();
        for (ChunkPos p : all) real.put(p, server.submit(() -> (ChunkAccess) ow.getChunk(p.x(), p.z())).join());
        report.append(String.format("server: %.0f ms for the chunks (and the neighbours they need)%n", (System.nanoTime() - t0) / 1e6));
        Set<ChunkPos> need = new java.util.LinkedHashSet<>();
        for (ChunkPos p : all)
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) need.add(new ChunkPos(p.x() + dx, p.z() + dz));
        List<ChunkPos> needList = new ArrayList<>(need);
        int[] xz = new int[needList.size() * 2];
        for (int i = 0; i < needList.size(); i++) {
            xz[i * 2] = needList.get(i).x();
            xz[i * 2 + 1] = needList.get(i).z();
        }
        AtlasGenNative nat = AtlasGenNative.load(Path.of(System.getenv("NIRNCRAFT_ATLAS_LIB")));
        Map<Long, int[]> chunkIds = new HashMap<>();
        Map<Long, int[]> biomePages = new java.util.concurrent.ConcurrentHashMap<>();
        try (AtlasGenNative.Gen g = nat.create(AtlasGenNative.BACKEND_VULKAN)) {
            g.setProgram(prog.words);
            int[] miss = g.missing(xz);
            int[][] made = new int[miss.length / 3][];
            java.util.stream.IntStream.range(0, made.length).parallel().forEach(i -> made[i] = pages.page(miss[i * 3], miss[i * 3 + 1], miss[i * 3 + 2] != 0));
            for (int i = 0; i < made.length; i++) {
                g.putPage(miss[i * 3], miss[i * 3 + 1], miss[i * 3 + 2] != 0, made[i]);
                if (miss[i * 3 + 2] != 0) biomePages.put(ChunkPos.pack(miss[i * 3], miss[i * 3 + 1]), made[i]);
            }
            int[] ids = new int[needList.size() * AtlasGenNative.CHUNK_WORDS];
            g.chunks(xz, 0, ids, null);
            for (int i = 0; i < needList.size(); i++)
                chunkIds.put(needList.get(i).pack(), Arrays.copyOfRange(ids, i * AtlasGenNative.CHUNK_WORDS, (i + 1) * AtlasGenNative.CHUNK_WORDS));
        }
        need.parallelStream().forEach(p -> biomePages.computeIfAbsent(p.pack(), k -> pages.page(p.x(), p.z(), true)));
        int biomeAt = prog.fields.size() * 256 + 256;
        AtlasGenFeatures.Terrain terrain = new AtlasGenFeatures.Terrain() {
            public int id(int x, int y, int z) {
                int[] c = chunkIds.get(ChunkPos.pack(x >> 4, z >> 4));
                if (c == null) return -1;
                if (y < -64 || y > 575) return 0;
                int b = (x & 15) + 16 * ((z & 15) + 16 * (y + 64));
                int w = c[b >> 1];
                return (b & 1) != 0 ? w >>> 16 : w & 0xFFFF;
            }

            public int quartBiome(int qx, int qy, int qz) {
                int[] pg = biomePages.get(ChunkPos.pack(qx >> 2, qz >> 2));
                if (pg == null) return -1;
                int y = Math.max(-16, Math.min(159, qy));
                int i = ((qx & 3) * 4 + (qz & 3)) * AtlasGenProgram.QY + (y + 16);
                int w = pg[biomeAt + (i >> 1)];
                return (i & 1) != 0 ? w >>> 16 : w & 0xFFFF;
            }
        };
        long ft = System.nanoTime();
        Map<ChunkPos, AtlasGenFeatures.Window> windows = new HashMap<>();
        for (ChunkPos p : all) {
            AtlasGenFeatures.Window w = features.new Window(terrain);
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) features.decorate(p.x() + dx, p.z() + dz, w);
            windows.put(p, w);
        }
        double fms = (System.nanoTime() - ft) / 1e6;
        report.append(String.format("client decoration: %d chunks in %.0f ms (%.1f ms each), %d feature failures%n",
                features.decorated, fms, fms / features.decorated, features.failures));
        features.failed.forEach((k, v) -> report.append("  failed ").append(k).append(": ").append(v).append(System.lineSeparator()));
        for (int pass = 0; pass < 2; pass++) {
            long total = 0, same = 0;
            Map<String, Long> pairs = new TreeMap<>();
            Map<String, long[]> byKind = new LinkedHashMap<>();
            BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
            for (ChunkPos p : all) {
                String kind = kinds.entrySet().stream().filter(e -> e.getValue().contains(p)).findFirst().map(Map.Entry::getKey).orElse("?");
                long[] k = byKind.computeIfAbsent(kind, x -> new long[2]);
                ChunkAccess c = real.get(p);
                ProtoChunk decorated = windows.get(p).chunk(p.x(), p.z());
                for (int y = -64; y < 640; y++)
                    for (int z = 0; z < 16; z++)
                        for (int x = 0; x < 16; x++) {
                            int wx = p.getMinBlockX() + x, wz = p.getMinBlockZ() + z;
                            BlockState mine = pass == 1 ? decorated.getBlockState(new BlockPos(wx, y, wz))
                                    : prog.blocks.get(y > 575 ? 0 : terrain.id(wx, y, wz));
                            BlockState theirs = c.getBlockState(bp.set(wx, y, wz));
                            total++;
                            k[0]++;
                            if (theirs == mine) {
                                same++;
                                k[1]++;
                            } else pairs.merge(name(theirs) + " -> " + name(mine), 1L, Long::sum);
                        }
            }
            report.append(String.format("%s: agreement %.4f %% (%d of %d blocks differ)%n", pass == 0 ? "terrain only" : "terrain + client features",
                    100.0 * same / total, total - same, total));
            for (var e : byKind.entrySet())
                report.append(String.format("  %-9s %.4f %%%n", e.getKey(), 100.0 * e.getValue()[1] / Math.max(1, e.getValue()[0])));
            pairs.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(30)
                    .forEach(e -> report.append(String.format("  %8d  %s%n", e.getValue(), e.getKey())));
        }
        String out = System.getenv("NIRNCRAFT_GEN_PARITY_OUT");
        if (out != null) Files.writeString(Path.of(out + ".real.txt"), report.toString());
        System.out.println(report);
    }

    /** Chunks of each terrain kind on a scan of the map (deterministic). */
    static Map<String, List<ChunkPos>> pick(int per) {
        Map<String, List<ChunkPos>> kinds = new LinkedHashMap<>();
        String[] names = {"mountain", "high", "coast", "ocean", "river", "lake", "desert", "swamp", "plains", "road"};
        for (String n : names) kinds.put(n, new ArrayList<>());
        kinds.get("plains").add(new ChunkPos(0, 0));  // Whiterun
        MapSample s = new MapSample();
        Set<ChunkPos> used = new HashSet<>();
        used.add(new ChunkPos(0, 0));
        for (int z = TamrielMap.MIN_Z + 300; z < TamrielMap.MAX_Z; z += 397)
            for (int x = TamrielMap.MIN_X + 300; x < TamrielMap.MAX_X; x += 409) {
                TamrielMap.sample(x, z, s);
                if (!s.inTamriel) continue;
                String k = s.coastDistance < -300 ? "ocean"
                        : Math.abs(s.coastDistance) < 20 ? "coast"
                        : s.inWater() && s.river > 0.5f && s.waterLevel > TamrielMap.SEA_LEVEL ? "river"
                        : s.inWater() && s.lake > 0.5f ? "lake"
                        : s.elevation > 420 ? "high"
                        : s.elevation > 220 && s.ridge > 0.3f ? "mountain"
                        : s.dunes > 0.3f ? "desert"
                        : s.wetland > 0.5f ? "swamp"
                        : s.road > 0.5f ? "road"
                        : s.roughness < 0.25f && s.coastDistance > 300 ? "plains" : null;
                if (k == null || kinds.get(k).size() >= per) continue;
                ChunkPos p = new ChunkPos(x >> 4, z >> 4);
                if (used.add(p)) kinds.get(k).add(p);
            }
        return kinds;
    }

    static ProtoChunk biomes(ChunkPos pos, NoiseBasedChunkGenerator gen, RandomState rs, LevelHeightAccessor heights,
                             PalettedContainerFactory containers, StructureManager structures, Map<ChunkPos, ProtoChunk> cache) {
        ProtoChunk c = cache.get(pos);
        if (c != null) return c;
        c = new ProtoChunk(pos, UpgradeData.EMPTY, heights, containers, null);
        gen.createBiomes(rs, Blender.empty(), structures, c).join();
        c.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
        cache.put(pos, c);
        return c;
    }

    static ProtoChunk javaChunk(ChunkPos pos, NoiseBasedChunkGenerator gen, RandomState rs, LevelHeightAccessor heights,
                                PalettedContainerFactory containers, StructureManager structures, Map<ChunkPos, ProtoChunk> cache) {
        Map<ChunkPos, ProtoChunk> around = new HashMap<>();
        Set<Holder<Biome>> possible = new HashSet<>();
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                ChunkPos p = new ChunkPos(pos.x() + dx, pos.z() + dz);
                ProtoChunk b = biomes(p, gen, rs, heights, containers, structures, cache);
                around.put(p, b);
                b.collectBiomesInPalette(possible);
            }
        // the chunk itself: a fresh proto chunk with its own biomes (the cache's copy keeps only biomes)
        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, heights, containers, null);
        gen.createBiomes(rs, Blender.empty(), structures, chunk).join();
        chunk.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
        around.put(pos, chunk);
        BiomeManager biomes = new BiomeManager((qx, qy, qz) -> {
            ProtoChunk c = around.get(new ChunkPos(qx >> 2, qz >> 2));
            if (c == null) throw new IllegalStateException("biome outside the 3 x 3 at quart " + qx + ", " + qz);
            return c.getNoiseBiome(qx, qy, qz);
        }, BiomeManager.obfuscateSeed(SEED));
        gen.buildTerrain(chunk, Blender.empty(), rs, structures, biomes, null, possible).join();
        // the mod's map water (the first decoration feature), over this chunk only
        WorldGenLevel level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getHeight" -> args != null && args.length == 3
                            ? chunk.getHeight((Heightmap.Types) args[0], (int) args[1] & 15, (int) args[2] & 15) + 1
                            : heights.getHeight();
                    case "getMinY" -> heights.getMinY();
                    case "getBlockState" -> chunk.getBlockState((BlockPos) args[0]);
                    case "setBlock" -> {
                        chunk.setBlockState((BlockPos) args[0], (BlockState) args[1], 0);
                        yield true;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "parity level";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        new MapWaterFeature(com.nirncraft.worldgen.NoneFeatureConfiguration.CODEC).place(new FeaturePlaceContext<>(level, gen, null, pos.getWorldPosition(), com.nirncraft.worldgen.NoneFeatureConfiguration.INSTANCE));
        return chunk;
    }

    /** Block for block, by kind; the differences by (Java, GPU) block pair. */
    static void compare(StringBuilder report, Map<String, List<ChunkPos>> kinds, List<ChunkPos> all,
                        Map<ChunkPos, ProtoChunk> reference, AtlasGenProgram prog, int[] ids, int[] flags) {
        long total = 0, same = 0;
        Map<String, long[]> byKind = new LinkedHashMap<>();
        Map<String, Long> pairs = new TreeMap<>();
        Map<String, String> example = new HashMap<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int flagged = 0;
        for (int i = 0; i < all.size(); i++) {
            ChunkPos pos = all.get(i);
            if (flags[i] != 0) flagged++;
            String kind = kinds.entrySet().stream().filter(e -> e.getValue().contains(pos)).findFirst().map(Map.Entry::getKey).orElse("?");
            long[] k = byKind.computeIfAbsent(kind, x -> new long[2]);
            ProtoChunk ref = reference.get(pos);
            for (int ly = 0; ly < 640; ly++)
                for (int lz = 0; lz < 16; lz++)
                    for (int lx = 0; lx < 16; lx++) {
                        int b = lx + 16 * (lz + 16 * ly);
                        int w = ids[i * AtlasGenNative.CHUNK_WORDS + (b >> 1)];
                        int gpu = (b & 1) != 0 ? w >>> 16 : w & 0xFFFF;
                        BlockState java = ref.getBlockState(p.set(pos.getMinBlockX() + lx, ly - 64, pos.getMinBlockZ() + lz));
                        Integer jid = prog.blockIds.get(java);
                        total++;
                        k[0]++;
                        if (jid != null && jid == gpu) {
                            same++;
                            k[1]++;
                            continue;
                        }
                        String gs = gpu < prog.blocks.size() ? name(prog.blocks.get(gpu)) : "#" + gpu;
                        String key = name(java) + " -> " + gs;
                        pairs.merge(key, 1L, Long::sum);
                        example.putIfAbsent(key, (pos.getMinBlockX() + lx) + " " + (ly - 64) + " " + (pos.getMinBlockZ() + lz));
                    }
        }
        report.append(String.format("agreement %.5f %% (%d of %d blocks differ); chunks flagged inexact: %d%n",
                100.0 * same / total, total - same, total, flagged));
        for (var e : byKind.entrySet())
            report.append(String.format("  %-9s %.5f %%%n", e.getKey(), 100.0 * e.getValue()[1] / Math.max(1, e.getValue()[0])));
        pairs.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(40)
                .forEach(e -> report.append(String.format("  %8d  %s   (e.g. %s)%n", e.getValue(), e.getKey(), example.get(e.getKey()))));
    }

    /**
     * Stage 5's corrections: the generated chunk as the feed carries it (AtlasClientGen.decode) against the client
     * feed's own reading of Java's chunk (AtlasClientSections.read's rule: far ids, deep solid rock left out). Equal
     * sections are skipped at upload (AtlasEditsClient's hash), so only differences would go up again.
     */
    static void feed(StringBuilder report, AtlasClientGen cg, List<ChunkPos> all, Map<ChunkPos, ProtoChunk> reference, int[] ids) {
        long same = 0, differ = 0, onlyGen = 0, onlyFeed = 0, staleRock = 0;
        java.util.IdentityHashMap<BlockState, Integer> cache = new java.util.IdentityHashMap<>();
        for (int i = 0; i < all.size(); i++) {
            ChunkPos pos = all.get(i);
            ProtoChunk c = reference.get(pos);
            AtlasSections.Decoded g = cg.decode(pos.x(), pos.z(), ids, i);
            int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    low = Math.min(low, c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
                    high = Math.max(high, c.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
                }
            int minSec = c.getMinSectionY(), sy1 = Math.min(Math.floorDiv(high, 16), c.getMaxSectionY());
            for (int s = 0; s < 40; s++) {
                int sy = minSec + s;
                int[] fed = null;
                if (sy <= sy1) {
                    var sec = c.getSection(s);
                    if (!(AtlasSections.deep(sy, low) && AtlasSections.solidRock(sec)) && !sec.hasOnlyAir()) fed = AtlasSections.ids(sec, cache);
                }
                int[] gen = g.sections()[s];
                if (fed == null && gen == null) continue;
                if (fed == null) onlyGen++;
                else if (gen == null) {
                    onlyFeed++;
                    // deep rock by content? (solidRock reads the palette, which may list states the blocks no longer have)
                    var sec = c.getSection(s);
                    boolean rock = true;
                    for (int b = 0; b < 4096 && rock; b++) rock = sec.getBlockState(b & 15, b >> 8, b >> 4 & 15).isSolidRender();
                    if (rock && AtlasSections.deep(sy, low)) staleRock++;
                }
                else if (java.util.Arrays.equals(fed, gen)) same++;
                else differ++;
            }
        }
        report.append(String.format("   feed: %d sections equal to the client feed's, %d differ, %d only generated, %d only in the feed (%d of them deep solid rock by content)%n",
                same, differ, onlyGen, onlyFeed, staleRock));
    }

    /** Where Vulkan and the C++ reference part: the density bits, then the substance before the surface. */
    static void debugBackends(StringBuilder report, AtlasGenNative nat, AtlasGenProgram prog, AtlasGenPages pages,
                              List<ChunkPos> all, Map<ChunkPos, ProtoChunk> reference) {
        try (AtlasGenNative.Gen vk = nat.create(AtlasGenNative.BACKEND_VULKAN); AtlasGenNative.Gen cpu = nat.create(AtlasGenNative.BACKEND_CPU)) {
            if (vk == null || cpu == null) return;
            int[] xz = new int[all.size() * 2];
            for (int i = 0; i < all.size(); i++) {
                xz[i * 2] = all.get(i).x();
                xz[i * 2 + 1] = all.get(i).z();
            }
            int n = all.size(), cw = AtlasGenNative.CHUNK_WORDS;
            int[][] v = new int[17][], c = new int[17][];
            for (AtlasGenNative.Gen g : new AtlasGenNative.Gen[]{vk, cpu}) {
                g.setProgram(prog.words);
                int[] miss = g.missing(xz);
                for (int i = 0; i < miss.length; i += 3) g.putPage(miss[i], miss[i + 1], miss[i + 2] != 0, pages.page(miss[i], miss[i + 1], miss[i + 2] != 0));
                for (int mode : new int[]{0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}) {
                    int[] ids = new int[n * cw];
                    g.chunks(xz, mode, ids, null);
                    if (g == vk) v[mode] = ids; else c[mode] = ids;
                }
            }
            int shown = 0;
            long densDiff = 0, subDiff = 0, finalDiff = 0;
            for (int i = 0; i < n; i++)
                for (int b = 0; b < 163840; b++) {
                    int k = i * cw + (b >> 1), sh = (b & 1) * 16;
                    int vd = (v[3][k] >>> sh & 0xFFFF) << 16 | (v[4][k] >>> sh & 0xFFFF);
                    int cd = (c[3][k] >>> sh & 0xFFFF) << 16 | (c[4][k] >>> sh & 0xFFFF);
                    int vs = v[2][k] >>> sh & 0xFFFF, cs = c[2][k] >>> sh & 0xFFFF;
                    int vf = v[0][k] >>> sh & 0xFFFF, cf = c[0][k] >>> sh & 0xFFFF;
                    if (vd != cd) densDiff++;
                    if (vs != cs) subDiff++;
                    if (vf != cf) finalDiff++;
                    if ((vd != cd || vs != cs) && shown < 4) {
                        shown++;
                        int x = all.get(i).getMinBlockX() + (b & 15), z = all.get(i).getMinBlockZ() + (b >> 4 & 15), y = (b >> 8) - 64;
                        report.append(String.format("   %d %d %d: density vk %s (%08x) cpu %s (%08x); substance vk %s cpu %s%n", x, y, z,
                                Float.intBitsToFloat(vd), vd, Float.intBitsToFloat(cd), cd, name(prog.blocks.get(vs)), name(prog.blocks.get(cs))));
                        String[] what = {"barrier", "l1", "l2", "s12", "p12", "i1/i2/d1"};
                        for (int q = 0; q < what.length; q++) {
                            int mh = 5 + q * 2;
                            int va = (v[mh][k] >>> sh & 0xFFFF) << 16 | (v[mh + 1][k] >>> sh & 0xFFFF);
                            int ca = (c[mh][k] >>> sh & 0xFFFF) << 16 | (c[mh + 1][k] >>> sh & 0xFFFF);
                            report.append(String.format("      %s vk %08x (%s / %d) cpu %08x (%s / %d)%n", what[q], va, Float.intBitsToFloat(va), va,
                                    ca, Float.intBitsToFloat(ca), ca));
                        }
                    }
                }
            report.append(String.format("vulkan vs cpu: density bits differ %d, substance %d, final %d blocks%n", densDiff, subDiff, finalDiff));
        }
    }

    static String name(BlockState s) {
        return s.getBlock().builtInRegistryHolder().key().identifier().getPath();
    }
}
