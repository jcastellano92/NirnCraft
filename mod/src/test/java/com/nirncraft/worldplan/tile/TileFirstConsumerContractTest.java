package com.nirncraft.worldplan.tile;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.nirncraft.bake.Bake;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.WorldData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Executes vanilla's actual first structure status and save codec; this is an architecture proof, not an installed generator. */
class TileFirstConsumerContractTest {
    private static HolderLookup.Provider lookup;
    private static final Unsafe UNSAFE;
    static {
        try { Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);UNSAFE=(Unsafe)f.get(null); }
        catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}
    }
    private static <T> T allocate(Class<T> type) throws Exception {return type.cast(UNSAFE.allocateInstance(type));}
    @BeforeAll static void bootstrap() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        lookup=VanillaRegistries.createWorldLookup();
        try(DataInputStream in=new DataInputStream(Bake.class.getResourceAsStream("/nirncraft/baked/roadplan.bin"))){
            Field f=Bake.class.getDeclaredField("inputs");f.setAccessible(true);if(f.get(null)==null)f.set(null,in.readUTF());
        }
    }
    public static class ProbeGenerator extends NoiseBasedChunkGenerator {
        Consumer<ChunkAccess> consumer;
        ProbeGenerator(BiomeSource source,Holder<NoiseGeneratorSettings> settings){super(source,settings);}
        // The attractive legacy-compatible encoding deliberately uses vanilla's exact codec.
        @Override protected com.mojang.serialization.MapCodec<? extends ChunkGenerator> codec(){return NoiseBasedChunkGenerator.CODEC;}
        @Override public void createStructures(RegistryAccess access,ChunkGeneratorStructureState state,
                StructureManager structures,ChunkAccess chunk,StructureTemplateManager templates, net.minecraft.resources.ResourceKey<Level> dimension){consumer.accept(chunk);}
    }
    private static ProbeGenerator generator(){
        return new ProbeGenerator(new FixedBiomeSource(lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS)),
                lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD));
    }
    public static class ProbeChunk extends ProtoChunk {
        volatile ChunkStatus status;
        public ProbeChunk(){super(null,null,null,null,null);}
        @Override public ChunkPos getPos(){return new ChunkPos(1024,0);}
        @Override public ChunkStatus getPersistedStatus(){return status;}
        @Override public void setPersistedStatus(ChunkStatus next){status=next;}
    }
    public static class ProbeChunks extends ServerChunkCache {
        public ProbeChunks(){super(null,null,null,null,null,null,0,0,false,null);}
        @Override public ChunkGeneratorStructureState getGeneratorState(){return null;}
    }
    public static class ProbeLevel extends ServerLevel {
        MinecraftServer server;ProbeChunks chunks;AtomicInteger published;
        public ProbeLevel(){super(null,null,null,null,null,null,false,0,List.of(),false);}
        @Override public MinecraftServer getServer(){return server;}
        @Override public ServerChunkCache getChunkSource(){return chunks;}
        @Override public net.minecraft.resources.ResourceKey<Level> dimension(){return Level.OVERWORLD;}
        @Override public RegistryAccess registryAccess(){return RegistryAccess.EMPTY;}
        @Override public StructureManager structureManager(){return null;}
        @Override public void onStructureStartsAvailable(ChunkAccess chunk){published.incrementAndGet();}
    }
    private static ProbeLevel level() throws Exception {
        var level=allocate(ProbeLevel.class);level.server=allocate(DedicatedServer.class);
        var data = new net.minecraft.world.level.levelgen.WorldGenSettings(new WorldOptions(17L,true,false), null);
        Field field=MinecraftServer.class.getDeclaredField("worldGenSettings");
        UNSAFE.putObject(level.server,UNSAFE.objectFieldOffset(field),data);
        level.chunks=allocate(ProbeChunks.class);level.published=new AtomicInteger();return level;
    }
    private static ProbeChunk chunk() throws Exception {var chunk=allocate(ProbeChunk.class);chunk.status=ChunkStatus.EMPTY;return chunk;}

    /** Execute the native generation pyramid, including its status-publication continuation. */
    private static CompletableFuture<ChunkAccess> generateStructures(ProbeLevel level, ProbeGenerator generator, ProbeChunk chunk) {
        var context = new net.minecraft.world.level.chunk.status.WorldGenContext(level, generator, null, null, Runnable::run, null);
        return net.minecraft.world.level.chunk.status.ChunkPyramid.GENERATION_PYRAMID
                .getStepTo(ChunkStatus.STRUCTURE_STARTS).apply(context, null, chunk);
    }

    @Test void legacyNoiseSaveRoundtripLosesSubclassPrerequisite() {
        ProbeGenerator guarded=generator();
        RegistryOps<JsonElement> ops=RegistryOps.create(JsonOps.INSTANCE,lookup);
        JsonElement encoded=ChunkGenerator.CODEC.encodeStart(ops,guarded).getOrThrow();
        assertEquals("minecraft:noise",encoded.getAsJsonObject().get("type").getAsString());
        ChunkGenerator decoded=ChunkGenerator.CODEC.parse(ops,encoded).getOrThrow();
        assertEquals(NoiseBasedChunkGenerator.class,decoded.getClass(),"Legacy noise codec reconstructs vanilla, never the custom guard");
        assertFalse(decoded instanceof ProbeGenerator);
        assertTrue(((NoiseBasedChunkGenerator)decoded).generatorSettings().is(NoiseGeneratorSettings.OVERWORLD));
        assertEquals(encoded,ChunkGenerator.CODEC.encodeStart(ops,decoded).getOrThrow());
    }

    @Test void actualStructureStatusPublishesBeforeAsynchronousVoidHookCompletes() throws Exception {
        var level=level();var chunk=chunk();var generator=generator();
        var planning=new CompletableFuture<TilePlan>();var consumed=new AtomicReference<TilePlan>();
        generator.consumer=c->planning.thenAccept(consumed::set);
        var completion=generateStructures(level,generator,chunk);
        assertTrue(completion.isDone());assertEquals(ChunkStatus.STRUCTURE_STARTS,chunk.status);
        assertEquals(1,level.published.get());assertNull(consumed.get());assertFalse(planning.isDone());
        planning.complete(TilePlanner.buildTilePlan(17L,4,0));
        assertNotNull(consumed.get(),"Too-late completion cannot undo already published absence");
    }

    @Test void actualStructureStatusRequiresWorkerFallbackBeforePublicationAndMatchesWarmPlan() throws Exception {
        var level=level();var cold=chunk();var warm=chunk();var generator=generator();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var seen=new AtomicReference<byte[]>();
        Thread owner=Thread.currentThread();
        TilePlanner.attachQueued(17L,new Object(),(seed,x,z)->{
            entered.countDown();
            try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Not released");}
            catch(InterruptedException e){throw new AssertionError(e);}
            return null;
        },null,null,()->Thread.currentThread()==owner);
        var worker=Executors.newSingleThreadExecutor(r->new Thread(r,"proof-structure-worldgen"));
        try {
            generator.consumer=c->{
                try {seen.set(TilePlanner.requireTileForWorker(17L,4,0).tile().write());}
                catch(Exception e){throw new AssertionError(e);}
            };
            var coldCompletion=CompletableFuture.supplyAsync(()->generateStructures(level,generator,cold),worker).thenCompose(f->f);
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            assertFalse(coldCompletion.isDone());assertSame(ChunkStatus.EMPTY,cold.status);assertEquals(0,level.published.get());
            release.countDown();coldCompletion.get(10,TimeUnit.SECONDS);
            assertSame(ChunkStatus.STRUCTURE_STARTS,cold.status);assertEquals(1,level.published.get());
            byte[] coldPlan=seen.get();assertNotNull(coldPlan);
            generator.consumer=c->{try{seen.set(TilePlanner.requireTileForWorker(17L,4,0).tile().write());}catch(Exception e){throw new AssertionError(e);}};
            CompletableFuture.supplyAsync(()->generateStructures(level,generator,warm),worker).thenCompose(f->f).get(10,TimeUnit.SECONDS);
            assertArrayEquals(coldPlan,seen.get());assertEquals(2,level.published.get());assertSame(ChunkStatus.STRUCTURE_STARTS,warm.status);
        } finally {release.countDown();worker.shutdownNow();TilePlanner.detach();}
    }
}
