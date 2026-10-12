package com.skycraft.perf;

import com.skycraft.Skycraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * {@code /skycraft-perf stages [chunks per area]}: drives the generator's early stages directly on throw-away proto
 * chunks (nothing is saved, no neighbours, no structures) at the benchmark areas, one thread, and reports exact
 * milliseconds per chunk for structure starts, biomes, noise and surface. The surface stage runs twice on identical
 * noise: once with the original surface rule and once through {@link FastSurfaceRule}, and every block of the two
 * results is compared - an A/B proof that the fast rule builds the same world.
 */
public final class StageBench {
    private StageBench() {}

    public static void run(ServerLevel level, int perArea, Consumer<String> out, Runnable done) {
        Thread t = new Thread(() -> {
            try {
                for (String s : measure(level, perArea)) {
                    out.accept(s);
                    Skycraft.LOGGER.info("[perf] {}", s);
                }
            } catch (RuntimeException e) {
                Skycraft.LOGGER.error("[perf] stage bench failed", e);
                out.accept("stage bench failed: " + e);
            } finally {
                if (done != null) done.run();
            }
        }, "skycraft-perf-stages");
        t.setDaemon(true);
        t.start();
    }

    private static List<String> measure(ServerLevel level, int perArea) {
        List<String> lines = new ArrayList<>();
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator gen)) {
            lines.add("not a noise generator");
            return lines;
        }
        RandomState rs = level.getChunkSource().randomState();
        NoiseGeneratorSettings settings = gen.generatorSettings().value();
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        BiomeSource source = gen.getBiomeSource();
        BiomeManager manager = new BiomeManager((x, y, z) -> source.getNoiseBiome(x, y, z, rs.sampler()),
                BiomeManager.obfuscateSeed(level.getSeed()));
        SurfaceRules.RuleSource fast = settings.surfaceRule();
        SurfaceRules.RuleSource plain = fast instanceof FastSurfaceRule.Source f ? f.rule() : fast;
        boolean ab = fast instanceof FastSurfaceRule.Source;
        Executor direct = Runnable::run;
        int side = Math.max(1, (int) Math.round(Math.sqrt(perArea)));
        long tStarts = 0, tBiomes = 0, tNoise = 0, tPlain = 0, tFast = 0;
        long blocks = 0, diff = 0;
        int chunks = 0;
        List<ChunkBench.Area> areas = ChunkBench.defaultAreas();
        // warm-up on the first area, unmeasured
        for (int pass = 0; pass < 2; pass++) {
            for (ChunkBench.Area a : pass == 0 ? areas.subList(0, 1) : areas) {
                // offset from the full benchmark's squares so the two never touch the same chunks
                int cx0 = (a.x() >> 4) + 40, cz0 = (a.z() >> 4) + 40;
                for (int i = 0; i < side * side; i++) {
                    ChunkPos pos = new ChunkPos(cx0 + i % side, cz0 + i / side);
                    long t0 = System.nanoTime();
                    ProtoChunk starts = proto(level, biomes, pos);
                    gen.createStructures(level.registryAccess(), level.getChunkSource().getGeneratorState(),
                            level.structureManager(), starts, level.getStructureManager());
                    long t1 = System.nanoTime();
                    ProtoChunk a1 = proto(level, biomes, pos);
                    StructureManager sm = level.structureManager().forWorldGenRegion(
                            new WorldGenRegion(level, List.of(a1), ChunkStatus.SURFACE, 0));
                    gen.createBiomes(direct, rs, Blender.empty(), sm, a1).join();
                    long t2 = System.nanoTime();
                    gen.fillFromNoise(direct, Blender.empty(), rs, sm, a1).join();
                    long t3 = System.nanoTime();
                    ProtoChunk b1 = proto(level, biomes, pos);
                    StructureManager smB = level.structureManager().forWorldGenRegion(
                            new WorldGenRegion(level, List.of(b1), ChunkStatus.SURFACE, 0));
                    gen.createBiomes(direct, rs, Blender.empty(), smB, b1).join();
                    gen.fillFromNoise(direct, Blender.empty(), rs, smB, b1).join();
                    long t4 = System.nanoTime();
                    surface(rs, manager, biomes, settings, gen, a1, plain);
                    long t5 = System.nanoTime();
                    surface(rs, manager, biomes, settings, gen, b1, fast);
                    long t6 = System.nanoTime();
                    if (pass == 0) continue;
                    chunks++;
                    tStarts += t1 - t0;
                    tBiomes += t2 - t1;
                    tNoise += t3 - t2;
                    tPlain += t5 - t4;
                    tFast += t6 - t5;
                    long[] cmp = compare(a1, b1);
                    blocks += cmp[0];
                    diff += cmp[1];
                }
            }
        }
        double n = Math.max(1, chunks) * 1e6;
        lines.add(String.format(Locale.ROOT, "stages (direct, 1 thread, %d chunks, ms/chunk): structure_starts %.2f | biomes %.2f | noise %.2f | surface %.2f (plain rule) -> %.2f (fast_surface)",
                chunks, tStarts / n, tBiomes / n, tNoise / n, tPlain / n, tFast / n));
        lines.add(ab ? String.format(Locale.ROOT, "fast_surface A/B: %d blocks compared, %d differ%s", blocks, diff, diff == 0 ? " (identical)" : " - MISMATCH")
                : "fast_surface A/B: the noise settings don't use skycraft:fast_surface (both passes ran the plain rule)");
        return lines;
    }

    /** A blank proto chunk, marked as past structure references (it has none) so a region over it can be read. */
    private static ProtoChunk proto(ServerLevel level, Registry<Biome> biomes, ChunkPos pos) {
        ProtoChunk c = new ProtoChunk(pos, UpgradeData.EMPTY, level, biomes, null);
        c.setStatus(ChunkStatus.STRUCTURE_REFERENCES);
        return c;
    }

    private static void surface(RandomState rs, BiomeManager manager, Registry<Biome> biomes, NoiseGeneratorSettings settings,
                                NoiseBasedChunkGenerator gen, ChunkAccess chunk, SurfaceRules.RuleSource rule) {
        NoiseChunk noise = chunk.getOrCreateNoiseChunk(c -> {
            throw new IllegalStateException("no noise chunk");
        });
        rs.surfaceSystem().buildSurface(rs, manager, biomes, settings.useLegacyRandomSource(),
                new WorldGenerationContext(gen, chunk), chunk, noise, rule);
    }

    /** {blocks compared, blocks that differ}. */
    private static long[] compare(ChunkAccess a, ChunkAccess b) {
        long n = 0, d = 0;
        LevelChunkSection[] sa = a.getSections(), sb = b.getSections();
        for (int s = 0; s < sa.length; s++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        n++;
                        if (sa[s].getBlockState(x, y, z) != sb[s].getBlockState(x, y, z)) d++;
                    }
                }
            }
        }
        return new long[]{n, d};
    }
}
