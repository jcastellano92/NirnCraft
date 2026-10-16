package com.skycraft.society;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which regions and provinces have their own spoken lines, read from {@code skycraft/society/region_voice.json}:
 * {@code regions} (ids with a {@code society.skycraft.say.region.<id>} answer), {@code provinces} (ids with a
 * {@code say.province.<id>} answer) and {@code gossip} (id -> number of {@code chat.gossip_<id>} variants). Adding a
 * region's lines is a lang entry plus an id in that file; no Java edit.
 */
public final class RegionVoice {
    private static final String[] FILES = {
            "/skycraft/npc/region_voice.json",
            "/skycraft/society/region_voice.json"
    };
    private static final Set<String> REGIONS = new HashSet<>();
    private static final Set<String> PROVINCES = new HashSet<>();
    private static final Map<String, Integer> GOSSIP = new HashMap<>();

    static {
        reload();
    }

    private RegionVoice() {}

    public static synchronized void reload() {
        REGIONS.clear();
        PROVINCES.clear();
        GOSSIP.clear();
        boolean anyLoaded = false;
        for (String file : FILES) {
            try (InputStream in = RegionVoice.class.getResourceAsStream(file)) {
                if (in == null) continue;
                anyLoaded = true;
                JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("regions")) {
                    JsonElement rEl = o.get("regions");
                    if (rEl.isJsonArray()) {
                        for (JsonElement e : rEl.getAsJsonArray()) REGIONS.add(normalize(e.getAsString()));
                    } else if (rEl.isJsonObject()) {
                        for (String k : rEl.getAsJsonObject().keySet()) REGIONS.add(normalize(k));
                    }
                }
                if (o.has("provinces")) {
                    JsonElement pEl = o.get("provinces");
                    if (pEl.isJsonArray()) {
                        for (JsonElement e : pEl.getAsJsonArray()) PROVINCES.add(normalize(e.getAsString()));
                    } else if (pEl.isJsonObject()) {
                        for (String k : pEl.getAsJsonObject().keySet()) PROVINCES.add(normalize(k));
                    }
                }
                if (o.has("gossip") && o.get("gossip").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("gossip").entrySet()) {
                        String key = normalize(e.getKey());
                        int count = e.getValue().getAsInt();
                        GOSSIP.merge(key, count, Math::max);
                    }
                }
            } catch (Exception ex) {
                Skycraft.LOGGER.warn("Could not read {}", file, ex);
            }
        }
        if (!anyLoaded) {
            Skycraft.LOGGER.warn("Missing region_voice.json: region voice lines fall back to 'unknown'");
        }
    }

    private static String normalize(@Nullable String id) {
        if (id == null) return "";
        String s = id.trim().toLowerCase(java.util.Locale.ROOT);
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    public static boolean hasRegion(@Nullable String regionShortId) {
        return regionShortId != null && REGIONS.contains(normalize(regionShortId));
    }

    public static boolean hasProvince(@Nullable String province) {
        return province != null && PROVINCES.contains(normalize(province));
    }

    public static Set<String> regions() {
        return java.util.Collections.unmodifiableSet(REGIONS);
    }

    public static Set<String> provinces() {
        return java.util.Collections.unmodifiableSet(PROVINCES);
    }

    public static int gossipCount(@Nullable String id) {
        if (id == null) return 0;
        return GOSSIP.getOrDefault(normalize(id), 0);
    }

    /** The {@code say.} key answering "Tell me about this place.": region, else province, else unknown. */
    public static String sayKey(String regionShortId, String province) {
        String rNorm = normalize(regionShortId);
        String pNorm = normalize(province);
        if (REGIONS.contains(rNorm)) return "region." + rNorm;
        if (PROVINCES.contains(pNorm)) return "province." + pNorm;
        return "region.unknown";
    }

    public static String sayKey(com.skycraft.tamriel.Region region) {
        if (region == null) return "region.unknown";
        return sayKey(region.shortId(), region.province());
    }

    /** Chat topic {@code gossip_<id>}: the region's when {@code preferRegion} and it has one, else its province's, else null. */
    @Nullable
    public static String gossipTopic(String regionShortId, String province, boolean preferRegion) {
        String rNorm = normalize(regionShortId);
        String pNorm = normalize(province);
        if (preferRegion && GOSSIP.containsKey(rNorm)) return "gossip_" + rNorm;
        if (GOSSIP.containsKey(pNorm)) return "gossip_" + pNorm;
        if (GOSSIP.containsKey(rNorm)) return "gossip_" + rNorm;
        return null;
    }

    /** Variant counts of every gossip topic, keyed {@code gossip_<id>}. */
    public static Map<String, Integer> gossipTopics() {
        Map<String, Integer> out = new HashMap<>();
        GOSSIP.forEach((k, v) -> out.put("gossip_" + k, v));
        return out;
    }
}
