package com.skycraft.realms.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.skycraft.Skycraft;
import com.skycraft.realms.Realm;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;

import java.util.Random;

/**
 * The realms' skies, drawn instead of vanilla's (never allocating per frame beyond the shared tesselator):
 * <ul>
 *     <li>a gradient dome from the fog colour at the horizon to each realm's zenith;</li>
 *     <li>the Deadlands: a blood-red sky with a slowly turning burning vortex (Dagon's eye) and drifting smoke;</li>
 *     <li>Sovngarde: a pale golden sun, faint stars and a golden-green aurora over the Hall;</li>
 *     <li>Apocrypha: the green-black void with Hermaeus Mora's watching eye overhead;</li>
 *     <li>the Soul Cairn: a violet bruise of a sky and the Ideal Masters' great soul gem hanging low;</li>
 *     <li>Coldharbour: a near-black sky, a dark sun with a cold corona and churning clouds.</li>
 * </ul>
 * The Shivering Isles keep vanilla's sky (sun, moons, stars, clouds) with their biomes' colours.
 */
public final class RealmSky {
    private static final ResourceLocation DEADLANDS_EYE = tex("deadlands_eye");
    private static final ResourceLocation DEADLANDS_SMOKE = tex("deadlands_smoke");
    private static final ResourceLocation SOVNGARDE_SUN = tex("sovngarde_sun");
    private static final ResourceLocation APOCRYPHA_EYE = tex("apocrypha_eye");
    private static final ResourceLocation SOUL_ORB = tex("soul_cairn_orb");
    private static final ResourceLocation COLDHARBOUR_SUN = tex("coldharbour_sun");
    private static final ResourceLocation COLDHARBOUR_CLOUDS = tex("coldharbour_clouds");
    private static final ResourceLocation GLOW = tex("glow");

    private static final int STARS = 900;
    private static final float[] STAR = new float[STARS * 4];

    static {
        Random r = new Random(4114L);
        for (int i = 0; i < STARS; i++) {
            double u = r.nextDouble() * 2 - 1, a = r.nextDouble() * Math.PI * 2;
            double s = Math.sqrt(1 - u * u);
            STAR[i * 4] = (float) (s * Math.cos(a));
            STAR[i * 4 + 1] = (float) Math.abs(u);
            STAR[i * 4 + 2] = (float) (s * Math.sin(a));
            STAR[i * 4 + 3] = 0.15f + r.nextFloat() * r.nextFloat() * 0.45f;
        }
    }

    private RealmSky() {}

    private static ResourceLocation tex(String name) {
        return new ResourceLocation(Skycraft.MODID, "textures/environment/realms/" + name + ".png");
    }

    static void render(Realm realm, ClientLevel level, int ticks, float pt, PoseStack pose, Camera camera, Matrix4f proj, Runnable setupFog) {
        Minecraft mc = Minecraft.getInstance();
        FogType fluid = camera.getFluidInCamera();
        if (fluid == FogType.LAVA || fluid == FogType.POWDER_SNOW) return;
        if (mc.player != null && (mc.player.hasEffect(MobEffects.BLINDNESS) || mc.player.hasEffect(MobEffects.DARKNESS))) return;
        double t = (ticks + pt) / 20.0;
        FogRenderer.levelFogColor();
        float[] fog = RenderSystem.getShaderFogColor();
        float fr = fog[0], fg = fog[1], fb = fog[2];
        FogRenderer.setupNoFog();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        try {
            switch (realm) {
                case DEADLANDS -> {
                    dome(pose, fr, fg, fb, 0.42f, 0.04f, 0.02f, 0.10f, 0.02f, 0.01f);
                    additive();
                    float pulse = 0.85f + 0.15f * (float) Math.sin(t * 0.7);
                    sprite(pose, GLOW, 200, 28, 95, 0, 1.0f, 0.35f, 0.1f, 0.55f * pulse);
                    sprite(pose, DEADLANDS_EYE, 200, 28, 60, (float) (t * 2.0), 1.0f, 0.8f, 0.7f, 0.95f);
                    normal();
                    layer(pose, DEADLANDS_SMOKE, 22f, 260f, (float) (t * 0.004), (float) (t * 0.0015), 0.35f, 0.08f, 0.05f, 0.55f);
                    layer(pose, DEADLANDS_SMOKE, 30f, 300f, (float) (-t * 0.0025), (float) (t * 0.003), 0.55f, 0.12f, 0.05f, 0.35f);
                }
                case SOVNGARDE -> {
                    dome(pose, fr, fg, fb, 0.36f, 0.52f, 0.82f, 0.55f, 0.6f, 0.7f);
                    additive();
                    stars(pose, 0.35f, t, 1.0f, 0.95f, 0.85f);
                    aurora(pose, t, 0.55f);
                    sprite(pose, GLOW, 180, 38, 70, 0, 1.0f, 0.9f, 0.6f, 0.6f);
                    sprite(pose, SOVNGARDE_SUN, 180, 38, 26, 0, 1.0f, 0.95f, 0.8f, 1.0f);
                    normal();
                }
                case APOCRYPHA -> {
                    dome(pose, fr, fg, fb, 0.01f, 0.05f, 0.03f, 0.01f, 0.02f, 0.015f);
                    additive();
                    stars(pose, 0.25f, t, 0.5f, 1.0f, 0.6f);
                    sprite(pose, GLOW, 0, 80, 110, 0, 0.3f, 1.0f, 0.4f, 0.35f);
                    sprite(pose, APOCRYPHA_EYE, 0, 80, 75, (float) (t * 1.2), 0.9f, 1.0f, 0.8f, 0.9f);
                    normal();
                }
                case SOUL_CAIRN -> {
                    dome(pose, fr, fg, fb, 0.16f, 0.08f, 0.26f, 0.08f, 0.04f, 0.12f);
                    additive();
                    stars(pose, 0.3f, t, 0.85f, 0.75f, 1.0f);
                    float pulse = 0.8f + 0.2f * (float) Math.sin(t * 0.4);
                    sprite(pose, GLOW, 135, 16, 120, 0, 0.6f, 0.3f, 1.0f, 0.5f * pulse);
                    sprite(pose, SOUL_ORB, 135, 16, 62, (float) (t * 0.5), 1.0f, 0.9f, 1.0f, 1.0f);
                    normal();
                }
                case COLDHARBOUR -> {
                    dome(pose, fr, fg, fb, 0.03f, 0.04f, 0.07f, 0.02f, 0.02f, 0.03f);
                    additive();
                    sprite(pose, GLOW, 225, 34, 80, 0, 0.4f, 0.55f, 1.0f, 0.45f);
                    sprite(pose, COLDHARBOUR_SUN, 225, 34, 40, (float) (-t * 1.5), 0.8f, 0.9f, 1.0f, 1.0f);
                    normal();
                    layer(pose, COLDHARBOUR_CLOUDS, 18f, 260f, (float) (t * 0.003), (float) (-t * 0.002), 0.12f, 0.13f, 0.18f, 0.7f);
                }
                default -> {
                }
            }
        } finally {
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            setupFog.run();
        }
    }

    private static void additive() {
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
    }

    private static void normal() {
        RenderSystem.defaultBlendFunc();
    }

    /**
     * The gradient dome: below the horizon the fog colour darkens toward {@code below}; above it rises to the
     * {@code zenith} colour.
     */
    private static void dome(PoseStack pose, float hr, float hg, float hb, float zr, float zg, float zb, float br, float bg, float bb) {
        Matrix4f m = pose.last().pose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int seg = 32;
        float[] el = {-90, -40, -12, -3, 0, 4, 10, 20, 34, 52, 72, 90};
        float r = 100f;
        for (int i = 0; i < el.length - 1; i++) {
            float e0 = el[i] * Mth.DEG_TO_RAD, e1 = el[i + 1] * Mth.DEG_TO_RAD;
            float[] c0 = domeColor(el[i], hr, hg, hb, zr, zg, zb, br, bg, bb);
            float[] c1 = domeColor(el[i + 1], hr, hg, hb, zr, zg, zb, br, bg, bb);
            for (int j = 0; j < seg; j++) {
                float a0 = j * Mth.TWO_PI / seg, a1 = (j + 1) * Mth.TWO_PI / seg;
                vtx(b, m, e0, a0, r, c0);
                vtx(b, m, e0, a1, r, c0);
                vtx(b, m, e1, a1, r, c1);
                vtx(b, m, e1, a0, r, c1);
            }
        }
        BufferUploader.drawWithShader(b.end());
    }

    private static float[] domeColor(float elevDeg, float hr, float hg, float hb, float zr, float zg, float zb, float br, float bg, float bb) {
        if (elevDeg <= 0) {
            float t = Mth.clamp(-elevDeg / 25f, 0f, 1f);
            return new float[]{Mth.lerp(t, hr, br), Mth.lerp(t, hg, bg), Mth.lerp(t, hb, bb)};
        }
        float t = (float) Math.pow(Mth.clamp(elevDeg / 90f, 0f, 1f), 0.55);
        return new float[]{Mth.lerp(t, hr, zr), Mth.lerp(t, hg, zg), Mth.lerp(t, hb, zb)};
    }

    private static void vtx(BufferBuilder b, Matrix4f m, float e, float a, float r, float[] c) {
        float ce = Mth.cos(e);
        b.vertex(m, ce * Mth.sin(a) * r, Mth.sin(e) * r, ce * Mth.cos(a) * r).color(c[0], c[1], c[2], 1f).endVertex();
    }

    /**
     * A textured square facing the viewer at azimuth {@code yawDeg} (0 = south, 90 = west, 180 = north),
     * elevation {@code pitchDeg}, half-size {@code size} at distance 100, spun by {@code spinDeg}.
     */
    private static void sprite(PoseStack pose, ResourceLocation texture, float yawDeg, float pitchDeg, float size, float spinDeg,
                               float r, float g, float bl, float a) {
        pose.pushPose();
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-yawDeg));
        pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-pitchDeg));
        pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(spinDeg));
        Matrix4f m = pose.last().pose();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        b.vertex(m, -size, -size, 100f).uv(0f, 0f).color(r, g, bl, a).endVertex();
        b.vertex(m, size, -size, 100f).uv(1f, 0f).color(r, g, bl, a).endVertex();
        b.vertex(m, size, size, 100f).uv(1f, 1f).color(r, g, bl, a).endVertex();
        b.vertex(m, -size, size, 100f).uv(0f, 1f).color(r, g, bl, a).endVertex();
        BufferUploader.drawWithShader(b.end());
        pose.popPose();
    }

    /** A horizontal cloud/smoke layer {@code height} above the eye, scrolling, fading out toward its rim. */
    private static void layer(PoseStack pose, ResourceLocation texture, float height, float extent, float du, float dv,
                              float r, float g, float bl, float a) {
        Matrix4f m = pose.last().pose();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        int n = 6;
        float step = extent * 2 / n;
        float uvScale = 3f / n;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                float x0 = -extent + i * step, z0 = -extent + j * step, x1 = x0 + step, z1 = z0 + step;
                float u0 = i * uvScale + du, v0 = j * uvScale + dv, u1 = u0 + uvScale, v1 = v0 + uvScale;
                b.vertex(m, x0, height, z0).uv(u0, v0).color(r, g, bl, a * fade(x0, z0, extent)).endVertex();
                b.vertex(m, x0, height, z1).uv(u0, v1).color(r, g, bl, a * fade(x0, z1, extent)).endVertex();
                b.vertex(m, x1, height, z1).uv(u1, v1).color(r, g, bl, a * fade(x1, z1, extent)).endVertex();
                b.vertex(m, x1, height, z0).uv(u1, v0).color(r, g, bl, a * fade(x1, z0, extent)).endVertex();
            }
        BufferUploader.drawWithShader(b.end());
    }

    private static float fade(float x, float z, float extent) {
        float d = Mth.sqrt(x * x + z * z) / extent;
        return Mth.clamp(1.2f - d * 1.1f, 0f, 1f);
    }

    private static void stars(PoseStack pose, float alpha, double t, float r, float g, float bl) {
        Matrix4f m = pose.last().pose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < STARS; i++) {
            float x = STAR[i * 4] * 100, y = STAR[i * 4 + 1] * 100, z = STAR[i * 4 + 2] * 100;
            if (y < 4) continue;
            float s = STAR[i * 4 + 3];
            float tw = alpha * (0.7f + 0.3f * (float) Math.sin(t * (1.3 + (i % 7) * 0.31) + i));
            // a little square facing the centre: two axes perpendicular to the star's direction
            float ax = -z, az = x;
            float len = Mth.sqrt(ax * ax + az * az);
            if (len < 1e-3f) continue;
            ax = ax / len * s;
            az = az / len * s;
            float ux = -x * y, uy = x * x + z * z, uz = -z * y;
            float ul = Mth.sqrt(ux * ux + uy * uy + uz * uz);
            ux = ux / ul * s;
            uy = uy / ul * s;
            uz = uz / ul * s;
            b.vertex(m, x - ax - ux, y - uy, z - az - uz).color(r, g, bl, tw).endVertex();
            b.vertex(m, x + ax - ux, y - uy, z + az - uz).color(r, g, bl, tw).endVertex();
            b.vertex(m, x + ax + ux, y + uy, z + az + uz).color(r, g, bl, tw).endVertex();
            b.vertex(m, x - ax + ux, y + uy, z - az + uz).color(r, g, bl, tw).endVertex();
        }
        BufferUploader.drawWithShader(b.end());
    }

    /** Sovngarde's aurora: three slow ribbons over the north (the Hall's side), gold below, green-blue above. */
    private static void aurora(PoseStack pose, double t, float alpha) {
        Matrix4f m = pose.last().pose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int steps = 56;
        for (int k = 0; k < 3; k++) {
            float base = 22 + k * 9;
            float h = 10 + k * 3;
            float off = k * 1.7f;
            for (int i = 0; i < steps; i++) {
                float a0 = (float) Math.toRadians(100 + i * 160.0 / steps), a1 = (float) Math.toRadians(100 + (i + 1) * 160.0 / steps);
                float e0 = (float) Math.toRadians(base + 4 * Math.sin(i * 0.35 + t * 0.25 + off));
                float e1 = (float) Math.toRadians(base + 4 * Math.sin((i + 1) * 0.35 + t * 0.25 + off));
                float t0 = (float) Math.toRadians(h), edge = Mth.clamp(Math.min(i, steps - 1 - i) / 8f, 0f, 1f);
                float aa = alpha * edge * (0.55f + 0.45f * (float) Math.sin(i * 0.21 + t * 0.6 + off));
                float[] lo = {1.0f, 0.82f, 0.35f}, hi = {0.25f, 0.95f, 0.65f};
                avtx(b, m, e0, a0, lo, aa);
                avtx(b, m, e1, a1, lo, aa);
                avtx(b, m, e1 + t0, a1, hi, 0f);
                avtx(b, m, e0 + t0, a0, hi, 0f);
            }
        }
        BufferUploader.drawWithShader(b.end());
    }

    private static void avtx(BufferBuilder b, Matrix4f m, float e, float a, float[] c, float alpha) {
        float ce = Mth.cos(e), r = 98f;
        b.vertex(m, ce * Mth.sin(a) * r, Mth.sin(e) * r, ce * Mth.cos(a) * r).color(c[0], c[1], c[2], alpha).endVertex();
    }
}
