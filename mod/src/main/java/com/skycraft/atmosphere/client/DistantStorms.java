package com.skycraft.atmosphere.client;

import com.skycraft.atmosphere.AtmosphereConfig;
import com.skycraft.atmosphere.LightningMath;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import com.skycraft.atmosphere.WeatherFront;
import com.skycraft.atmosphere.WeatherPhysics;
import com.skycraft.atmosphere.weather.WeatherKind;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.client.Minecraft;
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
import com.skycraft.render.client.WorldVertices;
import com.mojang.blaze3d.vertex.VertexConsumer;

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
 * flashes). Thunder follows after the acoustic travel delay ({@code 343 m/s}). A small 3D source packet
 * shares the connected channel and contact with terrain and volumetric lighting.</p>
 */
final class DistantStorms {
    /** Floats per storm candidate: world x, base y, z, top y, radius, strength. */
    static final int STRIDE = 6;
    private static final int MAX_FLASHES = 8;
    private static final int MAX_THUNDER = 16;
    private static final int BOLT_POINTS = LightningMath.POINTS;
    private static final double SPEED_OF_SOUND = 343.0;

    private static final int MODE_INTRA_CLOUD = 0;
    private static final int MODE_CLOUD_TO_CLOUD = 1;
    private static final int MODE_CLOUD_TO_GROUND = 2;

    private static float[] cells = new float[0];
    private static final float[] SHADER_CELLS = new float[STRIDE * 64];
    private static int cellCount;
    private static long dischargeCount;
    private static long lastFieldScanTick = -1000L;
    private static final RandomSource RNG = RandomSource.create();

    // ---- active flashes: x0, yBase, z0, yTop, radius, groundY, age, life, x1(target), y1(target), z1(target), source strength
    private static final float[][] FLASH = new float[MAX_FLASHES][12];
    private static final long[] FLASH_SEED = new long[MAX_FLASHES];
    private static final int[] FLASH_MODE = new int[MAX_FLASHES];
    private static final LightningMath.Channel[] CHANNELS = new LightningMath.Channel[MAX_FLASHES];
    static { for (int i = 0; i < MAX_FLASHES; i++) CHANNELS[i] = new LightningMath.Channel(); }

    private static int cooldown;
    /** Test hook: the next tick flashes the first eligible storm. */
    static boolean force;
    private static int forcedMode = MODE_CLOUD_TO_GROUND;

    static void force(String mode) {
        forcedMode = switch (mode) {
            case "ic" -> MODE_INTRA_CLOUD;
            case "c2c" -> MODE_CLOUD_TO_CLOUD;
            case "cg" -> MODE_CLOUD_TO_GROUND;
            default -> throw new IllegalArgumentException("Lightning mode must be ic, c2c, or cg");
        };
        force = true;
    }

    // ---- queued thunder: tick due, volume, pitch, direction x, z
    private static final float[][] THUNDER = new float[MAX_THUNDER][5];
    private static final long[] THUNDER_AT = new long[MAX_THUNDER];
    private static int thunderCount;

    private static final float[] FX = new float[BOLT_POINTS], FY = new float[BOLT_POINTS], FZ = new float[BOLT_POINTS];

    private DistantStorms() {}

    static void clear() {
        cellCount = 0;
        dischargeCount = 0;
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

    static String diagnostics() {
        return " electrical_cells=" + cellCount + " discharges=" + dischargeCount;
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

        // Both renderer paths qualify electrical columns independently of mesh rebuilding.
        if (Math.abs(gameTime - lastFieldScanTick) >= 20L) {
            lastFieldScanTick = gameTime;
            discoverStormCells(cam, gameTime / 20.0);
        }

        if (cooldown > 0) cooldown--;
        if (force && cellCount == 0) {
            com.skycraft.Skycraft.LOGGER.info("Autotest lightning: rejected; no electrically qualified storm cells");
            force = false;
        }
        if (cellCount == 0 || (cooldown > 0 && !force) || !AtmosphereConfig.DISTANT_WEATHER.get()) return;

        // Global electrical cadence wave: sometimes storms rumble with a flurry of cloud-to-cloud arcs, sometimes quiet
        double sec = gameTime / 20.0;
        float globalWave = 0.5f + 0.3f * Mth.sin((float) (sec * 0.045)) + 0.2f * Mth.sin((float) (sec * 0.13 + 1.4));

        int forcedCell = -1;
        if (force) {
            // Select an actual storm in the camera's view, rather than the first grid cell behind it.
            var look = mc.gameRenderer.mainCamera().forwardVector();
            double best = -Double.MAX_VALUE;
            for (int c = 0; c < cellCount; c++) {
                int o = c * STRIDE;
                double dx = cells[o] - cam.x, dz = cells[o + 2] - cam.z;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < 130) continue;
                double score = (dx * look.x() + dz * look.z()) / d - d * 0.00005;
                if (score > best) { best = score; forcedCell = c; }
            }
        }
        if (force && forcedCell < 0) {
            com.skycraft.Skycraft.LOGGER.info("Autotest lightning: rejected; no eligible cell outside the camera exclusion radius");
            force = false;
            return;
        }
        for (int c = 0; c < cellCount; c++) {
            if (force && c != forcedCell) continue;
            int o = c * STRIDE;
            float strength = cells[o + 5];
            float cellWave = 0.55f + 0.45f * Mth.sin((float) (sec * 0.09 + c * 1.7));
            float activity = strength * (0.35f + 0.95f * globalWave * cellWave);

            if (!force && RNG.nextFloat() > activity / (85f * Math.max(1f, cellCount / 8f))) continue;
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
            // Mountains can penetrate the condensation layer. A discharge must
            // originate in the remaining cloud above the terrain, never inside rock.
            f[1] = Math.max(f[1], f[5] + 24f);
            if (f[3] < f[1] + 32f) continue;
            f[6] = 0f;

            // Choose lightning mode:
            // - Cloud-to-Cloud (C2C) / Anvil Crawler (~48% of flashes, especially in high-activity mesocyclones/fronts)
            // - Cloud-to-Ground (CG) forked bolt (~32% of flashes, or when forced by test)
            // - Intra-Cloud (IC) sheet lightning (~20% of flashes)
            int mode;
            float roll = RNG.nextFloat();
            if (force) {
                mode = forcedMode;
            } else if (roll < 0.48f) {
                mode = MODE_CLOUD_TO_CLOUD;
            } else if (roll < 0.80f) {
                mode = MODE_CLOUD_TO_GROUND;
            } else {
                mode = MODE_INTRA_CLOUD;
            }
            FLASH_MODE[slot] = mode;
            FLASH_SEED[slot] = RNG.nextLong();
            f[7] = LightningMath.lifetime(FLASH_SEED[slot]);
            f[11] = Mth.clamp(strength, 0.5f, 1.25f);

            // Pick target (x1, y1, z1) for Cloud-to-Cloud / Anvil Crawler lightning
            if (mode != MODE_CLOUD_TO_GROUND) {
                int neighbor = findNeighborTower(c, f[0], f[2]);
                if (neighbor >= 0 && mode == MODE_CLOUD_TO_CLOUD) {
                    int no = neighbor * STRIDE;
                    f[8] = cells[no] + (RNG.nextFloat() - 0.5f) * cells[no + 4] * 0.5f;
                    f[9] = cells[no + 1] + (cells[no + 3] - cells[no + 1]) * (0.05f + 0.20f * RNG.nextFloat());
                    f[10] = cells[no + 2] + (RNG.nextFloat() - 0.5f) * cells[no + 4] * 0.5f;
                    f[9] = Math.max(f[9], TamrielMap.elevation(Mth.floor(f[8]), Mth.floor(f[10])) + 24f);
                } else {
                    // Without another qualified tower, remain inside this storm's
                    // canopy instead of flashing an unrelated fair-weather cloud.
                    float ang = RNG.nextFloat() * Mth.TWO_PI;
                    float span = r * (mode == MODE_INTRA_CLOUD ? 0.3f + 0.5f * RNG.nextFloat()
                            : 0.8f + 0.4f * RNG.nextFloat());
                    f[8] = f[0] + Mth.cos(ang) * span;
                    f[9] = f[1] + (f[3] - f[1]) * (mode == MODE_INTRA_CLOUD ? 0.55f : 0.12f);
                    f[10] = f[2] + Mth.sin(ang) * span;
                }
            } else {
                chooseGroundTarget(mc, f);
                f[5] = f[9];
            }

            float sourceY = mode == MODE_CLOUD_TO_GROUND
                    ? Math.min(f[3], Math.max(f[1] + 12, f[5] + 75))
                    : f[1] + (f[3] - f[1]) * (mode == MODE_INTRA_CLOUD ? 0.45f : 0.04f);
            CHANNELS[slot].build(FLASH_SEED[slot], f[0], sourceY, f[2], f[8], f[9], f[10],
                    mode == MODE_CLOUD_TO_GROUND, mode == MODE_CLOUD_TO_GROUND ? f[5] : f[1], f[3]);

            // Ash is not an emissive red cloud. Discharges retain their white-blue source colour;
            // actual lava/fire radiance and scattering determine any warm plume illumination.

            if (force) com.skycraft.Skycraft.LOGGER.info("Autotest lightning: mode={} origin=({},{},{}) target=({},{},{}) ground={} seed={}",
                    mode, f[0], f[1], f[2], f[8], f[9], f[10], f[5], FLASH_SEED[slot]);
            force = false;
            dischargeCount++;
            // Rare intense flurries; a large number of horizon cells must not make
            // every visible cloud flash continuously.
            if (globalWave > 0.82f && strength > 0.85f && RNG.nextFloat() < 0.20f) {
                cooldown = 8 + RNG.nextInt(26);
            } else {
                cooldown = 35 + RNG.nextInt(85);
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
        // Most arcs remain local; a minority bridge widely separated qualified towers.
        float preferredSpan = RNG.nextFloat() < 0.25f ? 1200f : 360f;
        for (int i = 0; i < cellCount; i++) {
            if (i == selfIdx) continue;
            int o = i * STRIDE;
            float dx = cells[o] - x0;
            float dz = cells[o + 2] - z0;
            float d = Mth.sqrt(dx * dx + dz * dz);
            float score = Math.abs(d - preferredSpan);
            if (d >= 160f && d <= 1800f && score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /**
     * Qualifies electrical columns independently of mesh rebuilding for both renderer paths.
     */
    private static void discoverStormCells(Vec3 cam, double timeSec) {
        long dayTime = Minecraft.getInstance().level.getOverworldClockTime();
        float[] buf = SHADER_CELLS;
        int count = 0;
        int cloudBase = 232;
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
                WeatherPhysics.ColumnState col = WeatherPhysics.evaluate(cx, cz, timeSec, regParams, regKind, wx, wz, fronts, dayTime);

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
                // High convective towers & mesoscale rain bands amplify active electrical storms (never plain snow/rain)
                if (thunderStrength > 0.05f && col.stormTower() > 0.62f && col.rainBand() > 0.38f) {
                    thunderStrength = Math.max(thunderStrength, (col.stormTower() - 0.45f) * 1.2f);
                }

                thunderStrength = WeatherPhysics.electricalActivity(col, thunderStrength);
                if (thunderStrength > 0.14f) {
                    int o = count++ * STRIDE;
                    buf[o] = (float) cx;
                    buf[o + 1] = cloudBase + col.cloudBaseOffset();
                    buf[o + 2] = (float) cz;
                    buf[o + 3] = buf[o + 1] + 72f + 120f * col.coverage() + 300f * col.stormTower();
                    buf[o + 4] = 95f + 55f * col.stormTower();
                    buf[o + 5] = Mth.clamp(thunderStrength, 0.15f, 1.25f);
                }
            }
        }
        // A small second tier covers synoptic storms through the DH vista without
        // scanning a continent-sized dense grid or loading any terrain chunks.
        for (WeatherFront front : fronts) {
            if (count >= 64) break;
            double dx = front.x() - cam.x, dz = front.z() - cam.z;
            double distance = Math.sqrt(dx * dx + dz * dz);
            float thunder = front.kind().thunder;
            if (front.kind() == WeatherKind.ASH_STORM || front.kind() == WeatherKind.BLIGHT) thunder = Math.max(thunder, 0.45f);
            if (distance < 1900 || distance > 30000 || thunder < 0.14f) continue;
            var col = WeatherPhysics.evaluate(front.x(), front.z(), timeSec, null, front.kind(), wx, wz, fronts, dayTime);
            thunder = WeatherPhysics.electricalActivity(col, thunder * front.intensity());
            if (thunder < 0.14f) continue;
            int o = count++ * STRIDE;
            buf[o] = front.x(); buf[o + 1] = cloudBase + col.cloudBaseOffset(); buf[o + 2] = front.z();
            buf[o + 3] = buf[o + 1] + 72f + 120f * col.coverage() + 300f * col.stormTower();
            buf[o + 4] = 160f + 60f * col.stormTower(); buf[o + 5] = thunder;
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
        mc.getSoundManager().play(new SimpleSoundInstance(SoundEvents.LIGHTNING_BOLT_THUNDER.location(), SoundSource.WEATHER,
                t[0], t[1], RandomSource.create(RNG.nextLong()), false, 0, SoundInstance.Attenuation.NONE, x, cam.y + 4.0, z, false));
    }

    record RenderFrame(WorldVertices glow, WorldVertices bolts) {}

    /** Capture original channel growth and sheet-lightning geometry without touching GPU or mutable draw state. */
    static RenderFrame extract(Vec3 cam, float pt, float daylight, boolean boltsOnly) {
        var glow = new WorldVertices.Builder(true);
        var channels = new WorldVertices.Builder(false);
        if (!hasActiveFlash()) return new RenderFrame(glow.build(), channels.build());
        if (!boltsOnly) {
            VertexConsumer bb = glow;
        float dim = 1f - 0.55f * daylight;
        for (int s = 0; s < MAX_FLASHES; s++) {
            float[] f = FLASH[s];
            if (f[7] <= 0f) continue;
            float k = strobe(FLASH_SEED[s], f[6] + pt, f[7]) * dim;
            if (k < 0.01f) continue;
            float cr = 0.66f;
            float cg = 0.74f;
            float cb = 0.98f;

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
        }
        VertexConsumer bb = channels;
        for (int s = 0; s < MAX_FLASHES; s++) {
            float[] f = FLASH[s];
            int mode = FLASH_MODE[s];
            if (f[7] <= 0f) continue;
            float k = strobe(FLASH_SEED[s], f[6] + pt, f[7]);
            if (k < 0.04f) continue;
            drawChannel(bb, cam, s, k, f[6] + pt);
        }
        return new RenderFrame(glow.build(), channels.build());
    }

    private static float strobe(long seed, float age, float life) {
        return LightningMath.pulse(seed, age, life);
    }

    /** At most 25 cached-heightmap probes per discharge, never requests a chunk. */
    private static void chooseGroundTarget(Minecraft mc, float[] f) {
        float sourceY = Math.min(f[3], Math.max(f[1] + 12, f[5] + 75));
        float baseline = f[5], best = -Float.MAX_VALUE;
        int loaded = 0;
        for (int i = 0; i < 25; i++) {
            float radius = i == 0 ? 0 : Math.min(130, Math.max(35, sourceY - baseline) * 0.55f)
                    * (float) Math.sqrt(i / 24f);
            float angle = i * 2.3999632f;
            int x = Mth.floor(f[0] + Mth.cos(angle) * radius);
            int z = Mth.floor(f[2] + Mth.sin(angle) * radius);
            var chunk = mc.level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
            boolean available = chunk != null && !(chunk instanceof EmptyLevelChunk);
            // ClientLevel.hasChunk is not a residency check. Read only real cached columns.
            float y = available ? chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1
                    : Math.max(TamrielMap.SEA_LEVEL, TamrielMap.elevation(x, z));
            if (available && y <= mc.level.getMinY()) {
                available = false;
                y = Math.max(TamrielMap.SEA_LEVEL, TamrielMap.elevation(x, z));
            }
            if (available) loaded++;
            if (y >= sourceY - 8) continue;
            float score = LightningMath.targetScore(y, baseline, radius, sourceY);
            // A few loaded-column checks penalize a path intercepted by a ridge or roof.
            for (int step = 1; step <= 3; step++) {
                float t = step * 0.24f;
                int px = Mth.floor(Mth.lerp(t, f[0], x + 0.5f));
                int pz = Mth.floor(Mth.lerp(t, f[2], z + 0.5f));
                var pathChunk = mc.level.getChunkSource().getChunk(px >> 4, pz >> 4, ChunkStatus.FULL, false);
                if (pathChunk != null && !(pathChunk instanceof EmptyLevelChunk)
                        && pathChunk.getHeight(Heightmap.Types.MOTION_BLOCKING, px & 15, pz & 15) + 1
                        > Mth.lerp(t, sourceY, y) - 2) score -= 180;
            }
            if (score > best) { best = score; f[8] = x + 0.5f; f[9] = y + 0.12f; f[10] = z + 0.5f; }
        }
        if (best == -Float.MAX_VALUE) { f[8] = f[0]; f[9] = baseline + 0.12f; f[10] = f[2]; }
        if (force) com.skycraft.Skycraft.LOGGER.info(
                "Autotest lightning target: probes=25 loaded={} contact=({},{},{}) prominence={} path=heightmap",
                loaded, f[8], f[9], f[10], f[9] - baseline);
    }

    /** Shared source packet: four coarse primary points plus one major fork. */
    static int writeLightSources(com.mojang.blaze3d.platform.NativeImage image, Vec3 camera, float pt) {
        // Keep the four highest-impact flashes, with a small distance preference.
        int count = 0, used = 0;
        for (int row = 0; row < 4; row++) {
            int selected = -1; float best = 0;
            for (int s = 0; s < MAX_FLASHES; s++) {
                float[] f = FLASH[s];
                if ((used & (1 << s)) != 0 || f[7] <= 0) continue;
                float pulse = strobe(FLASH_SEED[s], f[6] + pt, f[7]);
                float score = pulse / (1f + (float) camera.distanceToSqr(f[0], f[1], f[2]) / 4_000_000f);
                if (score > best) { best = score; selected = s; }
            }
            if (selected < 0) break;
            used |= 1 << selected; count++;
            float[] f = FLASH[selected];
            image.setPixelABGR(0, row, LightningFieldTexture.rgba(strobe(FLASH_SEED[selected], f[6] + pt, f[7]),
                    LightningMath.progress(FLASH_SEED[selected], f[6] + pt),
                    FLASH_MODE[selected] == MODE_CLOUD_TO_GROUND ? 1 : 0, f[11] / 1.25f));
            LightningMath.Channel channel = CHANNELS[selected];
            for (int point = 0; point < 6; point++) {
                int branch = point < 4 ? 0 : 1;
                int index = point < 4 ? point * (BOLT_POINTS - 1) / 3 : (point == 4 ? 0 : LightningMath.FORK_POINTS - 1);
                LightningFieldTexture.point(image, point * 2 + 1, row,
                        (float) (channel.x[branch][index] - camera.x), (float) (channel.y[branch][index] - camera.y), (float) (channel.z[branch][index] - camera.z));
            }
        }
        return count;
    }

    private static void drawChannel(VertexConsumer bb, Vec3 cam, int slot, float pulse, float age) {
        LightningMath.Channel c = CHANNELS[slot];
        float[] f = FLASH[slot];
        float distance = (float) cam.distanceTo(new Vec3(f[0], f[1], f[2]));
        float width = Math.max(0.24f, distance * 0.00040f);
        float progress = LightningMath.progress(FLASH_SEED[slot], age);
        for (int branch = 0; branch <= LightningMath.FORKS; branch++) {
            float growth = Mth.clamp((progress - c.starts[branch]) / (c.ends[branch] - c.starts[branch]), 0, 1);
            int n = branch == 0 ? BOLT_POINTS : LightningMath.FORK_POINTS;
            float w = width * (branch == 0 ? 1 : branch > 6 ? 0.30f : 0.55f);
            float power = pulse * (branch == 0 ? 1 : 0.75f);
            ribbon(bb, cam, c.x[branch], c.y[branch], c.z[branch], n, w * 9, 0.55f, 0.66f, 1, power * 0.08f, growth);
            ribbon(bb, cam, c.x[branch], c.y[branch], c.z[branch], n, w * 3, 0.72f, 0.83f, 1, power * 0.32f, growth);
            ribbon(bb, cam, c.x[branch], c.y[branch], c.z[branch], n, w, 0.96f, 0.98f, 1, power, growth);
        }
        if (FLASH_MODE[slot] == MODE_CLOUD_TO_GROUND && progress < 1) {
            float streamer = Mth.clamp((progress - 0.6f) / 0.4f, 0, 1);
            for (int i = 0; i < 3; i++) {
                FX[i] = f[8] + (i == 1 ? 2 : 0);
                FY[i] = f[9] + i * 8 * streamer;
                FZ[i] = f[10];
            }
            ribbon(bb, cam, FX, FY, FZ, 3, width * 0.7f, 0.8f, 0.88f, 1, pulse);
        }
    }

    private static void glow(VertexConsumer bb, float x, float y, float z, float size, float r, float g, float b, float a) {
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
        bb.addVertex(x - (rx + ux) * size, y - uy * size, z - (rz + uz) * size).setUv(u0, v1).setColor(r, g, b, a);
        bb.addVertex(x + (rx - ux) * size, y - uy * size, z + (rz - uz) * size).setUv(u1, v1).setColor(r, g, b, a);
        bb.addVertex(x + (rx + ux) * size, y + uy * size, z + (rz + uz) * size).setUv(u1, v0).setColor(r, g, b, a);
        bb.addVertex(x - (rx - ux) * size, y + uy * size, z - (rz + uz) * size).setUv(u0, v0).setColor(r, g, b, a);
    }

    private static void ribbon(VertexConsumer bb, Vec3 cam, float[] px, float[] py, float[] pz, int n, float w,
                               float r, float g, float b, float a) {
        ribbon(bb, cam, px, py, pz, n, w, r, g, b, a, 1f);
    }

    private static void ribbon(VertexConsumer bb, Vec3 cam, float[] px, float[] py, float[] pz, int n, float w,
                               float r, float g, float b, float a, float progress) {
        for (int i = 0; i < n - 1; i++) {
            float fraction = Mth.clamp(progress * (n - 1) - i, 0f, 1f);
            if (fraction <= 0f) break;
            float x0 = (float) (px[i] - cam.x), y0 = (float) (py[i] - cam.y), z0 = (float) (pz[i] - cam.z);
            float x1 = (float) (Mth.lerp(fraction, px[i], px[i + 1]) - cam.x);
            float y1 = (float) (Mth.lerp(fraction, py[i], py[i + 1]) - cam.y);
            float z1 = (float) (Mth.lerp(fraction, pz[i], pz[i + 1]) - cam.z);
            float ax = x1 - x0, ay = y1 - y0, az = z1 - z0;
            // side = segment x view direction
            float sx = ay * z0 - az * y0, sy = az * x0 - ax * z0, sz = ax * y0 - ay * x0;
            float sl = Mth.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < 1e-4f) continue;
            sx = sx / sl * w;
            sy = sy / sl * w;
            sz = sz / sl * w;
            bb.addVertex(x0 - sx, y0 - sy, z0 - sz).setColor(r, g, b, a);
            bb.addVertex(x0 + sx, y0 + sy, z0 + sz).setColor(r, g, b, a);
            bb.addVertex(x1 + sx, y1 + sy, z1 + sz).setColor(r, g, b, a);
            bb.addVertex(x1 - sx, y1 - sy, z1 - sz).setColor(r, g, b, a);
        }
    }
}
