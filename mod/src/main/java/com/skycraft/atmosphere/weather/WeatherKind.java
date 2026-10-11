package com.skycraft.atmosphere.weather;

import java.util.Locale;

/**
 * One state of a region's local weather. Each kind is a bundle of presentation parameters the client blends
 * between (precipitation, overcast, fog, wind, thunder, fog tint); the server only decides which kind each region
 * has (see {@link RegionalWeather}). Kinds are synced by ordinal: append new ones at the end.
 */
public enum WeatherKind {
    //            precip         amount clouds fog   wind  thunder fog tint  spell length
    CLEAR(Precip.NONE, 0.00f, 0.00f, 0.00f, 0.15f, 0f, 0x000000, 1.20f),
    CLOUDY(Precip.NONE, 0.00f, 0.40f, 0.04f, 0.30f, 0f, 0x9AA3AD, 1.00f),
    OVERCAST(Precip.NONE, 0.00f, 0.72f, 0.14f, 0.35f, 0f, 0x8C939B, 1.00f),
    FOG(Precip.NONE, 0.00f, 0.55f, 0.85f, 0.05f, 0f, 0xA9B0B5, 0.55f),
    DRIZZLE(Precip.RAIN, 0.35f, 0.70f, 0.35f, 0.20f, 0f, 0x8D959D, 0.85f),
    RAIN(Precip.RAIN, 0.80f, 0.85f, 0.30f, 0.45f, 0f, 0x737B84, 0.80f),
    STORM(Precip.RAIN, 1.00f, 0.95f, 0.40f, 0.80f, 1f, 0x50575F, 0.50f),
    SNOW(Precip.SNOW, 0.60f, 0.75f, 0.35f, 0.30f, 0f, 0xC3CAD3, 0.90f),
    BLIZZARD(Precip.SNOW, 1.00f, 0.95f, 0.80f, 1.00f, 0f, 0xD0D6DE, 0.60f),
    ASHFALL(Precip.ASH, 0.40f, 0.60f, 0.35f, 0.25f, 0f, 0x7A6E62, 0.90f),
    ASH_STORM(Precip.ASH, 1.00f, 0.95f, 0.85f, 0.90f, 0f, 0x6E5A48, 0.55f),
    DUST(Precip.SAND, 0.30f, 0.25f, 0.30f, 0.60f, 0f, 0xC8A97A, 0.80f),
    SANDSTORM(Precip.SAND, 1.00f, 0.80f, 0.90f, 1.00f, 0f, 0xC39A62, 0.50f),
    /** Vvardenfell's blight storm: a choking ash storm stained the red of Red Mountain. */
    BLIGHT(Precip.ASH, 1.00f, 0.95f, 0.88f, 0.95f, 0f, 0x7A3A2C, 0.45f),
    /** Dry, roaring wind under broken cloud: the gales of Atmora, the Sea of Ghosts and the frozen north. */
    GALE(Precip.NONE, 0.00f, 0.45f, 0.06f, 1.00f, 0f, 0xA8B4C0, 0.60f);

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
    /** How long a spell of this kind lasts, relative to the climate's usual length (storms and fog pass sooner). */
    public final float spellLength;

    WeatherKind(Precip precip, float amount, float clouds, float fog, float wind, float thunder, int fogColor, float spellLength) {
        this.precip = precip;
        this.amount = amount;
        this.clouds = clouds;
        this.fog = fog;
        this.wind = wind;
        this.thunder = thunder;
        this.fogColor = fogColor;
        this.spellLength = spellLength;
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
