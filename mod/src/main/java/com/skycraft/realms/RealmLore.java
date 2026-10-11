package com.skycraft.realms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import com.skycraft.lore.LoreBooks;
import com.skycraft.world.LocationKind;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The realms' lore books (registered with the lore module) and the discovery names of the realms' sites, both from
 * class-path tables written by tools/worldgen/realms.py ({@code skycraft/realms/books.json}, {@code site_names.json}).
 */
public final class RealmLore {
    private static final Map<String, String> SITE_NAMES = new HashMap<>();

    private RealmLore() {}

    static void init() {
        JsonElement books = read("/skycraft/realms/books.json");
        if (books instanceof JsonArray arr) {
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                try {
                    LoreBooks.register(o.get("id").getAsString(),
                            LoreBooks.Category.valueOf(o.get("category").getAsString().toUpperCase(Locale.ROOT)),
                            cover(o.get("cover").getAsString()), o.get("weight").getAsInt(), o.get("value").getAsInt());
                } catch (RuntimeException ex) {
                    Skycraft.LOGGER.warn("Bad realm book entry {}", o, ex);
                }
            }
        }
        JsonElement names = read("/skycraft/realms/site_names.json");
        if (names instanceof JsonObject o) {
            for (Map.Entry<String, JsonElement> e : o.entrySet()) SITE_NAMES.put(e.getKey(), e.getValue().getAsString());
        }
    }

    private static int cover(String name) {
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "RED" -> LoreBooks.RED;
            case "GREEN" -> LoreBooks.GREEN;
            case "BLUE" -> LoreBooks.BLUE;
            case "BLACK" -> LoreBooks.BLACK;
            case "SHEET" -> LoreBooks.SHEET;
            case "GOLD" -> LoreBooks.GOLD;
            default -> LoreBooks.BROWN;
        };
    }

    @Nullable
    private static JsonElement read(String path) {
        try (InputStream in = RealmLore.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Could not read {}", path, e);
            return null;
        }
    }

    // ------------------------------------------------------------------ discovery hooks (world module)

    /** The site id of a realm structure path ({@code realm/deadlands/sigil_keep} -> {@code deadlands/sigil_keep}), or null. */
    @Nullable
    private static String site(String structurePath) {
        return structurePath.startsWith("realm/") ? structurePath.substring(6) : null;
    }

    /** What a realm structure is on the map and in discovery banners; null for other structures. */
    @Nullable
    public static LocationKind kind(String structurePath) {
        String s = site(structurePath);
        if (s == null) return null;
        return switch (s) {
            case "deadlands/sigil_keep", "deadlands/spire" -> LocationKind.DAEDRIC_TOWER;
            case "deadlands/citadel" -> LocationKind.CITADEL;
            case "nirn/oblivion_gate", "nirn/named_gate" -> LocationKind.OBLIVION_GATE;
            case "sovngarde/hall_of_valor" -> LocationKind.HALL_OF_VALOR;
            case "isles/new_sheoth" -> LocationKind.CITY;
            case "isles/ruin", "soul_cairn/mausoleum", "coldharbour/hollow_city" -> LocationKind.RUIN;
            case "coldharbour/ruined_tower" -> LocationKind.TOWER;
            case "soul_cairn/valerica_keep" -> LocationKind.FORT;
            case "apocrypha/chapter" -> LocationKind.TEMPLE;
            case "nirn/black_book_altar", "nirn/madness_door", "nirn/dark_anchor", "nirn/soul_cairn_portal" -> LocationKind.DAEDRIC_SHRINE;
            default -> LocationKind.LANDMARK;
        };
    }

    /** The discovery name of a realm structure (a fixed name for landmarks, a name with a twist for repeats). */
    @Nullable
    public static String name(String locationId, String structurePath) {
        String s = site(structurePath);
        if (s == null) return null;
        String base = SITE_NAMES.get(s);
        if (base == null) return null;
        long h = com.skycraft.world.LocationNames.hash(locationId);
        String[] princes = {"Mehrunes Dagon", "the Dremora", "Ruin", "Ash", "Cinders", "the Burning Eye", "Thorns", "the Red Sky"};
        String[] masters = {"Sighs", "the Lost", "Old Bones", "the Forgotten", "Violet Dust", "Hollow Souls"};
        return switch (s) {
            case "deadlands/citadel" -> "Citadel of " + princes[(int) Math.floorMod(h, princes.length)];
            case "deadlands/spire" -> "Spire of " + princes[(int) Math.floorMod(h >> 8, princes.length)];
            case "deadlands/bridge" -> "Bridge of " + princes[(int) Math.floorMod(h >> 16, princes.length)];
            case "soul_cairn/mausoleum" -> "Mausoleum of " + masters[(int) Math.floorMod(h, masters.length)];
            case "apocrypha/chapter" -> {
                if (locationId.endsWith("|0,0")) yield "The Summit of Apocrypha";
                String[] chapters = {"Chapter of Whispers", "Chapter of Lost Names", "Chapter of the Drowned Eye",
                        "Chapter of Ink and Ash", "Chapter of Forbidden Margins", "Chapter of the Unread"};
                yield chapters[(int) Math.floorMod(h, chapters.length)];
            }
            default -> base;
        };
    }
}
