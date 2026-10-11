package com.skycraft.atmosphere.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.skycraft.atmosphere.AtmosphereConfig;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Lightning in storms out on the horizon. {@link CloudBuilder} reports every storm tower (and Red Mountain in an ash
 * storm) as a candidate; each client tick a few of them may flash: the cloud lights up from inside with a flicker,
 * often with a forked bolt down to the ground, and the thunder follows after the time sound takes to cover the
 * distance (343 blocks a second), softer and deeper the further away it is, and from the storm's direction. Storms
 * within a short distance are left to vanilla's own lightning.
 */
final class DistantStorms {
    /** Floats per storm candidate: world x, base y, z, top y, radius, strength. */
    static final int STRIDE = 6;
    private static final int MAX_FLASHES = 4;
    private static final int MAX_THUNDER = 12;
    private static final int BOLT_POINTS = 13;
    private static final double SPEED_OF_SOUND = 343.0;

    private static float[] cells = new float[0];
    private static int cellCount;
    private static final RandomSource RNG = RandomSource.create();

    // ---- active flashes
    private static final float[][] FLASH = new float[MAX_FLASHES][8];   // x, y(base), z, top, radius, ground, age, life
    private static final long[] FLASH_SEED = new long[MAX_FLASHES];
    private static final boolean[] FLASH_BOLT = new boolean[MAX_FLASHES];
    private static int cooldown;
    /** Test hook: the next tick flashes the first eligible storm. */
    static boolean force;

    // ---- queued thunder: tick due, volume, pitch, direction x, z
    private static final float[][] THUNDER = new float[MAX_THUNDER][5];
    private static final long[] THUNDER_AT = new long[MAX_THUNDER];
    private static int thunderCount;

    private static final float[] BX = new float[BOLT_POINTS], BY = new float[BOLT_POINTS], BZ = new float[BOLT_POINTS];

    private DistantStorms() {}

    static void update(float[] storms, int count) {
        cells = storms;
        cellCount = count;
    }

    static void clear() {
        cellCount = 0;
        thunderCount = 0;
        for (float[] f : FLASH) f[7] = 0f;
    }

    /** Ages flashes, starts new ones and plays thunder that has arrived. */
    static void tick(Minecraft mc, Vec3 cam, long gameTime) {
        for (float[] f : FLASH) if (f[7] > 0f && ++f[6] > f[7]) f[7] = 0f;
        for (int i = 0; i < thunderCount; ) {
            if (THUNDER_AT[i] <= gameTime) {
                playThunder(mc, cam, THUNDER[i]);
                thunderCount--;
                THUNDER_AT[i] = THUNDER_AT[thunderCount];
                System.arraycopy(THUNDER[thunderCount], 0, THUNDER[i], 0, 5);
            } else {
                i++;
            }
        }
        if (cooldown > 0) cooldown--;
        if (cellCount == 0 || cooldown > 0 && !force || !AtmosphereConfig.DISTANT_WEATHER.get()) return;
        for (int c = 0; c < cellCount; c++) {
            int o = c * STRIDE;
            float strength = cells[o + 5];
            // a raging storm flashes every six seconds or so
            if (!force && RNG.nextFloat() > strength / 120f) continue;
            double dx = cells[o] - cam.x, dz = cells[o + 2] - cam.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 140) continue;
            int slot = -1;
            for (int s = 0; s < MAX_FLASHES; s++) if (FLASH[s][7] <= 0f) slot = s;
            if (slot < 0) return;
            float r = cells[o + 4];
            float[] f = FLASH[slot];
            f[0] = cells[o] + (RNG.nextFloat() - 0.5f) * r;
            f[1] = cells[o + 1];
            f[2] = cells[o + 2] + (RNG.nextFloat() - 0.5f) * r;
            f[3] = cells[o + 3];
            f[4] = r;
            f[5] = Math.max(TamrielMap.SEA_LEVEL, TamrielMap.elevation(Mth.floor(f[0]), Mth.floor(f[2])));
            f[6] = 0f;
            f[7] = 8 + RNG.nextInt(8);
            FLASH_SEED[slot] = RNG.nextLong();
            FLASH_BOLT[slot] = force || RNG.nextFloat() < 0.6f;
            force = false;
            cooldown = 6 + RNG.nextInt(14);
            double full = Math.sqrt(dist * dist + (f[1] - cam.y) * (f[1] - cam.y));
            queueThunder(gameTime + (long) (full / SPEED_OF_SOUND * 20.0), full, (float) (dx / dist), (float) (dz / dist),
                    FLASH_BOLT[slot]);
            return;
        }
    }

    private static void queueThunder(long at, double dist, float dirX, float dirZ, boolean bolt) {
        if (!AtmosphereConfig.DISTANT_THUNDER.get() || thunderCount >= MAX_THUNDER) return;
        float near = (float) (250.0 / (dist + 250.0));
        float[] t = THUNDER[thunderCount];
        t[0] = Mth.clamp(near * (bolt ? 2.2f : 1.4f), 0.06f, 1.0f);
        t[1] = 0.5f + 0.3f * near + RNG.nextFloat() * 0.12f;
        t[2] = dirX;
        t[3] = dirZ;
        THUNDER_AT[thunderCount++] = at;
    }

    private static void playThunder(Minecraft mc, Vec3 cam, float[] t) {
        // from the storm's direction, without distance falloff: the volume already carries the distance
        double x = cam.x + t[2] * 12.0, z = cam.z + t[3] * 12.0;
        mc.getSoundManager().play(new SimpleSoundInstance(SoundEvents.LIGHTNING_BOLT_THUNDER.getLocation(), SoundSource.WEATHER,
                t[0], t[1], RandomSource.create(RNG.nextLong()), false, 0, SoundInstance.Attenuation.NONE, x, cam.y + 4.0, z, false));
    }

    /** Draws the flashes (additive). The caller has set up blending and depth; camera-relative coordinates. */
    static void render(Matrix4f modelView, Matrix4f proj, Vec3 cam, float pt, float daylight) {
        boolean any = false;
        for (float[] f : FLASH) any |= f[7] > 0f;
        if (!any) return;
        // immediate draws use the global matrices: make them this frame's camera rotation and projection
        com.mojang.blaze3d.vertex.PoseStack mvs = RenderSystem.getModelViewStack();
        mvs.pushPose();
        mvs.setIdentity();
        mvs.mulPoseMatrix(modelView);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(proj, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
        try {
            draw(cam, pt, daylight);
        } finally {
            RenderSystem.restoreProjectionMatrix();
            mvs.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void draw(Vec3 cam, float pt, float daylight) {
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        // the glow inside the cloud
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, CloudRenderer.TEXTURE);
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        float dim = 1f - 0.65f * daylight;
        for (int s = 0; s < MAX_FLASHES; s++) {
            float[] f = FLASH[s];
            if (f[7] <= 0f) continue;
            float k = strobe(FLASH_SEED[s], f[6] + pt, f[7]) * dim;
            if (k < 0.01f) continue;
            float x = (float) (f[0] - cam.x), y = (float) (f[1] + (f[3] - f[1]) * 0.3f - cam.y), z = (float) (f[2] - cam.z);
            glow(bb, x, y, z, f[4] * 2.6f, 0.62f, 0.68f, 0.9f, k * 0.55f);
            glow(bb, x, (float) (f[1] - cam.y), z, f[4] * 1.8f, 0.7f, 0.75f, 0.95f, k * 0.45f);
        }
        BufferUploader.drawWithShader(bb.end());
        // the bolts
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        boolean bolts = false;
        for (int s = 0; s < MAX_FLASHES; s++) {
            float[] f = FLASH[s];
            if (f[7] <= 0f || !FLASH_BOLT[s] || f[6] > 5f) continue;
            float k = strobe(FLASH_SEED[s], f[6] + pt, f[7]);
            if (k < 0.05f) continue;
            bolt(bb, cam, f, FLASH_SEED[s], k);
            bolts = true;
        }
        if (bolts) BufferUploader.drawWithShader(bb.end());
        else bb.end().release();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
    }

    /** A lightning flicker: a bright first stroke, then one or two return strokes. */
    private static float strobe(long seed, float age, float life) {
        if (age >= life) return 0f;
        int step = (int) age;
        long h = seed ^ (step * 0x9E3779B97F4A7C15L);
        h ^= h >>> 31;
        float r = (h & 0xFF) / 255f;
        float base = step == 0 ? 1f : step < 3 ? 0.25f + 0.75f * r : r * r * 0.7f;
        return base * (1f - age / life * 0.6f);
    }

    private static void glow(BufferBuilder bb, float x, float y, float z, float size, float r, float g, float b, float a) {
        float d = Mth.sqrt(x * x + y * y + z * z);
        if (d < 1f) return;
        float tx = -x / d, ty = -y / d, tz = -z / d;
        float rx = tz, rz = -tx;
        float rl = Mth.sqrt(rx * rx + rz * rz);
        if (rl < 1e-3f) return;
        rx /= rl;
        rz /= rl;
        float ux = ty * rz, uy = tz * rx - tx * rz, uz = -ty * rx;
        float u0 = 0.25f, v0 = 0.75f, u1 = 0.5f, v1 = 1.0f;  // atlas cell 13: the glow
        bb.vertex(x - (rx + ux) * size, y - uy * size, z - (rz + uz) * size).uv(u0, v1).color(r, g, b, a).endVertex();
        bb.vertex(x + (rx - ux) * size, y - uy * size, z + (rz - uz) * size).uv(u1, v1).color(r, g, b, a).endVertex();
        bb.vertex(x + (rx + ux) * size, y + uy * size, z + (rz + uz) * size).uv(u1, v0).color(r, g, b, a).endVertex();
        bb.vertex(x - (rx - ux) * size, y + uy * size, z - (rz - uz) * size).uv(u0, v0).color(r, g, b, a).endVertex();
    }

    /** A forked bolt from the cloud base to the ground, as a camera-facing ribbon with a soft halo. */
    private static void bolt(BufferBuilder bb, Vec3 cam, float[] f, long seed, float k) {
        RandomSource r = RandomSource.create(seed);
        float top = f[1], bottom = f[5];
        float h = top - bottom;
        if (h < 10f) return;
        float x = f[0], z = f[2];
        for (int i = 0; i < BOLT_POINTS; i++) {
            float t = (float) i / (BOLT_POINTS - 1);
            BX[i] = x;
            BY[i] = top - h * t;
            BZ[i] = z;
            x += (r.nextFloat() - 0.5f) * h * 0.09f;
            z += (r.nextFloat() - 0.5f) * h * 0.09f;
        }
        double dist = Math.sqrt((f[0] - cam.x) * (f[0] - cam.x) + (f[2] - cam.z) * (f[2] - cam.z));
        float w = (float) Math.max(0.6, dist * 0.003);
        ribbon(bb, cam, BOLT_POINTS, w * 5f, 0.55f, 0.6f, 1.0f, 0.18f * k);
        ribbon(bb, cam, BOLT_POINTS, w, 0.95f, 0.96f, 1.0f, 0.95f * k);
        // one fork off the upper half
        int from = 2 + r.nextInt(4);
        float fx = BX[from], fz = BZ[from];
        int n = 5;
        for (int i = 0; i < n; i++) {
            BX[i] = fx;
            BY[i] = BY[from] - h * 0.06f * i;
            BZ[i] = fz;
            fx += (r.nextFloat() - 0.5f) * h * 0.12f;
            fz += (r.nextFloat() - 0.5f) * h * 0.12f;
        }
        ribbon(bb, cam, n, w * 0.6f, 0.9f, 0.92f, 1.0f, 0.6f * k);
    }

    private static void ribbon(BufferBuilder bb, Vec3 cam, int n, float w, float r, float g, float b, float a) {
        for (int i = 0; i < n - 1; i++) {
            float x0 = (float) (BX[i] - cam.x), y0 = (float) (BY[i] - cam.y), z0 = (float) (BZ[i] - cam.z);
            float x1 = (float) (BX[i + 1] - cam.x), y1 = (float) (BY[i + 1] - cam.y), z1 = (float) (BZ[i + 1] - cam.z);
            float ax = x1 - x0, ay = y1 - y0, az = z1 - z0;
            // side = segment x view direction
            float sx = ay * z0 - az * y0, sy = az * x0 - ax * z0, sz = ax * y0 - ay * x0;
            float sl = Mth.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < 1e-4f) continue;
            sx = sx / sl * w;
            sy = sy / sl * w;
            sz = sz / sl * w;
            bb.vertex(x0 - sx, y0 - sy, z0 - sz).color(r, g, b, a).endVertex();
            bb.vertex(x0 + sx, y0 + sy, z0 + sz).color(r, g, b, a).endVertex();
            bb.vertex(x1 + sx, y1 + sy, z1 + sz).color(r, g, b, a).endVertex();
            bb.vertex(x1 - sx, y1 - sy, z1 - sz).color(r, g, b, a).endVertex();
        }
    }
}
