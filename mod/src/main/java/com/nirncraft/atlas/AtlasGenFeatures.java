package com.nirncraft.atlas;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.material.FluidState;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The client generator's features (docs/ATLAS-CLIENT-GENERATION.md, stage 4): a chunk's decoration as
 * ChunkGenerator.applyBiomeDecoration runs it (the steps in order, every feature by its global index, the same
 * decoration and feature seeds, the biome check), with the world's own placed features, over proto chunks filled
 * with the generated blocks ({@link Window}: what a WorldGenRegion holds at the features step; features that write
 * sections directly, mark fluids for post-processing or read heightmaps find real chunks). Structures are not placed
 * here (their pieces come from the plans: a later stage). A decoration writes into its 3 x 3 chunks; a chunk is final
 * once its 3 x 3 neighbours are decorated. The order neighbours are decorated in is the client's (vanilla's own order
 * depends on what the server loaded first).
 */
final class AtlasGenFeatures {
    /** The generated world a window is filled from: generator ids and stored quart biomes. */
    interface Terrain {
        /** The generator id at the block, -1 where the chunk is not generated. */
        int id(int x, int y, int z);

        /** The stored noise biome (the program's index) of a quart, -1 where the chunk is not generated. */
        int quartBiome(int qx, int qy, int qz);

        /** Fills a proto chunk's biomes (by default from the stored quarts). */
        default void fillBiomes(ProtoChunk c, AtlasGenProgram program) {
            c.fillBiomesFromNoise((qx, qy, qz) -> program.biomes.get(Math.max(0, quartBiome(qx, qy, qz))));
        }
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int ORES = GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();
    private final ChunkGenerator generator;
    private final ServerLevel owner;
    private final RegistryAccess registries;
    private final PalettedContainerFactory containers;
    private final LevelHeightAccessor heights;
    private final long seed, zoomSeed;
    private final AtlasGenProgram program;
    private final List<FeatureSorter.StepFeatureData> steps;
    private final Set<Holder<Biome>> sourceBiomes;
    private final boolean ores;
    private final java.util.concurrent.atomic.LongAdder decorated = new java.util.concurrent.atomic.LongAdder(),
            failures = new java.util.concurrent.atomic.LongAdder(), nanos = new java.util.concurrent.atomic.LongAdder();

    long decorated() {
        return decorated.sum();
    }

    long failures() {
        return failures.sum();
    }

    double millis() {
        return nanos.sum() / 1e6;
    }
    /** The features that failed here: the first failure of each (dev reports). */
    final Map<String, String> failed = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    AtlasGenFeatures(ChunkGenerator generator, ServerLevel owner, RegistryAccess registries, LevelHeightAccessor heights, long seed,
                     AtlasGenProgram program, boolean ores) throws ReflectiveOperationException {
        this.generator = generator;
        this.owner = owner;
        this.registries = registries;
        this.containers = PalettedContainerFactory.create(registries);
        this.heights = heights;
        this.seed = seed;
        this.zoomSeed = BiomeManager.obfuscateSeed(seed);
        this.program = program;
        this.ores = ores;
        Field f = ChunkGenerator.class.getDeclaredField("featuresPerStep");
        f.setAccessible(true);
        steps = ((Supplier<List<FeatureSorter.StepFeatureData>>) f.get(generator)).get();
        sourceBiomes = generator.getBiomeSource().possibleBiomes();
    }

    /** Proto chunks over a terrain, filled as they are first touched (one thread at a time). */
    final class Window {
        final Terrain terrain;
        final Map<Long, ProtoChunk> chunks = new ConcurrentHashMap<>();
        /** Told each chunk once it is filled (the terrain's own copy may go then). */
        volatile java.util.function.LongConsumer filled;

        Window(Terrain terrain) {
            this.terrain = terrain;
        }

        /** The proto chunk of (cx, cz), null where the terrain has no chunk. */
        ProtoChunk chunk(int cx, int cz) {
            long k = ChunkPos.pack(cx, cz);
            ProtoChunk c = chunks.get(k);
            if (c != null) return c;
            if (terrain.id(cx << 4, 0, cz << 4) < 0) return null;
            ProtoChunk filled = fill(cx, cz);
            c = chunks.putIfAbsent(k, filled);  // two decorations filling one chunk: both use the first
            if (c == null && this.filled != null) this.filled.accept(k);
            return c != null ? c : filled;
        }

        private ProtoChunk fill(int cx, int cz) {
            ProtoChunk c = new ProtoChunk(new ChunkPos(cx, cz), UpgradeData.EMPTY, heights, containers, null);
            terrain.fillBiomes(c, program);
            int x0 = cx << 4, z0 = cz << 4;
            LevelChunkSection[] secs = c.getSections();
            for (int s = 0; s < secs.length; s++) {
                int y0 = heights.getMinY() + s * 16;
                if (y0 > 575) break;
                LevelChunkSection sec = secs[s];
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++)
                        for (int x = 0; x < 16; x++) {
                            int id = terrain.id(x0 + x, y0 + y, z0 + z);
                            if (id > 0) sec.setBlockState(x, y, z, program.blocks.get(id), false);
                        }
            }
            Heightmap.primeHeightmaps(c, EnumSet.allOf(Heightmap.Types.class));
            c.setPersistedStatus(ChunkStatus.TERRAIN);  // what the features step finds (its heightmaps kept as it writes)
            return c;
        }
    }

    /** Decorates chunk (cx, cz) in the window (applyBiomeDecoration without the structures). */
    void decorate(int cx, int cz, Window w) {
        long t0 = System.nanoTime();
        Level l = new Level(w, cx, cz);
        WorldGenLevel level = l.proxy();
        int x0 = cx << 4, z0 = cz << 4;
        BlockPos origin = new BlockPos(x0, heights.getMinY(), z0);
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        long deco = random.setDecorationSeed(seed, x0, z0);
        // the biomes the 3 x 3 chunks hold (applyBiomeDecoration: their sections' biome palettes)
        Set<Holder<Biome>> possible = new HashSet<>();
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                ProtoChunk c = w.chunk(cx + dx, cz + dz);
                if (c != null) for (LevelChunkSection s : c.getSections()) s.getBiomes().getAll(possible::add);
            }
        possible.retainAll(sourceBiomes);
        FeaturePlacer placer = new FeaturePlacer(level, generator);
        for (int step = 0; step < steps.size(); step++) {
            if (step == ORES && !ores) continue;  // the public / secret split: ores from the server
            FeatureSorter.StepFeatureData data = steps.get(step);
            it.unimi.dsi.fastutil.ints.IntArraySet idx = new it.unimi.dsi.fastutil.ints.IntArraySet();
            for (Holder<Biome> b : possible) {
                List<HolderSet<PlacedFeature>> fs = b.value().getGenerationSettings().features();
                if (step < fs.size()) for (Holder<PlacedFeature> h : fs.get(step)) idx.add(data.indexMapping().applyAsInt(h.value()));
            }
            int[] order = idx.toIntArray();
            Arrays.sort(order);
            for (int gi : order) {
                PlacedFeature feature = data.features().get(gi);
                random.setFeatureSeed(deco, gi, step);
                try {
                    placer.placeWithBiomeCheck(feature, random, origin);
                } catch (RuntimeException | LinkageError e) {
                    failures.increment();  // a feature reading what this level does not offer: the server's blocks correct it
                    String key = registries.lookupOrThrow(net.minecraft.core.registries.Registries.PLACED_FEATURE)
                            .getResourceKey(feature).map(k -> k.identifier().toString()).orElse("?");
                    failed.computeIfAbsent(key, k -> {
                        StackTraceElement[] st = e.getStackTrace();
                        return e + (st.length > 0 ? " at " + st[0] + (st.length > 1 ? " < " + st[1] : "") : "");
                    });
                }
            }
        }
        decorated.increment();
        nanos.add(System.nanoTime() - t0);
    }

    /** One decoration's level over the window: writable over the 3 x 3 chunks, as a WorldGenRegion at the features step. */
    private final class Level {
        final Window w;
        final int cx, cz;
        final RandomSource random;
        final BiomeManager biomes;

        Level(Window w, int cx, int cz) {
            this.w = w;
            this.cx = cx;
            this.cz = cz;
            random = RandomSource.create(seed);
            biomes = new BiomeManager((qx, qy, qz) -> {
                ProtoChunk c = w.chunk(qx >> 2, qz >> 2);
                return c != null ? c.getNoiseBiome(qx, qy, qz) : program.biomes.get(Math.max(0, w.terrain.quartBiome(qx, qy, qz)));
            }, zoomSeed);
        }

        BlockState state(BlockPos p) {
            ProtoChunk c = w.chunk(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()));
            return c == null ? AIR : c.getBlockState(p);
        }

        boolean writable(BlockPos p) {
            int dx = SectionPos.blockToSectionCoord(p.getX()) - cx, dz = SectionPos.blockToSectionCoord(p.getZ()) - cz;
            return dx >= -1 && dx <= 1 && dz >= -1 && dz <= 1 && p.getY() >= heights.getMinY() && p.getY() <= heights.getMaxY();
        }

        ChunkAccess chunkAt(int x, int z) {
            return w.chunk(x, z);
        }

        @SuppressWarnings("unchecked")
        WorldGenLevel proxy() {
            InvocationHandler h = (proxy, m, args) -> {
                int n = args == null ? 0 : args.length;
                switch (m.getName()) {
                    case "getBlockState":
                        if (n == 1 && args[0] instanceof BlockPos p) return state(p);
                        break;
                    case "getFluidState":
                        if (n == 1 && args[0] instanceof BlockPos p) return state(p).getFluidState();
                        break;
                    case "setBlock":
                        if (n >= 3 && args[0] instanceof BlockPos p && args[1] instanceof BlockState s) {
                            if (!writable(p)) return false;
                            ProtoChunk c = w.chunk(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()));
                            if (c == null) return false;
                            c.setBlockState(p, s, (int) args[2]);
                            return true;
                        }
                        break;
                    case "removeBlock":
                    case "destroyBlock":
                        if (n >= 1 && args[0] instanceof BlockPos p && writable(p)) {
                            ProtoChunk c = w.chunk(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()));
                            if (c != null) c.setBlockState(p, state(p).getFluidState().createLegacyBlock(), 3);
                            return c != null;
                        }
                        return false;
                    case "ensureCanWrite":
                        return n == 1 && args[0] instanceof BlockPos p && writable(p);
                    case "getChunk":
                        if (n >= 2 && args[0] instanceof Integer x && args[1] instanceof Integer z) return chunkAt(x, z);
                        if (n == 1 && args[0] instanceof BlockPos p)
                            return chunkAt(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()));
                        break;
                    case "getHeight":
                        if (n == 0) return heights.getHeight();
                        if (n == 3 && args[0] instanceof Heightmap.Types t) {
                            int x = (int) args[1], z = (int) args[2];
                            ChunkAccess c = chunkAt(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
                            return c == null ? heights.getMinY() : c.getHeight(t, x & 15, z & 15) + 1;
                        }
                        break;
                    case "getHeightmapPos":
                        if (n == 2 && args[0] instanceof Heightmap.Types t && args[1] instanceof BlockPos p) {
                            ChunkAccess c = chunkAt(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()));
                            int y = c == null ? heights.getMinY() : c.getHeight(t, p.getX() & 15, p.getZ() & 15) + 1;
                            return new BlockPos(p.getX(), y, p.getZ());
                        }
                        break;
                    case "getMinY":
                        return heights.getMinY();
                    case "hasChunk":
                        if (n == 2) return chunkAt((int) args[0], (int) args[1]) != null;
                        break;
                    case "hasChunksAt":
                        return true;
                    case "isStateAtPosition":
                        if (n == 2) return ((Predicate<BlockState>) args[1]).test(state((BlockPos) args[0]));
                        break;
                    case "isFluidAtPosition":
                        if (n == 2) return ((Predicate<FluidState>) args[1]).test(state((BlockPos) args[0]).getFluidState());
                        break;
                    case "getBlockEntity":
                        return n == 2 ? Optional.empty() : null;
                    case "getSeed":
                        return seed;
                    case "getRandom":
                        return random;
                    case "isClientSide":
                        return false;
                    case "getSeaLevel":
                        return 63;
                    case "getBiome":
                        if (n == 1 && args[0] instanceof BlockPos p) return biomes.getBiome(p);
                        break;
                    case "getNoiseBiome":
                    case "getUncachedNoiseBiome":
                        if (n == 3) {
                            int qx = (int) args[0], qy = (int) args[1], qz = (int) args[2];
                            ProtoChunk c = w.chunk(qx >> 2, qz >> 2);
                            return c != null ? c.getNoiseBiome(qx, qy, qz) : program.biomes.get(Math.max(0, w.terrain.quartBiome(qx, qy, qz)));
                        }
                        break;
                    case "getBiomeManager":
                        return biomes;
                    case "getLevel":
                        if (owner != null) return owner;
                        break;
                    case "getLightEngine":
                        if (owner != null) return owner.getLightEngine();  // WorldGenRegion's: the level's (unlit there)
                        break;
                    case "registryAccess":
                        return registries;
                    case "getWorldBorder":
                        return owner != null ? owner.getWorldBorder() : new net.minecraft.world.level.border.WorldBorder();
                    case "scheduleTick":
                    case "setCurrentlyGenerating":
                    case "blockUpdated":
                    case "updateNeighborsAt":
                        return m.getReturnType() == boolean.class ? false : null;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    case "toString":
                        return "atlas generated level";
                    default:
                        break;
                }
                if (m.isDefault()) return InvocationHandler.invokeDefault(proxy, m, args);
                return AtlasCapture.zero(m.getReturnType());
            };
            return (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] {WorldGenLevel.class}, h);
        }
    }
}
