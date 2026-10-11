package com.skycraft.atmosphere;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Settings of the atmosphere module: client presentation in {@code config/skycraft-atmosphere-client.toml}, the
 * regional weather simulation in the per-world {@code serverconfig/skycraft-atmosphere-server.toml}.
 */
public final class AtmosphereConfig {
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final ForgeConfigSpec SERVER_SPEC;

    public static final ForgeConfigSpec.BooleanValue SKY;
    public static final ForgeConfigSpec.BooleanValue SECUNDA;
    public static final ForgeConfigSpec.BooleanValue MASSER_HALO;
    public static final ForgeConfigSpec.BooleanValue AURORA;
    public static final ForgeConfigSpec.BooleanValue AURORA_EVERYWHERE;
    public static final ForgeConfigSpec.DoubleValue AURORA_MAX_TEMPERATURE;
    public static final ForgeConfigSpec.DoubleValue AURORA_BRIGHTNESS;
    public static final ForgeConfigSpec.BooleanValue STARS;
    public static final ForgeConfigSpec.BooleanValue GALAXY;
    public static final ForgeConfigSpec.BooleanValue SKIP_WITH_SHADERS;
    public static final ForgeConfigSpec.IntValue CLOUD_HEIGHT;
    public static final ForgeConfigSpec.BooleanValue LEVEL_UP_STING;
    public static final ForgeConfigSpec.BooleanValue AURORA_BY_REGION;
    public static final ForgeConfigSpec.BooleanValue CONSTELLATIONS;
    public static final ForgeConfigSpec.BooleanValue SHOOTING_STARS;
    public static final ForgeConfigSpec.BooleanValue LOCAL_WEATHER;
    public static final ForgeConfigSpec.BooleanValue WEATHER_FOG;
    public static final ForgeConfigSpec.BooleanValue WEATHER_SKY;
    public static final ForgeConfigSpec.BooleanValue WEATHER_PARTICLES;
    public static final ForgeConfigSpec.DoubleValue WEATHER_FOG_STRENGTH;

    public static final ForgeConfigSpec.BooleanValue REGIONAL_WEATHER;
    public static final ForgeConfigSpec.BooleanValue DRIVE_VANILLA_WEATHER;
    public static final ForgeConfigSpec.DoubleValue WEATHER_DURATION_SCALE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Skyrim sky: Masser & Secunda, aurora, stars. Masser itself replaces the vanilla moon texture",
                "(assets/minecraft/textures/environment/moon_phases.png); a resource pack can override it.").push("sky");
        SKY = b.comment("Master switch for every extra sky element below.").define("enabled", true);
        SECUNDA = b.comment("Draw Secunda, the small white moon, on its own tilted orbit.").define("secunda", true);
        MASSER_HALO = b.comment("Faint reddish glow around Masser at night.").define("masserHalo", true);
        AURORA = b.comment("Aurora borealis on clear nights in cold biomes.").define("aurora", true);
        AURORA_EVERYWHERE = b.comment("Show the aurora in every biome, not just cold ones.").define("auroraEverywhere", false);
        AURORA_MAX_TEMPERATURE = b.comment("Biomes with a base temperature at or below this count as cold (taiga 0.25, snowy plains 0.0).")
                .defineInRange("auroraMaxTemperature", 0.35, -2.0, 2.0);
        AURORA_BRIGHTNESS = b.comment("Aurora brightness multiplier.").defineInRange("auroraBrightness", 1.0, 0.0, 3.0);
        AURORA_BY_REGION = b.comment("Show the aurora where the region's climate has a northern sky (Skyrim, Atmora, cold peaks);",
                "off = use the biome temperature limit below.").define("auroraByRegion", true);
        STARS = b.comment("Denser, coloured, gently twinkling star field at night.").define("stars", true);
        GALAXY = b.comment("Faint galaxy band across the night sky.").define("galaxy", true);
        CONSTELLATIONS = b.comment("The thirteen birthsign constellations, faintly traced among the stars.").define("constellations", true);
        SHOOTING_STARS = b.comment("Occasional shooting stars on clear nights.").define("shootingStars", true);
        SKIP_WITH_SHADERS = b.comment("Don't draw the extra sky while an Oculus/Iris shader pack is active (shader packs draw their own sky).")
                .define("skipWithShaders", true);
        CLOUD_HEIGHT = b.comment("Overworld cloud layer altitude (vanilla 192). Tamriel's mountains reach far above y 192, so the clouds sit",
                "higher; shader packs that follow the cloud height (Complementary) move with it.").defineInRange("cloudHeight", 330, 64, 2048);
        b.pop();
        b.push("weather");
        LOCAL_WEATHER = b.comment("Render the server's regional weather where you stand (local rain, snow, ash and sand storms,",
                "fog and overcast) instead of vanilla's one global weather.").define("localWeather", true);
        WEATHER_FOG = b.comment("Regional fog (mist, blizzards, ash and sand storms).").define("fog", true);
        WEATHER_FOG_STRENGTH = b.comment("Multiplier for regional fog density.").defineInRange("fogStrength", 1.0, 0.0, 2.0);
        WEATHER_SKY = b.comment("Overcast skies darken and tint with the regional weather.").define("overcastSky", true);
        WEATHER_PARTICLES = b.comment("Snow, ash and sand precipitation particles for regional weather vanilla can't draw.").define("precipitationParticles", true);
        b.pop();
        b.push("music");
        LEVEL_UP_STING = b.comment("Play the short level-up fanfare (skycraft:music.level_up) when your character levels up.")
                .define("levelUpSting", true);
        b.pop();
        CLIENT_SPEC = b.build();

        ForgeConfigSpec.Builder s = new ForgeConfigSpec.Builder();
        s.push("weather");
        REGIONAL_WEATHER = s.comment("Each region rolls its own weather from its climate (regions.json \"weather\") and syncs it to clients.")
                .define("regionalWeather", true);
        DRIVE_VANILLA_WEATHER = s.comment("Vanilla's global rain/thunder (crops, fire, fishing, lightning) follows the weather where most players are.",
                "A /weather command is adopted into the players' regions.").define("driveVanillaWeather", true);
        WEATHER_DURATION_SCALE = s.comment("Multiplier for how long each weather spell lasts.").defineInRange("durationScale", 1.0, 0.1, 20.0);
        s.pop();
        SERVER_SPEC = s.build();
    }

    private AtmosphereConfig() {}
}
