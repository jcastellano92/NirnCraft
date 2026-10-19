package com.nirncraft.mounts.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nirncraft.NirnCraft;
import com.nirncraft.client.hud.CompassMarkers;
import com.nirncraft.mounts.CompanionPackets;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A downed mount ({@code mounts.MountCompanion}) lies on its side: whatever renderer draws it, the model is rolled 90
 * degrees about its length and lowered to the ground ({@link RenderLivingEvent}). The owner's own downed mount also
 * shows on the compass.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class MountDownedClient {
    private static final Set<Integer> DOWN = ConcurrentHashMap.newKeySet();
    private static volatile int markerEntity = -1;
    private static final String SOURCE = "mount_downed";

    private MountDownedClient() {}

    public static void apply(CompanionPackets.Downed m) {
        if (m.down()) DOWN.add(m.entityId());
        else DOWN.remove(m.entityId());
        if (m.mine()) {
            if (m.down()) {
                markerEntity = m.entityId();
                CompassMarkers.set(SOURCE, List.of(new CompassMarkers.Marker(new Vec3(m.x(), m.y(), m.z()), CompassMarkers.Shape.LOCATION,
                        0xE0B050, I18n.get("mounts.nirncraft.downed.marker"), 0)));
            } else if (markerEntity == m.entityId()) {
                markerEntity = -1;
                CompassMarkers.clear(SOURCE);
            }
        }
    }

    private static final net.minecraft.util.context.ContextKey<Boolean> DOWNED=new net.minecraft.util.context.ContextKey<>(net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft","mount_downed"));
    private static final net.minecraft.util.context.ContextKey<Boolean> PUSHED=new net.minecraft.util.context.ContextKey<>(net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft","mount_downed_pushed"));
    public static void extract(LivingEntity entity,net.minecraft.client.renderer.entity.state.LivingEntityRenderState state){state.setRenderData(DOWNED,DOWN.contains(entity.getId()));state.setRenderData(PUSHED,false);}
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void pre(RenderLivingEvent.Pre<?,?,?> event){
        var state=event.getRenderState();if(event.isCanceled()||!Boolean.TRUE.equals(state.getRenderData(DOWNED)))return;
        PoseStack pose=event.getPoseStack();pose.pushPose();state.setRenderData(PUSHED,true);
        float angle=180-state.bodyRot;pose.translate(0,state.boundingBoxWidth*.5,0);pose.rotateDegrees(Axis.YP,angle);
        pose.translate(state.boundingBoxHeight*.5,0,0);pose.rotateDegrees(Axis.ZP,90);pose.rotateDegrees(Axis.YP,-angle);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void post(RenderLivingEvent.Post<?,?,?> event){
        if(Boolean.TRUE.equals(event.getRenderState().getRenderData(PUSHED))){event.getPoseStack().popPose();event.getRenderState().setRenderData(PUSHED,false);}
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) return;
        int id = event.getEntity().getId();
        if (DOWN.remove(id) && markerEntity == id) {
            markerEntity = -1;
            CompassMarkers.clear(SOURCE);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        DOWN.clear();
        markerEntity = -1;
        CompassMarkers.clear(SOURCE);
    }
}
