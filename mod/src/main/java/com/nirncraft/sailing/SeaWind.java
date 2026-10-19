package com.nirncraft.sailing;

import com.nirncraft.atmosphere.AtmosphereConfig;
import com.nirncraft.atmosphere.weather.RegionalWeather;
import com.nirncraft.atmosphere.weather.WeatherKind;
import com.nirncraft.tamriel.TamrielMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * The wind at sea (server side). Direction comes from the region's weather spell ({@link RegionalWeather}), the same
 * wind the client draws snow and ash with; strength from the weather kind (a storm blows hard, a clear day gently),
 * gusts, and how open the water is (sheltered harbours are calmer than the open ocean). Ships sync the result.
 */
public final class SeaWind {
    private SeaWind() {}

    /** Wind at (x, z): {@code [direction it blows towards in degrees, strength 0..1.5]}. */
    public static float[] at(ServerLevel level, double x, double z) {
        long t = level.getGameTime();
        BlockPos pos = BlockPos.containing(x, 64, z);
        float dir;
        float strength;
        WeatherKind kind = WeatherKind.CLEAR;
        int regional = AtmosphereConfig.REGIONAL_WEATHER.get() ? RegionalWeather.windAt(level, pos) : -1;
        if (regional >= 0) {
            kind = RegionalWeather.kindAt(level, pos);
            dir = regional;
        } else {
            // no regional weather: a prevailing wind that slowly veers through the days
            dir = (float) ((t / 24000.0 * 47.0 + Math.sin(t / 9000.0) * 60.0) % 360.0);
            if (level.isThundering()) kind = WeatherKind.STORM;
            else if (level.isRaining()) kind = WeatherKind.RAIN;
        }
        strength = 0.42f + kind.wind * 0.6f;
        // gusts and veering
        strength += 0.10f * (float) Math.sin(t / 170.0 + x * 0.0013) + 0.05f * (float) Math.sin(t / 47.0 + z * 0.002);
        dir += 14f * (float) Math.sin(t / 900.0 + x * 0.0004);
        if (level.dimension() == Level.OVERWORLD) {
            float coast = TamrielMap.sampleScratch(Mth.floor(x), Mth.floor(z)).coastDistance;
            strength *= Mth.clamp(0.7f - coast / 600f, 0.6f, 1.1f);
        }
        strength *= SailingConfig.WIND_STRENGTH.get().floatValue();
        return new float[]{Mth.wrapDegrees(dir), Mth.clamp(strength, 0f, 1.5f)};
    }

    /** True for weather that batters ships at sea. */
    public static boolean stormy(WeatherKind kind) {
        return kind == WeatherKind.STORM || kind == WeatherKind.BLIZZARD || kind == WeatherKind.ASH_STORM
                || kind == WeatherKind.SANDSTORM;
    }

    public static WeatherKind weatherAt(ServerLevel level, BlockPos pos) {
        if (AtmosphereConfig.REGIONAL_WEATHER.get()) return RegionalWeather.kindAt(level, pos);
        return level.isThundering() ? WeatherKind.STORM : level.isRaining() ? WeatherKind.RAIN : WeatherKind.CLEAR;
    }
}
