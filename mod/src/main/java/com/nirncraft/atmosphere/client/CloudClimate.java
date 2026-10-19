package com.nirncraft.atmosphere.client;

import com.nirncraft.atmosphere.weather.WeatherKind;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * What the sky looks like over each region, for {@link CloudRenderer}: every synced region's {@link WeatherKind} is
 * turned into cloud parameters (how much cumulus, overcast deck, storm towers, cirrus, rain, snow, ash, sand, fog,
 * darkness, blight and thunder), eased over about twenty seconds so distant clouds build up and break apart instead of
 * popping. Regions the server hasn't sent weather for read as fair.
 */
final class CloudClimate {
    static final int CU = 0;        // fair-weather cumulus coverage
    static final int ST = 1;        // stratus / overcast deck
    static final int CB = 2;        // towering storm clouds (cumulonimbus)
    static final int CI = 3;        // high cirrus
    static final int RAIN = 4;
    static final int SNOW = 5;
    static final int ASH = 6;
    static final int SAND = 7;
    static final int FOG = 8;
    static final int DARK = 9;      // how grey and heavy the clouds are
    static final int BLIGHT = 10;   // red tint of blight storms
    static final int THUNDER = 11;
    static final int GALE = 12;     // torn, fast cloud
    static final int COUNT = 13;

    private static final float[][] KIND = new float[WeatherKind.values().length][COUNT];
    /** Eased parameters per region id (render thread only). */
    private static final Map<String, float[]> EASED = new HashMap<>();
    private static final float EASE = 0.004f;

    static {
        //   kind                     CU    ST    CB    CI   RAIN  SNOW  ASH   SAND  FOG   DARK  BLIGHT THUNDER GALE
        set(WeatherKind.CLEAR,       0.24f, 0f,   0.14f, 0.30f, 0f, 0f,  0f,   0f,   0f,   0f,   0f,   0f,   0f);
        set(WeatherKind.CLOUDY,      0.58f, 0.08f, 0.44f, 0.42f, 0f, 0f, 0f,   0f,   0.02f, 0.12f, 0f, 0f,   0f);
        set(WeatherKind.OVERCAST,    0.18f, 0.92f, 0.22f, 0.10f, 0f, 0f, 0f,   0f,   0.10f, 0.45f, 0f, 0f,   0f);
        set(WeatherKind.FOG,         0.05f, 0.60f, 0f,  0f,   0f,   0f,   0f,   0f,   1f,   0.15f, 0f,  0f,   0f);
        set(WeatherKind.DRIZZLE,     0.15f, 0.78f, 0.28f, 0f,  0.48f, 0f, 0f,  0f,   0.25f, 0.45f, 0f, 0f,   0f);
        set(WeatherKind.RAIN,        0.32f, 0.76f, 0.64f, 0f,  0.88f, 0f, 0f,  0f,   0.22f, 0.68f, 0f, 0f,   0f);
        set(WeatherKind.STORM,       0.18f, 0.88f, 1.00f, 0f,  1f,   0f,  0f,   0f,   0.28f, 0.94f, 0f, 1f,   0.35f);
        set(WeatherKind.SNOW,        0.10f, 0.90f, 0.18f, 0f,  0f,   0.78f, 0f, 0f,  0.42f, 0.28f, 0f, 0f,   0f);
        set(WeatherKind.BLIZZARD,    0.05f, 0.96f, 0.30f, 0f,  0f,   1f,   0f,   0f,   0.85f, 0.35f, 0f, 0f,   0.75f);
        set(WeatherKind.ASHFALL,     0.30f, 0.50f, 0.10f, 0f,  0f,   0f,   0.60f, 0f,  0.30f, 0.35f, 0f, 0f,   0f);
        set(WeatherKind.ASH_STORM,   0.20f, 0.70f, 0.55f, 0f,  0f,   0f,   1f,   0f,   0.80f, 0.78f, 0f, 0.35f, 0.55f);
        set(WeatherKind.DUST,        0.10f, 0f,   0f,   0.25f, 0f,  0f,   0f,   0.52f, 0.28f, 0.05f, 0f, 0f,   0.35f);
        set(WeatherKind.SANDSTORM,   0.06f, 0.04f, 0f,  0.15f, 0f,  0f,   0f,   1f,   0.85f, 0.18f, 0f, 0f,   0.85f);
        set(WeatherKind.BLIGHT,      0.20f, 0.65f, 0.55f, 0f,  0f,   0f,   0.90f, 0f,  0.85f, 0.72f, 1f, 0.4f, 0.55f);
        set(WeatherKind.GALE,        0.45f, 0.20f, 0.25f, 0.70f, 0f, 0f,  0f,   0f,   0.05f, 0.10f, 0f, 0f,   1f);
    }

    private CloudClimate() {}

    private static void set(WeatherKind kind, float... values) {
        System.arraycopy(values, 0, KIND[kind.ordinal()], 0, COUNT);
    }

    /** The parameters a region settles at under {@code kind}. */
    static float[] target(WeatherKind kind) {
        return KIND[(kind == null ? WeatherKind.CLEAR : kind).ordinal()];
    }

    /** Eases every known region toward its current weather; forgets regions the server dropped. Once per client tick. */
    static void tick() {
        for (Iterator<String> it = EASED.keySet().iterator(); it.hasNext(); ) {
            if (ClientWeather.kindOf(it.next()) == null) it.remove();
        }
        for (String id : ClientWeather.regionIds()) {
            float[] t = target(ClientWeather.kindOf(id));
            float[] cur = EASED.get(id);
            if (cur == null) {
                // nothing known (login, teleport): start from the weather already formed; otherwise grow out of the
                // average of the sky around it so crossing into a new region never pops the clouds
                if (EASED.isEmpty()) {
                    EASED.put(id, t.clone());
                } else {
                    float[] mean = new float[COUNT];
                    for (float[] other : EASED.values()) for (int i = 0; i < COUNT; i++) mean[i] += other[i] / EASED.size();
                    EASED.put(id, mean);
                }
                continue;
            }
            for (int i = 0; i < COUNT; i++) cur[i] += (t[i] - cur[i]) * EASE;
        }
    }

    static void clear() {
        EASED.clear();
    }

    /** Every region jumps to its current weather (tests). */
    static void snapAll() {
        for (String id : ClientWeather.regionIds()) {
            EASED.put(id, target(ClientWeather.kindOf(id)).clone());
        }
        EASED.forEach((id, cur) -> System.arraycopy(target(ClientWeather.kindOf(id)), 0, cur, 0, COUNT));
    }

    /** A copy of the eased table for the cloud builder thread. */
    static Map<String, float[]> snapshot() {
        Map<String, float[]> copy = new HashMap<>(EASED.size() * 2);
        EASED.forEach((id, v) -> copy.put(id, v.clone()));
        return copy;
    }
}
