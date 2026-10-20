package com.nirncraft.atmosphere.client;

import com.nirncraft.atmosphere.AtmosphereMath;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/** One bounded sky exposure state shared by the shader and the mesh fallback. */
final class NightSkyState {
    static float pollution, visibility, masserLight, secundaLight, night;
    private static ClientLevel previousLevel;

    private NightSkyState() {}

    static void tick(ClientLevel level, Vec3 eye) {
        BlockPos pos = BlockPos.containing(eye);
        float target = level.hasChunkAt(pos) ? level.getBrightness(LightLayer.BLOCK, pos) / 15f : 0f;
        // A torch in hand changes adaptation too, without scanning any world emitters.
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null && (player.getMainHandItem().is(net.minecraft.world.item.Items.TORCH)
                || player.getOffhandItem().is(net.minecraft.world.item.Items.TORCH)
                || player.getMainHandItem().is(net.minecraft.world.item.Items.LANTERN)
                || player.getOffhandItem().is(net.minecraft.world.item.Items.LANTERN))) target = Math.max(target, 0.85f);
        if (previousLevel != level) { pollution = target; previousLevel = level; }
        pollution = Mth.lerp(0.04f, pollution, target);
        double solarAngle = com.nirncraft.atmosphere.client.SkyEnvironment.sunCycle(level, 1f) * Math.PI * 2;
        masserLight = AtmosphereMath.visibleMoonIllumination(solarAngle, AtmosphereMath.moonCycle(level.getOverworldClockTime(), false), false);
        secundaLight = AtmosphereMath.visibleMoonIllumination(solarAngle, AtmosphereMath.moonCycle(level.getOverworldClockTime(), true), true);
        night = Mth.clamp(com.nirncraft.atmosphere.client.SkyEnvironment.starBrightness(level, 1f) * 2f, 0f, 1f);
        // stars only once the sky has darkened: the sun past civil twilight (-6 degrees) towards nautical (-12): before
        // that the twilight sky outshines them (they showed beside a setting sun)
        double sunElev = Math.asin(Math.max(-1.0, Math.min(1.0, Math.cos(solarAngle))));
        night *= (float) Mth.clamp((-sunElev - Math.toRadians(5.0)) / Math.toRadians(7.0), 0.0, 1.0);
        visibility = AtmosphereMath.nightSkyVisibility(night, 0.80f * masserLight + 0.20f * secundaLight, pollution);
        // and the eye: whatever brightens the local scene (a lit town's torches, the moon, twilight, later lightning and
        // clouds lit from below) closes it and the faint stars go; dark-adapted (log2 exposure 4+) they all show
        float adapted = com.nirncraft.atlas.AtlasClient.eyeExposureLog2();
        if (!Float.isNaN(adapted)) visibility *= Mth.clamp((adapted - 1.0f) / 3.0f, 0f, 1f);
    }
}
