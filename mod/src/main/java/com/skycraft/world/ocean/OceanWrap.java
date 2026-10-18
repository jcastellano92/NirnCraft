package com.skycraft.world.ocean;

import com.skycraft.Skycraft;
import com.skycraft.perf.Prewarm;
import com.skycraft.tamriel.terrain.Beyond;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Same-level ocean voyages. Silent tickets load destinations asynchronously without occupying player travel UI. */
@net.neoforged.fml.common.EventBusSubscriber(modid=Skycraft.MODID)
public final class OceanWrap {
    private static final Map<UUID, Attempt> ATTEMPTS=new HashMap<>();
    private static final int HOLD_TICKS=120;
    /** At most six live prefetch holds per voyage, even if the predicted target moves every tick. */
    static final class HoldSchedule {
        private boolean started;
        private int chunkX,chunkZ;
        private long refreshAt,renewAt;
        boolean acquire(int x,int z,long now) {
            int cx=x>>4,cz=z>>4;
            if(now<refreshAt || (started && cx==chunkX && cz==chunkZ && now<renewAt)) return false;
            started=true;chunkX=cx;chunkZ=cz;refreshAt=now+20;renewAt=now+100;
            return true;
        }
    }
    private static final class Attempt {
        final Entity root;
        final ServerPlayer captain;
        final ServerLevel level;
        final HoldSchedule holds=new HoldSchedule();
        Attempt(Entity root,ServerPlayer captain) { this.root=root;this.captain=captain;this.level=captain.level(); }
    }
    private record Pose(Entity entity,float yaw,float pitch,Vec3 velocity) {}
    private OceanWrap() {}

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var server=event.getServer();
        ATTEMPTS.values().removeIf(a->a.level.getServer()!=server || !valid(a) || !OceanWorldConfig.enabled());
        if(!OceanWorldConfig.enabled()) return;
        var bounds=OceanWorldConfig.bounds();long now=server.getTickCount();
        for(ServerPlayer player:server.getPlayerList().getPlayers()) {
            if(player.level().dimension()!=Level.OVERWORLD || !player.isAlive()) continue;
            Entity root=player.getRootVehicle();
            if(!Double.isFinite(root.getX()) || !Double.isFinite(root.getZ())) continue;
            var target=OceanWrapMath.prefetch(root.getX(),root.getZ(),bounds);
            if(target.isEmpty()) { ATTEMPTS.remove(root.getUUID());continue; }
            // Inland players exit before allocating or walking a passenger stream.
            // One owner handles a boat carrying several players.
            if(root.getSelfAndPassengers().filter(ServerPlayer.class::isInstance).findFirst().orElse(null)!=player) continue;
            Attempt a=ATTEMPTS.computeIfAbsent(root.getUUID(),id->new Attempt(root,player));
            if(a.root!=root || a.captain!=player) { ATTEMPTS.remove(root.getUUID());continue; }
            int x=Mth.floor(target.get().x()),z=Mth.floor(target.get().z());
            if(a.holds.acquire(x,z,now)) Prewarm.hold(a.level,x,z,Prewarm.RADIUS,HOLD_TICKS);
            // Poll only a currently crossed seam. Holds never acquire player-busy ownership or send progress packets.
            if(OceanWrapMath.crossing(root.getX(),root.getZ(),bounds).isPresent()) complete(a);
        }
    }

    private static boolean valid(Attempt a) {
        return a.root.isAlive() && a.captain.isAlive() && a.root.level()==a.level
                && a.captain.level()==a.level && a.level.dimension()==Level.OVERWORLD
                && a.captain.getRootVehicle()==a.root
                && a.level.getServer().getPlayerList().getPlayer(a.captain.getUUID())==a.captain;
    }
    private static void complete(Attempt a) {
        if(ATTEMPTS.get(a.root.getUUID())!=a || !valid(a) || !OceanWorldConfig.enabled()) return;
        var bounds=OceanWorldConfig.bounds();
        // Never use the position captured when the loading job began: a ship may turn back or cross another axis.
        var target=OceanWrapMath.crossing(a.root.getX(),a.root.getZ(),bounds);
        if(target.isEmpty()) return;
        double x=target.get().x(),z=target.get().z();
        if(Beyond.fixedWorldFootprint(Mth.floor(x),Mth.floor(z)) || !Prewarm.isReady(a.level,Mth.floor(x),Mth.floor(z))) return;
        List<Entity> tree=a.root.getSelfAndPassengers().limit(65).toList();
        if(tree.size()>64 || tree.stream().anyMatch(e->!e.isAlive() || e.level()!=a.level)) return;
        double dx=x-a.root.getX(),dz=z-a.root.getZ();
        for(Entity entity:tree) if(!safe(a.level,entity,entity.getBoundingBox().move(dx,0,dz))) return;
        List<Pose> poses=tree.stream().map(e->new Pose(e,e.getYRot(),e.getXRot(),e.getDeltaMovement())).toList();
        // The same-level Entity overload preserves riding links and recursively places passengers in their seats.
        // The cross-level ServerPlayer overload deliberately dismounts, so it must not be used here.
        a.root.teleportTo(x,a.root.getY(),z);
        for(Pose pose:poses) {
            Entity e=pose.entity();e.setYRot(pose.yaw());e.setXRot(pose.pitch());e.setDeltaMovement(pose.velocity());
            if(e instanceof ServerPlayer p) p.connection.teleport(p.getX(),p.getY(),p.getZ(),pose.yaw(),pose.pitch());
            a.level.getChunkSource().sendToTrackingPlayersAndSelf(e,ClientboundTeleportEntityPacket.teleport(e.getId(), net.minecraft.world.entity.PositionMoveRotation.of(e), java.util.Set.of(), e.onGround()));
        }
        // Send mounts after positions, including to their own player riders; no dismount/remount or save conversion.
        for(Entity e:tree) if(e.isVehicle()) a.level.getChunkSource().sendToTrackingPlayersAndSelf(e,new ClientboundSetPassengersPacket(e));
        // Keep the hold cadence across the crossing so a fast ship cannot create a fresh job every frame.
    }

    private static boolean safe(ServerLevel level,Entity entity,AABB box) {
        if(box.getXsize()>64 || box.getZsize()>64 || !level.getWorldBorder().isWithinBounds(box)) return false;
        // Collision iterators inspect the one-block shell as well. Never let this check request a missing chunk.
        for(int cx=Mth.floor(box.minX-1)>>4;cx<=(Mth.floor(box.maxX+1)>>4);cx++)
            for(int cz=Mth.floor(box.minZ-1)>>4;cz<=(Mth.floor(box.maxZ+1)>>4);cz++)
                if(level.getChunkSource().getChunkNow(cx,cz)==null) return false;
        if(!level.noCollision(entity,box)) return false;
        // Old saves may contain previously generated islands: refuse occupied land rather than deleting/rebuilding it.
        BlockPos sea=new BlockPos(Mth.floor(box.getCenter().x),level.getSeaLevel()-1,Mth.floor(box.getCenter().z));
        return level.getFluidState(sea).is(FluidTags.WATER);
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { ATTEMPTS.clear(); }
}
