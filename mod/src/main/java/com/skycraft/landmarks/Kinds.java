package com.skycraft.landmarks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The kind table ({@code skycraft/landmarks/kinds.json}), loaded once; thread-safe afterwards. */
public final class Kinds {
    private static final Map<String, Kind> BY_ID = new LinkedHashMap<>();
    private static final Map<Tier, List<Kind>> BY_TIER = new EnumMap<>(Tier.class);

    static {
        for (Tier t : Tier.VALUES) BY_TIER.put(t, new ArrayList<>());
        try (InputStream in = Kinds.class.getResourceAsStream("/skycraft/landmarks/kinds.json")) {
            if (in == null) {
                Skycraft.LOGGER.warn("Skycraft landmarks: kinds.json is missing");
            } else {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (JsonElement e : root.getAsJsonArray("kinds")) {
                    try {
                        Kind k = parse(e.getAsJsonObject());
                        if (BY_ID.putIfAbsent(k.id(), k) != null) {
                            Skycraft.LOGGER.warn("Skycraft landmarks: duplicate kind {}", k.id());
                            continue;
                        }
                        BY_TIER.get(k.tier()).add(k);
                    } catch (RuntimeException ex) {
                        Skycraft.LOGGER.warn("Skycraft landmarks: bad kind {}", e, ex);
                    }
                }
            }
        } catch (Exception ex) {
            Skycraft.LOGGER.warn("Skycraft landmarks: could not read kinds.json", ex);
        }
        Skycraft.LOGGER.info("Skycraft landmarks: {} kinds (S {}, M {}, L {}, XL {})", BY_ID.size(), BY_TIER.get(Tier.S).size(),
                BY_TIER.get(Tier.M).size(), BY_TIER.get(Tier.L).size(), BY_TIER.get(Tier.XL).size());
    }

    private Kinds() {}

    private static Kind parse(JsonObject o) {
        Tier tier = Tier.byId(o.get("tier").getAsString());
        if (tier == null) throw new IllegalArgumentException("tier");
        // placement must have a world reason: gen_kinds.py writes purpose/context/clue for every kind
        if (!o.has("purpose") || !o.has("context")) throw new IllegalArgumentException("no purpose/context");
        Map<String, Double> w = new HashMap<>();
        if (o.has("w")) for (var en : o.getAsJsonObject("w").entrySet()) w.put(en.getKey(), en.getValue().getAsDouble());
        int radius = Math.min(tier.radiusCap, o.has("radius") ? o.get("radius").getAsInt() : tier.radiusCap);
        return new Kind(o.get("id").getAsString(), tier, o.get("builder").getAsString(),
                o.has("style") ? o.get("style").getAsString() : "", radius, Map.copyOf(w),
                strings(o, "provinces"), strings(o, "not"), strings(o, "needs"), new ArrayList<>(strings(o, "cast")),
                o.has("secret") ? o.get("secret").getAsDouble() : 0.0,
                o.has("noun") ? o.get("noun").getAsString() : "", o.has("note") ? o.get("note").getAsString() : "",
                o.has("sign") && o.get("sign").getAsBoolean(), o.has("spread") ? o.get("spread").getAsInt() : defaultSpread(tier),
                o.has("params") ? o.getAsJsonObject("params") : new JsonObject());
    }

    private static int defaultSpread(Tier t) {
        return switch (t) {
            case S -> 3;
            case M -> 5;
            case L -> 12;
            case XL -> 18;
        };
    }

    private static Set<String> strings(JsonObject o, String key) {
        Set<String> out = new java.util.LinkedHashSet<>();
        if (o.has(key)) {
            JsonArray a = o.getAsJsonArray(key);
            for (JsonElement e : a) out.add(e.getAsString());
        }
        return out;
    }

    public static List<Kind> all() {
        return List.copyOf(BY_ID.values());
    }

    public static List<Kind> of(Tier tier) {
        return BY_TIER.get(tier);
    }

    @Nullable
    public static Kind byId(String id) {
        return BY_ID.get(id);
    }

    /** Weighted choice for a spot from a uniform number {@code u} in [0, 1); null when nothing of the tier fits. */
    @Nullable
    public static Kind choose(Tier tier, LandCtx c, double u) {
        List<Kind> list = BY_TIER.get(tier);
        double total = 0;
        double[] w = new double[list.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = list.get(i).weight(c);
            total += w[i];
        }
        if (total <= 0) return null;
        double roll = u * total;
        for (int i = 0; i < w.length; i++) {
            roll -= w[i];
            if (roll < 0 && w[i] > 0) return list.get(i);
        }
        return null;
    }
}
