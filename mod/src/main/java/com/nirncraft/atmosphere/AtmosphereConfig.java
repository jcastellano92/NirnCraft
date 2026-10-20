package com.nirncraft.atmosphere;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Settings of the atmosphere module: client presentation in {@code config/nirncraft-atmosphere-client.toml}, the
 * regional weather simulation in the per-world {@code syncedconfig/nirncraft-atmosphere-server.toml}.
 */
public final class AtmosphereConfig {
    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec SERVER_SPEC;

    public static final ModConfigSpec.BooleanValue SKY;
    public static final ModConfigSpec.BooleanValue SECUNDA;
    public static final ModConfigSpec.BooleanValue NECROMANCER_MOON;
    public static final ModConfigSpec.BooleanValue MASSER_HALO;
    public static final ModConfigSpec.BooleanValue AURORA;
    public static final ModConfigSpec.BooleanValue AURORA_EVERYWHERE;
    public static final ModConfigSpec.DoubleValue AURORA_MAX_TEMPERATURE;
    public static final ModConfigSpec.DoubleValue AURORA_BRIGHTNESS;
    public static final ModConfigSpec.BooleanValue STARS;
    public static final ModConfigSpec.BooleanValue GALAXY;
    public static final ModConfigSpec.BooleanValue SKIP_WITH_SHADERS;
    public static final ModConfigSpec.IntValue CLOUD_HEIGHT;
    public static final ModConfigSpec.BooleanValue LEVEL_UP_STING;
    public static final ModConfigSpec.BooleanValue AURORA_BY_REGION;
    public static final ModConfigSpec.BooleanValue CONSTELLATIONS;
    public static final ModConfigSpec.BooleanValue SHOOTING_STARS;
    public static final ModConfigSpec.ConfigValue<String> METEOR_SHOWERS;
    public static final ModConfigSpec.DoubleValue METEOR_SHOWER_RATE;
    public static final ModConfigSpec.BooleanValue LOCAL_WEATHER;
    public static final ModConfigSpec.BooleanValue WEATHER_VISUALS;
    public static final ModConfigSpec.BooleanValue WEATHER_FOG;
    public static final ModConfigSpec.BooleanValue WEATHER_SKY;
    public static final ModConfigSpec.BooleanValue WEATHER_PARTICLES;
    public static final ModConfigSpec.DoubleValue WEATHER_FOG_STRENGTH;
    public static final ModConfigSpec.BooleanValue CLOUDS;
    public static final ModConfigSpec.BooleanValue CLOUDS_SKIP_SHADERS;
    public static final ModConfigSpec.IntValue CLOUD_DISTANCE;
    public static final ModConfigSpec.BooleanValue DISTANT_WEATHER;
    public static final ModConfigSpec.BooleanValue DISTANT_THUNDER;

    public static final ModConfigSpec.BooleanValue REGIONAL_WEATHER;
    public static final ModConfigSpec.BooleanValue DRIVE_VANILLA_WEATHER;
    public static final ModConfigSpec.DoubleValue WEATHER_DURATION_SCALE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Skyrim sky: Masser & Secunda, aurora, stars. The moons' surfaces are NirnCraft's own bakes",
                "(assets/nirncraft/textures/environment/masser_albedo.png, masser_relief.png, secunda_*: tools/dev/moon_gen.py);",
                "a resource pack can override them.").push("sky");
        SKY = b.comment("Master switch for every extra sky element below.").define("enabled", true);
        SECUNDA = b.comment("Draw Secunda, the small white moon, on its own tilted orbit.").define("secunda", true);
        NECROMANCER_MOON = b.comment("The Necromancer's Moon (from 3E 417): a small dark body that hides the planet Arkay",
                "for a day every eight days.").define("necromancerMoon", false);
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
        METEOR_SHOWERS = b.comment("Nights of meteor showers: days of the year (0..365), comma separated; empty: none.")
                .define("meteorShowers", "42,221,347");
        METEOR_SHOWER_RATE = b.comment("How many times more meteors fall on a shower's night.")
                .defineInRange("meteorShowerRate", 8.0, 1.0, 60.0);
        SKIP_WITH_SHADERS = b.comment("Don't draw the extra sky while an Oculus/Iris shader pack is active.")
                .define("skipWithShaders", false);
        CLOUD_HEIGHT = b.comment("Overworld cloud layer altitude (vanilla 192). Tamriel's mountains reach far above y 192, so the clouds sit",
                "higher; shader packs that follow the cloud height (Complementary) move with it.").defineInRange("cloudHeight", 330, 64, 2048);
        b.pop();
        b.push("weather");
        WEATHER_VISUALS = b.comment("Draw the weather: clouds, rain and snow, fog banks, storm curtains. Off while the world itself",
                "(terrain, flora, towns, the far field) is being matched near to far: the sky, sun, moons, distant haze and",
                "all lighting stay. Comes back once weather is positional and deterministic.").define("visuals", false);
        LOCAL_WEATHER = b.comment("Render the server's regional weather where you stand (local rain, snow, ash and sand storms,",
                "fog and overcast) instead of vanilla's one global weather.").define("localWeather", true);
        WEATHER_FOG = b.comment("Regional fog (mist, blizzards, ash and sand storms).").define("fog", true);
        WEATHER_FOG_STRENGTH = b.comment("Multiplier for regional fog density.").defineInRange("fogStrength", 1.0, 0.0, 2.0);
        WEATHER_SKY = b.comment("Overcast skies darken and tint with the regional weather.").define("overcastSky", true);
        WEATHER_PARTICLES = b.comment("Snow, ash and sand precipitation particles for regional weather vanilla can't draw.").define("precipitationParticles", true);
        b.pop();
        b.push("clouds");
        CLOUDS = b.comment("NirnCraft's own clouds replace vanilla's in the overworld:",
                "cumulus, cirrus, overcast decks, storm towers, fog banks, peak caps, ash and sand, chosen by each region's",
                "weather and climate. They follow Video Settings > Clouds (Fast = fewer puffs, Off = none).").define("nirncraftClouds", true);
        CLOUDS_SKIP_SHADERS = b.comment("Don't draw NirnCraft clouds while an Oculus/Iris shader pack is active.")
                .define("skipWithShaders", false);
        CLOUD_DISTANCE = b.comment("How far out clouds and distant weather are drawn, in blocks.").defineInRange("cloudDistance", 1800, 400, 3200);
        DISTANT_WEATHER = b.comment("Weather you can see in other regions: rain curtains under storms, snow squalls over mountains,",
                "lightning, ash plumes and sandstorm walls on the horizon.").define("distantWeather", true);
        DISTANT_THUNDER = b.comment("Distant lightning is followed by thunder, delayed and softened by its distance.").define("distantThunder", true);
        b.pop();
        b.push("music");
        LEVEL_UP_STING = b.comment("Play the short level-up fanfare (nirncraft:music.level_up) when your character levels up.")
                .define("levelUpSting", true);
        b.pop();
        CLIENT_SPEC = b.build();

        ModConfigSpec.Builder s = new ModConfigSpec.Builder();
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
