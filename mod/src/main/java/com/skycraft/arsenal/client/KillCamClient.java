package com.skycraft.arsenal.client;

import com.skycraft.Skycraft;
import com.skycraft.arsenal.ArsenalConfig;
import com.skycraft.arsenal.ArsenalPackets;
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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Skyrim kill cams. The camera is moved to a client-only, never-spawned {@link ArmorStand} ({@link
 * Minecraft#setCameraEntity}) that orbits the kill (melee) or flies in behind the arrow (archery), with a slight roll
 * and the HUD hidden. Everything is restored when the cam ends, a screen opens, the player is hurt or dies, or the
 * player logs out. Purely client-side: the server only sends the trigger.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
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
            if (active || player == null || level == null || mc.screen != null || !player.isAlive() || mc.getCameraEntity() != player) return;
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
            side = level.random.nextBoolean() ? 1f : -1f;
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
            prevHideGui = mc.options.hideGui;
            changedHud = ArsenalConfig.KILL_CAM_HIDE_HUD.get();
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            if (changedHud) mc.options.hideGui = true;
            mc.setCameraEntity(marker);
            active = true;
        } catch (Throwable t) {
            Skycraft.LOGGER.warn("Kill cam failed to start", t);
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
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !active) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null || mc.level == null || marker == null || marker.level() != mc.level || !player.isAlive()
                    || mc.screen != null || mc.getCameraEntity() != marker || (player.hurtTime > 0 && tick > 1)) {
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
            Skycraft.LOGGER.warn("Kill cam failed", t);
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
                if (changedHud) mc.options.hideGui = prevHideGui;
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

    private static boolean pushedKillCamPose;

    @SubscribeEvent
    public static void onRenderLivingPre(net.minecraftforge.client.event.RenderLivingEvent.Pre<?, ?> event) {
        pushedKillCamPose = false;
        net.minecraft.world.entity.LivingEntity entity = event.getEntity();
        float pt = event.getPartialTick();
        float camProgress = active && duration > 0 ? Mth.clamp((tick + pt) / (float) duration, 0.0f, 1.0f) : 1.0f;

        // 1. Dying CorpseEntity render dummy: smoothly topple from 0 degrees (upright) to 90 degrees (flat)
        if (!entity.isAddedToWorld()) {
            float fallProgress = com.skycraft.loot.CorpseEvents.getDeathFallProgress(entity, pt, active, camProgress);
            if (fallProgress < 0.999f) {
                var poseStack = event.getPoseStack();
                poseStack.pushPose();
                pushedKillCamPose = true;
                float half = entity.getBbHeight() * 0.5f;
                float lift = Math.min(entity.getBbWidth() * 0.5f, 0.75f);
                // Undo CorpseRenderer's static 90-degree flat pose and interpolate smoothly by fallProgress
                poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(-90.0f));
                poseStack.translate(-half, -lift, 0.0f);
                poseStack.translate(half * fallProgress, lift * fallProgress, 0.0f);
                poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(90.0f * fallProgress));
                entity.hurtTime = fallProgress < 0.35f ? 8 : 0;
                if (event.getRenderer().getModel() instanceof net.minecraft.client.model.HumanoidModel<?> hm) {
                    hm.rightArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.SPYGLASS;
                    hm.leftArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.SPYGLASS;
                }
            }
            return;
        }

        // 2. Player attacker during melee kill-cam: cinematic overhead cleave and finishing follow-through
        if (active && kind == ArsenalPackets.KillCam.MELEE && entity == Minecraft.getInstance().player
                && event.getRenderer().getModel() instanceof net.minecraft.client.model.PlayerModel<?> pm) {
            pm.rightArmPose = camProgress < 0.38f
                    ? net.minecraft.client.model.HumanoidModel.ArmPose.THROW_SPEAR
                    : net.minecraft.client.model.HumanoidModel.ArmPose.BLOCK;
            float lunge = Mth.sin(camProgress * (float) Math.PI);
            var poseStack = event.getPoseStack();
            poseStack.pushPose();
            pushedKillCamPose = true;
            double yawRad = Math.toRadians(Mth.rotLerp(pt, entity.yBodyRotO, entity.yBodyRot));
            poseStack.translate(-Math.sin(yawRad) * 0.28 * lunge, 0.0, Math.cos(yawRad) * 0.28 * lunge);
        }
    }

    @SubscribeEvent
    public static void onRenderLivingPost(net.minecraftforge.client.event.RenderLivingEvent.Post<?, ?> event) {
        if (pushedKillCamPose) {
            event.getPoseStack().popPose();
            pushedKillCamPose = false;
        }
    }

    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov event) {
        if (!active || marker == null || Minecraft.getInstance().getCameraEntity() != marker) return;
        event.setFOV(event.getFOV() * (kind == ArsenalPackets.KillCam.ARROW ? 0.85 : 0.92));
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (active) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onOverlay(RenderGuiOverlayEvent.Pre event) {
        if (active && event.getOverlay() == VanillaGuiOverlay.CROSSHAIR.type()) event.setCanceled(true);
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

