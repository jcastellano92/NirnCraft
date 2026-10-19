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
        visibility = AtmosphereMath.nightSkyVisibility(night, 0.80f * masserLight + 0.20f * secundaLight, pollution);
    }
}
