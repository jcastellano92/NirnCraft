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
        debugBackends(report, nat, prog, pages, all, reference);
        String text = report.toString();
        String out = System.getenv("NIRNCRAFT_GEN_PARITY_OUT");
        if (out != null) Files.writeString(Path.of(out), text);
        System.out.println(text);
    }

    // ---------------------------------------------------------------------------------------------------------------

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
