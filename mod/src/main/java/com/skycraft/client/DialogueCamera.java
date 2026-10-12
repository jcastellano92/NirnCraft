package com.skycraft.client;

import com.skycraft.Skycraft;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Skyrim-style conversation camera ease: smoothly rotates view toward NPC face and zooms FOV in.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class DialogueCamera {
    private static int targetEntityId = -1;
    private static boolean active = false;
    private static float startYaw = 0.0f;
    private static float startPitch = 0.0f;
    private static float prevEaseProgress = 0.0f;
    private static float easeProgress = 0.0f;

    private static int missingScreenTicks = 0;

    private DialogueCamera() {}

    public static void start(int entityId) {
        missingScreenTicks = 0;
        if (active && targetEntityId == entityId) {
            // Already engaged in dialogue with this entity; keep camera zoomed and framed!
            return;
        }
        targetEntityId = entityId;
        active = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            startYaw = mc.player.getYRot();
            startPitch = mc.player.getXRot();
        }
        easeProgress = 0.0f;
        prevEaseProgress = 0.0f;
    }

    public static void stop() {
        active = false;
        missingScreenTicks = 0;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        prevEaseProgress = easeProgress;
        if (active) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof com.skycraft.client.screen.DialogueScreen) {
                missingScreenTicks = 0;
            } else {
                missingScreenTicks++;
                if (missingScreenTicks > 6) {
                    active = false;
                }
            }
            if (easeProgress < 1.0f) {
                easeProgress = Math.min(1.0f, easeProgress + 0.08f);
            }
        } else {
            if (easeProgress > 0.0f) {
                easeProgress = Math.max(0.0f, easeProgress - 0.10f);
                if (easeProgress <= 0.0f) {
                    targetEntityId = -1;
                }
            }
        }
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        float partial = (float) event.getPartialTick();
        float progress = Mth.clamp(Mth.lerp(partial, prevEaseProgress, easeProgress), 0.0f, 1.0f);
        if (progress <= 0.0001f || targetEntityId < 0) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;
        Entity target = mc.level.getEntity(targetEntityId);
        if (target == null) return;

        Vec3 playerEye = player.getEyePosition(partial);
        Vec3 targetEye = target.getEyePosition(partial);
        Vec3 diff = targetEye.subtract(playerEye);
        double distHoriz = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        if (distHoriz < 0.001) return;

        float wantedYaw = (float) (Mth.atan2(diff.z, diff.x) * (180.0 / Math.PI)) - 90.0F;
        float wantedPitch = (float) -(Mth.atan2(diff.y, distHoriz) * (180.0 / Math.PI));

        // Smooth cosine ease curve (eliminates tick-rate stepping jitter)
        float smoothFactor = 0.5f - 0.5f * Mth.cos(progress * (float) Math.PI);

        float blendedYaw = Mth.rotLerp(smoothFactor, startYaw, wantedYaw);
        float blendedPitch = Mth.lerp(smoothFactor, startPitch, wantedPitch);

        event.setYaw(blendedYaw);
        event.setPitch(blendedPitch);
    }

    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        float partial = (float) event.getPartialTick();
        float progress = Mth.clamp(Mth.lerp(partial, prevEaseProgress, easeProgress), 0.0f, 1.0f);
        if (progress <= 0.0001f) return;

        float smoothFactor = 0.5f - 0.5f * Mth.cos(progress * (float) Math.PI);
        double baseFov = event.getFOV();
        // Zoom in by ~18% (Skyrim conversation zoom)
        double targetFov = baseFov * 0.82;
        event.setFOV(Mth.lerp(smoothFactor, baseFov, targetFov));
    }

    /** The talker's floating nameplate would draw giant over its own face (and the dialogue's plate): hide it while talking. */
    @SubscribeEvent
    public static void onNameTag(RenderNameTagEvent event) {
        if ((active || easeProgress > 0.0f) && event.getEntity().getId() == targetEntityId) {
            event.setResult(net.minecraftforge.eventbus.api.Event.Result.DENY);
        }
    }
}
