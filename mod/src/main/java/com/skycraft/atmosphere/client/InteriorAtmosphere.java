package com.skycraft.atmosphere.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.skycraft.atmosphere.DungeonAir;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Bounded local media and actual scene emitters. Geometry remains in the native depth/shadow buffers. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = com.skycraft.Skycraft.MODID,
        value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class InteriorAtmosphere {
    private static String dimension = "";
    private static List<DungeonAir.Volume> volumes = List.of();
    private static final List<Light> bursts = new ArrayList<>();
    private static final List<BlockPos> fires = new ArrayList<>();
    private static final List<Impulse> impulses = new ArrayList<>();
    private static ClientLevel tracked;
    private static int renderedRooms, renderedLights, renderedSmoke;
    private static final com.skycraft.atmosphere.VolumeBlend ROOM_BLEND = new com.skycraft.atmosphere.VolumeBlend();
    private static int sourceChunkX=Integer.MIN_VALUE, sourceChunkZ=Integer.MIN_VALUE;
    private record Light(Vec3 position, int color, float energy, float radius, long until) {}
    private record Impulse(Vec3 position, float radius, long start) {}
    private InteriorAtmosphere() {}

    public static void receive(DungeonAir.Sync sync) {
        dimension = sync.dimension(); volumes = sync.volumes(); sourceChunkX=sync.chunkX(); sourceChunkZ=sync.chunkZ();
    }

    public static boolean readyAt(Vec3 point) {
        var level=Minecraft.getInstance().level;
        return level!=null && dimension.equals(level.dimension().location().toString())
                && (Mth.floor(point.x)>>4)==sourceChunkX && (Mth.floor(point.z)>>4)==sourceChunkZ && ROOM_BLEND.settled();
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void logout(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        dimension=""; volumes=List.of(); bursts.clear(); fires.clear(); impulses.clear(); tracked=null; ROOM_BLEND.clear();
    }

    public static int profileAt(Vec3 point) {
        var level=Minecraft.getInstance().level;
        if(level==null || !dimension.equals(level.dimension().location().toString())) return -1;
        for(var v:volumes) if(v.distanceSquared(point.x,point.y,point.z)==0) return v.style();
        return -1;
    }

    public static String describe() {
        return "rooms="+renderedRooms+" received="+volumes.size()+" lights="+renderedLights+" smoke="+renderedSmoke;
    }

    /** Called by real spell FX, not a separate dungeon showcase effect. */
    public static void light(Vec3 position, int color, float energy, float radius, int ticks) {
        var level = Minecraft.getInstance().level;
        if (level == null || !Double.isFinite(position.x + position.y + position.z)) return;
        bursts.removeIf(l -> l.until < level.getGameTime() || l.position.distanceToSqr(position) < 0.25);
        if (bursts.size() >= 32) bursts.remove(0);
        bursts.add(new Light(position, color, Mth.clamp(energy, 0, 8), Mth.clamp(radius, 1, 32),
                level.getGameTime() + Mth.clamp(ticks, 1, 200)));
    }

    /** Short-lived density displacement for a physical shock or shout; no world simulation changes. */
    public static void displace(Vec3 position, float radius) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        if (impulses.size() >= 4) impulses.remove(0);
        impulses.add(new Impulse(position, Mth.clamp(radius, 1, 16), level.getGameTime()));
    }

    static void write(NativeImage image, ClientLevel level, Vec3 camera) {
        if (tracked != level) { tracked = level; bursts.clear(); fires.clear(); impulses.clear(); ROOM_BLEND.clear(); }
        List<DungeonAir.Volume> nearby = dimension.equals(level.dimension().location().toString())
                ? new ArrayList<>(volumes) : new ArrayList<>();
        nearby.removeIf(v -> v.distanceSquared(camera.x, camera.y, camera.z) > 140*140);
        nearby.sort(Comparator.comparingDouble(v -> v.distanceSquared(camera.x, camera.y, camera.z)));
        for (int y=64; y<74; y++) for (int x=0; x<64; x++) image.setPixelRGBA(x,y,0);
        var blended = ROOM_BLEND.update(nearby.subList(0,Math.min(4,nearby.size())));
        int count = blended.size();
        for (int i=0; i<count; i++) {
            var entry=blended.get(i);
            var v=entry.volume();
            point(image,i*2,64,new Vec3(v.x0(),v.y0(),v.z0()).subtract(camera));
            point(image,i*2,65,new Vec3(v.x1(),v.y1(),v.z1()).subtract(camera));
            int floor=encode(v.floor()+1-camera.y);
            image.setPixelRGBA(i,66,(floor&255) | (floor>>>8)<<8 | v.style()<<16 | Math.round(entry.weight()*255)<<24);
        }
        image.setPixelRGBA(63,66,count);
        long time=level.getGameTime();
        bursts.removeIf(l -> l.until < time);
        impulses.removeIf(i -> time-i.start > 30);
        if (time % 20 == 0) discoverFires(level,camera);
        List<Light> lights=new ArrayList<>(bursts);
        for (var p:fires) lights.add(new Light(Vec3.atCenterOf(p).add(0,0.4,0),0xFF9B42,1.2f,12,time+1));
        int checked=0;
        for (var e:level.entitiesForRendering()) {
            if (++checked>256) break;
            if (e.isRemoved() || e.distanceToSqr(camera)>48*48) continue;
            String id=EntityType.getKey(e.getType()).getPath();
            int color=switch(id) {
                case "flame_atronach","ash_spawn" -> 0xFF7C30;
                case "frost_atronach","wisp","will_o_wisp" -> 0x83D8FF;
                case "storm_atronach","dragon_priest" -> 0xB8A4FF;
                case "dwarven_centurion","dwarven_sphere","dwarven_spider","dwarven_ballista" -> 0xFFA94F;
                default -> 0;
            };
            if(color!=0) lights.add(new Light(e.getEyePosition(),color,id.contains("atronach")?1f:0.12f,
                    id.contains("atronach")?10:3,time+1));
            else if(e.isOnFire()) lights.add(new Light(e.position().add(0,e.getBbHeight()*0.5,0),0xFF9340,0.6f,7,time+1));
        }
        lights.sort(Comparator.comparingDouble(l -> -l.energy*l.radius/(4+l.position.distanceToSqr(camera))));
        int lightCount=0, examined=0;
        for (var l:lights) {
            if(lightCount>=4 || examined++>=12) break;
            if(l.position.distanceToSqr(camera)>64*64) continue;
            // Conservative visibility: no light through a solid wall. A full point-light
            // shadow volume would be needed to light around an occluding corner.
            var hit=level.clip(new ClipContext(camera,l.position,ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,Minecraft.getInstance().player));
            if(hit.getType()!=HitResult.Type.MISS && hit.getLocation().distanceToSqr(l.position)>0.6) continue;
            point(image,lightCount*2,68,l.position.subtract(camera));
            float fade=Mth.clamp((l.until-time)/4f,0,1);
            // Scene lights added each tick have no expiration fade.
            if(l.until==time+1) fade=1;
            int rgb=l.color;
            image.setPixelRGBA(lightCount,69,rgba(((rgb>>16)&255)/255f*l.energy*fade/4,
                    ((rgb>>8)&255)/255f*l.energy*fade/4,(rgb&255)/255f*l.energy*fade/4,l.radius/32));
            lightCount++;
        }
        image.setPixelRGBA(63,69,lightCount);
        int smokeCount=0;
        for(var p:fires) {
            if(smokeCount>=4) break;
            if(nearby.stream().noneMatch(v->v.distanceSquared(p.getX(),p.getY()+1,p.getZ())==0)) continue;
            if(!level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
                    && !level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE)) continue;
            if(!level.getBlockState(p).getValue(CampfireBlock.LIT)) continue;
            int height=1;
            while(height<24 && level.getBlockState(p.above(height)).getCollisionShape(level,p.above(height)).isEmpty()) height++;
            point(image,smokeCount*2,70,Vec3.atCenterOf(p).add(0,0.3,0).subtract(camera));
            image.setPixelRGBA(smokeCount,71,rgba(height/32f,1f,0f,1f));
            smokeCount++;
        }
        image.setPixelRGBA(63,71,smokeCount);
        for(int i=0;i<Math.min(4,impulses.size());i++) {
            var impulse=impulses.get(i); point(image,i*2,72,impulse.position.subtract(camera));
            image.setPixelRGBA(i,73,rgba(impulse.radius/16f,(time-impulse.start)/30f,0,1));
        }
        image.setPixelRGBA(63,73,Math.min(4,impulses.size()));
        renderedRooms=count; renderedLights=lightCount; renderedSmoke=smokeCount;
    }

    private static void discoverFires(ClientLevel level,Vec3 camera) {
        fires.clear(); int cx=Mth.floor(camera.x)>>4,cz=Mth.floor(camera.z)>>4;
        for(int z=cz-2;z<=cz+2;z++) for(int x=cx-2;x<=cx+2;x++) {
            var chunk=level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false);
            if(chunk==null) continue;
            for(var be:chunk.getBlockEntities().values()) if(be instanceof CampfireBlockEntity
                    && be.getBlockState().getValue(CampfireBlock.LIT)
                    && be.getBlockPos().distToCenterSqr(camera)<48*48) fires.add(be.getBlockPos());
        }
        fires.sort(Comparator.comparingDouble(p->p.distToCenterSqr(camera)));
        if(fires.size()>8) fires.subList(8,fires.size()).clear();
    }

    static int rgba(float r,float g,float b,float a) {
        return Mth.clamp(Math.round(r*255),0,255) | Mth.clamp(Math.round(g*255),0,255)<<8
                | Mth.clamp(Math.round(b*255),0,255)<<16 | Mth.clamp(Math.round(a*255),0,255)<<24;
    }
    private static int encode(double v) { return Mth.clamp((int)Math.round(v*4)+32768,0,65535); }
    private static void point(NativeImage image,int column,int row,Vec3 p) {
        int x=encode(p.x),y=encode(p.y),z=encode(p.z);
        image.setPixelRGBA(column,row,(x&255) | (x>>>8)<<8 | (y&255)<<16 | (y>>>8)<<24);
        image.setPixelRGBA(column+1,row,z);
    }
}
