package com.nirncraft.perf;

import com.nirncraft.NirnCraft;
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
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;
import net.minecraft.world.level.levelgen.material.rule.ConditionRule;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * {@code /nirncraft-perf stages [chunks per area]}: drives the generator's early stages directly on throw-away proto
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
                    NirnCraft.LOGGER.info("[perf] {}", s);
                }
            } catch (RuntimeException e) {
                NirnCraft.LOGGER.error("[perf] stage bench failed", e);
                out.accept("stage bench failed: " + e);
            } finally {
                if (done != null) done.run();
            }
        }, "nirncraft-perf-stages");
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
        Registry<Biome> biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
        BiomeSource source = gen.getBiomeSource();
        BiomeManager manager = new BiomeManager(source.createUncachedResolver(rs),
                BiomeManager.obfuscateSeed(level.getSeed()));
        MaterialRule fast = settings.materialRule().value();
        MaterialRule plain = plain(fast);
        boolean ab = containsFast(fast);
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
                            level.structureManager(), starts, level.getStructureTemplateManager(), level.dimension());
                    long t1 = System.nanoTime();
                    ProtoChunk a1 = proto(level, biomes, pos);
                    biomes(rs, source, a1);
                    long t2 = System.nanoTime();
                    noise(rs, settings, a1);
                    long t3 = System.nanoTime();
                    ProtoChunk b1 = proto(level, biomes, pos);
                    biomes(rs, source, b1);
                    noise(rs, settings, b1);
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
                : "fast_surface A/B: the noise settings don't use nirncraft:fast_surface (both passes ran the plain rule)");
        return lines;
    }

    /** A blank proto chunk, marked as past structure references (it has none) so a region over it can be read. */
    private static ProtoChunk proto(ServerLevel level, Registry<Biome> biomes, ChunkPos pos) {
        ProtoChunk c = new ProtoChunk(pos, UpgradeData.EMPTY, level, net.minecraft.world.level.chunk.PalettedContainerFactory.create(level.registryAccess()), null);
        c.setPersistedStatus(ChunkStatus.STRUCTURE_REFERENCES);
        return c;
    }

    private static void biomes(RandomState rs, BiomeSource source, ChunkAccess chunk) {
        var pool = rs.acquireDensityBufferPool();
        try {
            var sampler = rs.createClimateSampler(SamplerContext.builder().enableCaches().useBufferArena(pool).build());
            ChunkPos pos = chunk.getPos();
            chunk.fillBiomesFromNoise(source.createResolverForChunk(sampler, QuartPos.fromBlock(pos.getMinBlockX()),
                    QuartPos.fromBlock(chunk.getMinY()), QuartPos.fromBlock(pos.getMinBlockZ()),
                    4, QuartPos.fromBlock(chunk.getHeight()), 4));
        } finally { rs.releaseDensityBufferPool(pool); }
    }

    private static NoiseChunk noiseChunk(RandomState rs, NoiseGeneratorSettings settings, ChunkAccess chunk) {
        var noise = settings.noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        var lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        var sea = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
        var empty = new Aquifer.FluidStatus(net.minecraft.world.level.dimension.DimensionType.MIN_Y * 2, Blocks.AIR.defaultBlockState());
        Aquifer.FluidPicker fluids = (x, y, z) -> net.minecraft.SharedConstants.DEBUG_DISABLE_FLUID_GENERATION
                ? empty : y < Math.min(-54, settings.seaLevel()) ? lava : sea;
        ChunkPos pos = chunk.getPos();
        return new NoiseChunk(rs, null, settings, fluids, Blender.empty(),
                new DensityVolume(16, noise.height(), 16, pos.getMinBlockX(), noise.minY(), pos.getMinBlockZ()));
    }

    /** Native scalar/volume density and aquifer path, with the same iteration and heightmaps as buildTerrain. */
    private static void noise(RandomState rs, NoiseGeneratorSettings settings, ChunkAccess chunk) {
        try (NoiseChunk noise = noiseChunk(rs, settings, chunk)) {
            var volume = noise.volume();
            var aquifer = noise.aquifer();
            var sampler = noise.cachingSamplers().get(settings.noiseRouter().finalDensity());
            Heightmap ocean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
            Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            try (var buffer = sampler.sampleVolume(volume)) {
                for (int z = 0; z < volume.sizeZ(); z++) for (int x = 0; x < volume.sizeX(); x++) {
                    for (int y = volume.sizeY() - 1; y >= 0; y--) {
                        int blockY = volume.blockY(y);
                        BlockState state = aquifer.computeSubstance(volume.blockX(x), blockY, volume.blockZ(z),
                                buffer.get(volume.indexUnchecked(x, y, z)));
                        if (state == null) state = settings.defaultBlock();
                        if (!state.isAir()) {
                            chunk.getSection(chunk.getSectionIndex(blockY)).setBlockState(x, blockY & 15, z, state, false);
                            ocean.update(x, blockY, z, state);
                            surface.update(x, blockY, z, state);
                            if (aquifer.shouldScheduleFluidUpdate() && !state.getFluidState().isEmpty())
                                chunk.markPosForPostProcessing(pos.set(volume.blockX(x), blockY, volume.blockZ(z)));
                        }
                    }
                }
            }
        }
    }

    private static void surface(RandomState rs, BiomeManager manager, Registry<Biome> biomes, NoiseGeneratorSettings settings,
                                NoiseBasedChunkGenerator gen, ChunkAccess chunk, MaterialRule rule) {
        try (NoiseChunk noise = noiseChunk(rs, settings, chunk)) {
            rs.surfaceSystem().buildSurface(rs, manager, new WorldGenerationContext(gen, chunk.getHeightAccessorForGeneration()),
                    chunk, noise, rule, gen.getBiomeSource().possibleBiomes());
        }
    }

    private static boolean containsFast(MaterialRule rule) {
        if (rule instanceof FastSurfaceRule.Source) return true;
        if (rule instanceof MaterialRule.HolderHolder ref) return containsFast(ref.holder().value());
        if (rule instanceof ConditionRule condition) return containsFast(condition.thenRun());
        return rule instanceof SequenceRule sequence && sequence.sequence().stream().anyMatch(StageBench::containsFast);
    }

    private static MaterialRule plain(MaterialRule rule) {
        if (rule instanceof FastSurfaceRule.Source fast) return plain(fast.rule());
        if (rule instanceof MaterialRule.HolderHolder ref) return plain(ref.holder().value());
        if (rule instanceof ConditionRule condition) return new ConditionRule(condition.ifTrue(), plain(condition.thenRun()));
        if (rule instanceof SequenceRule sequence) return new SequenceRule(sequence.sequence().stream().map(StageBench::plain).toList());
        return rule;
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
