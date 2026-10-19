package com.nirncraft.client.autotest;

import com.nirncraft.atmosphere.client.InteriorAtmosphere;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Capture-only observations. No forced chunk loads, generation tickets or renderer internals. */
@net.neoforged.fml.common.EventBusSubscriber(modid=com.nirncraft.NirnCraft.MODID,value=Dist.CLIENT)
public final class WorldReadiness {
    private static WorldReadiness active;
    private static int renderFrame;
    private static boolean rendering;
    private static final java.util.concurrent.ExecutorService PROBES=java.util.concurrent.Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"NirnCraft LOD readiness"); t.setDaemon(true); return t;
    });
    private CompletableFuture<String> lod;
    private int distance, x, z, blocked;
    private long nextProbe, start;
    private String lodStatus="not requested", detail="";
    private Method rendererInstance, rendererComplete, rendererDiagnostics, rendererVisible;
    private boolean rendererChecked,modernRenderer;
    private final LightQueueReadiness lightQueue=new LightQueueReadiness();
    private String rendererFailure, meshDetail="not sampled",postMeshDetail="not sampled",frameFailure;
    private final TerrainMeshReadiness.MainFrames meshFrames=new TerrainMeshReadiness.MainFrames();
    private Object frameLevel,frameRenderer;
    private net.minecraft.world.phys.Vec3 framePosition;
    private float frameYaw,framePitch;
    private boolean shadowChecked;
    private Object irisApi;
    private Method shadowPass;
    private net.minecraft.world.phys.Vec3 settledPosition;
    private String settledDimension;
    private int observations,readyObservations,readyStreak,longestReadyStreak,repeatedFrameObservations;
    private int lastObservationFrame=Integer.MIN_VALUE;
    private final int[] waitingCounts=new int[9];
    private String lastWaiting="none";

    /** A marker observes the real public FIFO after all previously queued light packets, without reflection or forced loads. */
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void lightQueueStarted(net.neoforged.neoforge.client.event.ClientTickEvent.Pre event){
        var watch=active;var mc=Minecraft.getInstance();var level=mc.level;
        if(watch==null || level==null || mc.isPaused())return;
        // Native26.3 drains this FIFO after tick Post, during ClientLevel.update(). Keep a completed
        // witness until poll observes it instead of replacing it at the start of every tick.
        watch.lightQueue.queueIfAbsent(level,level::queueLightUpdate);
    }
    private boolean lightQueueDrained(Object level){return lightQueue.isComplete(level);}

    private void checkRenderer() {
        if(rendererFailure!=null) throw new IllegalStateException(rendererFailure);
        if(!rendererChecked) {
            rendererChecked=true;
            try {
                Class<?> type;
                try{type=Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");modernRenderer=true;}
                catch(ClassNotFoundException legacy){type=Class.forName("me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer");}
                Method instance=type.getMethod("instanceNullable"), complete=type.getMethod("isTerrainRenderComplete");
                Method diagnostics=modernRenderer?type.getMethod("getDebugStrings",boolean.class):type.getMethod("getDebugStrings");
                Method visible=type.getMethod("getVisibleChunkCount");
                rendererInstance=instance; rendererComplete=complete; rendererDiagnostics=diagnostics; rendererVisible=visible;
            } catch(ClassNotFoundException ignored) {
            } catch(ReflectiveOperationException e) {
                rendererFailure="Unsupported terrain renderer capture readiness API";
                throw new IllegalStateException(rendererFailure,e);
            }
        }
    }

    private TerrainMeshReadiness.Counters counters(Object renderer) throws ReflectiveOperationException {
        Object diagnostics=modernRenderer?rendererDiagnostics.invoke(renderer,true):rendererDiagnostics.invoke(renderer);
        if(!(diagnostics instanceof java.util.Collection<?> lines))
            throw new IllegalStateException("Unsupported Embeddium capture readiness diagnostics result");
        return TerrainMeshReadiness.parse(lines);
    }

    private boolean shadowPass() throws ReflectiveOperationException {
        if(!shadowChecked) {
            shadowChecked=true;
            try {
                Class<?> type=Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi=type.getMethod("getInstance").invoke(null);
                shadowPass=type.getMethod("isRenderingShadowPass");
                if(irisApi==null) throw new IllegalStateException("Iris capture shadow-pass API is unavailable");
            } catch(ClassNotFoundException absent) { /* Vanilla or Embeddium without Oculus. */ }
        }
        return shadowPass!=null && Boolean.TRUE.equals(shadowPass.invoke(irisApi));
    }

    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void frameStartedPre(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) { frameStarted(event); }

    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void frameStartedPost(net.neoforged.neoforge.client.event.RenderFrameEvent.Post event) { frameStarted(event); }

    private static void frameStarted(net.neoforged.neoforge.client.event.RenderFrameEvent event) {
        rendering=event instanceof net.neoforged.neoforge.client.event.RenderFrameEvent.Pre;
        if(!rendering) return;
        renderFrame++;
        WorldReadiness watch=active;
        if(watch!=null) {
            Minecraft mc=Minecraft.getInstance();
            // No AFTER_LEVEL is dispatched after logout. Release the previous world's observation references here.
            if(mc.level==null || mc.player==null) {
                watch.meshFrames.reset();watch.frameLevel=watch.frameRenderer=null;watch.framePosition=null;
                watch.lightQueue.reset();
                active=null;
            }
        }
    }

    /** Forge dispatches AFTER_LEVEL after GameRenderer's main renderLevel call; shadow events are rejected explicitly. */
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST)
    public static void mainWorldDrawn(RenderLevelStageEvent.AfterLevel event) {
        WorldReadiness watch=active;
        if(watch==null) return;
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null || mc.player==null) {watch.meshFrames.reset();return;}
        try {
            if(watch.shadowPass()) return;
            watch.checkRenderer();
            boolean lighting=watch.lightQueueDrained(mc.level) && !mc.level.getLightEngine().hasLightWork();
            if(watch.rendererInstance!=null) {
                Object renderer=watch.rendererInstance.invoke(null);
                if(renderer==null) {watch.meshFrames.reset();watch.postMeshDetail="renderer not initialized";return;}
                if(watch.frameRenderer!=renderer || watch.frameLevel!=mc.level) watch.meshFrames.reset();
                watch.frameRenderer=renderer;
                var counts=watch.counters(renderer);
                int visible=((Number)watch.rendererVisible.invoke(renderer)).intValue();
                boolean idle=counts.idle(Boolean.TRUE.equals(watch.rendererComplete.invoke(renderer))) && lighting;
                watch.meshFrames.complete(renderFrame,visible,idle,false);
                watch.postMeshDetail=counts.describe()+" "+watch.meshFrames.describe();
            } else {
                watch.meshFrames.complete(renderFrame,0,lighting && mc.levelRenderer.hasRenderedAllSections(),false);
                watch.postMeshDetail="vanilla "+watch.meshFrames.describe();
            }
            watch.frameLevel=mc.level;watch.framePosition=mc.player.position();
            watch.frameYaw=mc.player.getYRot();watch.framePitch=mc.player.getXRot();
        } catch(ReflectiveOperationException|RuntimeException|LinkageError e) {
            // Fail only the requested capture, never throw out of the game's render pass.
            watch.meshFrames.reset();watch.frameFailure="Main-frame terrain readiness failed: "+e;
        }
    }

    private boolean meshesReady(Minecraft mc,boolean lighting) {
        if(frameFailure!=null) throw new IllegalStateException(frameFailure);
        checkRenderer();
        boolean sameRenderer=true;
        if(rendererInstance!=null) try {
            Object renderer=rendererInstance.invoke(null);
            if(renderer==null) { meshFrames.reset(); meshDetail="renderer not initialized"; return false; }
            // Tick-time queues can describe work submitted by another visibility pass between draws.
            // They remain diagnostic evidence; readiness requires the identical strict idle test after MAIN draw.
            meshDetail="tick["+counters(renderer).describe()+"] postMain["+postMeshDetail+"]";
            sameRenderer=frameRenderer==renderer;
        } catch(ReflectiveOperationException e) { throw new IllegalStateException("Terrain renderer query failed",e); }
        else meshDetail="postMain["+postMeshDetail+"]";
        double positionDeltaSquared=framePosition==null?Double.POSITIVE_INFINITY:framePosition.distanceToSqr(mc.player.position());
        double yawDelta=TerrainMeshReadiness.yawDelta(frameYaw,mc.player.getYRot());
        double pitchDelta=(double)mc.player.getXRot()-framePitch;
        boolean sameLevel=frameLevel==mc.level;
        boolean samePose=sameLevel && framePosition!=null && TerrainMeshReadiness.sameView(positionDeltaSquared,
                frameYaw,framePitch,mc.player.getYRot(),mc.player.getXRot());
        boolean fresh=meshFrames.readyAt(renderFrame,rendering);
        meshDetail+=" witness[currentFrame="+renderFrame+" age="+meshFrames.age(renderFrame)+" rendering="+rendering
                +" fresh="+fresh+" sameLevel="+sameLevel+" sameRenderer="+sameRenderer+" samePose="+samePose
                +" positionDeltaSquared="+positionDeltaSquared+" yawDelta="+yawDelta+" pitchDelta="+pitchDelta+"]";
        return lighting && sameRenderer && samePose && fresh;
    }

    void begin(Minecraft mc,int lodChunks) {
        active=this;
        settledPosition=null;
        distance=lodChunks; x=mc.player.blockPosition().getX(); z=mc.player.blockPosition().getZ();
        lod=null; nextProbe=0; start=System.currentTimeMillis();
        blocked=0;
        observations=readyObservations=readyStreak=longestReadyStreak=repeatedFrameObservations=0;
        lastObservationFrame=Integer.MIN_VALUE;java.util.Arrays.fill(waitingCounts,0);lastWaiting="none";
        lightQueue.reset();
        meshFrames.reset(); meshDetail=postMeshDetail="not sampled";frameFailure=null;framePosition=null;
        lodStatus=distance>0?"pending":"not requested";
        var server=mc.getSingleplayerServer();
        var id=mc.player.getUUID();
        if(server!=null) server.execute(()->{
            var player=server.getPlayerList().getPlayer(id);
            if(player!=null) com.nirncraft.atmosphere.DungeonAir.sync(player,true);
        });
    }

    /** "need/have" of the last visibleReady, for logs. */
    String visibleDetail = "";

    /**
     * What the camera can actually see is ready: the chunk columns inside its view cone (horizontal FOV plus a margin)
     * and the ring right around it are loaded and lit, and Sodium reports the visible terrain built. Chunks behind the
     * camera or outside the view are not waited for.
     */
    boolean visibleReady(Minecraft mc) {
        int radius = Math.max(1, mc.options.getEffectiveRenderDistance() - 2);
        var c = mc.player.chunkPosition();
        double yaw = Math.toRadians(mc.player.getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double half = Math.toRadians(Math.min(85, mc.options.fov().get() * 0.75 + 20));
        double minDot = Math.cos(half);
        int need = 0, have = 0;
        for (int dz = -radius; dz <= radius; dz++)
            for (int dx = -radius; dx <= radius; dx++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > radius * radius) continue;
                double len = Math.sqrt(d2);
                boolean ring = d2 <= 4, cone = len > 0 && (dx * fx + dz * fz) / len >= minDot;
                if (!ring && !cone) continue;
                need++;
                var chunk = mc.level.getChunkSource().getChunk(c.x() + dx, c.z() + dz, ChunkStatus.FULL, false);
                if (chunk != null && !(chunk instanceof EmptyLevelChunk) && mc.level.getLightEngine()
                        .lightOnInColumn(net.minecraft.core.SectionPos.asLong(c.x() + dx, 0, c.z() + dz))) have++;
            }
        boolean terrain = true;
        checkRenderer();
        if (rendererInstance != null) try {
            Object r = rendererInstance.invoke(null);
            terrain = r != null && Boolean.TRUE.equals(rendererComplete.invoke(r));
        } catch (ReflectiveOperationException e) {
            terrain = false;
        }
        visibleDetail = "visible chunks " + have + "/" + need + " terrain " + (terrain ? "built" : "building");
        return have == need && terrain;
    }

    boolean poll(Minecraft mc) {
        int radius=Math.max(1,mc.options.getEffectiveRenderDistance()-2), have=0, total=0, lit=0;
        var camera=mc.player.chunkPosition();
        for(int dz=-radius;dz<=radius;dz++) for(int dx=-radius;dx<=radius;dx++) {
            if(dx*dx+dz*dz>radius*radius) continue;
            total++;
            var chunk=mc.level.getChunkSource().getChunk(camera.x()+dx,camera.z()+dz,ChunkStatus.FULL,false);
            if(chunk!=null && !(chunk instanceof EmptyLevelChunk)){have++;
                if(mc.level.getLightEngine().lightOnInColumn(net.minecraft.core.SectionPos.asLong(camera.x()+dx,0,camera.z()+dz)))lit++;}
        }
        // The packet listener can queue light tasks before the engine itself reports propagation work.
        // Both are public client-thread observations; FULL chunks alone do not imply completed lighting.
        boolean lightTasks=lightQueueDrained(mc.level);
        boolean lightEngine=!mc.level.getLightEngine().hasLightWork();
        boolean lighting=lightTasks && lightEngine && lit==total;
        boolean meshes=meshesReady(mc,lighting);
        boolean media=InteriorAtmosphere.readyAt(mc.player.getEyePosition());
        // the eye, as the capture checks it (the test player is a spectator: its body may stand in a wall or a roof a
        // settlement grew; the whole box failed readiness for poses whose picture was fine)
        var eye=mc.player.getEyePosition();
        boolean cameraClear=mc.level.noCollision(mc.player,new net.minecraft.world.phys.AABB(eye,eye).inflate(0.12));
        boolean weather=com.nirncraft.atmosphere.client.WeatherFieldTexture.ready();
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
        detail="native="+have+"/"+total+" lit="+lit+"/"+total+" radius="+radius+" meshes="+meshes+" air="+media
                +" cameraClear="+cameraClear+" weather="+weather+" lighting="+lighting
                +" lightTasksEmpty="+lightTasks+" lightEngineIdle="+lightEngine
                +" LOD="+lodStatus+" terrain["+meshDetail+"]";
        boolean lodReady=distance==0 || lodStatus.startsWith("sampled data ready");
        boolean ready=have==total && lighting && meshes && media && cameraClear && weather && lodReady;
        observations++;
        if(lastObservationFrame==renderFrame)repeatedFrameObservations++;
        lastObservationFrame=renderFrame;
        if(ready){readyObservations++;longestReadyStreak=Math.max(longestReadyStreak,++readyStreak);}
        else{
            readyStreak=0;
            if(have!=total)waitingCounts[0]++;
            if(lit!=total)waitingCounts[1]++;
            if(!lightTasks)waitingCounts[2]++;
            if(!lightEngine)waitingCounts[3]++;
            if(!meshes)waitingCounts[4]++;
            if(!media)waitingCounts[5]++;
            if(!cameraClear)waitingCounts[6]++;
            if(!weather)waitingCounts[7]++;
            if(!lodReady)waitingCounts[8]++;
            // Retain one bounded failure sample: a final all-ready sample must not hide the resets
            // that prevented the strict consecutive-observation window from completing.
            lastWaiting="sample="+observations+" frame="+renderFrame+" "+detail;
        }
        // Consume only a completed observation. The next MAIN pass must also drain the newly queued
        // witness and propagate light; no work, lit-column, engine or completed-frame gate is bypassed.
        if(lightTasks)lightQueue.renewIfComplete(mc.level,mc.level::queueLightUpdate);
        return ready;
    }

    String describe() {
        return start==0?"not requested":detail+" observations[total="+observations+" ready="+readyObservations
                +" streak="+readyStreak+" longest="+longestReadyStreak+" repeatedFrame="+repeatedFrameObservations
                +" waiting(native,lit,fifo,engine,meshes,air,camera,weather,lod)="+java.util.Arrays.toString(waitingCounts)
                +" lastWaiting={"+lastWaiting+"}] elapsedMs="+(System.currentTimeMillis()-start);
    }

    void settled(Minecraft mc) {
        settledPosition=mc.player.position(); settledDimension=mc.level.dimension().identifier().toString();
    }

    boolean validAt(Minecraft mc) {
        return settledPosition!=null && settledPosition.distanceToSqr(mc.player.position())<0.01
                && settledDimension.equals(mc.level.dimension().identifier().toString()) && poll(mc);
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
