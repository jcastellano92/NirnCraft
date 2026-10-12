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
    private static final String FILE = "/skycraft/society/region_voice.json";
    private static final Set<String> REGIONS = new HashSet<>();
    private static final Set<String> PROVINCES = new HashSet<>();
    private static final Map<String, Integer> GOSSIP = new HashMap<>();

    static {
        try (InputStream in = RegionVoice.class.getResourceAsStream(FILE)) {
            if (in == null) {
                Skycraft.LOGGER.warn("Missing {}: region voice lines fall back to 'unknown'", FILE);
            } else {
                JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("regions")) for (JsonElement e : o.getAsJsonArray("regions")) REGIONS.add(e.getAsString());
                if (o.has("provinces")) for (JsonElement e : o.getAsJsonArray("provinces")) PROVINCES.add(e.getAsString());
                if (o.has("gossip")) {
                    for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("gossip").entrySet()) {
                        GOSSIP.put(e.getKey(), e.getValue().getAsInt());
                    }
                }
            }
        } catch (Exception ex) {
            Skycraft.LOGGER.warn("Could not read {}", FILE, ex);
        }
    }

    private RegionVoice() {}

    /** The {@code say.} key answering "Tell me about this place.": region, else province, else unknown. */
    public static String sayKey(String regionShortId, String province) {
        if (REGIONS.contains(regionShortId)) return "region." + regionShortId;
        if (PROVINCES.contains(province)) return "province." + province;
        return "region.unknown";
    }

    /** Chat topic {@code gossip_<id>}: the region's when {@code preferRegion} and it has one, else its province's, else null. */
    @Nullable
    public static String gossipTopic(String regionShortId, String province, boolean preferRegion) {
        if (preferRegion && GOSSIP.containsKey(regionShortId)) return "gossip_" + regionShortId;
        if (GOSSIP.containsKey(province)) return "gossip_" + province;
        return null;
    }

    /** Variant counts of every gossip topic, keyed {@code gossip_<id>}. */
    public static Map<String, Integer> gossipTopics() {
        Map<String, Integer> out = new HashMap<>();
        GOSSIP.forEach((k, v) -> out.put("gossip_" + k, v));
        return out;
    }
}
