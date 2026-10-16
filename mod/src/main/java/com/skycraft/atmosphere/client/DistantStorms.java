package com.skycraft.atmosphere.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.skycraft.atmosphere.AtmosphereConfig;
import com.skycraft.atmosphere.WeatherFront;
import com.skycraft.atmosphere.WeatherPhysics;
import com.skycraft.atmosphere.weather.WeatherKind;
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

import java.util.List;
import java.util.Map;

/**
 * Distant lightning across Tamriel's storm systems:
 * <ul>
 *   <li><b>Intra-Cloud (IC) Sheet Lightning</b>: multi-pulsing internal illumination propagating from the convective
 *       updraft core into the upper anvil canopy.</li>
 *   <li><b>Cloud-to-Cloud (C2C) &amp; Anvil Crawlers</b>: horizontal and arching branched lightning ribbons leaping
 *       between neighboring cumulonimbus towers or spider-crawling across the underside of a mesocyclone anvil,
 *       lighting up both cloud masses sequentially.</li>
 *   <li><b>Cloud-to-Ground (CG) Forked Bolts</b>: stepped leaders and bright return strokes from the dark storm base
 *       to the terrain with multiple jagged secondary branches.</li>
 * </ul>
 *
 * <p>Electrical activity varies organically over time and by storm structure (mesocyclone supercells and frontal squall
 * lines produce active bursts of C2C and CG lightning, while weaker convective cells produce occasional soft sheet
 * flashes). Thunder follows after the acoustic travel delay ({@code 343 m/s}). Also exposes {@link #flashGlowAt} so
 * {@link WeatherFieldTexture} can illuminate 3D raymarched volumetric shader clouds from within during flashes.</p>
 */
final class DistantStorms {
    /** Floats per storm candidate: world x, base y, z, top y, radius, strength. */
    static final int STRIDE = 6;
    private static final int MAX_FLASHES = 8;
    private static final int MAX_THUNDER = 16;
    private static final int BOLT_POINTS = 15;
    private static final double SPEED_OF_SOUND = 343.0;

    private static final int MODE_INTRA_CLOUD = 0;
    private static final int MODE_CLOUD_TO_CLOUD = 1;
    private static final int MODE_CLOUD_TO_GROUND = 2;

    private static float[] cells = new float[0];
    private static int cellCount;
    private static long lastFieldScanTick = -1000L;
    private static final RandomSource RNG = RandomSource.create();

    // ---- active flashes: x0, yBase, z0, yTop, radius, groundY, age, life, x1(target), y1(target), z1(target), Volcanic/Tint
    private static final float[][] FLASH = new float[MAX_FLASHES][12];
    private static final long[] FLASH_SEED = new long[MAX_FLASHES];
    private static final int[] FLASH_MODE = new int[MAX_FLASHES];
    private static int cooldown;
    /** Test hook: the next tick flashes the first eligible storm. */
    static boolean force;

    // ---- queued thunder: tick due, volume, pitch, direction x, z
    private static final float[][] THUNDER = new float[MAX_THUNDER][5];
    private static final long[] THUNDER_AT = new long[MAX_THUNDER];
    private static int thunderCount;

    private static final float[] BX = new float[BOLT_POINTS], BY = new float[BOLT_POINTS], BZ = new float[BOLT_POINTS];
    private static final float[] FX = new float[BOLT_POINTS], FY = new float[BOLT_POINTS], FZ = new float[BOLT_POINTS];

    private DistantStorms() {}

    static void update(float[] storms, int count) {
        cells = storms;
        cellCount = count;
    }

    static void clear() {
        cellCount = 0;
        thunderCount = 0;
        lastFieldScanTick = -1000L;
        for (float[] f : FLASH) f[7] = 0f;
    }

    /** True if at least one distant lightning flash is currently active. */
    static boolean hasActiveFlash() {
        for (float[] f : FLASH) {
            if (f[7] > 0f) return true;
        }
        return false;
    }

    /**
     * Returns {@code 0..1} internal cloud lightning illumination at world coordinates {@code (wx, wz)} so
     * {@link WeatherFieldTexture} can light up 3D volumetric shader clouds from the inside during flashes.
     */
    static float flashGlowAt(double wx, double wz, float pt) {
        float best = 0f;
        for (int s = 0; s < MAX_FLASHES; s++) {
            float[] f = FLASH[s];
            if (f[7] <= 0f) continue;
            float k = strobe(FLASH_SEED[s], f[6] + pt, f[7]);
            if (k <= 0.01f) continue;
            float r = Math.max(180f, f[4] * 2.4f);
            float dx0 = (float) (wx - f[0]);
            float dz0 = (float) (wz - f[2]);
            float d0 = (float) Math.sqrt(dx0 * dx0 + dz0 * dz0);
            if (d0 < r) {
                float u = 1f - d0 / r;
                best = Math.max(best, k * u * u);
            }
            if (FLASH_MODE[s] == MODE_CLOUD_TO_CLOUD) {
                // Also illuminate along the cloud-to-cloud arc and at the receiving tower (x1, z1)
                float dx1 = (float) (wx - f[8]);
                float dz1 = (float) (wz - f[10]);
                float d1 = (float) Math.sqrt(dx1 * dx1 + dz1 * dz1);
                if (d1 < r) {
                    float u = 1f - d1 / r;
                    best = Math.max(best, k * u * u * 0.9f);
                }
                // Midpoint of the C2C bridge
                float mx = 0.5f * (f[0] + f[8]);
                float mz = 0.5f * (f[2] + f[10]);
                float dm = (float) Math.sqrt((wx - mx) * (wx - mx) + (wz - mz) * (wz - mz));
                if (dm < r * 0.9f) {
                    float u = 1f - dm / (r * 0.9f);
                    best = Math.max(best, k * u * 0.75f);
                }
            }
        }
        return Math.min(1f, best);
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

        // When Oculus shaders are active, CloudBuilder doesn't run, so discover storm towers directly from WeatherPhysics
        if ((ShaderCompat.shadersActive() || cellCount == 0) && Math.abs(gameTime - lastFieldScanTick) >= 20L) {
            lastFieldScanTick = gameTime;
            discoverStormCells(cam, gameTime / 20.0);
        }

        if (cooldown > 0) cooldown--;
        if (cellCount == 0 || (cooldown > 0 && !force) || !AtmosphereConfig.DISTANT_WEATHER.get()) return;

        // Global electrical cadence wave: sometimes storms rumble with a flurry of cloud-to-cloud arcs, sometimes quiet
        double sec = gameTime / 20.0;
        float globalWave = 0.5f + 0.3f * Mth.sin((float) (sec * 0.045)) + 0.2f * Mth.sin((float) (sec * 0.13 + 1.4));

        for (int c = 0; c < cellCount; c++) {
            int o = c * STRIDE;
            float strength = cells[o + 5];
            float cellWave = 0.55f + 0.45f * Mth.sin((float) (sec * 0.09 + c * 1.7));
            float activity = strength * (0.35f + 0.95f * globalWave * cellWave);

            if (!force && RNG.nextFloat() > activity / 85f) continue;
            double dx = cells[o] - cam.x, dz = cells[o + 2] - cam.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 130) continue;

            int slot = -1;
            for (int s = 0; s < MAX_FLASHES; s++) {
                if (FLASH[s][7] <= 0f) {
                    slot = s;
                    break;
                }
            }
            if (slot < 0) return;

            float r = cells[o + 4];
            float[] f = FLASH[slot];
            f[0] = cells[o] + (RNG.nextFloat() - 0.5f) * r * 0.6f;
            f[1] = cells[o + 1];
            f[2] = cells[o + 2] + (RNG.nextFloat() - 0.5f) * r * 0.6f;
            f[3] = cells[o + 3];
            f[4] = r;
            f[5] = Math.max(TamrielMap.SEA_LEVEL, TamrielMap.elevation(Mth.floor(f[0]), Mth.floor(f[2])));
            f[6] = 0f;

            // Choose lightning mode:
            // - Cloud-to-Cloud (C2C) / Anvil Crawler (~48% of flashes, especially in high-activity mesocyclones/fronts)
            // - Cloud-to-Ground (CG) forked bolt (~32% of flashes, or when forced by test)
            // - Intra-Cloud (IC) sheet lightning (~20% of flashes)
            int mode;
            float roll = RNG.nextFloat();
            if (force) {
                mode = MODE_CLOUD_TO_GROUND;
            } else if (roll < 0.48f) {
                mode = MODE_CLOUD_TO_CLOUD;
            } else if (roll < 0.80f) {
                mode = MODE_CLOUD_TO_GROUND;
            } else {
                mode = MODE_INTRA_CLOUD;
            }
            FLASH_MODE[slot] = mode;
            f[7] = mode == MODE_CLOUD_TO_CLOUD ? (10 + RNG.nextInt(10)) : (8 + RNG.nextInt(8));
            FLASH_SEED[slot] = RNG.nextLong();

            // Pick target (x1, y1, z1) for Cloud-to-Cloud / Anvil Crawler lightning
            if (mode == MODE_CLOUD_TO_CLOUD) {
                int neighbor = findNeighborTower(c, f[0], f[2]);
                if (neighbor >= 0) {
                    int no = neighbor * STRIDE;
                    f[8] = cells[no] + (RNG.nextFloat() - 0.5f) * cells[no + 4] * 0.5f;
                    f[9] = cells[no + 1] + (cells[no + 3] - cells[no + 1]) * (0.45f + 0.35f * RNG.nextFloat());
                    f[10] = cells[no + 2] + (RNG.nextFloat() - 0.5f) * cells[no + 4] * 0.5f;
                } else {
                    // Anvil crawler leaping across the broad storm canopy (220..480 blocks)
                    float ang = RNG.nextFloat() * Mth.TWO_PI;
                    float span = Math.max(200f, r * (1.8f + 1.6f * RNG.nextFloat()));
                    f[8] = f[0] + Mth.cos(ang) * span;
                    f[9] = f[1] + (f[3] - f[1]) * (0.50f + 0.35f * RNG.nextFloat());
                    f[10] = f[2] + Mth.sin(ang) * span;
                }
            } else {
                f[8] = f[0];
                f[9] = f[3];
                f[10] = f[2];
            }

            // Volcanic ash/blight storms tint lightning warm crimson-violet
            var sample = TamrielMap.sampleScratch(Mth.floor(f[0]), Mth.floor(f[2]));
            f[11] = sample.heat > 0.35f ? 1.0f : 0.0f;

            force = false;
            // During high electrical activity bursts, allow rapid multi-cell chain flashes (cooldown 2..6 ticks);
            // during quieter phases, space flashes out (cooldown 10..28 ticks).
            if (globalWave > 0.68f && strength > 0.65f && RNG.nextFloat() < 0.55f) {
                cooldown = 2 + RNG.nextInt(5);
            } else {
                cooldown = 8 + RNG.nextInt(18);
            }

            double full = Math.sqrt(dist * dist + (f[1] - cam.y) * (f[1] - cam.y));
            queueThunder(gameTime + (long) (full / SPEED_OF_SOUND * 20.0), full, (float) (dx / dist), (float) (dz / dist),
                    mode != MODE_INTRA_CLOUD);
            return;
        }
    }

    private static int findNeighborTower(int selfIdx, float x0, float z0) {
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < cellCount; i++) {
            if (i == selfIdx) continue;
            int o = i * STRIDE;
            float dx = cells[o] - x0;
            float dz = cells[o + 2] - z0;
            float d = Mth.sqrt(dx * dx + dz * dz);
            if (d >= 160f && d <= 850f && d < bestScore) {
                bestScore = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * Scans surrounding world cells and active {@link WeatherFront}s to populate storm lightning candidates when
     * {@link CloudBuilder} is bypassed by an active Oculus shader pack.
     */
    private static void discoverStormCells(Vec3 cam, double timeSec) {
        float[] buf = new float[STRIDE * 48];
        int count = 0;
        int cloudBase = AtmosphereConfig.CLOUD_HEIGHT.get() - 24;
        List<WeatherFront> fronts = ClientWeather.fronts();
        Map<String, float[]> climate = CloudClimate.snapshot();
        float wx = ClientWeather.windX();
        float wz = ClientWeather.windZ();

        final int step = 340;
        final int reach = 1700;
        int i0 = Mth.floor((cam.x - reach) / step);
        int i1 = Mth.floor((cam.x + reach) / step);
        int j0 = Mth.floor((cam.z - reach) / step);
        int j1 = Mth.floor((cam.z + reach) / step);

        for (int i = i0; i <= i1 && count < 48; i++) {
            for (int j = j0; j <= j1 && count < 48; j++) {
                long seed = CloudBuilder.seed(i, j, 13);
                double cx = (i + 0.2 + 0.6 * CloudBuilder.hash(seed, 1)) * step;
                double cz = (j + 0.2 + 0.6 * CloudBuilder.hash(seed, 2)) * step;
                double dx = cx - cam.x;
                double dz = cz - cam.z;
                double distSq = dx * dx + dz * dz;
                if (distSq < 150.0 * 150.0 || distSq > reach * reach) continue;

                String regId = TamrielMap.region((int) cx, (int) cz).id();
                float[] regParams = climate.get(regId);
                WeatherKind regKind = ClientWeather.kindOf(regId);
                WeatherPhysics.ColumnState col = WeatherPhysics.evaluate(cx, cz, timeSec, regParams, regKind, wx, wz, fronts);

                float thunderStrength = 0f;
                if (regParams != null && regParams.length > CloudClimate.THUNDER) {
                    thunderStrength = regParams[CloudClimate.THUNDER];
                } else if (regKind != null) {
                    thunderStrength = regKind.thunder;
                }
                for (WeatherFront f : fronts) {
                    float fi = f.intensityAt(cx, cz);
                    if (fi > 0.05f) {
                        float ft = f.kind().thunder;
                        if (f.kind() == WeatherKind.ASH_STORM || f.kind() == WeatherKind.BLIGHT) ft = Math.max(ft, 0.45f);
                        if (WeatherPhysics.isMesocyclone(f)) ft = Math.max(ft, 0.85f);
                        thunderStrength = Math.max(thunderStrength, fi * ft);
                    }
                }
                // High convective towers & mesoscale rain bands also generate lightning
                if (col.stormTower() > 0.62f && col.rainBand() > 0.38f) {
                    thunderStrength = Math.max(thunderStrength, (col.stormTower() - 0.45f) * 1.2f);
                }

                if (thunderStrength > 0.14f) {
                    int o = count++ * STRIDE;
                    buf[o] = (float) cx;
                    buf[o + 1] = cloudBase + col.cloudBaseOffset();
                    buf[o + 2] = (float) cz;
                    buf[o + 3] = cloudBase + 220f + col.cloudTopBoost();
                    buf[o + 4] = 95f + 55f * col.stormTower();
                    buf[o + 5] = Mth.clamp(thunderStrength, 0.15f, 1.25f);
                }
            }
        }
        cells = buf;
        cellCount = count;
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
        if (!hasActiveFlash()) return;
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
        CloudRenderer.ensureTextureLoaded();
        // 1. Internal cloud sheet & anvil glow
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, CloudRenderer.TEXTURE);
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        float dim = 1f - 0.55f * daylight;
        for (int s = 0; s < MAX_FLASHES; s++) {
            float[] f = FLASH[s];
            if (f[7] <= 0f) continue;
            float k = strobe(FLASH_SEED[s], f[6] + pt, f[7]) * dim;
            if (k < 0.01f) continue;
            boolean volcanic = f[11] > 0.5f;
            float cr = volcanic ? 0.95f : 0.66f;
            float cg = volcanic ? 0.48f : 0.74f;
            float cb = volcanic ? 0.38f : 0.98f;

            float x = (float) (f[0] - cam.x);
            float yMid = (float) (f[1] + (f[3] - f[1]) * 0.38f - cam.y);
            float yAnvil = (float) (f[1] + (f[3] - f[1]) * 0.78f - cam.y);
            float z = (float) (f[2] - cam.z);

            glow(bb, x, yMid, z, f[4] * 2.7f, cr, cg, cb, k * 0.58f);
            glow(bb, x, (float) (f[1] - cam.y), z, f[4] * 1.9f, cr + 0.08f, cg + 0.08f, cb, k * 0.48f);
            glow(bb, x, yAnvil, z, f[4] * 3.1f, cr, cg, cb, k * 0.42f);

            if (FLASH_MODE[s] == MODE_CLOUD_TO_CLOUD) {
                // Propagating glow at the receiving cloud tower and midpoint of the C2C bridge
                float prog = Mth.clamp((f[6] + pt) / Math.max(1f, f[7] * 0.6f), 0f, 1f);
                float x1 = (float) (f[8] - cam.x);
                float y1 = (float) (f[9] - cam.y);
                float z1 = (float) (f[10] - cam.z);
                glow(bb, Mth.lerp(0.5f, x, x1), Mth.lerp(0.5f, yAnvil, y1), Mth.lerp(0.5f, z, z1),
                        f[4] * 2.4f, cr, cg, cb, k * 0.46f);
                glow(bb, x1, y1, z1, f[4] * 2.8f, cr, cg, cb, k * (0.35f + 0.45f * prog));
            }
        }
        BufferUploader.drawWithShader(bb.end());

        // 2. Cloud-to-Cloud (C2C) anvil crawlers and Cloud-to-Ground (CG) forked bolts
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        boolean bolts = false;
        for (int s = 0; s < MAX_FLASHES; s++) {
            float[] f = FLASH[s];
            int mode = FLASH_MODE[s];
            if (f[7] <= 0f || mode == MODE_INTRA_CLOUD || f[6] > 8f) continue;
            float k = strobe(FLASH_SEED[s], f[6] + pt, f[7]);
            if (k < 0.04f) continue;
            if (mode == MODE_CLOUD_TO_CLOUD) {
                cloudToCloudBolt(bb, cam, f, FLASH_SEED[s], k);
            } else {
                cloudToGroundBolt(bb, cam, f, FLASH_SEED[s], k);
            }
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
        float base = step == 0 ? 1f : step < 4 ? 0.30f + 0.70f * r : r * r * 0.75f;
        return base * (1f - age / life * 0.55f);
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
        bb.vertex(x - (rx - ux) * size, y + uy * size, z - (rz + uz) * size).uv(u0, v0).color(r, g, b, a).endVertex();
    }

    /**
     * A Cloud-to-Cloud (C2C) / Anvil Crawler lightning arc leaping across the sky from {@code (f[0], yStart, f[2])}
     * to {@code (f[8], f[9], f[10])} with branching horizontal tendrils under the cloud deck.
     */
    private static void cloudToCloudBolt(BufferBuilder bb, Vec3 cam, float[] f, long seed, float k) {
        RandomSource r = RandomSource.create(seed ^ 0xA5A55A5AL);
        float x0 = f[0], y0 = f[1] + (f[3] - f[1]) * 0.22f, z0 = f[2];
        float x1 = f[8], y1 = f[9], z1 = f[10];
        float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        float span = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (span < 40f) return;

        float jx = 0f, jy = 0f, jz = 0f;
        float archHeight = Math.min(85f, span * 0.16f);
        for (int i = 0; i < BOLT_POINTS; i++) {
            float t = (float) i / (BOLT_POINTS - 1);
            float env = 4f * t * (1f - t); // 0 at ends, 1 in middle
            BX[i] = Mth.lerp(t, x0, x1) + jx * env;
            BY[i] = Mth.lerp(t, y0, y1) + archHeight * env + jy * env;
            BZ[i] = Mth.lerp(t, z0, z1) + jz * env;
            jx += (r.nextFloat() - 0.5f) * span * 0.085f;
            jy += (r.nextFloat() - 0.5f) * span * 0.045f;
            jz += (r.nextFloat() - 0.5f) * span * 0.085f;
        }

        double mx = 0.5 * (x0 + x1) - cam.x;
        double mz = 0.5 * (z0 + z1) - cam.z;
        double dist = Math.sqrt(mx * mx + mz * mz);
        float w = (float) Math.max(0.75, dist * 0.0032);
        boolean volcanic = f[11] > 0.5f;
        float hr = volcanic ? 1.0f : 0.62f;
        float hg = volcanic ? 0.52f : 0.70f;
        float hb = volcanic ? 0.38f : 1.0f;

        ribbon(bb, cam, BX, BY, BZ, BOLT_POINTS, w * 5.5f, hr, hg, hb, 0.22f * k);
        ribbon(bb, cam, BX, BY, BZ, BOLT_POINTS, w, 0.96f, 0.97f, 1.0f, 0.96f * k);

        // Two spider-crawling anvil branches splitting off the main C2C channel
        for (int b = 0; b < 2; b++) {
            int from = 3 + r.nextInt(BOLT_POINTS - 6);
            float fx = BX[from], fy = BY[from], fz = BZ[from];
            float bdx = (x1 - x0) / span + (r.nextFloat() - 0.5f) * 1.1f;
            float bdz = (z1 - z0) / span + (r.nextFloat() - 0.5f) * 1.1f;
            int n = 6;
            float step = span * 0.075f;
            for (int i = 0; i < n; i++) {
                FX[i] = fx;
                FY[i] = fy;
                FZ[i] = fz;
                fx += bdx * step + (r.nextFloat() - 0.5f) * step * 0.75f;
                fy += (r.nextFloat() - 0.58f) * step * 0.35f;
                fz += bdz * step + (r.nextFloat() - 0.5f) * step * 0.75f;
            }
            ribbon(bb, cam, FX, FY, FZ, n, w * 2.8f, hr, hg, hb, 0.16f * k);
            ribbon(bb, cam, FX, FY, FZ, n, w * 0.65f, 0.92f, 0.94f, 1.0f, 0.72f * k);
        }
    }

    /** A forked bolt from the cloud base to the ground, as a camera-facing ribbon with a soft halo. */
    private static void cloudToGroundBolt(BufferBuilder bb, Vec3 cam, float[] f, long seed, float k) {
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
        float w = (float) Math.max(0.65, dist * 0.0032);
        boolean volcanic = f[11] > 0.5f;
        float hr = volcanic ? 1.0f : 0.58f;
        float hg = volcanic ? 0.50f : 0.65f;
        float hb = volcanic ? 0.36f : 1.0f;

        ribbon(bb, cam, BX, BY, BZ, BOLT_POINTS, w * 5.2f, hr, hg, hb, 0.20f * k);
        ribbon(bb, cam, BX, BY, BZ, BOLT_POINTS, w, 0.96f, 0.97f, 1.0f, 0.96f * k);

        // Two forked secondary branches off the main channel
        for (int b = 0; b < 2; b++) {
            int from = 2 + b * 3 + r.nextInt(4);
            if (from >= BOLT_POINTS - 2) continue;
            float fx = BX[from], fz = BZ[from];
            int n = 6;
            for (int i = 0; i < n; i++) {
                FX[i] = fx;
                FY[i] = BY[from] - h * 0.065f * i;
                FZ[i] = fz;
                fx += (r.nextFloat() - 0.5f) * h * 0.12f;
                fz += (r.nextFloat() - 0.5f) * h * 0.12f;
            }
            ribbon(bb, cam, FX, FY, FZ, n, w * 2.5f, hr, hg, hb, 0.14f * k);
            ribbon(bb, cam, FX, FY, FZ, n, w * 0.65f, 0.92f, 0.94f, 1.0f, 0.68f * k);
        }
    }

    private static void ribbon(BufferBuilder bb, Vec3 cam, float[] px, float[] py, float[] pz, int n, float w,
                               float r, float g, float b, float a) {
        for (int i = 0; i < n - 1; i++) {
            float x0 = (float) (px[i] - cam.x), y0 = (float) (py[i] - cam.y), z0 = (float) (pz[i] - cam.z);
            float x1 = (float) (px[i + 1] - cam.x), y1 = (float) (py[i + 1] - cam.y), z1 = (float) (pz[i + 1] - cam.z);
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
