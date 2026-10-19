package com.nirncraft.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nirncraft.NirnCraft;
import com.nirncraft.tamriel.Place;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Plain English names of places for text the server stores as strings (quest labels, the discovered list's
 * fallback name). A dedicated server has no mod language files loaded, so this reads NirnCraft's merged
 * {@code en_us.json} from the jar once; clients still show the translated {@link Place#name()} wherever a
 * {@code Component} can be sent.
 */
public final class PlaceText {
    private static volatile Map<String, String> english;

    private PlaceText() {}

    public static String plain(Place place) {
        if (place.name().getContents() instanceof TranslatableContents t) {
            String v = lang().get(t.getKey());
            if (v != null) return v;
        }
        return place.name().getString();
    }

    private static Map<String, String> lang() {
        Map<String, String> m = english;
        if (m != null) return m;
        m = new HashMap<>();
        try (InputStream in = PlaceText.class.getResourceAsStream("/assets/nirncraft/lang/en_us.json")) {
            if (in != null) {
                JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                    if (e.getKey().startsWith("place.") && e.getValue().isJsonPrimitive()) m.put(e.getKey(), e.getValue().getAsString());
                }
            }
        } catch (Exception e) {
            NirnCraft.LOGGER.debug("Could not read place names", e);
        }
        english = m;
        return m;
    }
}
