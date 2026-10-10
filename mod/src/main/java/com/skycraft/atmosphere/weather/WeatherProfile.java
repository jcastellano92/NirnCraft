package com.skycraft.atmosphere.weather;

import com.skycraft.tamriel.Region;
import net.minecraft.util.RandomSource;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A climate: which {@link WeatherKind}s a region rolls and how often, how long each spell lasts, where rain turns
 * to snow, and the light ambient particles it always has. A region names its profile in {@link Region#weather()}
 * ({@code regions.json}); unknown names are matched by keyword so new regions always get something sensible.
 */
public final class WeatherProfile {
    /** Light particles the region shows even in clear weather (atmosphere/visual client). */
    public enum Ambient { NONE, SNOW, ASH, SAND, MARSH }

    private static final Map<String, WeatherProfile> BY_ID = new HashMap<>();

    public static final WeatherProfile SKYRIM_COLD = register("skyrim_cold", Ambient.SNOW, -10_000, true, 3600, 12000,
            WeatherKind.CLEAR, 3, WeatherKind.CLOUDY, 3, WeatherKind.OVERCAST, 2, WeatherKind.SNOW, 4, WeatherKind.BLIZZARD, 2,
            WeatherKind.FOG, 1);
    public static final WeatherProfile SKYRIM_TEMPERATE = register("skyrim_temperate", Ambient.NONE, 190, true, 4800, 14400,
            WeatherKind.CLEAR, 5, WeatherKind.CLOUDY, 4, WeatherKind.OVERCAST, 3, WeatherKind.FOG, 1, WeatherKind.DRIZZLE, 2,
            WeatherKind.RAIN, 3, WeatherKind.STORM, 1);
    public static final WeatherProfile ASH = register("ash", Ambient.ASH, 10_000, false, 3600, 12000,
            WeatherKind.CLEAR, 1, WeatherKind.CLOUDY, 1, WeatherKind.OVERCAST, 2, WeatherKind.ASHFALL, 4, WeatherKind.ASH_STORM, 2);
    public static final WeatherProfile DESERT = register("desert", Ambient.SAND, 10_000, false, 4800, 14400,
            WeatherKind.CLEAR, 6, WeatherKind.CLOUDY, 1, WeatherKind.DUST, 3, WeatherKind.SANDSTORM, 2);
    public static final WeatherProfile SWAMP = register("swamp", Ambient.MARSH, 10_000, false, 3600, 12000,
            WeatherKind.CLEAR, 1, WeatherKind.CLOUDY, 2, WeatherKind.OVERCAST, 3, WeatherKind.FOG, 4, WeatherKind.DRIZZLE, 3,
            WeatherKind.RAIN, 2, WeatherKind.STORM, 1);
    public static final WeatherProfile JUNGLE = register("jungle", Ambient.NONE, 10_000, false, 3600, 9600,
            WeatherKind.CLEAR, 2, WeatherKind.CLOUDY, 3, WeatherKind.FOG, 1, WeatherKind.DRIZZLE, 1, WeatherKind.RAIN, 4,
            WeatherKind.STORM, 2);
    public static final WeatherProfile MARITIME = register("maritime", Ambient.NONE, 240, false, 3600, 12000,
            WeatherKind.CLEAR, 3, WeatherKind.CLOUDY, 3, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 2, WeatherKind.DRIZZLE, 2,
            WeatherKind.RAIN, 2, WeatherKind.STORM, 1);
    public static final WeatherProfile FROZEN = register("frozen", Ambient.SNOW, -10_000, true, 3600, 12000,
            WeatherKind.CLEAR, 2, WeatherKind.CLOUDY, 2, WeatherKind.SNOW, 3, WeatherKind.BLIZZARD, 4, WeatherKind.FOG, 1);
    public static final WeatherProfile TEMPERATE = register("temperate", Ambient.NONE, 220, false, 4800, 14400,
            WeatherKind.CLEAR, 6, WeatherKind.CLOUDY, 4, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 1, WeatherKind.DRIZZLE, 2,
            WeatherKind.RAIN, 3, WeatherKind.STORM, 1);
    public static final WeatherProfile ALPINE = register("alpine", Ambient.SNOW, 150, true, 3600, 12000,
            WeatherKind.CLEAR, 4, WeatherKind.CLOUDY, 3, WeatherKind.OVERCAST, 2, WeatherKind.FOG, 2, WeatherKind.SNOW, 3,
            WeatherKind.BLIZZARD, 1, WeatherKind.RAIN, 1);
    public static final WeatherProfile SAVANNA = register("savanna", Ambient.NONE, 10_000, false, 4800, 14400,
            WeatherKind.CLEAR, 7, WeatherKind.CLOUDY, 3, WeatherKind.DUST, 1, WeatherKind.RAIN, 1, WeatherKind.STORM, 1);
    public static final WeatherProfile CRYSTAL = register("crystal", Ambient.NONE, 10_000, false, 4800, 14400,
            WeatherKind.CLEAR, 6, WeatherKind.CLOUDY, 3, WeatherKind.FOG, 2, WeatherKind.DRIZZLE, 2);
    public static final WeatherProfile MUSHROOM = register("mushroom", Ambient.MARSH, 10_000, false, 3600, 12000,
            WeatherKind.CLEAR, 2, WeatherKind.CLOUDY, 2, WeatherKind.FOG, 3, WeatherKind.DRIZZLE, 2, WeatherKind.RAIN, 1);
    /** Other dimensions: no local weather at all. */
    public static final WeatherProfile NONE = register("none", Ambient.NONE, 10_000, false, 24000, 24000, WeatherKind.CLEAR, 1);

    public final String id;
    public final Ambient ambient;
    /** Rain falls as snow above this Y. */
    public final int snowLine;
    /** Cold northern sky: aurora on clear nights. */
    public final boolean aurora;
    public final int minTicks;
    public final int maxTicks;
    private final WeatherKind[] kinds;
    private final int[] weights;
    private final int totalWeight;

    private WeatherProfile(String id, Ambient ambient, int snowLine, boolean aurora, int minTicks, int maxTicks, Object[] table) {
        this.id = id;
        this.ambient = ambient;
        this.snowLine = snowLine;
        this.aurora = aurora;
        this.minTicks = minTicks;
        this.maxTicks = maxTicks;
        this.kinds = new WeatherKind[table.length / 2];
        this.weights = new int[table.length / 2];
        int total = 0;
        for (int i = 0; i < kinds.length; i++) {
            kinds[i] = (WeatherKind) table[i * 2];
            weights[i] = (Integer) table[i * 2 + 1];
            total += weights[i];
        }
        this.totalWeight = total;
    }

    private static WeatherProfile register(String id, Ambient ambient, int snowLine, boolean aurora, int minTicks, int maxTicks, Object... table) {
        WeatherProfile p = new WeatherProfile(id, ambient, snowLine, aurora, minTicks, maxTicks, table);
        BY_ID.put(id, p);
        return p;
    }

    /** True if this profile can ever roll {@code kind}. */
    public boolean allows(WeatherKind kind) {
        for (WeatherKind k : kinds) if (k == kind) return true;
        return false;
    }

    /** A weighted random kind, avoiding an immediate repeat of {@code previous} (one re-roll). */
    public WeatherKind roll(RandomSource random, WeatherKind previous) {
        WeatherKind k = pick(random);
        if (k == previous && kinds.length > 1) k = pick(random);
        // Storms rarely follow clear skies directly: the sky clouds over first.
        if (previous == WeatherKind.CLEAR && (k == WeatherKind.STORM || k == WeatherKind.BLIZZARD
                || k == WeatherKind.ASH_STORM || k == WeatherKind.SANDSTORM) && random.nextInt(3) > 0) {
            k = allows(WeatherKind.CLOUDY) ? WeatherKind.CLOUDY : k;
        }
        return k;
    }

    private WeatherKind pick(RandomSource random) {
        int r = random.nextInt(Math.max(1, totalWeight));
        for (int i = 0; i < kinds.length; i++) {
            r -= weights[i];
            if (r < 0) return kinds[i];
        }
        return kinds[0];
    }

    /** The wettest kind this profile has (what {@code /weather rain} maps to). */
    public WeatherKind wetKind(boolean thunder) {
        // thunder: the strongest precipitation; plain rain: the one closest to a steady shower
        float target = thunder ? 1.0f : 0.7f;
        WeatherKind best = WeatherKind.CLEAR;
        float bestScore = Float.MAX_VALUE;
        for (WeatherKind k : kinds) {
            if (k.precip == WeatherKind.Precip.NONE) continue;
            float score = Math.abs(k.amount - target) - (thunder ? k.thunder : 0f);
            if (score < bestScore) {
                bestScore = score;
                best = k;
            }
        }
        return best;
    }

    public int duration(RandomSource random) {
        return minTicks + random.nextInt(Math.max(1, maxTicks - minTicks + 1));
    }

    // ------------------------------------------------------------------ lookup

    public static WeatherProfile of(Region region) {
        if (region == null || "dimension".equals(region.kind())) return NONE;
        return byName(region.weather());
    }

    /** Exact id first, then keyword matching (so {@code "skyrim_snow"} or {@code "volcanic"} still work). */
    public static WeatherProfile byName(String name) {
        if (name == null || name.isEmpty()) return TEMPERATE;
        String n = name.toLowerCase(Locale.ROOT);
        WeatherProfile exact = BY_ID.get(n);
        if (exact != null) return exact;
        if (has(n, "frozen", "ice", "arctic", "glacier", "atmora")) return FROZEN;
        if (has(n, "cold", "snow", "tundra", "winter", "pale")) return SKYRIM_COLD;
        if (has(n, "ash", "volcan", "lava", "red_mountain")) return ASH;
        if (has(n, "desert", "arid", "sand", "dune", "badland")) return DESERT;
        if (has(n, "swamp", "marsh", "bog", "mire", "fen")) return SWAMP;
        if (has(n, "jungle", "rainforest", "tropic", "monsoon")) return JUNGLE;
        if (has(n, "mushroom", "fungal")) return MUSHROOM;
        if (has(n, "sea", "coast", "maritime", "ocean", "island", "isle")) return MARITIME;
        if (has(n, "alpine", "mountain", "peak", "highland")) return ALPINE;
        if (has(n, "savanna", "steppe", "dry", "plain")) return SAVANNA;
        if (has(n, "crystal", "mystic", "fair", "summer")) return CRYSTAL;
        if (has(n, "none", "dimension", "nether", "end", "oblivion", "sovngarde")) return NONE;
        if (has(n, "skyrim")) return SKYRIM_TEMPERATE;
        return TEMPERATE;
    }

    private static boolean has(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }
}
