package com.skycraft.worldplan.tile;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Replays MinecraftServer.createLevels' map insertion/event order using real overworld lookup and SavedData storage. */
class TilePlanEarlyAttachTest {
    private static final Unsafe UNSAFE;
    static {
        try {Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);UNSAFE=(Unsafe)f.get(null);}
        catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}
    }
    private static <T>T allocate(Class<T> type)throws Exception{return type.cast(UNSAFE.allocateInstance(type));}
    private static void setServerField(MinecraftServer server,String name,Object value)throws Exception{
        Field field=MinecraftServer.class.getDeclaredField(name);UNSAFE.putObject(server,UNSAFE.objectFieldOffset(field),value);
    }
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
    private final java.util.List<AutoCloseable> resources = new java.util.ArrayList<>();
    @AfterEach void detach() throws Exception {
        TilePlanner.detach();
        for (int i = resources.size() - 1; i >= 0; i--) resources.get(i).close();
        resources.clear();
    }
    private SavedDataStorage storage(MinecraftServer server, LoadLevel level, Path directory) throws Exception {
        var access = net.minecraft.world.level.storage.LevelStorageSource.createDefault(directory).createAccess("world");
        resources.add(access);
        setServerField(server, "storageSource", access);
        var data = new SavedDataStorage(level, access.getWorldDir().resolve("data"), net.minecraft.util.datafix.DataFixers.getDataFixer(), net.minecraft.core.RegistryAccess.EMPTY);
        resources.add(data);
        return data;
    }
    private static TilePlanData saved(LoadLevel level) {
        return level.storage.get(com.skycraft.platform.SavedDataTypes.type(TilePlanData.NAME, TilePlanData::load, TilePlanData::new,
                value -> value.save(new net.minecraft.nbt.CompoundTag())));
    }
    private static void load(LoadLevel level)throws Exception{
        // Dispatch this actual load event through the subscriber annotation to isolate
        // its startup ordering from other modules in the genuine FML test runtime.
        var event=new LevelEvent.Load(level);
        for(var method:TilePlanCommands.class.getMethods()){
            if(method.isAnnotationPresent(net.neoforged.bus.api.SubscribeEvent.class)
                    && method.getParameterCount()==1 && method.getParameterTypes()[0].isInstance(event))method.invoke(null,event);
        }
    }
    public static class LoadLevel extends ServerLevel {
        MinecraftServer server;SavedDataStorage storage;ResourceKey<Level> key;long seed;
        public LoadLevel(){super(null,null,null,null,null,null,false,0,List.of(),false);}
        @Override public MinecraftServer getServer(){return server;}
        @Override public SavedDataStorage getDataStorage(){return storage;}
        @Override public ResourceKey<Level> dimension(){return key;}
        @Override public long getSeed(){return seed;}
    }
    @Test void overworldLoadAttachesBeforeSpawnAndStartedIsIdempotent(@TempDir Path directory)throws Exception{
        var server=allocate(DedicatedServer.class);var level=allocate(LoadLevel.class);
        level.server=server;level.key=Level.OVERWORLD;level.seed=0L;level.storage=storage(server,level,directory);
        var levels=new HashMap<ResourceKey<Level>,ServerLevel>();
        setServerField(server,"levels",levels);setServerField(server,"serverThread",Thread.currentThread());
        // Same public lookup used by TilePlanData.attach is unavailable until createLevels inserts it.
        assertNull(server.overworld());levels.put(Level.OVERWORLD,level);assertSame(level,server.overworld());
        load(level);
        TilePlanData data=saved(level);
        assertNotNull(data);Object epoch=TilePlanner.tokenFor(data);assertNotNull(epoch);
        assertEquals(0L,TilePlanner.getWorldSeed());
        // Spawn preparation happens here in createLevels, before ServerStarted.
        TilePlanCommands.onServerStarted(new ServerStartedEvent(server));
        assertSame(epoch,TilePlanner.tokenFor(data),"Later event must retain the startup session and its completed work");
    }
    @Test void loadingOtherDimensionCannotAttachOrReplaceOverworldSession(@TempDir Path directory)throws Exception{
        var server=allocate(DedicatedServer.class);var world=allocate(LoadLevel.class);var realm=allocate(LoadLevel.class);
        world.server=realm.server=server;world.key=Level.OVERWORLD;realm.key=Level.NETHER;world.seed=17;realm.seed=19;
        world.storage=storage(server,world,directory);
        setServerField(server,"levels",Map.of(Level.OVERWORLD,world,Level.NETHER,realm));
        setServerField(server,"serverThread",Thread.currentThread());
        load(world);
        var data=saved(world);Object epoch=TilePlanner.tokenFor(data);
        assertNotNull(epoch);
        // Deliberately no storage on the realm: calling attach here would fail instead of passing unnoticed.
        load(realm);
        assertSame(epoch,TilePlanner.tokenFor(data));assertEquals(17L,TilePlanner.getWorldSeed());
    }
}
