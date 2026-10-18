package com.skycraft.atmosphere.client;

import com.skycraft.Skycraft;
import com.skycraft.atmosphere.AtmosphereConfig;
import com.skycraft.atmosphere.WeatherFront;
import com.skycraft.atmosphere.weather.WeatherKind;
import com.skycraft.atmosphere.weather.WeatherPackets;
import com.skycraft.atmosphere.weather.WeatherProfile;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The regional weather where the camera is, rendered locally. The server syncs a small table of region -> weather
 * and moving synoptic {@link WeatherFront}s ({@link WeatherPackets}); every half second this class blends the weather
 * of the player's region across a 9-point spatial Gaussian kernel ({@code ±96} blocks) with {@link TamrielMap}
 * microclimate data and approaching fronts, and every tick eases the presentation parameters toward that blend. It then
 * <ul>
 *   <li>overrides the client level's rain and thunder levels, so vanilla's rain/snow, sky darkening, rain sounds
 *       and splashes only appear where it is actually raining (vanilla still decides rain vs snow per column);</li>
 *   <li>thickens and tints the fog ({@link ViewportEvent.RenderFog}, {@link ViewportEvent.ComputeFogColor});</li>
 *   <li>exposes snow/ash/sand amounts, overcast and wind for the sky veil ({@link SkyRenderer}) and the
 *       precipitation particles ({@code com.skycraft.visual.client.AmbientParticles}).</li>
 * </ul>
 * If the server sent no table (regional weather off), nothing is overridden and vanilla weather shows as usual.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class ClientWeather {
    private static final int[][] SAMPLES = {
            {0, 0}, {96, 0}, {-96, 0}, {0, 96}, {0, -96},
            {96, 96}, {96, -96}, {-96, 96}, {-96, -96}
    };
    private static final float[] SAMPLE_WEIGHT = {
            4f, 2f, 2f, 2f, 2f,
            1f, 1f, 1f, 1f
    };
    /** Fraction of the remaining distance covered per tick (about a 10 second transition). */
    private static final float EASE = 0.01f;

    private record Spell(WeatherKind kind, int wind) {}

    private static final Map<String, Spell> TABLE = new HashMap<>();
    private static final List<WeatherFront> FRONTS = new ArrayList<>();
    private static final MapSample MAP_SAMPLE = new MapSample();
    private static boolean snap = true;
    private static int ticks;

    // ---- targets (recomputed every 10 ticks) and eased values
    private static final float[] target = new float[P.COUNT];
    private static final float[] cur = new float[P.COUNT];
    private static WeatherProfile profile = WeatherProfile.TEMPERATE;
    private static Region region;
    private static WeatherKind centerKind = WeatherKind.CLEAR;
    private static float windTargetDeg;
    private static float windDeg;

    /** Final fog colour of the last frame (after our tint), used to colour the overcast veil. */
    static float fogR = 0.6f, fogG = 0.7f, fogB = 0.8f;

    private ClientWeather() {}

    /** Indices into the parameter vectors. */
    private static final class P {
        static final int VANILLA = 0;   // rain level handed to vanilla (rain, or snow in snowy biomes)
        static final int SNOW = 1;      // our snow particles (snow where vanilla would draw rain)
        static final int ASH = 2;
        static final int SAND = 3;
        static final int CLOUDS = 4;
        static final int FOG = 5;
        static final int WIND = 6;
        static final int THUNDER = 7;
        static final int TINT_R = 8;
        static final int TINT_G = 9;
        static final int TINT_B = 10;
        static final int TINT = 11;     // how strongly the tint applies
        static final int COUNT = 12;
    }

    // ------------------------------------------------------------------ public queries

    /** The server is running regional weather and the client option is on. */
    public static boolean active() {
        return (!TABLE.isEmpty() || !FRONTS.isEmpty()) && AtmosphereConfig.LOCAL_WEATHER.get();
    }

    public static float snow() { return cur[P.SNOW]; }
    public static float ash() { return cur[P.ASH]; }
    public static float sand() { return cur[P.SAND]; }
    public static float clouds() { return cur[P.CLOUDS]; }
    public static float fog() { return cur[P.FOG]; }
    public static float wind() { return cur[P.WIND]; }
    public static float rain() { return cur[P.VANILLA]; }
    /** Wind direction (where it blows towards) as a unit vector. */
    public static float windX() { return -Mth.sin(windDeg * Mth.DEG_TO_RAD); }
    public static float windZ() { return Mth.cos(windDeg * Mth.DEG_TO_RAD); }
    public static WeatherProfile profile() { return profile; }
    public static WeatherKind kind() { return centerKind; }
    public static int knownRegionCount() { return TABLE.size(); }
    /** The region the player stands in (null before the first update). */
    public static Region region() { return region; }
    /** The synced weather of any region (the server covers every region out to about 1.6 km), or null if unknown. */
    public static WeatherKind kindOf(String regionId) {
        Spell s = TABLE.get(regionId);
        return s == null ? null : s.kind;
    }
    /** Ids of the regions the server has sent weather for. */
    static java.util.Set<String> regionIds() { return TABLE.keySet(); }
    /** Wind direction (where it blows towards, degrees) as eased on the client. */
    public static float windDegrees() { return windDeg; }

    /** Thread-safe snapshot of active moving weather fronts synced from the server. */
    public static List<WeatherFront> fronts() {
        List<WeatherFront> copy = new ArrayList<>(FRONTS.size());
        for (WeatherFront f : FRONTS) {
            copy.add(new WeatherFront(f.id, f.x, f.z, f.radius, f.vx, f.vz, f.kind, f.intensity, f.ticksLeft));
        }
        return copy;
    }

    /** Dominant moving weather front covering {@code (x, z)}, or {@code null} if none. */
    public static WeatherFront sampleFront(double x, double z) {
        WeatherFront best = null;
        float bestI = 0.01f;
        for (WeatherFront f : FRONTS) {
            float i = f.intensityAt(x, z);
            if (i > bestI) {
                bestI = i;
                best = f;
            }
        }
        return best;
    }

    /** Strongest weather front approaching within 200..1,600 blocks of {@code (x, z)}, or {@code null}. */
    public static WeatherFront nearestApproachingFront(double x, double z) {
        WeatherFront best = null;
        float bestA = 0.01f;
        for (WeatherFront f : FRONTS) {
            float a = f.approachFactor(x, z);
            if (a > bestA) {
                bestA = a;
                best = f;
            }
        }
        return best;
    }

    /** Test hook (visual autotest): jump the eased weather and the cloud climate to the synced table right away. */
    public static void snapForTest() {
        snap = true;
        CloudClimate.snapAll();
        CloudRenderer.invalidate();
    }

    // ------------------------------------------------------------------ sync

    public static void receive(WeatherPackets.Sync msg) {
        if (msg.full()) TABLE.clear();
        for (WeatherPackets.Entry e : msg.entries()) {
            if (e.kind() < 0) TABLE.remove(e.region());
            else TABLE.put(e.region(), new Spell(WeatherKind.byOrdinal(e.kind()), e.wind()));
        }
        if (msg.fronts() != null && (!msg.fronts().isEmpty() || msg.full())) {
            FRONTS.clear();
            for (WeatherFront f : msg.fronts()) {
                FRONTS.add(new WeatherFront(f.id, f.x, f.z, f.radius, f.vx, f.vz, f.kind, f.intensity, f.ticksLeft));
            }
        }
        if (msg.full() || Boolean.getBoolean("skycraft.autotest")) {
            snap = true;
            if (Boolean.getBoolean("skycraft.autotest")) {
                CloudClimate.snapAll();
                CloudRenderer.invalidate();
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        TABLE.clear();
        FRONTS.clear();
        snap = true;
        region = null;
        java.util.Arrays.fill(cur, 0f);
        java.util.Arrays.fill(target, 0f);
    }

    // ------------------------------------------------------------------ ticking

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.client.event.ClientTickEvent.Post)) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) return;
        if (mc.isPaused()) {
            applyVanilla(level);
            return;
        }
        if (level.dimension() != Level.OVERWORLD) {
            java.util.Arrays.fill(target, 0f);
            profile = WeatherProfile.NONE;
            region = Regions.at(level, player.blockPosition());
        } else {
            FRONTS.removeIf(WeatherFront::tick);
            if (snap || ++ticks % 10 == 0) {
                sample(level, player);
            }
        }
        for (int i = 0; i < P.COUNT; i++) {
            cur[i] = snap ? target[i] : cur[i] + (target[i] - cur[i]) * EASE;
        }
        float dw = Mth.wrapDegrees(windTargetDeg - windDeg);
        windDeg = snap ? windTargetDeg : windDeg + dw * 0.02f;
        snap = false;
        applyVanilla(level);
    }

    /** Re-apply right before the frame too, in case a server weather packet arrived since the tick. */
    @SubscribeEvent
    public static void onRenderTick(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        if (!(event instanceof net.neoforged.neoforge.client.event.RenderFrameEvent.Pre)) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) applyVanilla(level);
    }

    private static void applyVanilla(ClientLevel level) {
        if (!active() || level.dimension() != Level.OVERWORLD) return;
        float rain = Mth.clamp(cur[P.VANILLA], 0f, 1f);
        level.setRainLevel(rain);
        level.setThunderLevel(Mth.clamp(cur[P.THUNDER], 0f, 1f) * rain);
    }

    public static void sample(ClientLevel level, Player player) {
        computeTarget(level, player);
    }

    private static float localHeat;
    private static float localWetland;
    private static float localDunes;
    private static float localCoastDist = 1000f;
    private static float localElevation = 63f;
    private static boolean summersetPrismatic;

    private static void computeTarget(ClientLevel level, Player player) {
        java.util.Arrays.fill(target, 0f);
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        BlockPos here = player.blockPosition();
        Biome biome = level.getBiome(here).value();
        Biome.Precipitation biomePrecip = biome.getPrecipitationAt(here, level.getSeaLevel());
        float total = 0f;
        float tintW = 0f;
        float heatSum = 0f, wetSum = 0f, duneSum = 0f, coastSum = 0f, elevSum = 0f;
        for (int i = 0; i < SAMPLES.length; i++) {
            int px = here.getX() + SAMPLES[i][0];
            int pz = here.getZ() + SAMPLES[i][1];
            probe.set(px, here.getY(), pz);
            Region r = Regions.at(level, probe);
            WeatherProfile prof = WeatherProfile.of(r);
            Spell spell = TABLE.get(r.id());
            WeatherKind k = spell == null || prof == WeatherProfile.NONE ? WeatherKind.CLEAR : spell.kind;

            // Sample continuous TamrielMap terrain & climate attributes at the kernel point
            TamrielMap.sample(px, pz, MAP_SAMPLE);
            TamrielMap.deriveStrengths(MAP_SAMPLE);
            float coastDist = MAP_SAMPLE.coastDistance;
            float mapElev = Math.max(MAP_SAMPLE.elevation, TamrielMap.SEA_LEVEL);
            float mapHeat = MAP_SAMPLE.heat;
            float rainshadow = MAP_SAMPLE.rainshadow;
            float wetland = MAP_SAMPLE.wetland;
            float dunes = MAP_SAMPLE.dunes;

            // Check moving synoptic WeatherFronts covering this sample point
            WeatherFront front = sampleFront(px, pz);
            float frontWeight = front != null && prof != WeatherProfile.NONE ? front.intensityAt(px, pz) : 0f;
            if (front != null && frontWeight >= 0.42f) {
                k = front.kind;
            }

            float w = SAMPLE_WEIGHT[i];
            if (i == 0) {
                region = r;
                profile = prof;
                centerKind = k;
                String rid = r.id();
                summersetPrismatic = "summerset_isles".equals(r.province())
                        || "summerset".equals(rid) || "auridon".equals(rid) || "arteum".equals(rid);
                if (front != null && frontWeight >= 0.42f) {
                    windTargetDeg = front.windDegrees();
                } else if (spell != null) {
                    windTargetDeg = spell.wind;
                }
            }
            total += w;
            heatSum += w * mapHeat;
            wetSum += w * wetland;
            duneSum += w * dunes;
            coastSum += w * coastDist;
            elevSum += w * mapElev;

            // Rainshadow attenuates mild precipitation; coastal & orographic lift intensify mist and clouds
            float dryAtten = (frontWeight > 0.25f) ? 1f : (1f - 0.25f * Mth.clamp((rainshadow - 0.45f) * 1.8f, 0f, 1f));
            float amount = k.amount * dryAtten;
            if (front != null && frontWeight > 0.01f) {
                amount = Mth.lerp(frontWeight, amount, front.kind.amount * Mth.clamp(front.intensity, 0.5f, 1.25f));
            }
            double lat = com.skycraft.atmosphere.AtmosphereMath.latitudeFactor(pz);
            boolean polar = lat > 0.72 && mapHeat < 0.15f;
            // how much of the precipitation falls as snow: 0 well below the snow line, 1 well above it, graded between
            float snowShare = polar ? 1f : Mth.clamp((Math.max(here.getY(), mapElev) - (prof.snowLine - 30f)) / 60f, 0f, 1f);
            snowShare = snowShare * snowShare * (3f - 2f * snowShare);

            switch (k.precip) {
                case RAIN -> {
                    if (biomePrecip == Biome.Precipitation.NONE) {
                        // a desert or other dry biome inside a rainy region: no rain here
                    } else if (biomePrecip == Biome.Precipitation.RAIN) {
                        target[P.SNOW] += w * amount * snowShare;
                        target[P.VANILLA] += w * amount * (1f - snowShare);
                    } else {
                        target[P.VANILLA] += w * amount;
                    }
                }
                case SNOW -> {
                    if (biomePrecip == Biome.Precipitation.SNOW) {
                        target[P.VANILLA] += w * amount;
                        // vanilla's snow is gentle: a blizzard adds our wind-driven flakes on top
                        if (k == WeatherKind.BLIZZARD) target[P.SNOW] += w * 0.6f;
                    } else {
                        target[P.SNOW] += w * amount;
                    }
                }
                case ASH -> target[P.ASH] += w * amount;
                case SAND -> target[P.SAND] += w * amount;
                default -> {}
            }

            boolean isVolcanic = prof == WeatherProfile.VOLCANIC
                    || prof == WeatherProfile.VVARDENFELL
                    || prof == WeatherProfile.RED_MOUNTAIN;

            float coastalMist = coastDist < 280f ? Mth.clamp(1f - coastDist / 280f, 0f, 1f) * 0.16f : 0f;
            float orographic = mapElev > 150f ? Mth.clamp((mapElev - 150f) / 240f, 0f, 0.20f) : 0f;
            float marshMist = (prof == WeatherProfile.SWAMP || wetland > 0.3f) ? Math.max(0.12f, wetland * 0.28f) : 0f;
            float heatHaze = (prof == WeatherProfile.ARID || dunes > 0.25f) ? 0.08f + 0.10f * dunes : 0f;

            float sampleClouds = Mth.clamp(k.clouds * dryAtten + orographic * 0.45f + coastalMist * 0.3f, 0f, 1f);
            if (k.precip != WeatherKind.Precip.NONE && k.precip != WeatherKind.Precip.SAND && amount > 0.02f) {
                sampleClouds = Math.max(sampleClouds, Mth.clamp(0.78f + 0.18f * amount, 0.78f, 0.98f));
            }
            float sampleFog = Mth.clamp(k.fog + coastalMist + orographic * 0.4f + marshMist + heatHaze, 0f, 1f);
            if (summersetPrismatic && k == WeatherKind.CLEAR) {
                sampleFog *= 0.55f; // crisp high-altitude air over Summerset & Eton Nir
            }
            float sampleWind = k.wind + (coastDist < 180f ? 0.06f : 0f);
            float sampleThunder = k.thunder;
            if (front != null && frontWeight > 0.01f) {
                sampleClouds = Mth.lerp(frontWeight, sampleClouds, front.kind.clouds);
                sampleFog = Mth.lerp(frontWeight, sampleFog, front.kind.fog);
                sampleWind = Mth.lerp(frontWeight, sampleWind, front.kind.wind);
                sampleThunder = Mth.lerp(frontWeight, sampleThunder, front.kind.thunder);
            }

            target[P.CLOUDS] += w * sampleClouds;
            target[P.FOG] += w * sampleFog;
            target[P.WIND] += w * sampleWind;
            target[P.THUNDER] += w * sampleThunder;
            int fogColor = (front != null && frontWeight > 0.35f && front.kind.fogColor != 0) ? front.kind.fogColor : k.fogColor;
            if (fogColor == 0) {
                if (isVolcanic) fogColor = 0x5E4A42;
                else if (prof == WeatherProfile.ARID || dunes > 0.4f) fogColor = 0xC9A878;
                else if (prof == WeatherProfile.SWAMP || wetland > 0.45f) fogColor = 0x74887A;
            }
            if (fogColor != 0) {
                float tw = w * Math.max(0.18f, Math.max(sampleClouds, sampleFog));
                target[P.TINT_R] += tw * ((fogColor >> 16) & 0xFF) / 255f;
                target[P.TINT_G] += tw * ((fogColor >> 8) & 0xFF) / 255f;
                target[P.TINT_B] += tw * (fogColor & 0xFF) / 255f;
                tintW += tw;
            }
        }
        localHeat = heatSum / total;
        localWetland = wetSum / total;
        localDunes = duneSum / total;
        localCoastDist = coastSum / total;
        localElevation = elevSum / total;

        for (int i = 0; i < P.COUNT; i++) {
            if (i == P.TINT_R || i == P.TINT_G || i == P.TINT_B) continue;
            target[i] /= total;
        }
        if (tintW > 0f) {
            target[P.TINT_R] /= tintW;
            target[P.TINT_G] /= tintW;
            target[P.TINT_B] /= tintW;
            target[P.TINT] = tintW / total;
        } else {
            // keep the last colour while the tint fades out
            target[P.TINT_R] = cur[P.TINT_R];
            target[P.TINT_G] = cur[P.TINT_G];
            target[P.TINT_B] = cur[P.TINT_B];
        }
        // Pre-frontal wind and horizon haze swell as a severe weather front approaches within 200..1,600 blocks
        WeatherFront approaching = nearestApproachingFront(here.getX(), here.getZ());
        if (approaching != null) {
            float app = approaching.approachFactor(here.getX(), here.getZ());
            target[P.CLOUDS] = Math.max(target[P.CLOUDS], Mth.lerp(app * 0.45f, target[P.CLOUDS], approaching.kind.clouds));
            target[P.WIND] = Math.max(target[P.WIND], Mth.lerp(app * 0.5f, target[P.WIND], approaching.kind.wind));
        }
        // The local precipitation and visibility sample the same world-space band
        // seen at a distance. A summit above a valley fog layer must remain clear.
        var column = com.skycraft.atmosphere.WeatherPhysics.evaluate(here.getX(), here.getZ(),
                level.getGameTime() / 20.0, CloudClimate.snapshot().get(region.id()), kind(), windX(), windZ(), fronts(), level.getDefaultClockTime());
        float bandAmount = Mth.clamp(0.35f + 1.25f * column.curtainDensity(), 0.2f, 1f);
        target[P.VANILLA] *= bandAmount;
        target[P.SNOW] *= bandAmount;
        target[P.ASH] *= bandAmount;
        target[P.SAND] *= bandAmount;
        float falling = com.skycraft.atmosphere.WeatherPhysics.precipitationAltitudeFactor(column, player.getEyeY());
        target[P.VANILLA] *= falling;
        if (kind() != WeatherKind.BLIZZARD) target[P.SNOW] *= falling;
        float aboveSurface = Math.max(0f, here.getY() - column.surfaceHeight());
        float groundLayer = 1f - Mth.clamp((aboveSurface - 6f) / 55f, 0f, 1f);
        float dewFog = column.groundFog();
        float aerosolFog = Math.max(target[P.SAND], Math.max(target[P.ASH], target[P.SNOW]));
        target[P.FOG] = Math.max(target[P.FOG] * Math.max(groundLayer, aerosolFog), dewFog * groundLayer * 0.85f);
        // Under a roof or deep underground the storm is outside: precipitation stays, but no fog indoors.
        if (!level.canSeeSky(here.above()) && level.getBrightness(net.minecraft.world.level.LightLayer.SKY, here) < 6) {
            target[P.FOG] *= 0.15f;
        }
    }

    // ------------------------------------------------------------------ fog

    private static boolean fogApplies(net.minecraft.client.Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        if (!active() || !AtmosphereConfig.WEATHER_FOG.get() || mc.level == null || mc.level.dimension() != Level.OVERWORLD) return false;
        if (camera.getFluidInCamera() != FogType.NONE) return false;
        return !(camera.entity() instanceof net.minecraft.world.entity.LivingEntity le
                && (le.hasEffect(com.skycraft.platform.Effects.holder(MobEffects.BLINDNESS)) || le.hasEffect(com.skycraft.platform.Effects.holder(MobEffects.DARKNESS))));
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        float f = Mth.clamp(cur[P.FOG] * AtmosphereConfig.WEATHER_FOG_STRENGTH.get().floatValue(), 0f, 1f);
        if (f < 0.02f || !fogApplies(event.getCamera())) return;
        float vanillaFar = event.getFarPlaneDistance();
        float inv = 1f - f;
        boolean isHaboob = cur[P.SAND] > 0.15f && cur[P.VANILLA] < 0.05f && cur[P.SNOW] < 0.05f;
        boolean isBlizzard = cur[P.SNOW] > 0.25f;
        float targetFar = isHaboob
                ? (58f + 220f * inv)
                : (isBlizzard ? (18f + 145f * inv * (float) Math.sqrt(inv)) : (16f + 200f * inv * (float) Math.sqrt(inv)));
        var fog = event.getFogData();
        // Native 26.3 carries sky and terrain ranges in the same fog snapshot.
        float skyWeight = isHaboob ? f * .35f : f;
        fog.skyEnd = Math.min(fog.skyEnd, Mth.lerp(skyWeight, fog.skyEnd, targetFar * (isHaboob ? 1.8f : .8f)));
        float far = Math.min(vanillaFar, targetFar);
        float near = isBlizzard
                ? Math.min(event.getNearPlaneDistance(), far * (.02f + .28f * inv))
                : Math.min(event.getNearPlaneDistance(), far * (.08f + .55f * inv * inv));
        event.setNearPlaneDistance(near);
        event.setFarPlaneDistance(far);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        Minecraft mc = Minecraft.getInstance();
        if (fogApplies(event.getCamera())) {
            float pt = (float) event.getPartialTick();
            float angle = com.skycraft.atmosphere.client.SkyEnvironment.sunCycle(mc.level, pt) * Mth.TWO_PI;
            float sunY = Mth.cos(angle);
            float day = Mth.clamp(sunY * 2.0f + 0.5f, 0f, 1f);
            float light = 0.06f + 0.94f * day;
            float r = event.getRed();
            float g = event.getGreen();
            float b = event.getBlue();
            if (cur[P.TINT] > 0.01f) {
                float t = Mth.clamp(cur[P.TINT], 0f, 1f) * 0.85f;
                r = Mth.lerp(t, r, cur[P.TINT_R] * light);
                g = Mth.lerp(t, g, cur[P.TINT_G] * light);
                b = Mth.lerp(t, b, cur[P.TINT_B] * light);
            }
            if (cur[P.SNOW] > 0.20f) {
                // Alpine blizzard whiteout: soft diffused bright snow mist enveloping trees and slopes
                float wWhite = Mth.clamp(cur[P.SNOW] * 0.88f, 0f, 0.92f);
                r = Mth.lerp(wWhite, r, 0.92f * light);
                g = Mth.lerp(wWhite, g, 0.95f * light);
                b = Mth.lerp(wWhite, b, 0.99f * light);
            } else if (cur[P.SAND] > 0.15f) {
                // Haboob sandstorm horizon glow: warm ochre/tan desert dust
                float wSand = Mth.clamp(cur[P.SAND] * 0.85f, 0f, 0.90f);
                r = Mth.lerp(wSand, r, 0.84f * light);
                g = Mth.lerp(wSand, g, 0.65f * light);
                b = Mth.lerp(wSand, b, 0.45f * light);
            }
            // Regional & latitude-driven twilight Rayleigh/Mie scattering so horizon fog is never generic
            double camZ = event.getCamera().position().z;
            float lat = (float) com.skycraft.atmosphere.AtmosphereMath.latitudeFactor(camZ);
            float twilightSpan = 0.24f + 0.10f * lat; // polar latitudes have longer lingering twilight
            float twilight = Mth.clamp(1f - Math.abs(sunY - 0.02f) / twilightSpan, 0f, 1f);
            twilight = twilight * twilight * (3f - 2f * twilight) * (1f - 0.55f * cur[P.FOG]);
            if (twilight > 0.01f) {
                float warmPhase = Mth.clamp((sunY + 0.12f) / 0.30f, 0f, 1f);
                float mieR = Mth.lerp(warmPhase, 0.48f - 0.10f * lat, 0.94f);
                float mieG = Mth.lerp(warmPhase, 0.46f + 0.06f * lat, 0.58f);
                float mieB = Mth.lerp(warmPhase, 0.74f + 0.12f * lat, 0.44f);
                if (profile == WeatherProfile.VOLCANIC || profile == WeatherProfile.VVARDENFELL || profile == WeatherProfile.RED_MOUNTAIN || cur[P.ASH] > 0.05f) {
                    // Vvardenfell / Stonefalls volcanic ash-ember twilight
                    mieR = 0.78f;
                    mieG = 0.35f;
                    mieB = 0.26f;
                } else if (profile == WeatherProfile.ARID || localDunes > 0.35f || cur[P.SAND] > 0.05f) {
                    // Alik'r / Anequina warm amber desert horizon scattering
                    mieR = 0.96f;
                    mieG = 0.72f;
                    mieB = 0.45f;
                } else if (summersetPrismatic || localElevation > 220f) {
                    // Summerset / Eton Nir crisp prismatic high-altitude light
                    mieR = Mth.lerp(warmPhase, 0.68f, 0.96f);
                    mieG = Mth.lerp(warmPhase, 0.62f, 0.74f);
                    mieB = Mth.lerp(warmPhase, 0.92f, 0.86f);
                }
                float scatter = twilight * 0.38f;
                r = Mth.lerp(scatter, r, mieR);
                g = Mth.lerp(scatter, g, mieG);
                b = Mth.lerp(scatter, b, mieB);
            }
            // Black Marsh / Bitter Coast / Hjaalmarch low bioluminescent humid mist undertone at dusk/night
            if ((profile == WeatherProfile.SWAMP || localWetland > 0.35f) && day < 0.55f) {
                float bio = (1f - day / 0.55f) * Math.max(0.25f, localWetland) * 0.22f;
                r = Mth.lerp(bio, r, 0.36f);
                g = Mth.lerp(bio, g, 0.62f);
                b = Mth.lerp(bio, b, 0.52f);
            }
            event.setRed(r);
            event.setGreen(g);
            event.setBlue(b);
        }
        fogR = event.getRed();
        fogG = event.getGreen();
        fogB = event.getBlue();
    }
}
