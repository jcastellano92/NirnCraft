package com.skycraft.atmosphere.weather;

import java.util.Locale;

/**
 * One state of a region's local weather. Each kind is a bundle of presentation parameters the client blends
 * between (precipitation, overcast, fog, wind, thunder, fog tint); the server only decides which kind each region
 * has (see {@link RegionalWeather}).
 */
public enum WeatherKind {
    //            precip         amount clouds fog   wind  thunder fog tint (day)
    CLEAR(Precip.NONE, 0.00f, 0.00f, 0.00f, 0.15f, 0f, 0x000000),
    CLOUDY(Precip.NONE, 0.00f, 0.40f, 0.04f, 0.30f, 0f, 0x9AA3AD),
    OVERCAST(Precip.NONE, 0.00f, 0.72f, 0.14f, 0.35f, 0f, 0x8C939B),
    FOG(Precip.NONE, 0.00f, 0.55f, 0.85f, 0.05f, 0f, 0xA9B0B5),
    DRIZZLE(Precip.RAIN, 0.35f, 0.70f, 0.35f, 0.20f, 0f, 0x8D959D),
    RAIN(Precip.RAIN, 0.80f, 0.85f, 0.30f, 0.45f, 0f, 0x737B84),
    STORM(Precip.RAIN, 1.00f, 0.95f, 0.40f, 0.80f, 1f, 0x50575F),
    SNOW(Precip.SNOW, 0.60f, 0.75f, 0.35f, 0.30f, 0f, 0xC3CAD3),
    BLIZZARD(Precip.SNOW, 1.00f, 0.95f, 0.80f, 1.00f, 0f, 0xD0D6DE),
    ASHFALL(Precip.ASH, 0.40f, 0.60f, 0.35f, 0.25f, 0f, 0x7A6E62),
    ASH_STORM(Precip.ASH, 1.00f, 0.95f, 0.85f, 0.90f, 0f, 0x6E5A48),
    DUST(Precip.SAND, 0.30f, 0.25f, 0.30f, 0.60f, 0f, 0xC8A97A),
    SANDSTORM(Precip.SAND, 1.00f, 0.80f, 0.90f, 1.00f, 0f, 0xC39A62);

    /** What falls from the sky. RAIN becomes snow in snowy biomes and above the profile's snow line. */
    public enum Precip { NONE, RAIN, SNOW, ASH, SAND }

    public final Precip precip;
    public final float amount;
    public final float clouds;
    public final float fog;
    public final float wind;
    public final float thunder;
    /** Day-time fog/sky tint (0 = keep the vanilla colour). */
    public final int fogColor;

    WeatherKind(Precip precip, float amount, float clouds, float fog, float wind, float thunder, int fogColor) {
        this.precip = precip;
        this.amount = amount;
        this.clouds = clouds;
        this.fog = fog;
        this.wind = wind;
        this.thunder = thunder;
        this.fogColor = fogColor;
    }

    /** Vanilla's global rain flag should be on for this kind (crops, fire, fishing, lightning). */
    public boolean wet() {
        return precip == Precip.RAIN || precip == Precip.SNOW;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static WeatherKind byId(String id) {
        for (WeatherKind k : values()) if (k.id().equals(id)) return k;
        return null;
    }

    public static WeatherKind byOrdinal(int ordinal) {
        WeatherKind[] v = values();
        return ordinal >= 0 && ordinal < v.length ? v[ordinal] : CLEAR;
    }
}
