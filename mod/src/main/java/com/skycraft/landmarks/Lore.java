package com.skycraft.landmarks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import com.skycraft.society.NpcNames;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.world.PlaceText;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Names, epitaphs and short stories of the vignettes, generated from {@code skycraft/landmarks/lore.json} (original
 * text) and the culture-specific name generators of the society module. Everything is a pure function of the random
 * source it is given, so a site tells the same story every time it is generated.
 */
public final class Lore {
    private static final Map<String, List<String>> LISTS = new HashMap<>();
    private static final Map<String, List<String>> EPITAPHS = new HashMap<>();
    private static final Map<String, List<String>> NOTES = new HashMap<>();
    private static final Map<String, List<String>> ADJ = new HashMap<>();
    private static final Map<String, List<Object[]>> RACES = new HashMap<>();
    private static final Map<String, String> GROUP = new HashMap<>();

    static {
        try (InputStream in = Lore.class.getResourceAsStream("/skycraft/landmarks/lore.json")) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (String k : new String[]{"trades", "causes", "things", "weather", "items", "warnings", "ofs"}) {
                    LISTS.put(k, strings(root.getAsJsonArray(k)));
                }
                for (var e : root.getAsJsonObject("epitaph").entrySet()) EPITAPHS.put(e.getKey(), strings(e.getValue().getAsJsonArray()));
                for (var e : root.getAsJsonObject("notes").entrySet()) NOTES.put(e.getKey(), strings(e.getValue().getAsJsonArray()));
                for (var e : root.getAsJsonObject("adj").entrySet()) ADJ.put(e.getKey(), strings(e.getValue().getAsJsonArray()));
                for (var e : root.getAsJsonObject("group").entrySet()) GROUP.put(e.getKey(), e.getValue().getAsString());
                for (var e : root.getAsJsonObject("races").entrySet()) {
                    List<Object[]> l = new ArrayList<>();
                    for (String s : strings(e.getValue().getAsJsonArray())) {
                        int cut = s.indexOf(':');
                        try {
                            l.add(new Object[]{NpcNames.Race.valueOf(s.substring(0, cut)), Integer.parseInt(s.substring(cut + 1))});
                        } catch (IllegalArgumentException ex) {
                            Skycraft.LOGGER.warn("Skycraft landmarks: unknown race {}", s);
                        }
                    }
                    RACES.put(e.getKey(), l);
                }
            }
        } catch (Exception ex) {
            Skycraft.LOGGER.warn("Skycraft landmarks: could not read lore.json", ex);
        }
    }

    private Lore() {}

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : a) out.add(e.getAsString());
        return out;
    }

    private static List<String> list(String key) {
        List<String> l = LISTS.get(key);
        return l == null || l.isEmpty() ? List.of("-") : l;
    }

    private static <T> T pick(RandomSource r, List<T> l) {
        return l.get(r.nextInt(l.size()));
    }

    // ------------------------------------------------------------------ people

    public static String group(String province) {
        return GROUP.getOrDefault(province, "any");
    }

    public static NpcNames.Race race(String province, RandomSource r) {
        List<Object[]> l = RACES.get(RACES.containsKey(province) ? province : "realm");
        if (l == null || l.isEmpty()) return NpcNames.Race.NORD;
        int total = 0;
        for (Object[] o : l) total += (Integer) o[1];
        int v = r.nextInt(Math.max(1, total));
        for (Object[] o : l) {
            v -= (Integer) o[1];
            if (v < 0) return (NpcNames.Race) o[0];
        }
        return (NpcNames.Race) l.get(0)[0];
    }

    /** A person's full name in the folk of the province. */
    public static String person(String province, RandomSource r) {
        return NpcNames.fullName(race(province, r), r.nextBoolean(), r);
    }

    /** A person's first name only. */
    public static String firstName(String province, RandomSource r) {
        return NpcNames.generate(race(province, r), r.nextBoolean(), r);
    }

    // ------------------------------------------------------------------ graves

    /** One sign line of an epitaph, 15 characters or fewer. */
    public static String epitaph(String province, RandomSource r) {
        List<String> specific = EPITAPHS.get(group(province));
        List<String> any = EPITAPHS.getOrDefault("any", List.of("Rest easy"));
        String s = specific != null && !specific.isEmpty() && r.nextInt(5) < 3 ? pick(r, specific) : pick(r, any);
        return clip(s);
    }

    static String clip(String s) {
        return s.length() <= 15 ? s : s.substring(0, 15);
    }

    /** The four lines of a grave sign: trade, first name, family name, epitaph. */
    public static String[] graveSign(String province, RandomSource r) {
        NpcNames.Race race = race(province, r);
        boolean female = r.nextBoolean();
        String first = NpcNames.generate(race, female, r);
        String family = NpcNames.familyName(race, female, r);
        if (family == null) family = "";
        return new String[]{clip(pick(r, list("trades"))), clip(first), clip(family), epitaph(province, r)};
    }

    /** Wraps a name onto at most four sign lines of 15 characters. */
    public static String[] wrap(String text) {
        String[] out = {"", "", "", ""};
        int line = 0;
        for (String w : text.split(" ")) {
            if (line < 4 && !out[line].isEmpty() && out[line].length() + 1 + w.length() > 15) line++;
            if (line >= 4) break;
            out[line] = out[line].isEmpty() ? clip(w) : out[line] + " " + w;
        }
        return out;
    }

    public static String warning(RandomSource r) {
        return pick(r, list("warnings"));
    }

    // ------------------------------------------------------------------ notes

    /** The nearest named place to (x, z), by name, or a stand-in when none is near. */
    public static String nearPlace(int x, int z) {
        return Places.nearest(null, x, z, 1400).map(Lore::plainName).orElse("the next village");
    }

    private static String plainName(Place p) {
        try {
            return PlaceText.plain(p);
        } catch (RuntimeException e) {
            return p.id().replace('_', ' ');
        }
    }

    /** One short story (fits a book page) of a category, with its placeholders filled. */
    public static String note(String category, String province, int x, int z, RandomSource r) {
        List<String> templates = NOTES.get(category);
        if (templates == null || templates.isEmpty()) templates = NOTES.getOrDefault("traveller", List.of("..."));
        String t = pick(r, templates);
        return t.replace("{name}", firstName(province, r))
                .replace("{other}", firstName(province, r))
                .replace("{place}", nearPlace(x, z))
                .replace("{trade}", pick(r, list("trades")).toLowerCase(java.util.Locale.ROOT))
                .replace("{cause}", pick(r, list("causes")))
                .replace("{weather}", pick(r, list("weather")))
                .replace("{item}", pick(r, list("items")))
                .replace("{thing}", pick(r, list("things")))
                .replace("{dir}", pick(r, List.of("north", "south", "east", "west", "north-east", "south-west")));
    }

    /** A written book (vanilla item: readable anywhere) holding {@code text} on one page. */
    public static ItemStack book(String title, String author, String... pages) {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString("title", title.length() > 32 ? title.substring(0, 32) : title);
        tag.putString("author", author);
        ListTag list = new ListTag();
        for (String p : pages) list.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(p))));
        tag.put("pages", list);
        tag.putBoolean("resolved", true);
        tag.putBoolean(com.skycraft.lore.place.StoryReader.STORY_TAG, true);
        return stack;
    }

    // ------------------------------------------------------------------ place names

    /**
     * The name of a natural landmark: "Frostbitten Arch", "Sulfur Spire of the Wanderer", "Hildra's Fall". Culture
     * lists by province, nouns from the kind.
     */
    public static String placeName(String province, String noun, RandomSource r) {
        String g = group(province);
        List<String> adj = new ArrayList<>(ADJ.getOrDefault(g, List.of()));
        adj.addAll(ADJ.getOrDefault("any", List.of("Old")));
        String n = noun.isEmpty() ? "Stones" : noun;
        return switch (r.nextInt(6)) {
            case 0, 1, 2 -> pick(r, adj) + " " + n;
            case 3 -> firstName(province, r) + "'s " + n;
            case 4 -> "The " + n + " of " + pick(r, list("ofs"));
            default -> pick(r, adj) + " " + n + " of " + pick(r, list("ofs"));
        };
    }
}
