package com.nirncraft.atlas;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

/**
 * Infinite ATLAS's look for the near pass's resolve: the eye's exposure and night vision, the shader pack's numbers
 * (shaderpack/InfiniteATLAS/shaders/lib/exposure.glsl, grade.glsl; native/tools/pack_look.hpp canonicalExposure).
 *
 * <p>The exposure is the pack's canonical one, the eye adapted to mid grey under the open sky at this time of day
 * (noon x0.85, night at the clamp), opened towards the clamp as the sky light at the eye falls (a cave, a deep
 * interior). A metered eye (the frame's own log-average) replaces it in R3.</p>
 */
final class AtlasLook {
    static final float EXPOSURE_KEY = -0.79f, EXPOSURE_SLOPE = 0.72f, EXPOSURE_MIN = -1.6f, EXPOSURE_MAX = 2.3f;
    static final float NIGHT_VISION = 0.7f;  // NIGHT_VISION 2 x 0.35

    private static float eyeSky = 1f;  // the sky light at the eye, eased (seconds to adapt, as eyes do)

    private AtlasLook() {}

    /** {exposure (linear multiplier), night vision strength} for this frame. */
    static float[] frame(Minecraft mc) {
        float[] sun = AtlasClient.sunDirection(mc.gameRenderer.gameRenderState().levelRenderState.skyRenderState.sunAngle);
        float target = 1f;
        if (mc.level != null && mc.player != null)
            target = mc.level.getBrightness(LightLayer.SKY, BlockPos.containing(mc.player.getEyePosition())) / 15f;
        eyeSky += (target - eyeSky) * 0.03f;
        float open = canonicalExposure(sun);
        float log2 = open + (EXPOSURE_MAX - open) * (1f - smooth(0.1f, 0.8f, eyeSky));
        return new float[] {(float) Math.pow(2, log2), NIGHT_VISION};
    }

    /**
     * The projection vanilla draws the level with this frame: the camera's, times the hurt tilt and view bob
     * (GameRenderer.renderLevel's bobHurt / bobView, reproduced: they are private). Atlas's rays, its near depth and the
     * sky take this one, so the far field moves with the near terrain while walking instead of swimming against it.
     * The nausea / portal warp is left out (it is a screen effect, rare, and not invertible as a camera).
     */
    static org.joml.Matrix4f levelProjection(net.minecraft.client.renderer.state.level.CameraRenderState cam) {
        org.joml.Matrix4f m = new org.joml.Matrix4f(cam.projectionMatrix);
        var e = cam.entityRenderState;
        var opts = Minecraft.getInstance().gameRenderer.gameRenderState().optionsRenderState;
        com.mojang.blaze3d.vertex.PoseStack bob = new com.mojang.blaze3d.vertex.PoseStack();
        if (e.isLiving) {
            if (e.isDeadOrDying)
                bob.rotateDegrees(com.mojang.math.Axis.ZP, 40.0F - 8000.0F / (Math.min(e.deathTime, 20.0F) + 200.0F));
            float hurt = e.hurtTime;
            if (hurt >= 0.0F && !e.preventFlinch) {
                hurt /= e.hurtDuration;
                hurt = net.minecraft.util.Mth.sin(hurt * hurt * hurt * hurt * (float) Math.PI);
                bob.rotateDegrees(com.mojang.math.Axis.YP, -e.hurtDir);
                bob.rotateDegrees(com.mojang.math.Axis.ZP, (float) (-hurt * 14.0 * opts.damageTiltStrength));
                bob.rotateDegrees(com.mojang.math.Axis.YP, e.hurtDir);
            }
        }
        if (opts.bobView && e.isPlayer) {
            float w = e.backwardsInterpolatedWalkDistance, b = e.bob;
            bob.translate(net.minecraft.util.Mth.sin(w * (float) Math.PI) * b * 0.5F,
                    -Math.abs(net.minecraft.util.Mth.cos(w * (float) Math.PI) * b), 0.0F);
            bob.rotateDegrees(com.mojang.math.Axis.ZP, net.minecraft.util.Mth.sin(w * (float) Math.PI) * b * 3.0F);
            bob.rotateDegrees(com.mojang.math.Axis.XP, Math.abs(net.minecraft.util.Mth.cos(w * (float) Math.PI - 0.2F) * b) * 5.0F);
        }
        return m.mul(bob.last().pose());
    }

    /** {key dir xyz, key radiance rgb, sky-up rgb, ground rgb}: the pack's key light and sky ambient (pack_look.hpp). */
    static float[] light(Minecraft mc) {
        float[] sun = AtlasClient.sunDirection(mc.gameRenderer.gameRenderState().levelRenderState.skyRenderState.sunAngle);
        float[] o = new float[12];
        float day = smooth(-0.12f, 0.25f, sun[1]);
        if (sun[1] > -0.05f) {
            float e = sun[1], a = clamp01(e * 3), s = 3.2f * smooth(-0.04f, 0.12f, e);
            o[0] = sun[0]; o[1] = sun[1]; o[2] = sun[2];
            o[3] = s; o[4] = lerp(0.62f, 0.96f, a) * s; o[5] = lerp(0.38f, 0.9f, a) * s;
        } else {
            float m = smooth(-0.05f, -0.2f, sun[1]);
            o[0] = -sun[0]; o[1] = -sun[1]; o[2] = -sun[2];
            o[3] = 0.035f * m; o[4] = 0.05f * m; o[5] = 0.09f * m;
        }
        o[6] = lerp(0.02f, 0.33f, day); o[7] = lerp(0.025f, 0.45f, day); o[8] = lerp(0.05f, 0.7f, day);
        o[9] = lerp(0.01f, 0.2f, day); o[10] = lerp(0.01f, 0.18f, day); o[11] = lerp(0.012f, 0.14f, day);
        return o;
    }

    /** log2 exposure of the eye adapted to mid grey (albedo 0.18) under the open sky, lit by the key light and sky. */
    static float canonicalExposure(float[] sun) {
        float day = smooth(-0.12f, 0.25f, sun[1]);
        float skyUp = lum(lerp(0.02f, 0.33f, day), lerp(0.025f, 0.45f, day), lerp(0.05f, 0.7f, day));
        float key, keyY;
        if (sun[1] > -0.05f) {
            float e = sun[1], a = clamp01(e * 3);
            float s = 3.2f * smooth(-0.04f, 0.12f, e);
            key = lum(1f, lerp(0.62f, 0.96f, a), lerp(0.38f, 0.9f, a)) * s;
            keyY = sun[1];
        } else {
            key = lum(0.035f, 0.05f, 0.09f) * smooth(-0.05f, -0.2f, sun[1]);
            keyY = -sun[1];
        }
        float l = 0.18f * (key * Math.max(keyY, 0f) + skyUp);
        float logL = (float) (Math.log(Math.max(l, 1e-5f)) / Math.log(2));
        return Math.max(EXPOSURE_MIN, Math.min(EXPOSURE_MAX, EXPOSURE_KEY - EXPOSURE_SLOPE * logL));
    }

    private static float lum(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float x) {
        return Math.max(0f, Math.min(1f, x));
    }

    private static float smooth(float e0, float e1, float x) {
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }
}
