package com.nirncraft.arsenal.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.arsenal.ArsenalConfig;
import com.nirncraft.arsenal.ArsenalPackets;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Skyrim kill cams. The camera is moved to a client-only, never-spawned {@link ArmorStand} ({@link
 * Minecraft#setCameraEntity}) that orbits the kill (melee) or flies in behind the arrow (archery), with a slight roll
 * and the HUD hidden. Everything is restored when the cam ends, a screen opens, the player is hurt or dies, or the
 * player logs out. Purely client-side: the server only sends the trigger.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class KillCamClient {
    private static final int MELEE_TICKS = 26;
    private static final int ARROW_FLIGHT_TICKS = 14;
    private static final int ARROW_TICKS = 32;

    private static boolean active;
    private static ArmorStand marker;
    private static byte kind;
    private static int tick;
    private static int duration;
    private static Vec3 center = Vec3.ZERO;
    private static Vec3 flightStart = Vec3.ZERO;
    private static Vec3 flightEnd = Vec3.ZERO;
    private static double radius;
    private static float startAngle;
    private static float sweep;
    private static float side;
    private static float lastYaw;

    private static CameraType prevCamera;
    private static boolean prevHideGui;
    private static boolean changedHud;

    private KillCamClient() {}

    public static boolean active() {
        return active;
    }

    // ------------------------------------------------------------------ start

    /**
     * Starts a kill cam. {@code victimMid} is the middle of the victim's body as the server saw it on the killing blow:
     * the victim itself may already be gone here (its loot body replaces it right after death).
     */
    public static void start(int victimId, byte camKind, int arrowId, Vec3 dir, Vec3 victimMid) {
        try {
            if (!ArsenalConfig.CLIENT_KILL_CAMS.get()) return;
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            ClientLevel level = mc.level;
            if (active || player == null || level == null || mc.gui.screen() != null || !player.isAlive() || mc.getCameraEntity() != player) return;
            if (victimMid.distanceToSqr(player.position()) > 96 * 96) return;
            if (!ArsenalConfig.KILL_CAM_NEAR_PLAYERS.get()) {
                for (Player other : level.players()) {
                    if (other != player && other.distanceToSqr(player) < 32 * 32) return;
                }
            }

            kind = camKind;
            tick = 0;
            Entity victim = level.getEntity(victimId);
            if (victim != null && !victim.isRemoved()) victimMid = victim.position().add(0, victim.getBbHeight() * 0.55, 0);
            side = level.getRandom().nextBoolean() ? 1f : -1f;
            if (kind == ArsenalPackets.KillCam.ARROW) {
                Vec3 d = dir.lengthSqr() < 1e-6 ? victimMid.subtract(player.getEyePosition()) : dir;
                d = d.normalize();
                center = victimMid;
                flightStart = clipBack(level, victimMid, victimMid.subtract(d.scale(7)).add(0, 0.3, 0), 1.5);
                flightEnd = clipBack(level, victimMid, victimMid.subtract(d.scale(1.9)).add(0, 0.15, 0), 1.0);
                duration = ARROW_TICKS;
            } else {
                Vec3 playerMid = player.position().add(0, player.getBbHeight() * 0.55, 0);
                center = victimMid.add(playerMid.subtract(victimMid).scale(0.4));
                Vec3 line = victimMid.subtract(playerMid);
                double lineYaw = Math.toDegrees(Math.atan2(-line.x, line.z));
                startAngle = (float) lineYaw + 75f * side;
                sweep = 35f * side;
                radius = Mth.clamp(Math.sqrt(line.x * line.x + line.z * line.z) * 0.8 + 2.4, 2.8, 5.0);
                duration = MELEE_TICKS;
            }

            marker = new ArmorStand(level, center.x, center.y, center.z);
            marker.setInvisible(true);
            marker.setNoGravity(true);
            lastYaw = player.getYRot();
            update(0f);
            marker.setOldPosAndRot();

            prevCamera = mc.options.getCameraType();
            prevHideGui = mc.gui.hud.isHidden();
            changedHud = ArsenalConfig.KILL_CAM_HIDE_HUD.get();
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            if (changedHud) if(mc.gui.hud.isHidden()!=true) mc.gui.hud.toggle();
            mc.setCameraEntity(marker);
            active = true;
        } catch (Throwable t) {
            NirnCraft.LOGGER.warn("Kill cam failed to start", t);
            stop();
        }
    }

    /** Pulls a camera position towards {@code anchor} if a block is in the way. */
    private static Vec3 clipBack(ClientLevel level, Vec3 anchor, Vec3 wanted, double min) {
        Minecraft mc = Minecraft.getInstance();
        BlockHitResult hit = level.clip(new ClipContext(anchor, wanted, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() == HitResult.Type.MISS) return wanted;
        Vec3 d = wanted.subtract(anchor);
        double len = d.length();
        double dist = Math.max(min, hit.getLocation().distanceTo(anchor) - 0.35);
        return len < 1e-6 ? wanted : anchor.add(d.scale(Math.min(1.0, dist / len)));
    }

    // ------------------------------------------------------------------ per tick & sub-tick

    /** Computes camera position at continuous progress t in [0, 1]. */
    private static Vec3 computeCam(float progress) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return center;
        Vec3 cam;
        if (kind == ArsenalPackets.KillCam.ARROW) {
            float arrowFlightProgress = ARROW_FLIGHT_TICKS / (float) duration;
            if (progress <= arrowFlightProgress) {
                float t = progress / arrowFlightProgress;
                float ease = 1f - (1f - t) * (1f - t);
                cam = flightStart.add(flightEnd.subtract(flightStart).scale(ease));
            } else {
                float t = (progress - arrowFlightProgress) / Math.max(0.001f, 1f - arrowFlightProgress);
                Vec3 off = flightEnd.subtract(center);
                double ang = Math.toRadians(12f * side * t);
                double cos = Math.cos(ang), sin = Math.sin(ang);
                cam = center.add(off.x * cos - off.z * sin, off.y + 0.15 * t, off.x * sin + off.z * cos);
                cam = clipBack(level, center, cam, 1.0);
            }
        } else {
            double ang = Math.toRadians(startAngle + sweep * progress);
            Vec3 wanted = center.add(-Math.sin(ang) * radius, 0.55 + 0.35 * progress, Math.cos(ang) * radius);
            cam = clipBack(level, center, wanted, 1.2);
        }
        return cam;
    }

    /** Positions the camera marker for the current tick. */
    private static void update(float unused) {
        if (marker == null) return;
        float t = Mth.clamp(tick / (float) duration, 0f, 1f);
        Vec3 cam = computeCam(t);
        Vec3 d = center.subtract(cam);
        double hd = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.max(1e-4, hd)));
        yaw = lastYaw + Mth.wrapDegrees(yaw - lastYaw);
        lastYaw = yaw;

        marker.setPos(cam.x, cam.y - marker.getEyeHeight(), cam.z);
        marker.setYRot(yaw);
        marker.setXRot(Mth.clamp(pitch, -89f, 89f));
        marker.setYHeadRot(yaw);
        marker.setYBodyRot(yaw);
    }

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (!active) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null || mc.level == null || marker == null || marker.level() != mc.level || !player.isAlive()
                    || mc.gui.screen() != null || mc.getCameraEntity() != marker || (player.hurtTime > 0 && tick > 1)) {
                stop();
                return;
            }
            tick++;
            if (tick > duration) {
                stop();
                return;
            }
            float prevHead = marker.getYHeadRot();
            marker.setOldPosAndRot();
            marker.yHeadRotO = prevHead;
            update(0f);
        } catch (Throwable t) {
            NirnCraft.LOGGER.warn("Kill cam failed", t);
            stop();
        }
    }

    /** Ends the kill cam and restores the player's camera, view mode and HUD. Safe to call at any time. */
    public static void stop() {
        Minecraft mc = Minecraft.getInstance();
        boolean wasActive = active;
        active = false;
        try {
            if (marker != null && mc.getCameraEntity() == marker) {
                if (mc.player != null) mc.setCameraEntity(mc.player);
            }
            if (wasActive) {
                if (prevCamera != null) mc.options.setCameraType(prevCamera);
                if (changedHud) if(mc.gui.hud.isHidden()!=prevHideGui) mc.gui.hud.toggle();
            }
        } finally {
            marker = null;
            prevCamera = null;
            changedHud = false;
        }
    }

    // ------------------------------------------------------------------ presentation & safety

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!active || marker == null || Minecraft.getInstance().getCameraEntity() != marker) return;
        float subTick = tick + (float) event.getPartialTick();
        float t = (float) Mth.clamp(subTick / (float) duration, 0.0, 1.0);
        Vec3 cam = computeCam(t);
        Vec3 d = center.subtract(cam);
        double hd = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.max(1e-4, hd)));

        event.setYaw(yaw);
        event.setPitch(Mth.clamp(pitch, -89f, 89f));

        float max = (float) (double) ArsenalConfig.KILL_CAM_ROLL.get();
        float roll = kind == ArsenalPackets.KillCam.ARROW ? max * 0.4f * Mth.sin(t * (float) Math.PI) : max * Mth.sin(t * (float) Math.PI);
        event.setRoll(event.getRoll() + roll * side);
    }

    private record Pose(float fall,float half,float lift,boolean lunge,float dx,float dz) {}
    private static final net.minecraft.util.context.ContextKey<Pose> POSE=new net.minecraft.util.context.ContextKey<>(net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft","kill_camera_pose"));
    private static final net.minecraft.util.context.ContextKey<Boolean> PUSHED=new net.minecraft.util.context.ContextKey<>(net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft","kill_camera_pushed"));
    public static void extract(net.minecraft.world.entity.LivingEntity entity,net.minecraft.client.renderer.entity.state.LivingEntityRenderState state) {
        state.setRenderData(POSE,null);state.setRenderData(PUSHED,false);
        float progress=active&&duration>0?Mth.clamp((tick+state.partialTick)/duration,0,1):1;
        if(!entity.isAddedToLevel()) {
            float fall=com.nirncraft.loot.CorpseEvents.getDeathFallProgress(entity,state.partialTick,active,progress);
            if(fall<.999f) {
                state.setRenderData(POSE,new Pose(fall,entity.getBbHeight()*.5f,Math.min(entity.getBbWidth()*.5f,.75f),false,0,0));
                state.hasRedOverlay=fall<.35f;
                if(state instanceof net.minecraft.client.renderer.entity.state.HumanoidRenderState humanoid) {
                    humanoid.rightArmPose=net.minecraft.client.model.HumanoidModel.ArmPose.SPYGLASS;
                    humanoid.leftArmPose=net.minecraft.client.model.HumanoidModel.ArmPose.SPYGLASS;
                }
            }
        } else if(active&&kind==ArsenalPackets.KillCam.MELEE&&entity==Minecraft.getInstance().player&&state instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState avatar) {
            avatar.rightArmPose=progress<.38f?net.minecraft.client.model.HumanoidModel.ArmPose.THROW_TRIDENT:net.minecraft.client.model.HumanoidModel.ArmPose.BLOCK;
            float lunge=Mth.sin(progress*(float)Math.PI);double yaw=Math.toRadians(state.bodyRot);
            state.setRenderData(POSE,new Pose(1,0,0,true,(float)(-Math.sin(yaw)*.28*lunge),(float)(Math.cos(yaw)*.28*lunge)));
        }
    }
    @SubscribeEvent public static void onRenderLivingPre(net.neoforged.neoforge.client.event.RenderLivingEvent.Pre<?,?,?> event) {
        var state=event.getRenderState();Pose data=state.getRenderData(POSE);if(event.isCanceled()||data==null)return;
        var pose=event.getPoseStack();pose.pushPose();state.setRenderData(PUSHED,true);
        if(data.lunge()) pose.translate(data.dx(),0,data.dz());
        else {
            pose.rotateDegrees(com.mojang.math.Axis.ZP,-90);
            pose.translate(-data.half(),-data.lift(),0);
            pose.translate(data.half()*data.fall(),data.lift()*data.fall(),0);
            pose.rotateDegrees(com.mojang.math.Axis.ZP,90*data.fall());
        }
    }
    @SubscribeEvent public static void onRenderLivingPost(net.neoforged.neoforge.client.event.RenderLivingEvent.Post<?,?,?> event) {
        if(Boolean.TRUE.equals(event.getRenderState().getRenderData(PUSHED))) {
            event.getPoseStack().popPose();event.getRenderState().setRenderData(PUSHED,false);
        }
    }

    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov event) {
        if (!active || marker == null || Minecraft.getInstance().getCameraEntity() != marker) return;
        event.setFOV(event.getFOV() * (kind == ArsenalPackets.KillCam.ARROW ? .85f : .92f));
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (active) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onOverlay(RenderGuiLayerEvent.Pre event) {
        if (active && event.getName().equals(net.neoforged.neoforge.client.gui.VanillaGuiLayers.CROSSHAIR)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onScreen(ScreenEvent.Opening event) {
        if (active) stop();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        if (active) stop();
        marker = null;
    }
}

