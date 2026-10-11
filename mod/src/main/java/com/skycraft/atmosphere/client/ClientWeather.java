package com.skycraft.atmosphere.client;

import com.skycraft.Skycraft;
import com.skycraft.atmosphere.AtmosphereConfig;
import com.skycraft.atmosphere.weather.WeatherKind;
import com.skycraft.atmosphere.weather.WeatherPackets;
import com.skycraft.atmosphere.weather.WeatherProfile;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;

/**
 * The regional weather where the camera is, rendered locally. The server syncs a small table of region -> weather
 * ({@link WeatherPackets}); every half second this class blends the weather of the player's region with the regions
 * just across any nearby border, and every tick eases the presentation parameters toward that blend. It then
 * <ul>
 *   <li>overrides the client level's rain and thunder levels, so vanilla's rain/snow, sky darkening, rain sounds
 *       and splashes only appear where it is actually raining (vanilla still decides rain vs snow per column);</li>
 *   <li>thickens and tints the fog ({@link ViewportEvent.RenderFog}, {@link ViewportEvent.ComputeFogColor});</li>
 *   <li>exposes snow/ash/sand amounts, overcast and wind for the sky veil ({@link SkyRenderer}) and the
 *       precipitation particles ({@code com.skycraft.visual.client.AmbientParticles}).</li>
 * </ul>
 * If the server sent no table (regional weather off), nothing is overridden and vanilla weather shows as usual.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class ClientWeather {
    private static final int[][] SAMPLES = {{0, 0}, {64, 0}, {-64, 0}, {0, 64}, {0, -64}};
    private static final float[] SAMPLE_WEIGHT = {2f, 1f, 1f, 1f, 1f};
    /** Fraction of the remaining distance covered per tick (about a 10 second transition). */
    private static final float EASE = 0.01f;

    private record Spell(WeatherKind kind, int wind) {}

    private static final Map<String, Spell> TABLE = new HashMap<>();
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
        return !TABLE.isEmpty() && AtmosphereConfig.LOCAL_WEATHER.get();
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
        if (msg.full()) snap = true;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        TABLE.clear();
        snap = true;
        region = null;
        java.util.Arrays.fill(cur, 0f);
        java.util.Arrays.fill(target, 0f);
    }

    // ------------------------------------------------------------------ ticking

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
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
        } else if (snap || ++ticks % 10 == 0) {
            computeTarget(level, player);
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
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) applyVanilla(level);
    }

    private static void applyVanilla(ClientLevel level) {
        if (!active() || level.dimension() != Level.OVERWORLD) return;
        float rain = Mth.clamp(cur[P.VANILLA], 0f, 1f);
        level.setRainLevel(rain);
        level.setThunderLevel(Mth.clamp(cur[P.THUNDER], 0f, 1f) * rain);
    }

    private static void computeTarget(ClientLevel level, Player player) {
        java.util.Arrays.fill(target, 0f);
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        BlockPos here = player.blockPosition();
        Biome biome = level.getBiome(here).value();
        Biome.Precipitation biomePrecip = biome.getPrecipitationAt(here);
        float total = 0f;
        float tintW = 0f;
        for (int i = 0; i < SAMPLES.length; i++) {
            probe.set(here.getX() + SAMPLES[i][0], here.getY(), here.getZ() + SAMPLES[i][1]);
            Region r = Regions.at(level, probe);
            WeatherProfile prof = WeatherProfile.of(r);
            Spell spell = TABLE.get(r.id());
            WeatherKind k = spell == null || prof == WeatherProfile.NONE ? WeatherKind.CLEAR : spell.kind;
            float w = SAMPLE_WEIGHT[i];
            if (i == 0) {
                region = r;
                profile = prof;
                centerKind = k;
                if (spell != null) windTargetDeg = spell.wind;
            }
            total += w;
            float amount = k.amount;
            switch (k.precip) {
                case RAIN -> {
                    boolean aboveSnowLine = here.getY() > prof.snowLine;
                    if (biomePrecip == Biome.Precipitation.NONE) {
                        // a desert or other dry biome inside a rainy region: no rain here
                    } else if (aboveSnowLine && biomePrecip == Biome.Precipitation.RAIN) {
                        target[P.SNOW] += w * amount;
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
            target[P.CLOUDS] += w * k.clouds;
            target[P.FOG] += w * k.fog;
            target[P.WIND] += w * k.wind;
            target[P.THUNDER] += w * k.thunder;
            if (k.fogColor != 0) {
                float tw = w * Math.max(k.clouds, k.fog);
                target[P.TINT_R] += tw * ((k.fogColor >> 16) & 0xFF) / 255f;
                target[P.TINT_G] += tw * ((k.fogColor >> 8) & 0xFF) / 255f;
                target[P.TINT_B] += tw * (k.fogColor & 0xFF) / 255f;
                tintW += tw;
            }
        }
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
        // Morning mist (and a little at night) where the climate breeds it, thickest down by the water.
        float mist = profile.dawnMist * WeatherProfile.mistHour(level.getDayTime());
        if (mist > 0.01f) {
            float low = Mth.clamp(1f - Math.max(0, here.getY() - 100) / 120f, 0.25f, 1f);
            target[P.FOG] = Math.max(target[P.FOG], target[P.FOG] + mist * 0.45f * low * (1f - target[P.FOG]));
        }
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
        return !(camera.getEntity() instanceof net.minecraft.world.entity.LivingEntity le
                && (le.hasEffect(MobEffects.BLINDNESS) || le.hasEffect(MobEffects.DARKNESS)));
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        float f = Mth.clamp(cur[P.FOG] * AtmosphereConfig.WEATHER_FOG_STRENGTH.get().floatValue(), 0f, 1f);
        if (f < 0.02f || !fogApplies(event.getCamera())) return;
        float vanillaFar = event.getFarPlaneDistance();
        float inv = 1f - f;
        float targetFar = 12f + 200f * inv * (float) Math.sqrt(inv);
        if (event.getMode() == net.minecraft.client.renderer.FogRenderer.FogMode.FOG_SKY) {
            // swallow the sky dome into the fog colour as the weather thickens
            float far = Math.min(vanillaFar, Mth.lerp(f, vanillaFar, targetFar * 0.8f));
            event.setNearPlaneDistance(0f);
            event.setFarPlaneDistance(far);
        } else {
            float far = Math.min(vanillaFar, targetFar);
            float near = Math.min(event.getNearPlaneDistance(), far * (0.05f + 0.55f * inv * inv));
            event.setNearPlaneDistance(near);
            event.setFarPlaneDistance(far);
        }
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        Minecraft mc = Minecraft.getInstance();
        if (fogApplies(event.getCamera()) && cur[P.TINT] > 0.01f) {
            float pt = (float) event.getPartialTick();
            float day = Mth.clamp(Mth.cos(mc.level.getTimeOfDay(pt) * Mth.TWO_PI) * 2.0f + 0.5f, 0f, 1f);
            float light = 0.06f + 0.94f * day;
            float t = Mth.clamp(cur[P.TINT], 0f, 1f) * 0.85f;
            event.setRed(Mth.lerp(t, event.getRed(), cur[P.TINT_R] * light));
            event.setGreen(Mth.lerp(t, event.getGreen(), cur[P.TINT_G] * light));
            event.setBlue(Mth.lerp(t, event.getBlue(), cur[P.TINT_B] * light));
        }
        fogR = event.getRed();
        fogG = event.getGreen();
        fogB = event.getBlue();
    }
}
