package com.nirncraft.atlas.server;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.nirncraft.NirnCraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * The integrated server's overworld generator beside the GPU world (docs/ATLAS-GPU-LOOP.md section 4,
 * {@code "type": "nirncraft:atlas"} in the world preset). It is Minecraft's noise generator, and makes every chunk exactly
 * as that does unless the client's GPU store can give the chunk: then {@link #buildTerrain} fills the proto chunk from the
 * store's finished chunk (terrain, surface, water, the decoration, the settlements and the road paint: the same blocks the
 * renderer draws), and {@link #applyBiomeDecoration} runs nothing the GPU already did. Structures' starts and references,
 * biomes, base height and the original mobs are the noise generator's own.
 *
 * <p>Nothing here waits on the GPU: the fill is a future the chunk system chains on, so no worker thread blocks. A chunk
 * the GPU cannot give exactly (flagged by the kernels, the store reset under it, no answer in time, a dedicated server)
 * is made by the noise generator, as before; {@link AtlasServerStats} counts both.</p>
 *
 * <p>An existing world keeps the generator in its saved dimensions ({@code minecraft:noise}); a datapack's
 * {@code data/minecraft/dimension/overworld.json} would replace it (WorldDimensions.bake prefers the datapack's stems),
 * but the preset gives it to new worlds only.</p>
 */
public final class AtlasChunkGenerator extends NoiseBasedChunkGenerator {
    public static final MapCodec<AtlasChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
            NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(NoiseBasedChunkGenerator::generatorSettings)
    ).apply(i, i.stable(AtlasChunkGenerator::new)));

    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, NirnCraft.MODID);

    static {
        GENERATORS.register("atlas", () -> CODEC);
    }

    public static void init(IEventBus modBus) {
        GENERATORS.register(modBus);
    }

    /** How long a request for a chunk may wait for the GPU before Java makes it. */
    static final long TIMEOUT_MS = Long.getLong("nirncraft.atlas.serverGen.timeoutMs", 30_000L);

    /** Chunks filled from the GPU whose decoration step has not run yet (chunk pos -> time, ns). */
    private final ConcurrentHashMap<Long, Long> filled = new ConcurrentHashMap<>();

    public AtlasChunkGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings) {
        super(biomeSource, settings);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    private static final Executor FILL = Util.backgroundExecutor().forName("atlas chunk fill");

    @Override
    public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState,
                                                       StructureManager structureManager, BiomeManager biomeManager,
                                                       @Nullable WorldGenRegion carverBiomeRegion, Set<Holder<Biome>> possibleBiomes) {
        AtlasServerFeed.Provider feed = AtlasServerMode.gpuChunks() ? AtlasServerFeed.forGenerator(this) : null;
        // (blending data: an upgraded old world; its terrain is not the store's)
        if (feed == null || !feed.ready() || !blender.isEmpty() || chunk.isUpgrading())
            return java(chunk, blender, randomState, structureManager, biomeManager, carverBiomeRegion, possibleBiomes);
        // a structure the GPU world does not carry reaches the chunk (everything but the recorded settlements and dungeons):
        // Java makes it whole. Those it carries come with their block entities and mobs (the side files)
        if (hasUncoveredStructure(chunk, feed)) {
            AtlasServerStats.STRUCTURE_CHUNKS.incrementAndGet();
            return java(chunk, blender, randomState, structureManager, biomeManager, carverBiomeRegion, possibleBiomes);
        }
        ChunkPos pos = chunk.getPos();
        long t0 = System.nanoTime();
        CompletableFuture<AtlasServerFeed.Chunk> ask = feed.request(pos.x(), pos.z());
        if (ask == null) return java(chunk, blender, randomState, structureManager, biomeManager, carverBiomeRegion, possibleBiomes);
        return ask.orTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).handleAsync((gpu, err) -> {
            AtlasServerStats.GPU_WAIT_NS.addAndGet(System.nanoTime() - t0);
            if (gpu != null && err == null) {
                try {
                    fill(chunk, gpu);
                    markFilled(pos);
                    AtlasServerStats.GPU_CHUNKS.incrementAndGet();
                    return CompletableFuture.completedFuture(chunk);
                } catch (RuntimeException e) {
                    NirnCraft.LOGGER.warn("atlas server chunk {} could not be filled from the GPU ({}): Java makes it", pos, e.toString());
                    if (!(chunk instanceof net.minecraft.world.level.chunk.ProtoChunk)) throw e;
                    clear(chunk);
                }
            } else if (err != null) {
                if (err instanceof java.util.concurrent.TimeoutException || err.getCause() instanceof java.util.concurrent.TimeoutException) {
                    AtlasServerStats.GPU_TIMEOUTS.incrementAndGet();
                    feed.cancel(pos.x(), pos.z());
                }
            }
            return java(chunk, blender, randomState, structureManager, biomeManager, carverBiomeRegion, possibleBiomes);
        }, FILL).thenCompose(f -> f);
    }

    /** Whether any structure's start lies in the chunk or its pieces' box reaches it (the references the structure step made). */
    static boolean hasUncoveredStructure(ChunkAccess chunk, AtlasServerFeed.Provider feed) {
        for (var e : chunk.getAllReferences().entrySet()) if (!e.getValue().isEmpty() && !feed.covers(e.getKey())) return true;
        for (var e : chunk.getAllStarts().entrySet()) if (e.getValue().isValid() && !feed.covers(e.getKey())) return true;
        return false;
    }

    private CompletableFuture<ChunkAccess> java(ChunkAccess chunk, Blender blender, RandomState randomState, StructureManager structureManager,
                                                BiomeManager biomeManager, @Nullable WorldGenRegion carverBiomeRegion, Set<Holder<Biome>> possibleBiomes) {
        AtlasServerStats.JAVA_TERRAIN_CALLS.incrementAndGet();
        AtlasServerStats.JAVA_CHUNKS.incrementAndGet();
        return super.buildTerrain(chunk, blender, randomState, structureManager, biomeManager, carverBiomeRegion, possibleBiomes);
    }

    private void markFilled(ChunkPos pos) {
        long now = System.nanoTime();
        filled.put(pos.pack(), now);
        if (filled.size() > 4096) filled.values().removeIf(t -> now - t > 120_000_000_000L);  // (chunks that never got to decorate)
    }

    /** Clears whatever a half-done fill wrote (the Java fallback starts from an empty chunk). */
    private static void clear(ChunkAccess chunk) {
        for (LevelChunkSection s : chunk.getSections()) {
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) s.setBlockState(x, y, z, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), false);
        }
    }

    /** The store's chunk into the proto chunk's sections, then the generation heightmaps (doFill keeps them). */
    static void fill(ChunkAccess chunk, AtlasServerFeed.Chunk gpu) {
        BlockState[] palette = gpu.palette();
        int n = Math.min(gpu.sections().length, chunk.getSectionsCount());
        for (int s = 0; s < n; s++) {
            short[] ids = gpu.sections()[s];
            if (ids == null) continue;
            LevelChunkSection section = chunk.getSection(s);
            section.acquire();
            try {
                for (int i = 0; i < 4096; i++) {
                    int id = ids[i] & 0xFFFF;
                    if (id == 0) continue;
                    BlockState state = palette[id];
                    if (state.isAir()) continue;
                    section.setBlockState(i & 15, i >> 8, i >> 4 & 15, state, false);
                }
            } finally {
                section.release();
            }
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
        replaySide(chunk, gpu);
    }

    /**
     * The structures' block entities and mobs, recorded with their tapes: a block entity where the final block still has
     * one of that type (a later write may have replaced it), a mob as the proto chunk keeps it.
     */
    private static void replaySide(ChunkAccess chunk, AtlasServerFeed.Chunk gpu) {
        if (gpu.side() == null) return;
        for (net.minecraft.nbt.CompoundTag t : gpu.side()) {
            String kind = t.getStringOr("nirnKind", "");
            t.remove("nirnKind");
            if (kind.equals("be")) {
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(t.getIntOr("x", 0), t.getIntOr("y", 0), t.getIntOr("z", 0));
                if (chunk.getPos().x() != pos.getX() >> 4 || chunk.getPos().z() != pos.getZ() >> 4) continue;
                BlockState at = chunk.getBlockState(pos);
                if (!at.hasBlockEntity() || !(at.getBlock() instanceof net.minecraft.world.level.block.EntityBlock eb)) continue;
                net.minecraft.world.level.block.entity.BlockEntity probe = eb.newBlockEntity(pos, at);
                if (probe == null || !net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(probe.getType()).toString()
                        .equals(t.getStringOr("id", ""))) continue;
                chunk.setBlockEntityNbt(t);
            } else if (kind.equals("entity") && chunk instanceof net.minecraft.world.level.chunk.ProtoChunk proto) {
                proto.addEntity(t);
            }
        }
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        Long gpu = filled.remove(chunk.getPos().pack());
        if (gpu == null) {
            AtlasServerStats.JAVA_DECORATION_CALLS.incrementAndGet();
            super.applyBiomeDecoration(level, chunk, structureManager);
            return;
        }
        // the decoration, the settlements and the road paint are in the chunk already (the GPU's stages): nothing to run
    }
}
