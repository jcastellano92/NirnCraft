package com.skycraft.atmosphere.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import com.skycraft.Skycraft;
import com.skycraft.atmosphere.AtmosphereConfig;
import com.skycraft.atmosphere.AtmosphereMath;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32;

/**
 * The Tamriel night sky, drawn right after the vanilla sky ({@link RenderLevelStageEvent.Stage#AFTER_SKY}) in the
 * overworld: Secunda on its own tilted orbit, Masser and its reddish glow, a denser twinkling star field, a faint
 * galaxy band, the thirteen birthsign constellations (the Serpent wanders), occasional shooting stars and, on clear
 * nights, the continuous world-Z latitude aurora. Under regional overcast a tinted veil dims the sky first
 * ({@link ClientWeather}), except when an Oculus/Iris shader pack is active so the shader's own sky gradient, sun,
 * and god-rays remain intact underneath the celestial overlays.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class SkyRenderer {
    private static final ResourceLocation MASSER = new ResourceLocation(Skycraft.MODID, "textures/environment/masser_phases.png");
    private static final ResourceLocation SECUNDA = new ResourceLocation(Skycraft.MODID, "textures/environment/secunda_phases.png");
    private static final ResourceLocation MASSER_HALO = new ResourceLocation(Skycraft.MODID, "textures/environment/masser_halo.png");
    private static final ResourceLocation GALAXY = new ResourceLocation(Skycraft.MODID, "textures/environment/galaxy.png");
    private static final ResourceLocation STAR = new ResourceLocation(Skycraft.MODID, "textures/environment/star.png");

    /** Secunda's orbit: inclined to Masser's and running a little ahead of it. */
    private static final float SECUNDA_TILT = 24.0F;
    private static final float SECUNDA_LEAD = 31.0F;
    private static final float SECUNDA_SIZE = 7.0F;
    private static final float MASSER_SIZE = 20.0F;
    private static final float HALO_SIZE = 36.0F;
    private static final double AURORA_KEY_SECONDS = 6.0;
    /** The Serpent drifts once around the sky in this many days, out of step with the other stars. */
    private static final double SERPENT_PERIOD_DAYS = 27.0;

    // ---- the current shooting star (all primitives: nothing is allocated per frame)
    private static final java.util.Random METEOR_RNG = new java.util.Random();
    private static double nextMeteorAt = -1.0;
    private static double meteorStart = -10.0;
    private static double meteorDuration;
    private static double meteorSpeed;      // radians per second
    private static double meteorTail;       // radians
    private static double mSx, mSy, mSz;     // start direction
    private static double mTx, mTy, mTz;     // travel direction (tangent at the start)

    /** 0..1, continuous latitude/climate factor for the aurora (smoothed by {@link AtmosphereClientEvents}). */
    static float coldness;
    static float coldnessPrev;

    private SkyRenderer() {}

    public static float latitudeFactor(double blockZ) {
        return AtmosphereMath.latitudeFactor(blockZ);
    }

    public static float auroraElevationMinDeg(double blockZ) {
        return AtmosphereMath.auroraElevationMinDeg(blockZ);
    }

    public static float auroraElevationMaxDeg(double blockZ) {
        return AtmosphereMath.auroraElevationMaxDeg(blockZ);
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            renderShaderLightning(event);
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;
        render(event);
    }

    /** Bolts belong after terrain, before the shader's depth-aware atmosphere composite. */
    private static void renderShaderLightning(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != Level.OVERWORLD || !ShaderCompat.shadersActive()
                || !AtmosphereConfig.DISTANT_WEATHER.get() || !DistantStorms.hasActiveFlash()) return;
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        GL11.glEnable(GL32.GL_DEPTH_CLAMP);
        try {
            DistantStorms.renderBolts(event.getPoseStack().last().pose(), event.getProjectionMatrix(),
                    event.getCamera().getPosition(), event.getPartialTick());
        } finally {
            GL11.glDisable(GL32.GL_DEPTH_CLAMP);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
        }
    }

    public static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;
        if (level.dimension() != Level.OVERWORLD || level.effects().skyType() != DimensionSpecialEffects.SkyType.NORMAL) return;
        if (!AtmosphereConfig.SKY.get()) return;
        boolean shadersActive = ShaderCompat.shadersActive();
        if (AtmosphereConfig.SKIP_WITH_SHADERS.get() && shadersActive) return;
        Camera camera = event.getCamera();
        if (camera.getFluidInCamera() != FogType.NONE) return;
        if (mc.player.hasEffect(MobEffects.BLINDNESS) || mc.player.hasEffect(MobEffects.DARKNESS)) return;
        if (mc.gui.getBossOverlay().shouldCreateWorldFog()) return;

        float pt = event.getPartialTick();
        float overcast = ClientWeather.active() && AtmosphereConfig.WEATHER_SKY.get() ? ClientWeather.clouds() : 0.0F;
        // The shader integrates per-ray cloud/fog extinction later. Camera-wide rain must not
        // erase an aurora above the deck or stars visible through a genuine opening.
        float clear = shadersActive ? 1f : 1f - overcast * 0.72f;
        float night = Mth.clamp(level.getStarBrightness(pt) * 2.0F, 0.0F, 1.0F);
        double camZ = camera.getPosition().z;

        SkyGeometry.ensureBuilt();
        PoseStack pose = event.getPoseStack();
        Matrix4f proj = event.getProjectionMatrix();
        double seconds = (level.getGameTime() + pt) / 20.0;

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        GL11.glEnable(GL32.GL_DEPTH_CLAMP);
        try {
            // overcast veil: normal blending over vanilla's sun and moon, tinted like the fog.
            // Skipped when an Oculus/Iris shader pack is active so the shader's own sky gradient and god-rays remain intact.
            float veil = overcast * (1.0F - 0.5F * level.getRainLevel(pt));
            if (veil > 0.01F && !shadersActive) {
                RenderSystem.defaultBlendFunc();
                float day = Mth.clamp(Mth.cos(level.getTimeOfDay(pt) * Mth.TWO_PI) * 2.0F + 0.5F, 0.0F, 1.0F);
                float lift = 0.85F + 0.15F * day;
                RenderSystem.setShaderColor(ClientWeather.fogR * lift, ClientWeather.fogG * lift, ClientWeather.fogB * lift, Math.min(0.94F, veil));
                SkyGeometry.veil.bind();
                SkyGeometry.veil.drawWithShader(pose.last().pose(), proj, GameRenderer.getPositionColorShader());
                VertexBuffer.unbind();
            }
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            if (clear <= 0.01F) return;

            // celestial frame: tilted subtly by world-Z latitude for the star dome, constellations, and galaxy
            float starAlpha = NightSkyState.visibility * clear;
            if (starAlpha > 0.003F && !shadersActive) {
                pose.pushPose();
                pose.mulPose(Axis.YP.rotationDegrees(-90.0F));
                pose.mulPose(Axis.ZP.rotationDegrees(AtmosphereMath.starDomeTiltDeg(camZ)));
                pose.mulPose(Axis.XP.rotationDegrees(level.getTimeOfDay(pt) * 360.0F));
                if (AtmosphereConfig.GALAXY.get()) drawGalaxy(pose, proj, starAlpha * 0.24F);
                if (AtmosphereConfig.STARS.get()) drawStars(pose, proj, starAlpha, seconds);
                if (AtmosphereConfig.CONSTELLATIONS.get()) drawConstellations(pose, proj, starAlpha, level.getDayTime() + pt);
                pose.popPose();
            }

            // Solar-relative lunar orbit: the same phase now also moves the body through the sky.
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(-90.0F));
            pose.mulPose(Axis.XP.rotationDegrees(level.getTimeOfDay(pt) * 360.0F
                    + (float) AtmosphereMath.moonCycle(level.getDayTime() + pt, false) * 360.0F));
            if (!shadersActive && clear > 0.01F) {
                drawMoon(pose.last().pose(), MASSER, MASSER_SIZE, clear,
                        AtmosphereMath.moonCycle(level.getDayTime() + pt, false));
            }
            if (!shadersActive && AtmosphereConfig.MASSER_HALO.get() && night > 0.003F) {
                drawHalo(pose.last().pose(), night * clear * NightSkyState.masserLight * 0.025F);
            }
            pose.popPose();

            if (AtmosphereConfig.SHOOTING_STARS.get()) drawShootingStar(pose.last().pose(), starAlpha, seconds);

            if (AtmosphereConfig.SECUNDA.get() && !shadersActive) {
                pose.pushPose();
                pose.mulPose(Axis.YP.rotationDegrees(-90.0F));
                pose.mulPose(Axis.ZP.rotationDegrees(SECUNDA_TILT));
                pose.mulPose(Axis.XP.rotationDegrees(level.getTimeOfDay(pt) * 360.0F
                        + (float) AtmosphereMath.moonCycle(level.getDayTime() + pt, true) * 360.0F));
                drawMoon(pose.last().pose(), SECUNDA, SECUNDA_SIZE, clear,
                        AtmosphereMath.moonCycle(level.getDayTime() + pt, true));
                pose.popPose();
            }
            // Atmospheric emission is nearer than both moons. Draw it after their
            // opaque silhouettes so the dark lunar limb cannot erase a curtain.
            if (AtmosphereConfig.AURORA.get() && !shadersActive) {
                float latAlpha = AtmosphereMath.auroraAlphaFactor(camZ);
                float cold = AtmosphereConfig.AURORA_EVERYWHERE.get()
                        ? 1.0F
                        : Mth.lerp(pt, coldnessPrev, coldness) * (AtmosphereConfig.AURORA_BY_REGION.get() ? (latAlpha > 0.0F ? 1.0F : 0.0F) : 1.0F);
                float alpha = night * night * clear * cold * activity(level) * AtmosphereConfig.AURORA_BRIGHTNESS.get().floatValue();
                if (alpha > 0.003F) drawAurora(pose, proj, alpha, seconds, camZ);
            }
        } finally {
            GL11.glDisable(GL32.GL_DEPTH_CLAMP);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
        }
    }

    // ------------------------------------------------------------------ elements

    private static void drawStars(PoseStack pose, Matrix4f proj, float alpha, double seconds) {
        RenderSystem.setShaderTexture(0, STAR);
        for (int i = 0; i < SkyGeometry.starLayers.length; i++) {
            float twinkle = 0.86f + 0.14f * (float) Math.sin(seconds * (0.75 + i * 0.137) + i * 2.41);
            drawBuffer(SkyGeometry.starLayers[i], pose.last().pose(), proj,
                    GameRenderer.getPositionTexColorShader(), alpha * twinkle);
        }
    }

    private static void drawConstellations(PoseStack pose, Matrix4f proj, float alpha, double dayTime) {
        RenderSystem.setShaderTexture(0, STAR);
        drawBuffer(SkyGeometry.constellations, pose.last().pose(), proj, GameRenderer.getPositionTexColorShader(), alpha * 0.55F);
        // the Serpent wanders against the fixed stars
        double days = dayTime / 24000.0;
        pose.pushPose();
        pose.mulPose(Axis.XP.rotationDegrees((float) (days / SERPENT_PERIOD_DAYS * 360.0 % 360.0)));
        pose.mulPose(Axis.ZP.rotationDegrees((float) Math.sin(days * 0.7) * 18.0F));
        drawBuffer(SkyGeometry.serpent, pose.last().pose(), proj, GameRenderer.getPositionTexColorShader(), alpha * 0.6F);
        pose.popPose();
    }

    /**
     * A shooting star every 15-60 seconds of clear night: a short tapered streak sliding along a great circle in the
     * camera's sky (not the celestial frame), fading in and out.
     */
    private static void drawShootingStar(Matrix4f m, float starAlpha, double seconds) {
        if (starAlpha < 0.25F) {
            nextMeteorAt = -1.0;
            return;
        }
        if (nextMeteorAt < 0.0 || nextMeteorAt > seconds + 120.0) nextMeteorAt = seconds + 10.0 + METEOR_RNG.nextDouble() * 40.0;
        if (seconds >= nextMeteorAt) {
            nextMeteorAt = seconds + 15.0 + METEOR_RNG.nextDouble() * 45.0;
            meteorStart = seconds;
            meteorDuration = 0.45 + METEOR_RNG.nextDouble() * 0.6;
            meteorSpeed = Math.toRadians(22.0 + METEOR_RNG.nextDouble() * 26.0);
            meteorTail = Math.toRadians(6.0 + METEOR_RNG.nextDouble() * 8.0);
            double az = METEOR_RNG.nextDouble() * Math.PI * 2.0;
            double el = Math.toRadians(28.0 + METEOR_RNG.nextDouble() * 45.0);
            mSx = Math.sin(az) * Math.cos(el);
            mSy = Math.sin(el);
            mSz = Math.cos(az) * Math.cos(el);
            // a downward-slanting tangent: mostly sideways, a little toward the horizon
            double side = METEOR_RNG.nextBoolean() ? 1.0 : -1.0;
            double hx = Math.cos(az) * side, hz = -Math.sin(az) * side;            // horizontal tangent
            double dx = -Math.sin(az) * Math.sin(el), dy = Math.cos(el), dz = -Math.cos(az) * Math.sin(el); // "up" tangent
            double drop = 0.35 + METEOR_RNG.nextDouble() * 0.4;
            mTx = hx - dx * drop;
            mTy = -dy * drop;
            mTz = hz - dz * drop;
            double l = Math.sqrt(mTx * mTx + mTy * mTy + mTz * mTz);
            mTx /= l;
            mTy /= l;
            mTz /= l;
        }
        double t = seconds - meteorStart;
        if (t < 0.0 || t > meteorDuration) return;
        float fade = (float) Math.sin(Math.PI * t / meteorDuration) * starAlpha;
        double head = meteorSpeed * t;
        double tail = Math.max(0.0, head - meteorTail * Math.min(1.0, t / (meteorDuration * 0.4)));
        float r = SkyGeometry.R * 0.95F;
        double hc = Math.cos(head), hs = Math.sin(head), tc = Math.cos(tail), ts = Math.sin(tail);
        float hx = (float) ((mSx * hc + mTx * hs) * r), hy = (float) ((mSy * hc + mTy * hs) * r), hz = (float) ((mSz * hc + mTz * hs) * r);
        float tx = (float) ((mSx * tc + mTx * ts) * r), ty = (float) ((mSy * tc + mTy * ts) * r), tz = (float) ((mSz * tc + mTz * ts) * r);
        // width vector: perpendicular to both the streak and the view direction to the head
        float ax = tx - hx, ay = ty - hy, az = tz - hz;
        float wx = ay * hz - az * hy, wy = az * hx - ax * hz, wz = ax * hy - ay * hx;
        float wl = Mth.sqrt(wx * wx + wy * wy + wz * wz);
        if (wl < 1.0E-4F) return;
        float w = 0.16F / wl;
        wx *= w;
        wy *= w;
        wz *= w;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bb.vertex(m, hx + wx, hy + wy, hz + wz).color(1.0F, 0.97F, 0.88F, fade).endVertex();
        bb.vertex(m, hx - wx, hy - wy, hz - wz).color(1.0F, 0.97F, 0.88F, fade).endVertex();
        bb.vertex(m, tx, ty, tz).color(0.55F, 0.70F, 1.0F, 0.0F).endVertex();
        bb.vertex(m, tx, ty, tz).color(0.55F, 0.70F, 1.0F, 0.0F).endVertex();
        BufferUploader.drawWithShader(bb.end());
    }

    private static void drawGalaxy(PoseStack pose, Matrix4f proj, float alpha) {
        RenderSystem.setShaderTexture(0, GALAXY);
        drawBuffer(SkyGeometry.galaxy, pose.last().pose(), proj, GameRenderer.getPositionTexColorShader(), alpha);
    }

    private static void drawAurora(PoseStack pose, Matrix4f proj, float alpha, double seconds, double camZ) {
        double phase = seconds / AURORA_KEY_SECONDS;
        int key = Math.floorMod((long) Math.floor(phase), SkyGeometry.AURORA_KEYS);
        float w = (float) (phase - Math.floor(phase));
        w = w * w * (3.0F - 2.0F * w);
        int next = (key + 1) % SkyGeometry.AURORA_KEYS;
        float pitchOffset = AtmosphereMath.auroraPitchOffsetDeg(camZ);
        float heightScale = AtmosphereMath.auroraHeightScale(camZ);
        for (int b = 0; b < SkyGeometry.AURORA_BANDS; b++) {
            float band = alpha * (0.55F + 0.45F * (float) Math.sin(seconds * 0.045 + b * 1.7));
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees((float) Math.sin(seconds * 0.011 + b * 2.3) * 4.0F));
            pose.mulPose(Axis.XP.rotationDegrees(pitchOffset));
            pose.scale(1.0F, heightScale, 1.0F);
            Matrix4f m = pose.last().pose();
            drawBuffer(SkyGeometry.AURORA[b][key], m, proj, GameRenderer.getPositionColorShader(), band * (1.0F - w));
            drawBuffer(SkyGeometry.AURORA[b][next], m, proj, GameRenderer.getPositionColorShader(), band * w);
            pose.popPose();
        }
    }

    private static void drawBuffer(VertexBuffer vb, Matrix4f modelView, Matrix4f proj, ShaderInstance shader, float alpha) {
        if (vb == null || shader == null || alpha <= 0.002F) return;
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, Math.min(1.0F, alpha));
        vb.bind();
        vb.drawWithShader(modelView, proj, shader);
        VertexBuffer.unbind();
    }

    /** Two adjacent baked phases blend smoothly; alpha blending lets the dark limb cover stars. */
    private static void drawMoon(Matrix4f m, ResourceLocation texture, float size, float alpha, double cycle) {
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        double phase = cycle * 16.0;
        int first = (int) phase;
        drawMoonPhase(m, size, alpha, first);
        float fraction = (float) (phase - first);
        if (fraction > 0.001f) drawMoonPhase(m, size, alpha * fraction, (first + 1) & 15);
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
    }

    private static void drawMoonPhase(Matrix4f m, float s, float alpha, int phase) {
        float u0 = (phase % 4) / 4f, v0 = (phase / 4) / 4f, u1 = u0 + 0.25f, v1 = v0 + 0.25f;
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bb.vertex(m, -s, -100f, s).uv(u1, v1).color(1f, 1f, 1f, alpha).endVertex();
        bb.vertex(m, s, -100f, s).uv(u0, v1).color(1f, 1f, 1f, alpha).endVertex();
        bb.vertex(m, s, -100f, -s).uv(u0, v0).color(1f, 1f, 1f, alpha).endVertex();
        bb.vertex(m, -s, -100f, -s).uv(u1, v0).color(1f, 1f, 1f, alpha).endVertex();
        BufferUploader.drawWithShader(bb.end());
    }

    /** Soft red glow around the vanilla moon (= Masser); same placement as vanilla's moon quad. */
    private static void drawHalo(Matrix4f m, float alpha) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, MASSER_HALO);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        float s = HALO_SIZE;
        float a = Math.min(1.0F, alpha);
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bb.vertex(m, -s, -100.0F, s).uv(1.0F, 1.0F).color(1.0F, 1.0F, 1.0F, a).endVertex();
        bb.vertex(m, s, -100.0F, s).uv(0.0F, 1.0F).color(1.0F, 1.0F, 1.0F, a).endVertex();
        bb.vertex(m, s, -100.0F, -s).uv(0.0F, 0.0F).color(1.0F, 1.0F, 1.0F, a).endVertex();
        bb.vertex(m, -s, -100.0F, -s).uv(1.0F, 0.0F).color(1.0F, 1.0F, 1.0F, a).endVertex();
        BufferUploader.drawWithShader(bb.end());
    }

    /** A rare, gradually varying high-altitude red component; unrelated to volcanic heat. */
    static float auroraRedMix(ClientLevel level) {
        double days = level.getDayTime() / 24000.0;
        double storm = 0.5 + 0.5 * Math.sin(days * 0.17 + Math.sin(days * 0.043));
        return (float) Math.pow(Math.max(0.0, (storm - 0.95) / 0.05), 2.0);
    }

    /** Some nights the aurora is faint, some nights it blazes: a stable pseudo-random per in-game day, plus slow surges. */
    static float activity(ClientLevel level) {
        long day = Math.floorDiv(level.getDayTime() + 12000L, 24000L);
        long h = day * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 29);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 32);
        float r = (h & 0xFFFF) / 65535.0F;
        return 0.5F + 0.5F * r;
    }
}
