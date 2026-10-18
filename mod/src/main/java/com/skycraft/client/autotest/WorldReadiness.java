package com.skycraft.client.autotest;

import com.skycraft.atmosphere.client.InteriorAtmosphere;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/** Capture-only observations. No forced chunk loads, generation tickets or renderer internals. */
final class WorldReadiness {
    private static final java.util.concurrent.ExecutorService PROBES=java.util.concurrent.Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"Skycraft LOD readiness"); t.setDaemon(true); return t;
    });
    private CompletableFuture<String> lod;
    private int distance, x, z, blocked;
    private long nextProbe, start;
    private String lodStatus="not requested", detail="";
    private Method rendererInstance, rendererComplete;
    private boolean rendererChecked;
    private net.minecraft.world.phys.Vec3 settledPosition;
    private String settledDimension;

    private boolean meshesReady(Minecraft mc) {
        if(!rendererChecked) {
            rendererChecked=true;
            try {
                Class<?> type=Class.forName("me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer");
                rendererInstance=type.getMethod("instanceNullable");
                rendererComplete=type.getMethod("isTerrainRenderComplete");
            } catch(ClassNotFoundException ignored) {
            } catch(ReflectiveOperationException e) { throw new IllegalStateException("Unsupported terrain renderer",e); }
        }
        if(rendererInstance!=null) try {
            Object renderer=rendererInstance.invoke(null);
            return renderer!=null && Boolean.TRUE.equals(rendererComplete.invoke(renderer));
        } catch(ReflectiveOperationException e) { throw new IllegalStateException("Terrain renderer query failed",e); }
        return mc.levelRenderer.hasRenderedAllChunks();
    }

    void begin(Minecraft mc,int lodChunks) {
        settledPosition=null;
        distance=lodChunks; x=mc.player.blockPosition().getX(); z=mc.player.blockPosition().getZ();
        lod=null; nextProbe=0; start=System.currentTimeMillis();
        blocked=0;
        lodStatus=distance>0?"pending":"not requested";
        var server=mc.getSingleplayerServer();
        var id=mc.player.getUUID();
        if(server!=null) server.execute(()->{
            var player=server.getPlayerList().getPlayer(id);
            if(player!=null) com.skycraft.atmosphere.DungeonAir.sync(player,true);
        });
    }

    boolean poll(Minecraft mc) {
        int radius=Math.max(1,mc.options.getEffectiveRenderDistance()-2), have=0, total=0;
        var camera=mc.player.chunkPosition();
        for(int dz=-radius;dz<=radius;dz++) for(int dx=-radius;dx<=radius;dx++) {
            if(dx*dx+dz*dz>radius*radius) continue;
            total++;
            var chunk=mc.level.getChunkSource().getChunk(camera.x+dx,camera.z+dz,ChunkStatus.FULL,false);
            if(chunk!=null && !(chunk instanceof EmptyLevelChunk)) have++;
        }
        boolean meshes=meshesReady(mc);
        boolean media=InteriorAtmosphere.readyAt(mc.player.getEyePosition());
        boolean cameraClear=mc.level.noCollision(mc.player,mc.player.getBoundingBox());
        boolean weather=com.skycraft.atmosphere.client.WeatherFieldTexture.ready();
        blocked=cameraClear?0:blocked+1;
        if(blocked>=5 && have==total) throw new IllegalStateException("Camera intersects loaded terrain; select another candidate");
        if(distance>0) {
            if(Math.abs(mc.player.getX()-x)>16 || Math.abs(mc.player.getZ()-z)>16) {
                x=mc.player.getBlockX(); z=mc.player.getBlockZ(); lod=null; nextProbe=0; lodStatus="pending";
            }
            if(lod!=null && lod.isDone()) {
                lodStatus=lod.join(); lod=null; nextProbe=System.currentTimeMillis()+1000;
            }
            if(!lodStatus.equals("sampled data ready; GPU meshes unverified") && lod==null && System.currentTimeMillis()>=nextProbe) {
                int px=x,pz=z,range=distance,near=radius;
                lod=CompletableFuture.supplyAsync(()->probe(px,pz,near,range),PROBES);
            }
        }
        detail="native="+have+"/"+total+" radius="+radius+" meshes="+meshes+" air="+media
                +" cameraClear="+cameraClear+" weather="+weather+" LOD="+lodStatus;
        return have==total && meshes && media && cameraClear && weather && (distance==0 || lodStatus.startsWith("sampled data ready"));
    }

    String describe() { return start==0?"not requested":detail+" elapsedMs="+(System.currentTimeMillis()-start); }

    void settled(Minecraft mc) {
        settledPosition=mc.player.position(); settledDimension=mc.level.dimension().location().toString();
    }

    boolean validAt(Minecraft mc) {
        return settledPosition!=null && settledPosition.distanceToSqr(mc.player.position())<0.01
                && settledDimension.equals(mc.level.dimension().location().toString()) && poll(mc);
    }

    private static String probe(int x,int z,int nativeRadius,int chunks) {
        try {
            Class<?> delayed=Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
            Object world=delayed.getField("worldProxy").get(null), repo=delayed.getField("terrainRepo").get(null);
            if(world==null || repo==null) return "API not initialized";
            Class<?> worldApi=Class.forName("com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy");
            Object level=worldApi.getMethod("getSinglePlayerLevel").invoke(world);
            if(level==null) return "level unavailable";
            Class<?> levelApi=Class.forName("com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper");
            Class<?> cacheApi=Class.forName("com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache");
            Method query=Class.forName("com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo")
                    .getMethod("getColumnDataAtBlockPos",levelApi,int.class,int.class,cacheApi);
            int have=0,total=0;
            for(int ring=1;ring<=3;ring++) for(int i=0;i<16;i++) {
                double radius=(nativeRadius+(Math.max(nativeRadius+1,chunks)-nativeRadius)*ring/3.0)*16;
                double angle=i*Math.PI/8;
                Object result=query.invoke(repo,level,(int)Math.floor(x+Math.cos(angle)*radius),
                        (int)Math.floor(z+Math.sin(angle)*radius),null);
                Object payload=result.getClass().getField("payload").get(result);
                if(result.getClass().getField("success").getBoolean(result) && payload!=null
                        && java.lang.reflect.Array.getLength(payload)>0) have++;
                total++;
            }
            return have==total?"sampled data ready; GPU meshes unverified":"data="+have+"/"+total+" to "+chunks+" chunks";
        } catch(ReflectiveOperationException | RuntimeException e) {
            return "unsupported/unavailable: "+e.getClass().getSimpleName();
        }
    }
}
