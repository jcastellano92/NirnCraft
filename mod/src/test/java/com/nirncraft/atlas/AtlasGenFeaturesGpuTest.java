package com.nirncraft.atlas;

import com.nirncraft.testing.BoundedServerProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The client generator's features on the GPU (docs/ATLAS-CLIENT-GENERATION.md, stage 4): the Java decoration
 * (AtlasGenFeatures, the world's own feature code) as the reference. A dev tool, off by default like
 * AtlasGenParityTest: {@code NIRNCRAFT_GEN_PARITY=1 NIRNCRAFT_TEST_WORLD=tamriel}, {@code NIRNCRAFT_GEN_PARITY_N}
 * chunks per terrain kind, {@code NIRNCRAFT_GEN_PARITY_OUT} the report's path.
 */
@EnabledIfEnvironmentVariable(named = "NIRNCRAFT_GEN_PARITY", matches = "1")
@ExtendWith(BoundedServerProvider.class)
class AtlasGenFeaturesGpuTest {

    /**
     * The decoration as the generator's store stages (atlas_gen_run_store + atlas_feat_attach), GPU to GPU: a square of
     * chunks generated in two different batchings (raster batches of 64; a shuffled order in batches of 37) must give the
     * same blocks for every chunk both finished (exact across batch edges), and the features must have written blocks.
     * NIRNCRAFT_FEAT_STORE=1, NIRNCRAFT_ATLAS_LIB the atlas_core library; NIRNCRAFT_FEAT_STORE_AT "cx,cz" the centre,
     * NIRNCRAFT_FEAT_STORE_R its radius (chunks).
     */
    @Test
    void storeBatchEdges(MinecraftServer server) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("tamriel".equals(System.getenv("NIRNCRAFT_TEST_WORLD")), "the flat test world");
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("NIRNCRAFT_FEAT_STORE") != null, "no store check asked for");
        ServerLevel ow = overworld(server);
        NoiseBasedChunkGenerator gen = (NoiseBasedChunkGenerator) ow.getChunkSource().getGenerator();
        RandomState rs = ow.getChunkSource().randomState();
        long seed = ow.getSeed();
        AtlasGenProgram prog = AtlasGenProgram.compile(gen, gen.generatorSettings().value(), rs, ow, seed, null);
        AtlasFeatureProgram fp = AtlasFeatureProgram.compile(gen, ow, prog, seed, net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(seed), null);
        System.out.println("TRACE begin map");
        int[] map = AtlasGenMap.words(prog, gen.getBiomeSource());
        System.out.println("TRACE end map");
        AtlasGenNative nat = AtlasGenNative.load(Path.of(System.getenv("NIRNCRAFT_ATLAS_LIB")));
        String at = System.getenv().getOrDefault("NIRNCRAFT_FEAT_STORE_AT", "285,-202");
        int cx0 = Integer.parseInt(at.split(",")[0].trim()), cz0 = Integer.parseInt(at.split(",")[1].trim());
        int r = Integer.parseInt(System.getenv().getOrDefault("NIRNCRAFT_FEAT_STORE_R", "7"));
        List<int[]> square = new ArrayList<>();
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) square.add(new int[]{cx0 + dx, cz0 + dz});
        List<int[]> shuffled = new ArrayList<>(square);
        java.util.Collections.shuffle(shuffled, new java.util.Random(7));
        StringBuilder report = new StringBuilder();
        Map<Long, int[]> a = storeRun(nat, prog, map, fp, square, 64, "raster", report);
        Map<Long, int[]> b = storeRun(nat, prog, map, fp, shuffled, 37, "shuffled", report);
        // the terrain alone (no stages): what the features changed
        Map<Long, int[]> plain = new HashMap<>();
        try (AtlasGenNative.Gen g = nat.create(AtlasGenNative.BACKEND_VULKAN)) {
            g.setProgram(prog.words);
            g.setMap(map);
            int[] ids = new int[64 * AtlasGenNative.CHUNK_WORDS];
            List<Long> keys = new ArrayList<>(a.keySet());
            for (int i = 0; i < keys.size(); i += 64) {
                int n = Math.min(64, keys.size() - i);
                int[] xz = new int[n * 2];
                for (int j = 0; j < n; j++) {
                    xz[j * 2] = ChunkPos.getX(keys.get(i + j));
                    xz[j * 2 + 1] = ChunkPos.getZ(keys.get(i + j));
                }
                g.chunks(xz, 0, ids, null);
                for (int j = 0; j < n; j++)
                    plain.put(keys.get(i + j), java.util.Arrays.copyOfRange(ids, j * AtlasGenNative.CHUNK_WORDS, (j + 1) * AtlasGenNative.CHUNK_WORDS));
            }
        }
        long both = 0, same = 0, diffBlocks = 0, featBlocks = 0;
        for (var e : a.entrySet()) {
            int[] o = b.get(e.getKey());
            int[] t = plain.get(e.getKey());
            if (t != null)
                for (int w = 0; w < t.length; w++) {
                    if ((t[w] & 0xFFFF) != (e.getValue()[w] & 0xFFFF)) featBlocks++;
                    if ((t[w] >>> 16) != (e.getValue()[w] >>> 16)) featBlocks++;
                }
            if (o == null) continue;
            both++;
            boolean eq = true;
            for (int w = 0; w < o.length; w++) {
                if ((o[w] & 0xFFFF) != (e.getValue()[w] & 0xFFFF)) {
                    diffBlocks++;
                    eq = false;
                }
                if ((o[w] >>> 16) != (e.getValue()[w] >>> 16)) {
                    diffBlocks++;
                    eq = false;
                }
            }
            if (eq) same++;
        }
        report.append(String.format("store: %d chunks asked, raster finished %d, shuffled finished %d, both %d, alike %d, %d blocks differ; "
                + "the features changed %d blocks of the raster's chunks%n", square.size(), a.size(), b.size(), both, same, diffBlocks, featBlocks));
        System.out.println(String.format("TRACE done store-batch-edges alike %d of %d chunks, %d blocks differ, %d blocks by the features", same, both,
                diffBlocks, featBlocks));
        String out = System.getenv("NIRNCRAFT_GEN_PARITY_OUT");
        if (out != null) Files.writeString(Path.of(out + ".store.txt"), report);
        System.out.print(report);
        if (both == 0 || same != both || featBlocks == 0) throw new AssertionError(report.toString());
    }

    /** One generator with the store and the decoration's stages: the chunks in batches of `per`, then drained. */
    static Map<Long, int[]> storeRun(AtlasGenNative nat, AtlasGenProgram prog, int[] map, AtlasFeatureProgram fp, List<int[]> order, int per,
                                     String name, StringBuilder report) {
        Map<Long, int[]> done = new HashMap<>();
        long t0 = System.nanoTime();
        try (AtlasGenNative.Gen g = nat.create(AtlasGenNative.BACKEND_VULKAN)) {
            if (g == null || !g.storeSupported()) throw new IllegalStateException("no Vulkan generator with the store");
            g.setProgram(prog.words);
            g.setMap(map);
            g.storeEnable(768);
            System.out.println("TRACE begin attach " + name);
            long ta = System.nanoTime();
            java.lang.foreign.MemorySegment f = g.attachFeatures(fp.words, null, null);
            System.out.println(String.format("TRACE done attach %s %.1fs", name, (System.nanoTime() - ta) / 1e9));
            int[] xz = new int[AtlasGenNative.RUN_CHUNKS * 2];
            int[] ids = new int[AtlasGenNative.RUN_CHUNKS * AtlasGenNative.CHUNK_WORDS];
            int calls = 0;
            for (int i = 0; ; i += per) {
                int n = Math.max(0, Math.min(per, order.size() - i));
                int[] batch = new int[n * 2];
                for (int j = 0; j < n; j++) {
                    batch[j * 2] = order.get(i + j)[0];
                    batch[j * 2 + 1] = order.get(i + j)[1];
                }
                int m = g.runStore(batch, xz, ids, null, null, null);
                calls++;
                for (int j = 0; j < m; j++)
                    done.put(ChunkPos.pack(xz[j * 2], xz[j * 2 + 1]),
                            java.util.Arrays.copyOfRange(ids, j * AtlasGenNative.CHUNK_WORDS, (j + 1) * AtlasGenNative.CHUNK_WORDS));
                System.out.println("TRACE progress store-" + name + " " + Math.min(i + per, order.size()) + "/" + order.size());
                if (n == 0 && m == 0) break;
                if (calls > 1000) throw new IllegalStateException("the store does not drain");
            }
            int[] st = g.storeStats();
            report.append(String.format("store %s: %d calls, %d finished, store held %d waiting %d ready %d, %.0f ms%n", name, calls, done.size(),
                    st[0], st[1], st[2], (System.nanoTime() - t0) / 1e6));
            g.detachFeatures(f);
        }
        return done;
    }

    /** The overworld of the JUnit server (created as a dedicated server does when the test server loads none). */
    static ServerLevel overworld(MinecraftServer server) {
        if (server.overworld() == null) {
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
        return server.overworld();
    }

    /** Terrain from Minecraft's own generator (javaChunk: biomes, noise, surface, map water), made as first asked. */
    static final class JavaTerrain implements AtlasGenFeatures.Terrain {
        final NoiseBasedChunkGenerator gen;
        final RandomState rs;
        final ServerLevel level;
        final AtlasGenProgram prog;
        final PalettedContainerFactory containers;
        final StructureManager structures = new StructureManager(null, null, null) {
            @Override
            public List<StructureStart> startsForStructure(int x, int z, Predicate<Structure> m) {
                return List.of();
            }
        };
        final Map<ChunkPos, ProtoChunk> biomeCache = new ConcurrentHashMap<>();
        final Map<Long, ProtoChunk> chunks = new ConcurrentHashMap<>();
        final Map<Holder<Biome>, Integer> biomeIds = new IdentityHashMap<>();

        JavaTerrain(NoiseBasedChunkGenerator gen, RandomState rs, ServerLevel level, AtlasGenProgram prog) {
            this.gen = gen;
            this.rs = rs;
            this.level = level;
            this.prog = prog;
            this.containers = PalettedContainerFactory.create(level.registryAccess());
            for (int i = 0; i < prog.biomes.size(); i++) biomeIds.put(prog.biomes.get(i), i);
        }

        ProtoChunk chunk(int cx, int cz) {
            return chunks.computeIfAbsent(ChunkPos.pack(cx, cz), k -> AtlasGenParityTest.javaChunk(new ChunkPos(cx, cz), gen, rs, level,
                    containers, structures, biomeCache));
        }

        final BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();

        @Override
        public int id(int x, int y, int z) {
            if (y < -64 || y > 575) return 0;
            ProtoChunk c = chunk(x >> 4, z >> 4);
            Integer id = prog.blockIds.get(c.getBlockState(bp.set(x, y, z)));
            return id == null ? 0 : id;
        }

        @Override
        public int quartBiome(int qx, int qy, int qz) {
            Integer i = biomeIds.get(chunk(qx >> 2, qz >> 2).getNoiseBiome(qx, qy, qz));
            return i == null ? 0 : i;
        }
    }

    /**
     * Where the decoration's time and blocks go: every placed feature's calls, time and writes over the 3 x 3 of each
     * sample chunk, and the blocks the decoration changed in the sample chunks (by block, above / below the terrain's
     * top). The survey's measure (E:/NirnCraft-scratch/features).
     */
    @Test
    void featureProfile(MinecraftServer server) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("tamriel".equals(System.getenv("NIRNCRAFT_TEST_WORLD")), "the flat test world");
        ServerLevel ow = overworld(server);
        NoiseBasedChunkGenerator gen = (NoiseBasedChunkGenerator) ow.getChunkSource().getGenerator();
        RandomState rs = ow.getChunkSource().randomState();
        long seed = ow.getSeed();
        AtlasGenProgram prog = AtlasGenProgram.compile(gen, gen.generatorSettings().value(), rs, ow, seed, null);
        AtlasGenFeatures features = new AtlasGenFeatures(gen, ow, server.registryAccess(), ow, seed, prog, true);
        Map<String, long[]> profile = new ConcurrentHashMap<>();
        int per = Integer.parseInt(System.getenv().getOrDefault("NIRNCRAFT_GEN_PARITY_N", "11"));
        Map<String, List<ChunkPos>> kinds = AtlasGenParityTest.pick(per);
        List<ChunkPos> all = new ArrayList<>();
        kinds.values().forEach(all::addAll);
        StringBuilder report = new StringBuilder(String.format("feature profile: seed %d, %d sample chunks (each one's 3 x 3 decorated)%n", seed, all.size()));
        JavaTerrain terrain = new JavaTerrain(gen, rs, ow, prog);
        long tt = System.nanoTime();
        for (ChunkPos p : all)
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) terrain.chunk(p.x() + dx, p.z() + dz);
        report.append(String.format("java terrain: %d chunks in %.0f ms%n", terrain.chunks.size(), (System.nanoTime() - tt) / 1e6));
        // a warm-up decoration (class loading, the rule caches), then the measured ones
        features.decorate(all.getFirst().x(), all.getFirst().z(), features.new Window(terrain));
        features.profile = profile;
        long ft = System.nanoTime();
        long before = features.decorated();
        Map<ChunkPos, AtlasGenFeatures.Window> windows = new HashMap<>();
        for (ChunkPos p : all) {
            AtlasGenFeatures.Window w = features.new Window(terrain);
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) features.decorate(p.x() + dx, p.z() + dz, w);
            windows.put(p, w);
        }
        long n = features.decorated() - before;
        double fms = (System.nanoTime() - ft) / 1e6;
        report.append(String.format("decoration: %d chunks in %.0f ms (%.1f ms a chunk), %d feature failures%n", n, fms, fms / n, features.failures()));
        features.failed.forEach((k, v) -> report.append("  failed ").append(k).append(": ").append(v).append(System.lineSeparator()));
        long totalNs = profile.values().stream().mapToLong(a -> a[1]).sum();
        report.append(String.format("%nper placed feature (ms a chunk, share, calls, writes a chunk), by time:%n"));
        profile.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).forEach(e -> {
            long[] a = e.getValue();
            report.append(String.format("  %8.3f ms  %5.1f %%  %7d  %9.1f  %s%n", a[1] / 1e6 / n, 100.0 * a[1] / totalNs, a[0], (double) a[2] / n, e.getKey()));
        });
        // what the decoration changed in the (final) sample chunks
        Map<String, long[]> changed = new TreeMap<>();
        long above = 0, below = 0;
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (ChunkPos p : all) {
            ProtoChunk t = terrain.chunk(p.x(), p.z());
            ProtoChunk d = windows.get(p).chunk(p.x(), p.z());
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int top = t.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                    for (int y = -64; y < 576; y++) {
                        bp.set(p.getMinBlockX() + x, y, p.getMinBlockZ() + z);
                        BlockState a = t.getBlockState(bp), b = d.getBlockState(bp);
                        if (a == b) continue;
                        boolean up = y >= top - 1;
                        if (up) above++;
                        else below++;
                        long[] c = changed.computeIfAbsent(AtlasGenParityTest.name(b), k -> new long[2]);
                        c[up ? 0 : 1]++;
                    }
                }
        }
        report.append(String.format("%nblocks the decoration changed in the %d sample chunks: %d at or above the terrain's top - 1, %d below (a chunk: %.0f, %.0f)%n",
                all.size(), above, below, (double) above / all.size(), (double) below / all.size()));
        changed.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]))
                .limit(60).forEach(e -> report.append(String.format("  %9d up %9d down  %s%n", e.getValue()[0], e.getValue()[1], e.getKey())));
        String out = System.getenv("NIRNCRAFT_GEN_PARITY_OUT");
        if (out != null) Files.writeString(Path.of(out), report.toString());
        System.out.println(report);
    }

    // ---- the dump for native/tests/features_test.cpp ----------------------------------------------------------------

    /** Little-endian ints, streamed to the dump file. */
    static final class Out implements AutoCloseable {
        final java.io.OutputStream b;

        Out(Path file) throws java.io.IOException {
            b = new java.io.BufferedOutputStream(Files.newOutputStream(file), 1 << 20);
        }

        void i(int v) {
            try {
                b.write(v);
                b.write(v >>> 8);
                b.write(v >>> 16);
                b.write(v >>> 24);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        public void close() throws java.io.IOException {
            b.close();
        }

        void s(String v) {
            byte[] u = v.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            i(u.length);
            try {
                b.write(u, 0, u.length);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    /** A chunk's 44 sections: 0 air, 1 uniform (id), 2 the 4096 ids two to a word (b = x + 16 z + 256 y). */
    static void sections(Out o, ProtoChunk c, Map<BlockState, Integer> ids, List<String> unknown) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int x0 = c.getPos().getMinBlockX(), z0 = c.getPos().getMinBlockZ();
        int[] v = new int[4096];
        for (int s = 0; s < 44; s++) {
            int y0 = -64 + s * 16;
            boolean uniform = true;
            for (int b = 0; b < 4096; b++) {
                BlockState st = c.getBlockState(p.set(x0 + (b & 15), y0 + (b >> 8), z0 + (b >> 4 & 15)));
                Integer id = ids.get(st);
                if (id == null) {
                    if (unknown.size() < 50) unknown.add(st.toString());
                    id = 0xFFFF;
                }
                v[b] = id;
                if (v[b] != v[0]) uniform = false;
            }
            if (uniform && v[0] == 0) o.i(0);
            else if (uniform) {
                o.i(1);
                o.i(v[0]);
            } else {
                o.i(2);
                for (int b = 0; b < 4096; b += 2) o.i(v[b] | v[b + 1] << 16);
            }
        }
    }

    /**
     * The GPU decoration's reference (native atlas_feat_test): the feature program, the terrain of every sample chunk's
     * 5 x 5, each origin of its 3 x 3 decorated alone by Java (its changed blocks), each sample decorated with its 3 x 3
     * in Java's order. NIRNCRAFT_FEAT_ONLY: a regex over the placed features' keys; NIRNCRAFT_GEN_PARITY_OUT the dump.
     */
    @Test
    void featureDump(MinecraftServer server) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("tamriel".equals(System.getenv("NIRNCRAFT_TEST_WORLD")), "the flat test world");
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("NIRNCRAFT_FEAT_DUMP") != null, "no dump asked for");
        ServerLevel ow = overworld(server);
        NoiseBasedChunkGenerator gen = (NoiseBasedChunkGenerator) ow.getChunkSource().getGenerator();
        RandomState rs = ow.getChunkSource().randomState();
        long seed = ow.getSeed();
        AtlasGenProgram prog = AtlasGenProgram.compile(gen, gen.generatorSettings().value(), rs, ow, seed, null);
        AtlasGenFeatures features = new AtlasGenFeatures(gen, ow, server.registryAccess(), ow, seed, prog, true);
        String only = System.getenv("NIRNCRAFT_FEAT_ONLY");
        long ct = System.nanoTime();
        AtlasFeatureProgram fp = AtlasFeatureProgram.compile(gen, ow, prog, seed, net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(seed),
                only == null ? null : k -> k.matches(only));
        StringBuilder report = new StringBuilder(String.format("feature program: %d words, %d blocks, %d dispatched, %d left out (%.0f ms)%n",
                fp.words.length, fp.blocks.size(), fp.dispatched.size(), fp.unsupported.size(), (System.nanoTime() - ct) / 1e6));
        Map<String, Integer> why = new TreeMap<>();
        fp.unsupported.values().forEach(w -> why.merge(w, 1, Integer::sum));
        why.forEach((w, n) -> report.append(String.format("  left out: %4d  %s%n", n, w)));
        for (var pf : fp.dispatched) report.append("  dispatched ").append(fp.keys.get(pf)).append('\n');
        features.only = fp.supported::contains;
        int per = Integer.parseInt(System.getenv().getOrDefault("NIRNCRAFT_GEN_PARITY_N", "3"));
        Map<String, List<ChunkPos>> kinds = AtlasGenParityTest.pick(per);
        List<ChunkPos> samples = new ArrayList<>();
        kinds.values().forEach(samples::addAll);
        JavaTerrain terrain = new JavaTerrain(gen, rs, ow, prog);
        Map<Long, Integer> slots = new LinkedHashMap<>();
        Map<Long, Integer> origins = new LinkedHashMap<>();
        for (ChunkPos p : samples) {
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) slots.putIfAbsent(ChunkPos.pack(p.x() + dx, p.z() + dz), slots.size());
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) origins.putIfAbsent(ChunkPos.pack(p.x() + dx, p.z() + dz), origins.size());
        }
        String out = System.getenv("NIRNCRAFT_FEAT_DUMP");
        Out o = new Out(Path.of(out));
        System.out.println("TRACE begin dump-terrain");
        o.i(0x504D4446);
        o.i(2);
        o.i(fp.words.length);
        for (int w : fp.words) o.i(w);
        o.i(fp.blocks.size());
        for (BlockState s : fp.blocks) o.s(s.toString());
        o.i(fp.dispatched.size());
        for (var pf : fp.dispatched) o.s(fp.keys.get(pf));
        List<String> unknown = new ArrayList<>();
        AtlasGenFeatures.Window maskWindow = features.new Window(terrain);
        o.i(slots.size());
        for (long k : slots.keySet()) {
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            ProtoChunk c = terrain.chunk(cx, cz);
            o.i(cx);
            o.i(cz);
            sections(o, c, fp.blockIds, unknown);
            int[] q = new int[2816];
            for (int qx = 0; qx < 4; qx++)
                for (int qz = 0; qz < 4; qz++)
                    for (int qy = -16; qy < 160; qy++) {
                        Integer b = terrain.biomeIds.get(c.getNoiseBiome(cx * 4 + qx, qy, cz * 4 + qz));
                        q[(qx * 4 + qz) * 176 + qy + 16] = b == null ? 0 : b;
                    }
            for (int i = 0; i < 2816; i += 2) o.i(q[i] | q[i + 1] << 16);
            int[] m = new int[256];
            for (int i = 0; i < 256; i++) m[i] = Math.max(0, Math.min(255, features.clearance(maskWindow, cx, cz, cx * 16 + (i & 15), cz * 16 + (i >> 4))));
            for (int i = 0; i < 256; i += 4) o.i(m[i] | m[i + 1] << 8 | m[i + 2] << 16 | m[i + 3] << 24);
            int[] al = new int[768];
            for (int v = 0; v < 3; v++)
                for (int i = 0; i < 256; i++) al[v * 256 + i] = fp.alchemyClass(cx * 16 + (i & 15), cz * 16 + (i >> 4), v);
            for (int i = 0; i < 768; i += 4) o.i(al[i] | al[i + 1] << 8 | al[i + 2] << 16 | al[i + 3] << 24);
            int[] frozen = new int[8];
            for (int i = 0; i < 256; i++) {
                int x = cx * 16 + (i & 15), z = cz * 16 + (i >> 4);
                o.i(Float.floatToRawIntBits(AtlasFeatureProgram.temperatureNoise(x, z)));
                if (AtlasFeatureProgram.frozenColumn(x, z)) frozen[i >> 5] |= 1 << (i & 31);
            }
            for (int w : frozen) o.i(w);
        }
        System.out.println("TRACE end dump-terrain");
        // the origins alone
        System.out.println("TRACE begin java-isolated");
        long it = System.nanoTime(), lastTrace = it;
        int done = 0;
        long javaChanged = 0, decoNs = 0;
        o.i(origins.size());
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (long k : origins.keySet()) {
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            o.i(cx);
            o.i(cz);
            for (int dz = -2; dz <= 2; dz++)
                for (int dx = -2; dx <= 2; dx++) {
                    Integer s = slots.get(ChunkPos.pack(cx + dx, cz + dz));
                    o.i(s == null ? -1 : s);
                }
            o.i(0);
            AtlasGenFeatures.Window w = features.new Window(terrain);
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) w.chunk(cx + dx, cz + dz);  // filled before the clock
            long d0 = System.nanoTime();
            features.decorate(cx, cz, w);
            decoNs += System.nanoTime() - d0;
            List<int[]> diff = new ArrayList<>();
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    ProtoChunk t = terrain.chunk(cx + dx, cz + dz), d = w.chunk(cx + dx, cz + dz);
                    for (int y = -64; y < 640; y++)
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) {
                                bp.set((cx + dx) * 16 + x, y, (cz + dz) * 16 + z);
                                BlockState a = t.getBlockState(bp), b = d.getBlockState(bp);
                                if (a == b) continue;
                                Integer id = fp.blockIds.get(b);
                                if (id == null) {
                                    if (unknown.size() < 50) unknown.add(b.toString());
                                    id = 0xFFFF;
                                }
                                int lx = (dx + 1) * 16 + x, lz = (dz + 1) * 16 + z;
                                diff.add(new int[]{1 + ((y + 64) * 48 + lz) * 48 + lx, id});
                            }
                }
            javaChanged += diff.size();
            o.i(diff.size());
            for (int[] e : diff) {
                o.i(e[0]);
                o.i(e[1]);
            }
            done++;
            if (System.nanoTime() - lastTrace > 20_000_000_000L) {
                lastTrace = System.nanoTime();
                System.out.println("TRACE progress java-isolated " + done + "/" + origins.size());
            }
        }
        System.out.println("TRACE end java-isolated");
        double isoMs = (System.nanoTime() - it) / 1e6;
        // the targets in Java's order
        System.out.println("TRACE begin java-targets");
        o.i(samples.size());
        for (ChunkPos p : samples) {
            o.i(slots.get(p.pack()));
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) o.i(origins.get(ChunkPos.pack(p.x() + dx, p.z() + dz)));
            o.i(p.x());
            o.i(p.z());
            AtlasGenFeatures.Window w = features.new Window(terrain);
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) features.decorate(p.x() + dx, p.z() + dz, w);
            sections(o, w.chunk(p.x(), p.z()), fp.blockIds, unknown);
        }
        System.out.println("TRACE end java-targets");
        // each feature type alone (the per-type match): the origins decorated with only that type's features
        Map<Integer, List<net.minecraft.world.level.levelgen.placement.PlacedFeature>> byType = new TreeMap<>();
        for (int i = 0; i < fp.dispatched.size(); i++)
            byType.computeIfAbsent(fp.words[fp.records.getInt(i)], t -> new ArrayList<>()).add(fp.dispatched.get(i));
        o.i(byType.size());
        String[] typeNames = {"none", "ore", "cover", "rock", "tree", "freeze", "disk", "spring", "lake"};
        for (var te : byType.entrySet()) {
            String tn = te.getKey() < typeNames.length ? typeNames[te.getKey()] : "type" + te.getKey();
            System.out.println("TRACE begin java-type " + tn);
            long t0 = System.nanoTime();
            java.util.Set<net.minecraft.world.level.levelgen.placement.PlacedFeature> set =
                    java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            set.addAll(te.getValue());
            features.only = set::contains;
            o.i(te.getKey());
            o.s(tn);
            long changed = 0;
            // the trees' trace (FloraTreeFeature.trace) beside the dump: per origin, each placement's stages
            StringBuilder treeTrace = "tree".equals(tn) ? new StringBuilder() : null;
            for (long k : origins.keySet()) {
                int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
                AtlasGenFeatures.Window w = features.new Window(terrain);
                if (treeTrace != null) {
                    treeTrace.append("origin ").append(cx).append(' ').append(cz).append('\n');
                    Thread me = Thread.currentThread();  // (the server's own generation reports too)
                    com.nirncraft.tamriel.biome.flora.FloraTreeFeature.trace = e -> {
                        if (Thread.currentThread() != me) return;
                        synchronized (treeTrace) {
                            for (long v : e) treeTrace.append(v).append(' ');
                            treeTrace.append('\n');
                        }
                    };
                }
                features.decorate(cx, cz, w);
                com.nirncraft.tamriel.biome.flora.FloraTreeFeature.trace = null;
                List<int[]> diff = new ArrayList<>();
                for (int dz = -1; dz <= 1; dz++)
                    for (int dx = -1; dx <= 1; dx++) {
                        ProtoChunk t = terrain.chunk(cx + dx, cz + dz), d = w.chunk(cx + dx, cz + dz);
                        for (int y = -64; y < 640; y++)
                            for (int z = 0; z < 16; z++)
                                for (int x = 0; x < 16; x++) {
                                    bp.set((cx + dx) * 16 + x, y, (cz + dz) * 16 + z);
                                    BlockState a = t.getBlockState(bp), b = d.getBlockState(bp);
                                    if (a == b) continue;
                                    Integer id = fp.blockIds.get(b);
                                    diff.add(new int[]{1 + ((y + 64) * 48 + (dz + 1) * 16 + z) * 48 + (dx + 1) * 16 + x, id == null ? 0xFFFF : id});
                                }
                    }
                changed += diff.size();
                o.i(diff.size());
                for (int[] e : diff) {
                    o.i(e[0]);
                    o.i(e[1]);
                }
            }
            if (treeTrace != null) java.nio.file.Files.writeString(java.nio.file.Path.of(out + ".trees.txt"), treeTrace);
            report.append(String.format("  type %-7s %3d placed features, Java alone %.2f ms an origin (with the diff), %d blocks changed%n", tn,
                    te.getValue().size(), (System.nanoTime() - t0) / 1e6 / origins.size(), changed));
            System.out.println(String.format("TRACE done java-type:%s %.1fs", tn, (System.nanoTime() - t0) / 1e9));
        }
        features.only = fp.supported::contains;
        report.append(String.format("dump: %d slots, %d origins (Java's decoration of these features alone: %.2f ms an origin; with the diff %.0f ms), %d blocks changed, %d targets, %d feature failures%n",
                slots.size(), origins.size(), decoNs / 1e6 / origins.size(), isoMs, javaChanged, samples.size(), features.failures()));
        features.failed.forEach((k, v) -> report.append("  failed ").append(k).append(": ").append(v).append(System.lineSeparator()));
        if (!unknown.isEmpty()) report.append("  states not in the program's table: ").append(new java.util.TreeSet<>(unknown)).append('\n');
        o.close();
        Files.writeString(Path.of(out + ".txt"), report.toString());
        System.out.println(report);
    }
}
