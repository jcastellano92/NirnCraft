package com.nirncraft.client.autotest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Files;
import java.nio.file.Path;

/** Disposable preview camera scouting and repeatable evidence, never a world-generation operation. */
final class VisualCamera {
    static JsonObject identity(Minecraft mc) {
        var data=new JsonObject();
        var server=mc.getSingleplayerServer();
        data.addProperty("seedKnown",server!=null);
        if(server!=null)data.addProperty("seed",server.overworld().getSeed());
        QaTransport.describe(mc,data);
        data.addProperty("dimension",mc.level.dimension().identifier().toString());
        data.addProperty("bakeInputs",com.nirncraft.bake.Bake.inputsHash());
        try(var in=VisualCamera.class.getResourceAsStream("/nirncraft/generation-identity.txt")) {
            if(in==null) throw new IllegalStateException("Missing generated geometry identity; rebuild processResources");
            data.addProperty("generationInputs",com.nirncraft.bake.Bake.sha((com.nirncraft.bake.Bake.inputsHash()+":"
                    +new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(java.io.IOException e) { throw new IllegalStateException("Cannot identify geometry build",e); }
        return data;
    }

    static JsonObject context(Minecraft mc) {
        var data=identity(mc);
        mc.level.getBiome(mc.player.blockPosition()).unwrapKey().ifPresent(key ->
                data.addProperty("biomeId",key.identifier().toString()));
        if(mc.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
            var region=com.nirncraft.tamriel.TamrielMap.region(mc.player.getBlockX(),mc.player.getBlockZ());
            if(region!=null) {
                data.addProperty("regionId",region.id());
                if(region.palette().startsWith("realm_"))
                    data.addProperty("regionArchetype",region.palette().substring(6));
            }
        }
        data.add("position",vector(mc.player.position()));
        data.addProperty("difficulty",mc.level.getDifficulty().getSerializedName());
        data.addProperty("cameraClear",mc.level.noCollision(mc.player,mc.player.getBoundingBox()));
        data.addProperty("dayTime",mc.level.getOverworldClockTime());
        data.addProperty("gameTime",mc.level.getGameTime());
        data.addProperty("nightVision",mc.player.hasEffect(com.nirncraft.platform.Effects.holder(net.minecraft.world.effect.MobEffects.NIGHT_VISION)));
        data.addProperty("weather",com.nirncraft.atmosphere.client.ClientWeather.kind().id());
        data.addProperty("renderDistance",mc.options.getEffectiveRenderDistance());
        data.addProperty("readinessPassed",ClientAutotest.readinessPassed(mc));
        data.addProperty("readiness",ClientAutotest.readinessDescription());
        data.addProperty("screen",mc.gui.screen()==null?"game":mc.gui.screen().getClass().getSimpleName());
        try {
            var runtime=com.google.gson.JsonParser.parseString(Files.readString(Path.of("world-preview/outbox/runtime.json"))).getAsJsonObject();
            if(runtime.has("pid") && runtime.get("pid").getAsLong()==ProcessHandle.current().pid())
                data.addProperty("coreSha256",runtime.get("coreSha256").getAsString());
        } catch(java.io.IOException | RuntimeException ignored) {}
        try {
            Class<?> api=Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            data.addProperty("shaders",Boolean.TRUE.equals(api.getMethod("isShaderPackInUse").invoke(api.getMethod("getInstance").invoke(null))));
        } catch(ReflectiveOperationException e) { data.addProperty("shaders",false); }
        return data;
    }

    private static AABB body(Vec3 p) { return new AABB(p.x-0.3,p.y,p.z-0.3,p.x+0.3,p.y+1.8,p.z+0.3); }

    static float[] angles(Vec3 p,Vec3 target) {
        Vec3 direction=target.subtract(p.add(0,1.62,0));
        if(direction.lengthSqr()<0.01) throw new IllegalArgumentException("Look-at target is at the camera");
        return new float[]{(float)(Math.toDegrees(Math.atan2(direction.z,direction.x))-90),
                (float)-Math.toDegrees(Math.atan2(direction.y,Math.hypot(direction.x,direction.z)))};
    }

    /** What the last `frame` put in view (part of the capture receipt). */
    static volatile JsonObject lastFrame;

    /**
     * `frame ANGLE FILL`: the camera fits the subject nearest to it (its real bounding box, multipart parts included)
     * so the box fills FILL of the frame height, seen from ANGLE degrees off the way it faces (0 front, 90 its side),
     * 10 degrees above. Sizes come from the game itself, so a dragon and a mudcrab are both framed properly.
     */
    static java.util.concurrent.CompletableFuture<Integer> frame(Minecraft mc,String arguments) {
        if(mc.getSingleplayerServer()==null) throw new IllegalStateException("frame needs the integrated server");
        String[] a=arguments.trim().split("\\s+");
        double angle=a.length>0?Double.parseDouble(a[0]):30, fill=a.length>1?Double.parseDouble(a[1]):0.7;
        double fovV=Math.toRadians(mc.options.fov().get());
        var result=new java.util.concurrent.CompletableFuture<Integer>();
        var id=mc.player.getUUID();
        mc.getSingleplayerServer().execute(()->{
            try {
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                if(player==null) throw new IllegalStateException("No player");
                var subject=player.level().getEntities(player,player.getBoundingBox().inflate(64),e->!(e instanceof net.minecraft.world.entity.player.Player)
                        && !(e instanceof net.neoforged.neoforge.entity.PartEntity<?>)).stream()
                        .min(java.util.Comparator.comparingDouble(e->e.distanceToSqr(player))).orElse(null);
                if(subject==null) throw new IllegalStateException("frame: no subject within 64 blocks");
                var box=subject.getBoundingBox();
                if(subject.isMultipartEntity() && subject.getParts()!=null)
                    for(var part:subject.getParts()) box=box.minmax(part.getBoundingBox());
                Vec3 centre=box.getCenter();
                double radius=0.5*Math.sqrt(box.getXsize()*box.getXsize()+box.getYsize()*box.getYsize()+box.getZsize()*box.getZsize());
                double distance=Math.max(1.5,radius/Math.tan(fill*fovV/2));
                double yaw=Math.toRadians(subject.getYRot()+angle), elev=Math.toRadians(10);
                Vec3 facing=new Vec3(-Math.sin(yaw),0,Math.cos(yaw));
                Vec3 eye=centre.add(facing.scale(distance*Math.cos(elev))).add(0,distance*Math.sin(elev),0);
                place(player,eye.subtract(0,1.62,0),centre);
                var info=new JsonObject();
                info.addProperty("subject",net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(subject.getType()).toString());
                info.add("box",vector(new Vec3(box.getXsize(),box.getYsize(),box.getZsize())));
                info.add("centre",vector(centre));
                info.addProperty("distance",distance);
                info.addProperty("angle",angle);
                info.addProperty("fill",fill);
                lastFrame=info;
                result.complete(1);
            } catch(Exception e) { result.completeExceptionally(e); }
        });
        return result;
    }

    static java.util.concurrent.CompletableFuture<Integer> action(Minecraft mc,String arguments,boolean scout) {
        if(mc.getSingleplayerServer()==null)
            throw new IllegalStateException("Camera scouting requires integrated terrain access; position remote QA through its authoritative server inbox, then use client clearance/readiness checks");
        String[] parts=arguments.trim().split("\\s+");
        if(parts.length!=6) throw new IllegalArgumentException("Expected position X Y Z and look-at X Y Z");
        Vec3 position=new Vec3(Double.parseDouble(parts[0]),Double.parseDouble(parts[1]),Double.parseDouble(parts[2]));
        Vec3 target=new Vec3(Double.parseDouble(parts[3]),Double.parseDouble(parts[4]),Double.parseDouble(parts[5]));
        if(!Double.isFinite(position.x+position.y+position.z+target.x+target.y+target.z))
            throw new IllegalArgumentException("Nonfinite camera");
        var result=new java.util.concurrent.CompletableFuture<Integer>();
        var id=mc.player.getUUID();
        mc.getSingleplayerServer().execute(()->{
            try {
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                if(player==null) throw new IllegalStateException("No player");
                ServerLevel level=player.level();
                if(Math.abs(position.x)>29_999_000 || Math.abs(position.z)>29_999_000
                        || position.y<level.getMinY() || position.y+1.8>=(level.getMaxY()+1))
                    throw new IllegalArgumentException("Camera outside buildable world bounds");
                if(!scout && !loaded(level,position)) {
                    // A reusable pose can be outside the currently loaded area.
                    // Prewarm only its 3x3 server chunks, then validate before moving.
                    // Client terrain/meshes still stream normally and must settle.
                    var chunks=new java.util.ArrayList<java.util.concurrent.CompletableFuture<?>>();
                    int cx=net.minecraft.util.Mth.floor(position.x)>>4,cz=net.minecraft.util.Mth.floor(position.z)>>4;
                    for(int dz=-1;dz<=1;dz++) for(int dx=-1;dx<=1;dx++)
                        chunks.add(level.getChunkSource().getChunkFuture(cx+dx,cz+dz,
                                net.minecraft.world.level.chunk.status.ChunkStatus.FULL,true));
                    java.util.concurrent.CompletableFuture.allOf(chunks.toArray(java.util.concurrent.CompletableFuture[]::new))
                            .whenComplete((ignored,error)->mc.getSingleplayerServer().execute(()->{
                                try {
                                    if(error!=null) throw new IllegalStateException("Camera destination prewarm failed",error);
                                    if(player.hasDisconnected() || player.level()!=level)
                                        throw new IllegalStateException("Player changed level during camera prewarm");
                                    place(player,position,target); result.complete(1);
                                } catch(Exception e) { result.completeExceptionally(e); }
                            }));
                    return;
                }
                if(scout) {
                    var report=new JsonObject();
                    var candidates=new java.util.ArrayList<JsonObject>();
                    for(int dy=-2;dy<=2;dy+=2) for(int dz=-4;dz<=4;dz+=4) for(int dx=-4;dx<=4;dx+=4) {
                        Vec3 p=position.add(dx,dy,dz);
                        if(!loaded(level,p) || !level.noCollision(player,body(p))) continue;
                        Vec3 eye=p.add(0,1.62,0), end=eye.add(target.subtract(eye).normalize().scale(Math.min(96,target.distanceTo(eye))));
                        boolean rayLoaded=true;
                        for(double t=0;t<=1;t+=0.05) rayLoaded &= loaded(level,eye.lerp(end,t));
                        if(!rayLoaded) continue;
                        var hit=level.clip(new ClipContext(eye,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
                        double sight=hit.getLocation().distanceTo(eye);
                        if(sight<3) continue;
                        var entry=new JsonObject();
                        entry.add("position",vector(p)); entry.add("lookAt",vector(target));
                        entry.addProperty("sightline",sight);
                        entry.addProperty("score",sight-0.25*p.distanceTo(position));
                        candidates.add(entry);
                    }
                    candidates.sort(java.util.Comparator.comparingDouble(c->-c.get("score").getAsDouble()));
                    var array=new JsonArray(); candidates.forEach(array::add);
                    report.add("candidates",array);
                    report.addProperty("generationInputs",identity(mc).get("generationInputs").getAsString());
                    Path file=Path.of("world-preview/camera-candidates.json");
                    Files.createDirectories(file.getParent()); Files.writeString(file,report.toString());
                    result.complete(candidates.size());
                } else {
                    place(player,position,target);
                    result.complete(1);
                }
            } catch(Exception e) { result.completeExceptionally(e); }
        });
        return result;
    }

    private static void place(net.minecraft.server.level.ServerPlayer player,Vec3 position,Vec3 target) {
        var level=player.level();
        if(!loaded(level,position) || !level.noCollision(player,body(position)))
            throw new IllegalStateException("Camera position is unloaded or intersects terrain");
        float[] view=angles(position,target);
        player.teleportTo(level,position.x,position.y,position.z,java.util.Set.of(),view[0],view[1],true);
    }

    private static boolean loaded(ServerLevel level,Vec3 p) {
        for(int dz=-1;dz<=1;dz++) for(int dx=-1;dx<=1;dx++)
            if(!level.hasChunkAt(BlockPos.containing(p.x+dx,p.y,p.z+dz))) return false;
        return true;
    }

    static JsonArray vector(Vec3 p) { var a=new JsonArray(); a.add(p.x); a.add(p.y); a.add(p.z); return a; }

    static JsonObject captureReceipt(Minecraft mc) {
        if(mc.player==null || mc.level==null || !QaTransport.connected(mc)) return null;
        var data=context(mc);
        data.add("lookAt",vector(mc.player.getEyePosition().add(mc.player.getLookAngle().scale(48))));
        data.addProperty("yaw",mc.player.getYRot()); data.addProperty("pitch",mc.player.getXRot());
        data.addProperty("cameraClear",mc.level.noCollision(mc.player,mc.player.getBoundingBox()));
        data.addProperty("air",com.nirncraft.atmosphere.client.InteriorAtmosphere.describe());
        data.addProperty("capturedAt",System.currentTimeMillis());
        if(lastFrame!=null) data.add("frame",lastFrame);
        return data;
    }
}
