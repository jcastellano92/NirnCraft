package com.nirncraft.tamriel.settlement.grammar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nirncraft.NirnCraft;
import com.nirncraft.tamriel.settlement.Culture;
import com.nirncraft.tamriel.settlement.kit.Kits;
import com.nirncraft.tamriel.settlement.kit.Palette;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and caches the culture style sheets: class-path resources {@code nirncraft/tamriel/styles/<culture>.json}
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
        String kitName = Kits.kitName(c, style < 0 ? style : style & Kits.KIT_MASK);
        int overlay = style < 0 ? 0 : style >>> Kits.OVERLAY_SHIFT;
        String key = overlay == 0 ? c.id() + "/" + kitName : c.id() + "/" + kitName + "#" + overlay;
        return CACHE.computeIfAbsent(key, k -> {
            Style s = load(c, kitName);
            JsonObject sheet = overlay == 0 ? null : Overlays.sheet(overlay);
            if (sheet != null && (sheet.has("style") || sheet.has("tint"))) {
                try {
                    Style o = s.derive(s.name);
                    if (sheet.has("style")) o.read(sheet.getAsJsonObject("style"));
                    if (sheet.has("tint")) o.tint(sheet.getAsJsonObject("tint"));
                    return o;
                } catch (RuntimeException e) {
                    NirnCraft.LOGGER.error("Bad style overlay {} for {}", overlay, c.id(), e);
                }
            }
            return s;
        });
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
            NirnCraft.LOGGER.error("Bad style sheet for {} ({})", c.id(), kitName, e);
        }
        return base;
    }

    private static JsonObject read(Culture c) {
        String path = "/nirncraft/tamriel/styles/" + c.id() + ".json";
        try (InputStream in = com.nirncraft.dev.DevResources.open(Styles.class, path)) {
            if (in == null) {
                NirnCraft.LOGGER.warn("No style sheet {}", path);
                return new JsonObject();
            }
            JsonElement e = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            NirnCraft.LOGGER.error("Could not read style sheet {}", path, e);
            return new JsonObject();
        }
    }

    /** Drops the caches (dev reload). */
    public static void reload() {
        CACHE.clear();
        SHEETS.clear();
        Overlays.reload();
    }
}
