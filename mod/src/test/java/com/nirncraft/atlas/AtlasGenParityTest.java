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
     * The pages on the device (atlas_page.slang): the same chunks generated with the map set (the engine makes every
     * page itself: TamrielMap.sample, TamrielHeight, the channels, map water, the quart biomes), against Java's blocks;
     * then every page those chunks read against AtlasGenPages' words, field by field. NIRNCRAFT_GEN_PARITY_OUT gets
     * ".pages.txt".
     */
    @Test
    void gpuPagesMatchJava(MinecraftServer server) throws Exception {
        RegistryAccess ra = server.registryAccess();
        WorldDimensions dims = ra.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions();
        LevelStem stem = dims.dimensions().get(LevelStem.OVERWORLD);
        NoiseBasedChunkGenerator gen = (NoiseBasedChunkGenerator) stem.generator();
        NoiseGeneratorSettings settings = gen.generatorSettings().value();
        RandomState rs = RandomState.create(ra.lookupOrThrow(Registries.NOISE), SEED, settings);
        DimensionType dt = stem.type().value();
        LevelHeightAccessor heights = LevelHeightAccessor.create(dt.minY(), dt.height());
        StringBuilder report = new StringBuilder();
        AtlasGenProgram prog = AtlasGenProgram.compile(gen, settings, rs, heights, SEED, s -> 0);
        report.append(String.format("program: %d words, %d fields, page passes %s%n", prog.words.length, prog.fields.size(), prog.pageGen));
        for (int f = 0; f < prog.fields.size(); f++) report.append("  field ").append(f).append(": ").append(prog.fields.get(f)).append('\n');
        long mt = System.nanoTime();
        trace("begin map");
        int[] map = AtlasGenMap.words(prog, gen.getBiomeSource());
        trace("end map");
        report.append(String.format("map: %d words (%.0f ms)%n", map.length, (System.nanoTime() - mt) / 1e6));

        int per = Integer.parseInt(System.getenv().getOrDefault("NIRNCRAFT_GEN_PARITY_N", "3"));
        Map<String, List<ChunkPos>> kinds = pick(per);
        List<ChunkPos> all = new ArrayList<>();
        kinds.values().forEach(all::addAll);
        report.append("chunks: ").append(all.size()).append('\n');
        Map<ChunkPos, ProtoChunk> biomeChunks = new HashMap<>();
        Map<ChunkPos, ProtoChunk> reference = new LinkedHashMap<>();
        PalettedContainerFactory containers = PalettedContainerFactory.create(ra);
        StructureManager structures = new StructureManager(null, null, null) {
            @Override
            public List<StructureStart> startsForStructure(int x, int z, Predicate<Structure> m) {
                return List.of();
            }
        };
        trace("begin java-reference");
        for (ChunkPos pos : all) {
            reference.put(pos, javaChunk(pos, gen, rs, heights, containers, structures, biomeChunks));
            progress("java-reference", reference.size(), all.size());
        }
        trace("end java-reference");
        AtlasGenPages pages = new AtlasGenPages(prog, rs, gen.getBiomeSource(), heights);
        int[] xz = new int[all.size() * 2];
        for (int i = 0; i < all.size(); i++) {
            xz[i * 2] = all.get(i).x();
            xz[i * 2 + 1] = all.get(i).z();
        }
        // every page the chunks read, and whether with biomes
        Map<Long, Boolean> need = new LinkedHashMap<>();
        for (ChunkPos c : all)
            for (int dz = -2; dz <= 2; dz++)
                for (int dx = -4; dx <= 2; dx++)
                    need.merge(ChunkPos.pack(c.x() + dx, c.z() + dz), dx >= -1 && dx <= 1 && dz >= -1 && dz <= 1, Boolean::logicalOr);
        long jt = System.nanoTime();
        trace("begin java-pages");
        Map<Long, int[]> javaPages = new HashMap<>();
        need.entrySet().parallelStream().forEach(e -> {
            int[] w = pages.page(ChunkPos.getX(e.getKey()), ChunkPos.getZ(e.getKey()), e.getValue());
            synchronized (javaPages) {
                javaPages.put(e.getKey(), w);
            }
        });
        trace("end java-pages");
        report.append(String.format("java pages: %d in %.0f ms (all cores)%n", need.size(), (System.nanoTime() - jt) / 1e6));

        Path lib = Path.of(System.getenv("NIRNCRAFT_ATLAS_LIB"));
        AtlasGenNative nat = AtlasGenNative.load(lib);
        int nf = prog.fields.size();
        for (int backend : new int[]{AtlasGenNative.BACKEND_VULKAN, AtlasGenNative.BACKEND_CPU}) {
            try (AtlasGenNative.Gen g = nat.create(backend)) {
                if (g == null) {
                    report.append("backend ").append(backend).append(": unavailable\n");
                    continue;
                }
                trace("begin gpu " + g.device());
                g.setProgram(prog.words);
                g.setMap(map);
                double[] st0 = g.pageStats();
                report.append(String.format("%n== %s: pages on the device %s; java asks for %d pages%n", g.device(), st0[2] != 0,
                        g.missing(xz).length / 3));
                int[] ids = new int[all.size() * AtlasGenNative.CHUNK_WORDS];
                int[] flags = new int[all.size()];
                long t0 = System.nanoTime();
                AtlasGenNative.Times t = g.chunks(xz, 0, ids, flags);
                double wall = (System.nanoTime() - t0) / 1e6;
                double[] st = g.pageStats();
                report.append(String.format("   first run (pages made): %.1f ms wall, kernels %.1f ms; %d pages in %.1f ms of page passes (%.4f ms a page)%n",
                        wall, t.kernelMs(), (long) st[0], st[1], st[1] / Math.max(1, st[0])));
                AtlasGenNative.Times t2 = g.chunks(xz, 0, ids, flags);
                report.append(String.format("   second run (pages resident): kernels %.1f ms, %.2f ms a chunk%n", t2.kernelMs(), t2.totalMs() / all.size()));
                compare(report, kinds, all, reference, prog, ids, flags);
                // the pages, word for word
                long[] diff = new long[nf + 2], words = new long[nf + 2];
                int missing = 0, shown = 0;
                List<int[]> probeCols = new ArrayList<>();
                for (var e : need.entrySet()) {
                    int cx = ChunkPos.getX(e.getKey()), cz = ChunkPos.getZ(e.getKey());
                    int[] gw = g.readPage(cx, cz, prog.pageWords());
                    if (gw == null) {
                        missing++;
                        continue;
                    }
                    int[] jw = javaPages.get(e.getKey());
                    int biomeWords = e.getValue() ? jw.length - (nf * 256 + 256) : 0;
                    for (int i = 0; i < nf * 256 + 256 + biomeWords; i++) {
                        int part = i < nf * 256 ? i / 256 : i < nf * 256 + 256 ? nf : nf + 1;
                        words[part]++;
                        if (gw[i] == jw[i]) continue;
                        diff[part]++;
                        if (part < nf && probeCols.size() < 12) probeCols.add(new int[]{cx * 16 + (i % 256 & 15), cz * 16 + (i % 256 >> 4)});
                        if (shown++ < 24) {
                            int col = i % 256;
                            report.append(String.format("   page %d,%d part %d word %d (column %d,%d): gpu %08x (%s) java %08x (%s)%n", cx, cz, part, i,
                                    cx * 16 + (col & 15), cz * 16 + (col >> 4), gw[i], Float.intBitsToFloat(gw[i]), jw[i], Float.intBitsToFloat(jw[i])));
                        }
                    }
                }
                trace("end gpu+compare " + g.device());
                report.append(String.format("   pages compared: %d (%d not resident)%n", need.size() - missing, missing));
                if (!probeCols.isEmpty()) {
                    // the differing columns' parts: overhang, influence, bed / pad / plot weights, road, height
                    int[] pxz = new int[probeCols.size() * 2];
                    for (int i = 0; i < probeCols.size(); i++) {
                        pxz[i * 2] = probeCols.get(i)[0];
                        pxz[i * 2 + 1] = probeCols.get(i)[1];
                    }
                    int[] pr = g.probe(1, pxz);
                    for (int i = 0; i < probeCols.size(); i++) {
                        int x = pxz[i * 2], z = pxz[i * 2 + 1], o = i * AtlasGenNative.PROBE_WORDS;
                        MapSample ms = new MapSample();
                        TamrielMap.sample(x, z, ms);
                        com.nirncraft.worldplan.PlanGround pg = com.nirncraft.worldplan.PlanGround.getAt(x, z);
                        com.nirncraft.worldplan.PlanGround.Bed bb = new com.nirncraft.worldplan.PlanGround.Bed();
                        float jb = pg.bed(x, z, bb) ? bb.weight : -1f;
                        float jp = pg.pad(x, z, bb) ? bb.padWeight : -1f;
                        float jq = pg.plot(x, z, bb) ? bb.plotWeight : -1f;
                        float inf = com.nirncraft.worldplan.PlanGround.influence(x, z);
                        double ov = com.nirncraft.tamriel.terrain.TamrielChannel.value(com.nirncraft.tamriel.terrain.TamrielChannel.Channel.OVERHANG, ms) * (1 - inf);
                        double gov = Double.longBitsToDouble((pr[o] & 0xFFFFFFFFL) | (long) pr[o + 1] << 32);
                        report.append(String.format("   probe %d %d: overhang gpu %s java %s; influence gpu %s java %s; bed %s/%s pad %s/%s plot %s/%s; ridge %s crag %s calm %s road %s cd %s%n",
                                x, z, gov, ov, Float.intBitsToFloat(pr[o + 2]), inf, Float.intBitsToFloat(pr[o + 3]), jb, Float.intBitsToFloat(pr[o + 4]), jp,
                                Float.intBitsToFloat(pr[o + 5]), jq, ms.ridge, ms.crag, ms.calm, ms.road, ms.coastDistance));
                    }
                }
                for (int part = 0; part < nf + 2; part++)
                    report.append(String.format("     %-40s %d of %d words differ%n",
                            part < nf ? "field " + part + " " + prog.fields.get(part).getClass().getSimpleName() : part == nf ? "map water" : "quart biomes",
                            diff[part], words[part]));
            }
        }
        String text = report.toString();
        String out = System.getenv("NIRNCRAFT_GEN_PARITY_OUT");
        if (out != null) Files.writeString(Path.of(out + ".pages.txt"), text);
        System.out.println(text);
    }

    /**
     * Far tiles on the device (atlas_gen_tiles) against AtlasExactSource's CPU path (TamrielMap.sample, TamrielHeight,
     * waterAt), bit for bit, over LODs 0..6 at Whiterun, the sea, the raster's edge, Atmora and Yokuda; and the time a
     * tile. NIRNCRAFT_GEN_PARITY_OUT gets ".tiles.txt".
     */
    @Test
    void gpuTilesMatchJava(MinecraftServer server) throws Exception {
        RegistryAccess ra = server.registryAccess();
        WorldDimensions dims = ra.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions();
        LevelStem stem = dims.dimensions().get(LevelStem.OVERWORLD);
        NoiseBasedChunkGenerator gen = (NoiseBasedChunkGenerator) stem.generator();
        NoiseGeneratorSettings settings = gen.generatorSettings().value();
        RandomState rs = RandomState.create(ra.lookupOrThrow(Registries.NOISE), SEED, settings);
        DimensionType dt = stem.type().value();
        LevelHeightAccessor heights = LevelHeightAccessor.create(dt.minY(), dt.height());
        AtlasGenProgram prog = AtlasGenProgram.compile(gen, settings, rs, heights, SEED, s -> 0);
        int[] map = AtlasGenMap.words(prog, gen.getBiomeSource());
        com.nirncraft.worldgen.WorldNoise[] n = new com.nirncraft.worldgen.WorldNoise[4];
        for (int i = 0; i < 4; i++)
            n[i] = new com.nirncraft.worldgen.WorldNoise(null, rs.getOrCreateNoise(net.minecraft.resources.ResourceKey.create(Registries.NOISE,
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft", AtlasExactSource.NOISES[i]))));
        StringBuilder report = new StringBuilder();
        AtlasExactSource cpu = new AtlasExactSource(new com.nirncraft.tamriel.terrain.TamrielHeight(n[0], n[1], n[2], n[3]),
                () -> null, Runtime.getRuntime().availableProcessors());
        try {
            // (lod, block x, block z) of each tile's corner region
            int[][] spots = {{0, 0, 0}, {0, 1200, -800}, {1, -9000, 2000}, {2, 0, -11000}, {3, -19800, 6300}, {4, 0, 0}, {5, 20000, 4000},
                    {6, -30000, -20000}, {0, -15400, -5100}, {1, 3000, 9000}, {2, 9000, -3000}, {3, -6000, 14000}};
            int[] tiles = new int[spots.length * 3];
            for (int i = 0; i < spots.length; i++) {
                int lod = spots[i][0], span = AtlasNative.TILE << lod;
                tiles[i * 3] = lod;
                tiles[i * 3 + 1] = Math.floorDiv(spots[i][1], span);
                tiles[i * 3 + 2] = Math.floorDiv(spots[i][2], span);
            }
            int cols = AtlasGenNative.TILE_COLS;
            float[][] cs = new float[spots.length][cols], cw = new float[spots.length][cols];
            long ct = System.nanoTime();
            trace("begin java-tiles");
            for (int i = 0; i < spots.length; i++) {
                cpu.fillCpu(tiles[i * 3], tiles[i * 3 + 1], tiles[i * 3 + 2], cs[i], cw[i]);
                progress("java-tiles", i + 1, spots.length);
            }
            trace("end java-tiles");
            double cpuMs = (System.nanoTime() - ct) / 1e6;
            report.append(String.format("cpu: %d tiles in %.0f ms (%.1f ms a tile, all cores)%n", spots.length, cpuMs, cpuMs / spots.length));
            AtlasGenNative nat = AtlasGenNative.load(Path.of(System.getenv("NIRNCRAFT_ATLAS_LIB")));
            for (int backend : new int[]{AtlasGenNative.BACKEND_VULKAN, AtlasGenNative.BACKEND_CPU}) {
                try (AtlasGenNative.Gen g = nat.create(backend)) {
                    if (g == null) continue;
                    trace("begin gpu-tiles " + g.device());
                    g.setProgram(prog.words);
                    g.setMap(map);
                    float[] s = new float[spots.length * cols], w = new float[spots.length * cols];
                    double[] ms = new double[1];
                    g.tiles(tiles, s, w, ms);  // warm
                    long t0 = System.nanoTime();
                    int[] bad = g.tiles(tiles, s, w, ms);
                    double wall = (System.nanoTime() - t0) / 1e6;
                    report.append(String.format("%n== %s: %d tiles, kernel %.1f ms (%.2f ms a tile), wall %.1f ms%n", g.device(), spots.length, ms[0],
                            ms[0] / spots.length, wall));
                    long diff = 0, total = 0;
                    int shown = 0;
                    for (int i = 0; i < spots.length; i++) {
                        long d = 0;
                        for (int k = 0; k < cols; k++) {
                            total++;
                            float gs = s[i * cols + k], gw = w[i * cols + k];
                            if (Float.floatToRawIntBits(gs) == Float.floatToRawIntBits(cs[i][k])
                                    && Float.floatToRawIntBits(gw) == Float.floatToRawIntBits(cw[i][k])) continue;
                            d++;
                            if (shown++ < 16) {
                                int lod = tiles[i * 3], st = 1 << lod;
                                int x = tiles[i * 3 + 1] * (AtlasNative.TILE << lod) + (st >> 1) + (k & 255) * st;
                                int z = tiles[i * 3 + 2] * (AtlasNative.TILE << lod) + (st >> 1) + (k >> 8) * st;
                                report.append(String.format("   lod %d column %d %d: gpu %s / %s cpu %s / %s%n", lod, x, z, gs, gw, cs[i][k], cw[i][k]));
                            }
                        }
                        diff += d;
                        report.append(String.format("   tile lod %d (%d, %d): %d of %d columns differ%s%n", tiles[i * 3], tiles[i * 3 + 1], tiles[i * 3 + 2],
                                d, cols, bad[i] != 0 ? " (flagged)" : ""));
                    }
                    trace("end gpu-tiles " + g.device());
                    report.append(String.format("   total: %d of %d columns differ (%.5f %% agree)%n", diff, total, 100.0 * (total - diff) / total));
                }
            }
        } finally {
            cpu.close();
        }
        String text = report.toString();
        String out = System.getenv("NIRNCRAFT_GEN_PARITY_OUT");
        if (out != null) Files.writeString(Path.of(out + ".tiles.txt"), text);
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
                features.decorated(), fms, fms / features.decorated(), features.failures()));
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

    /**
     * The live path offline (NIRNCRAFT_TEST_WORLD=tamriel): AtlasClientGen's own threads (GPU terrain, uploads into an
     * atlas context through atlas_upload_section, parallel features) following a camera that settles at Whiterun and
     * then jumps 1,600 blocks; the state sampled every 0.6 s as atlas-info shows it, and the jump's times.
     */
    @Test
    void clientGenJump(MinecraftServer server) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("tamriel".equals(System.getenv("NIRNCRAFT_TEST_WORLD")), "the flat test world");
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
        Path lib = Path.of(System.getenv("NIRNCRAFT_ATLAS_LIB"));
        AtlasNative atlas = AtlasNative.load(lib);
        StringBuilder report = new StringBuilder();
        try (AtlasNative.Context ctx = atlas.create(AtlasGenNative.BACKEND_VULKAN)) {
            AtlasClientGen cg = AtlasClientGen.startFor(server, lib, ctx, new AtlasMaterials());
            if (cg == null) throw new IllegalStateException("no client generator");
            // the render thread: a small frame at the camera every 16 ms (the grid places its window and takes the GPU
            // sections with its frames)
            double[] cam0 = {0.5, 0.5};
            java.util.concurrent.atomic.AtomicBoolean rendering = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.atomic.AtomicLong frames = new java.util.concurrent.atomic.AtomicLong();
            String[] renderError = {null};
            Thread renderer = new Thread(() -> {
                AtlasNative.Frame f = new AtlasNative.Frame();
                f.width = 320;
                f.height = 180;
                f.flags = 0;
                f.nearFieldEnd = 0;
                f.maxDistance = 2000;
                new org.joml.Matrix4f().perspective((float) Math.toRadians(70), 320f / 180f, 0.05f, 4000f)
                        .lookAt(0, 0, 0, 0, -0.5f, 1, 0, 1, 0).invert().get(f.invViewProj);
                int[] color = new int[320 * 180];
                while (rendering.get()) {
                    f.x = cam0[0];
                    f.y = 160;
                    f.z = cam0[1];
                    try {
                        ctx.update(f.x, f.y, f.z, frames.get() == 0 ? 0 : 2);  // (the first builds everything)
                        ctx.render(f, color, null, null);
                        frames.incrementAndGet();
                        Thread.sleep(16);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable t) {
                        renderError[0] = t.toString();
                        return;
                    }
                }
            }, "test render");
            renderer.setDaemon(true);
            renderer.start();
            try {
                report.append(String.format("radius %d, features %d on %d threads%n", AtlasClientGen.RADIUS,
                        AtlasClientGen.FEATURE_RADIUS, AtlasClientGen.FEATURE_THREADS));
                for (int[] cam : new int[][]{{0, 0}, {1600, 0}}) {
                    cam0[0] = cam[0] + 0.5;
                    cam0[1] = cam[1] + 0.5;
                    AtlasClientGen.follow(cam[0] + 0.5, cam[1] + 0.5, 0f);
                    long t0 = System.nanoTime();
                    report.append(String.format("camera %d, %d%n", cam[0], cam[1]));
                    long next = 0;
                    while (true) {
                        double t = (System.nanoTime() - t0) / 1e9;
                        double[] j = cg.jumpTimes();
                        if (t * 1e9 >= next) {
                            long gcMs = 0;
                            for (var gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) gcMs += Math.max(0, gc.getCollectionTime());
                            Runtime rt = Runtime.getRuntime();
                            report.append(String.format("  %5.1f s  gc %d ms, heap %d / %d MB  %s%n", t, gcMs,
                                    (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20, AtlasClientGen.state()));
                            next += 600_000_000L;
                        }
                        if ((j[1] >= 0 && j[2] >= 0) || t > 40) break;
                        Thread.sleep(20);
                    }
                    double[] j = cg.jumpTimes();
                    report.append(String.format("  jump: first chunk up %.0f ms, the window's terrain up %.0f ms (%d chunks), "
                                    + "the features' window up %.0f ms (%d chunks); %d frames rendered%n", j[0], j[1], (int) j[3], j[2],
                            (int) j[4], frames.get()));
                    if (renderError[0] != null) report.append("  render thread: ").append(renderError[0]).append(System.lineSeparator());
                }
            } finally {
                cg.close();
                rendering.set(false);
                renderer.join(5000);
            }
        }
        String out = System.getenv("NIRNCRAFT_GEN_PARITY_OUT");
        if (out != null) Files.writeString(Path.of(out + ".jump.txt"), report.toString());
        System.out.println(report);
    }

    /**
     * GPU -> GPU (atlas_gen_create_for, atlas_gen_run, atlas_gen_ingest): the kernels' section infos against the Java
     * decode they replace (AtlasClientGen.decode / putGen: the lowest ground, highest top, each section's kind and its
     * hash, the tints' biome), then the sections device to device into one context against the same materials uploaded
     * through atlas_upload_section into another, rendered from the same camera: depth and colour pixel for pixel.
     */
    @Test
    void gpuIngest(MinecraftServer server) throws Exception {
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
        Path lib = Path.of(System.getenv("NIRNCRAFT_ATLAS_LIB"));
        AtlasNative atlas = AtlasNative.load(lib);
        AtlasGenNative nat = AtlasGenNative.load(lib);
        int w = Integer.parseInt(System.getenv().getOrDefault("NIRNCRAFT_GEN_WINDOW", "16"));
        StringBuilder report = new StringBuilder();
        net.minecraft.world.level.biome.BiomeResolver resolver = gen.getBiomeSource()
                .createResolver(rs.createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED));
        long zoom = BiomeManager.obfuscateSeed(SEED);
        try (AtlasNative.Context ctxA = atlas.create(AtlasGenNative.BACKEND_VULKAN);
             AtlasNative.Context ctxB = atlas.create(AtlasGenNative.BACKEND_VULKAN)) {
            AtlasGenNative.Gen g = nat.createFor(ctxA.handle());
            if (g == null) throw new IllegalStateException("atlas_gen_create_for refused the context's device");
            try (g) {
                g.setProgram(prog.words);
                int[] tab = AtlasClientGen.ingestTable(prog, new AtlasMaterials(), ctxA);
                int[] tabB = AtlasClientGen.ingestTable(prog, new AtlasMaterials(), ctxB);
                if (!Arrays.equals(tab, tabB)) throw new IllegalStateException("the two contexts' materials differ");
                g.setIngest(tab);
                int n = tab[0], nb = tab[1], tints = 2 + 2 * n, matBase = tints + 2 * nb;
                report.append(String.format("%s; %d generator ids, %d biomes%n", g.device(), n, nb));
                // the two contexts from one camera above Whiterun, looking north-east and down
                AtlasNative.Frame f = new AtlasNative.Frame();
                f.width = 480;
                f.height = 270;
                f.x = 8.5;
                f.y = 140;
                f.z = 8.5;
                f.nearFieldEnd = 0;
                f.maxDistance = 2000;
                f.flags = 0;
                org.joml.Matrix4f vp = new org.joml.Matrix4f().perspective((float) Math.toRadians(70), 480f / 270f, 0.05f, 4000f)
                        .lookAt(0, 0, 0, 0.6f, -0.45f, 0.65f, 0, 1, 0).invert();
                vp.get(f.invViewProj);
                int px = f.width * f.height;
                float[] da = new float[px], db = new float[px];
                int[] ca = new int[px], cb = new int[px];
                // a first frame each: the grids place their windows (GPU sections go only into a placed window)
                ctxA.update(f.x, f.y, f.z, 0);
                ctxB.update(f.x, f.y, f.z, 0);
                ctxA.render(f, ca, null, da);
                ctxB.render(f, cb, null, db);
                // a window at Whiterun, nearest first
                List<int[]> order = new ArrayList<>();
                for (int dz = -w / 2; dz < w / 2; dz++)
                    for (int dx = -w / 2; dx < w / 2; dx++) order.add(new int[]{dx, dz});
                order.sort(java.util.Comparator.comparingInt(c -> c[0] * c[0] + c[1] * c[1]));
                long chunksOk = 0, chunksN = 0, secN = 0, kindOk = 0, hashOk = 0, biomeOk = 0, lowOk = 0, highOk = 0, sent = 0;
                long hashOkJavaTints = 0;
                double runMs = 0, ingestMs = 0, kernelMs = 0, terrainMs = 0;
                String firstDiff = null;
                for (int b0 = 0; b0 < order.size(); b0 += AtlasGenNative.RUN_CHUNKS) {
                    int m = Math.min(AtlasGenNative.RUN_CHUNKS, order.size() - b0);
                    int[] xz = new int[m * 2];
                    for (int i = 0; i < m; i++) {
                        xz[i * 2] = order.get(b0 + i)[0];
                        xz[i * 2 + 1] = order.get(b0 + i)[1];
                    }
                    int[] miss = g.missing(xz);
                    for (int i = 0; i < miss.length / 3; i++)
                        g.putPage(miss[i * 3], miss[i * 3 + 1], miss[i * 3 + 2] != 0, pages.page(miss[i * 3], miss[i * 3 + 1], miss[i * 3 + 2] != 0));
                    int[] ids = new int[m * AtlasGenNative.CHUNK_WORDS], flags = new int[m], info = new int[m * AtlasGenNative.INFO_WORDS];
                    long t0 = System.nanoTime();
                    AtlasGenNative.Times t = g.run(xz, ids, flags, info);
                    runMs += (System.nanoTime() - t0) / 1e6;
                    kernelMs += t.kernelMs();
                    double terrain = 0;
                    for (double v : t.passMs()) terrain += v;
                    terrainMs += terrain;
                    if (b0 == 0) {
                        // the kernels' C++ build on a CPU context: the same infos?
                        try (AtlasNative.Context cpuCtx = atlas.create(AtlasGenNative.BACKEND_CPU)) {
                            AtlasGenNative.Gen gc = nat.createFor(cpuCtx.handle());
                            try (gc) {
                                gc.setProgram(prog.words);
                                gc.setIngest(tab);
                                int[] mc = gc.missing(xz);
                                for (int i = 0; i < mc.length / 3; i++)
                                    gc.putPage(mc[i * 3], mc[i * 3 + 1], mc[i * 3 + 2] != 0, pages.page(mc[i * 3], mc[i * 3 + 1], mc[i * 3 + 2] != 0));
                                int[] infoC = new int[m * AtlasGenNative.INFO_WORDS];
                                gc.run(xz, null, new int[m], infoC);
                                int same = 0;
                                for (int i = 0; i < infoC.length; i++) if (infoC[i] == info[i]) same++;
                                report.append(String.format("cpu (%s) infos of the first %d chunks: %d of %d words as Vulkan's; chunk 0 section -4 hash %08x%08x%n",
                                        gc.device(), m, same, infoC.length, infoC[5], infoC[4]));
                            }
                        }
                    }
                    long[] masks = new long[m];
                    for (int i = 0; i < m; i++) {
                        int cx = xz[i * 2], cz = xz[i * 2 + 1];
                        chunksN++;
                        if (flags[i] != 0) continue;
                        // Java's decode over the ids
                        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
                        int base = i * AtlasGenNative.CHUNK_WORDS;
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) {
                                int top = -65, ground = -65;
                                for (int y = 575; y >= -64 && (top == -65 || ground == -65); y--) {
                                    int k = ((y + 64) >> 4) * 4096 + x + 16 * (z + 16 * (y & 15));
                                    int word = ids[base + (k >> 1)], id = (k & 1) == 0 ? word & 0xFFFF : word >>> 16;
                                    int fl = tab[2 + id * 2 + 1];
                                    if (top == -65 && (fl & 1) == 0) top = y;
                                    if (ground == -65 && (fl & 4) != 0) ground = y;
                                }
                                low = Math.min(low, ground);
                                high = Math.max(high, top);
                            }
                        int o = i * AtlasGenNative.INFO_WORDS;
                        boolean ok = true;
                        if (info[o] == low) lowOk++; else ok = false;
                        if (info[o + 1] == high) highOk++; else ok = false;
                        Holder<Biome> jb = new BiomeManager(resolver, zoom).getBiome(new BlockPos(cx * 16 + 8, high, cz * 16 + 8));
                        int gb = info[o + 2];
                        boolean sameBiome = gb < prog.biomes.size() && prog.biomes.get(gb).equals(jb);
                        if (sameBiome) biomeOk++;
                        int jbi = prog.biomeIds.getOrDefault(jb, gb);
                        int highest = -1;
                        boolean started = false;
                        int[] kinds = new int[40];
                        for (int sec = 0; sec < 40; sec++) {
                            secN++;
                            boolean any = false, rock = true;
                            long h = 0xcbf29ce484222325L ^ ((long) tab[tints + gb * 2] << 24 ^ tab[tints + gb * 2 + 1]);
                            long hj = 0xcbf29ce484222325L ^ ((long) tab[tints + jbi * 2] << 24 ^ tab[tints + jbi * 2 + 1]);
                            for (int bk = 0; bk < 4096; bk++) {
                                int k = sec * 4096 + bk;
                                int word = ids[base + (k >> 1)], id = (k & 1) == 0 ? word & 0xFFFF : word >>> 16;
                                any |= id != 0;
                                rock &= (tab[2 + id * 2 + 1] & 2) != 0;
                                h = (h ^ tab[2 + id * 2]) * 0x100000001b3L;
                                hj = (hj ^ tab[2 + id * 2]) * 0x100000001b3L;
                            }
                            int kind = !any ? 0 : rock && AtlasSections.deep(sec - 4, low) ? 2 : 1;
                            kinds[sec] = kind;
                            if (kind == 1) highest = sec;
                            int so = o + 4 + sec * 4;
                            long gh = (info[so] & 0xFFFFFFFFL) | (long) info[so + 1] << 32;
                            if (info[so + 2] == kind) kindOk++; else ok = false;
                            if (kind == 2) {  // deep rock: no hash (it never goes up)
                                h = 0;
                                hj = 0;
                            }
                            if (gh == h) hashOk++; else ok = false;
                            if (gh == hj) hashOkJavaTints++;
                            if (!ok && firstDiff == null) {
                                // (which seed the kernel's hash came from, if not Java's)
                                long g1 = 0xcbf29ce484222325L ^ (((long) tab[tints + gb * 2] << 24 & 0xFFFFFFFFL) ^ tab[tints + gb * 2 + 1]);
                                long g2 = 0x84222325L ^ ((long) tab[tints + gb * 2] << 24 ^ tab[tints + gb * 2 + 1]);
                                long g3 = 0xcbf29ce484222325L;
                                for (int bk = 0; bk < 4096; bk++) {
                                    int k = sec * 4096 + bk;
                                    int word = ids[base + (k >> 1)], id = (k & 1) == 0 ? word & 0xFFFF : word >>> 16;
                                    g1 = (g1 ^ tab[2 + id * 2]) * 0x100000001b3L;
                                    g2 = (g2 ^ tab[2 + id * 2]) * 0x100000001b3L;
                                    g3 = (g3 ^ tab[2 + id * 2]) * 0x100000001b3L;
                                }
                                firstDiff = String.format("chunk %d,%d section %d: kind %d / %d, hash %x / %x (tint shift 32-bit %b, seed low only %b, "
                                                + "no tints %b; grass %x foliage %x), low %d / %d, high %d / %d",
                                        cx, cz, sec - 4, info[so + 2], kind, gh, h, gh == g1, gh == g2, gh == g3, tab[tints + gb * 2],
                                        tab[tints + gb * 2 + 1], info[o], low, info[o + 1], high);
                            }
                        }
                        if (ok) chunksOk++;
                        // the column as AtlasClientGen sends it
                        long mask = 0;
                        for (int sec = 0; sec <= highest; sec++) {
                            if (kinds[sec] == 2 || (kinds[sec] == 0 && !started)) continue;
                            started = true;
                            mask |= 1L << sec;
                            // the same materials up the CPU path into the other context
                            short[] mats = new short[4096];
                            for (int bk = 0; bk < 4096; bk++) {
                                int k = sec * 4096 + bk;
                                int word = ids[base + (k >> 1)], id = (k & 1) == 0 ? word & 0xFFFF : word >>> 16;
                                mats[bk] = (short) tab[matBase + gb * n + id];
                            }
                            ctxB.uploadSection(cx, sec - 4, cz, mats);
                        }
                        masks[i] = mask;
                    }
                    long t1 = System.nanoTime();
                    long[] asked = masks.clone();
                    int s = g.ingest(masks);
                    ingestMs += (System.nanoTime() - t1) / 1e6;
                    if (s < 0) throw new IllegalStateException("ingest refused: " + s);
                    if (!Arrays.equals(asked, masks)) throw new IllegalStateException("sections in the window not taken");
                    sent += s;
                }
                report.append(String.format("%d chunks: %d agree whole (lowest ground %d, highest top %d), %d sections: kind %d, hash %d "
                                + "(with Java's own tints' biome %d); tints' biome as Java's resolver %d of %d%n",
                        chunksN, chunksOk, lowOk, highOk, secN, kindOk, hashOk, hashOkJavaTints, biomeOk, chunksN));
                if (firstDiff != null) report.append("  first difference: ").append(firstDiff).append('\n');
                report.append(String.format("run %.0f ms (kernels %.1f ms: terrain %.1f, the sections' infos and materials %.1f), ingest %.1f ms: "
                                + "%d sections device to device%n", runMs, kernelMs, terrainMs, kernelMs - terrainMs, ingestMs, sent));
                for (int frame = 0; frame < 12; frame++) {
                    ctxA.update(f.x, f.y, f.z, 0);
                    ctxB.update(f.x, f.y, f.z, 0);
                    ctxA.render(f, ca, null, da);
                    ctxB.render(f, cb, null, db);
                }
                int depthSame = 0, colorSame = 0, hitA = 0, hitB = 0;
                for (int i = 0; i < px; i++) {
                    if (da[i] == db[i]) depthSame++;
                    if (ca[i] == cb[i]) colorSame++;
                    if (da[i] < 1500) hitA++;
                    if (db[i] < 1500) hitB++;
                }
                report.append(String.format("render %d x %d after 12 frames: depth equal %d of %d px (%.3f%%), colour equal %d (%.3f%%); "
                                + "terrain hit: GPU-ingested %d px, uploaded %d px%n", f.width, f.height, depthSame, px,
                        100.0 * depthSame / px, colorSame, 100.0 * colorSame / px, hitA, hitB));
                String out = System.getenv("NIRNCRAFT_GEN_PARITY_OUT");
                if (out != null) {
                    javax.imageio.ImageIO.write(image(ca, f.width, f.height), "png", Path.of(out + ".ingestA.png").toFile());
                    javax.imageio.ImageIO.write(image(cb, f.width, f.height), "png", Path.of(out + ".ingestB.png").toFile());
                }
            }
        }
        String out = System.getenv("NIRNCRAFT_GEN_PARITY_OUT");
        if (out != null) Files.writeString(Path.of(out + ".ingest.txt"), report.toString());
        System.out.println(report);
    }

    static java.awt.image.BufferedImage image(int[] rgba, int w, int h) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int c = rgba[y * w + x];
                img.setRGB(x, y, (c & 0xFF) << 16 | (c & 0xFF00) | (c >> 16 & 0xFF));
            }
        return img;
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

    /** Progress traces (heavy.py timestamps them; its watcher alerts on a silent job). */
    static void trace(String what) {
        System.out.println("TRACE " + what);
        System.out.flush();
    }

    private static long lastProgress;

    static void progress(String phase, int i, int n) {
        long now = System.nanoTime();
        if (i < n && now - lastProgress < 20_000_000_000L) return;
        lastProgress = now;
        trace("progress " + phase + " " + i + "/" + n);
    }

    static String name(BlockState s) {
        return s.getBlock().builtInRegistryHolder().key().identifier().getPath();
    }
}
