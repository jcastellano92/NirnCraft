package com.skycraft.mounts.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.skycraft.Skycraft;
import com.skycraft.client.hud.CompassMarkers;
import com.skycraft.mounts.CompanionPackets;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A downed mount ({@code mounts.MountCompanion}) lies on its side: whatever renderer draws it, the model is rolled 90
 * degrees about its length and lowered to the ground ({@link RenderLivingEvent}). The owner's own downed mount also
 * shows on the compass.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
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
                        0xE0B050, I18n.get("mounts.skycraft.downed.marker"), 0)));
            } else if (markerEntity == m.entityId()) {
                markerEntity = -1;
                CompassMarkers.clear(SOURCE);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void pre(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity e = event.getEntity();
        if (DOWN.isEmpty() || !DOWN.contains(e.getId())) return;
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        // the renderer turns the model by (180 - body yaw) afterwards: roll about the mount's own length in that frame,
        // so it lies on its side whichever way it faces, and set it down on the ground
        float a = 180f - Mth.rotLerp(event.getPartialTick(), e.yBodyRotO, e.yBodyRot);
        ps.translate(0, e.getBbWidth() * 0.5, 0);
        ps.mulPose(Axis.YP.rotationDegrees(a));
        ps.translate(e.getBbHeight() * 0.5, 0, 0);
        ps.mulPose(Axis.ZP.rotationDegrees(90f));
        ps.mulPose(Axis.YP.rotationDegrees(-a));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void post(RenderLivingEvent.Post<?, ?> event) {
        if (DOWN.isEmpty() || !DOWN.contains(event.getEntity().getId())) return;
        event.getPoseStack().popPose();
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
