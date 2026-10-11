package com.skycraft.tamriel.settlement.grammar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import com.skycraft.tamriel.settlement.Culture;
import com.skycraft.tamriel.settlement.kit.Kits;
import com.skycraft.tamriel.settlement.kit.Palette;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and caches the culture style sheets: class-path resources {@code skycraft/tamriel/styles/<culture>.json}
 * (needed at structure-planning time, before and apart from datapacks). A sheet's top level holds the culture's
 * defaults; its {@code styles} object layers overrides per kit style ({@code windhelm}, {@code markarth},
 * {@code telvanni}...), keyed by the kit palette's name.
 */
public final class Styles {
    private static final Map<String, Style> CACHE = new ConcurrentHashMap<>();
    private static final Map<Culture, JsonObject> SHEETS = new ConcurrentHashMap<>();

    private Styles() {}

    /** The style sheet of a culture and kit style. Never null (an empty sheet when the file is missing). */
    public static Style of(Culture c, int style) {
        Palette kit = Kits.palette(c, style);
        return CACHE.computeIfAbsent(c.id() + "/" + kit.name, k -> load(c, kit.name));
    }

    private static Style load(Culture c, String kitName) {
        JsonObject sheet = SHEETS.computeIfAbsent(c, Styles::read);
        Style base = new Style(c.id(), c.id());
        try {
            base.read(sheet);
            if (sheet.has("styles")) {
                JsonObject subs = sheet.getAsJsonObject("styles");
                if (subs.has(kitName)) {
                    Style s = base.derive(kitName);
                    s.read(subs.getAsJsonObject(kitName));
                    return s;
                }
            }
        } catch (RuntimeException e) {
            Skycraft.LOGGER.error("Bad style sheet for {} ({})", c.id(), kitName, e);
        }
        return base;
    }

    private static JsonObject read(Culture c) {
        String path = "/skycraft/tamriel/styles/" + c.id() + ".json";
        try (InputStream in = Styles.class.getResourceAsStream(path)) {
            if (in == null) {
                Skycraft.LOGGER.warn("No style sheet {}", path);
                return new JsonObject();
            }
            JsonElement e = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            Skycraft.LOGGER.error("Could not read style sheet {}", path, e);
            return new JsonObject();
        }
    }

    /** Drops the caches (dev reload). */
    public static void reload() {
        CACHE.clear();
        SHEETS.clear();
    }
}
