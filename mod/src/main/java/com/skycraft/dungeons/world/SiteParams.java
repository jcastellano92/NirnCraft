package com.skycraft.dungeons.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import com.skycraft.lore.AllMakerStone;
import com.skycraft.lore.StandingStone;
import com.skycraft.magic.shout.Shout;
import com.skycraft.tamriel.Place;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The per-site parameter ({@link Site#param}) of a named place, from its {@code variant} and the extra fields
 * Skycraft's dungeon places carry in the places files ({@code "shout"}: the shout its Word Wall teaches).
 *
 * <ul>
 *     <li>Standing stones: the sign's ordinal; {@link #GUARDIANS} for the Warrior, Mage and Thief together;
 *     {@link #ALL_MAKER} + ordinal for Solstheim's All-Maker stones.</li>
 *     <li>Word walls (and any dungeon with one): the shout's ordinal + 1; 0 picks a weighted random shout.</li>
 *     <li>Daedric shrines: the {@link Prince}'s ordinal.</li>
 *     <li>Landmarks: the {@link Style}'s ordinal.</li>
 * </ul>
 */
public final class SiteParams {
    public static final int GUARDIANS = 100;
    public static final int ALL_MAKER = 200;

    /** Shouts not (yet) in the magic module, mapped to the nearest one they have; a later shout keeps its own id. */
    private static final Map<String, String> SHOUT_FALLBACK = Map.ofEntries(
            Map.entry("ice_form", "frost_breath"), Map.entry("dismay", "unrelenting_force"),
            Map.entry("cyclone", "whirlwind_sprint"), Map.entry("dragon_aspect", "dragonrend"),
            Map.entry("battle_fury", "elemental_fury"), Map.entry("bend_will", "animal_allegiance"),
            Map.entry("throw_voice", "aura_whisper"), Map.entry("drain_vitality", "marked_for_death"),
            Map.entry("soul_tear", "marked_for_death"), Map.entry("call_dragon", "dragonrend"),
            Map.entry("summon_durnehviir", "storm_call"), Map.entry("call_of_valor", "elemental_fury"));

    /** Places-file extras by place id, read once from the class path (same files as {@code Places}). */
    private static final Map<String, JsonObject> EXTRAS = load();

    private SiteParams() {}

    /** The Daedric Princes, each with a shrine of their own. */
    public enum Prince {
        AZURA, BOETHIAH, CLAVICUS_VILE, HERMAEUS_MORA, HIRCINE, MALACATH, MEHRUNES_DAGON, MEPHALA,
        MERIDIA, MOLAG_BAL, NAMIRA, NOCTURNAL, PERYITE, SANGUINE, SHEOGORATH, VAERMINA;

        public static final Prince[] VALUES = values();

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Prince byId(String id) {
            for (Prince p : VALUES) if (p.id().equals(id)) return p;
            return null;
        }

        public static Prince byIndex(int i) {
            return VALUES[Math.floorMod(i, VALUES.length)];
        }
    }

    /** Kinds of minor landmark. */
    public enum Style {
        MONUMENT, STONES, RUIN, PEAK, PASS, CLEARING, DWEMER, BRIDGE, ALTAR, ROCK, WATERFALL, EYRIE, ISLAND, SOURCE, CAMPSITE;

        public static final Style[] VALUES = values();

        public static Style byId(String id) {
            for (Style s : VALUES) if (s.name().equalsIgnoreCase(id)) return s;
            return MONUMENT;
        }

        public static Style byIndex(int i) {
            return VALUES[Math.floorMod(i, VALUES.length)];
        }
    }

    public static int forPlace(Theme theme, Place place) {
        String v = place.variant();
        return switch (theme) {
            case STANDING_STONE -> stone(v);
            case WORD_WALL -> shout(v);
            case PRINCE_SHRINE, DAEDRIC_SHRINE -> {
                // "azura_great": the giant statue of the same Prince
                Prince p = Prince.byId(v.endsWith("_great") ? v.substring(0, v.length() - "_great".length()) : v);
                yield p == null ? 0 : p.ordinal();
            }
            case LANDMARK -> Style.byId(v).ordinal();
            default -> shout(extra(place.id(), "shout"));
        };
    }

    public static int stone(String id) {
        if ("guardian".equals(id) || "guardians".equals(id)) return GUARDIANS;
        StandingStone s = StandingStone.byId(id);
        if (s != null) return s.ordinal();
        AllMakerStone a = AllMakerStone.byId(id);
        return a != null ? ALL_MAKER + a.ordinal() : GUARDIANS;
    }

    /** Shout ordinal + 1 for a shout id (or its stand-in); 0 when unknown or empty. */
    public static int shout(String id) {
        if (id == null || id.isEmpty()) return 0;
        Shout s = Shout.byId(id);
        if (s == null) s = Shout.byId(SHOUT_FALLBACK.getOrDefault(id, ""));
        return s == null ? 0 : s.ordinal() + 1;
    }

    public static String extra(String placeId, String key) {
        JsonObject o = EXTRAS.get(placeId);
        if (o == null || !o.has(key)) return "";
        JsonElement e = o.get(key);
        return e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static Map<String, JsonObject> load() {
        Map<String, JsonObject> out = new HashMap<>();
        JsonElement index = read("/skycraft/tamriel/places/index.json");
        if (index == null || !index.isJsonArray()) return out;
        for (JsonElement f : index.getAsJsonArray()) {
            JsonElement root = read("/skycraft/tamriel/places/" + f.getAsString());
            if (root == null || !root.isJsonArray()) continue;
            for (JsonElement e : root.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                if (o.has("id") && (o.has("shout") || o.has("claw"))) out.putIfAbsent(o.get("id").getAsString(), o);
            }
        }
        return out;
    }

    private static JsonElement read(String path) {
        try (InputStream in = SiteParams.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Skycraft dungeons: could not read {}", path, e);
            return null;
        }
    }
}
