package com.skycraft.tamriel.settlement.grammar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skycraft.tamriel.settlement.build.B;
import com.skycraft.tamriel.settlement.kit.Palette;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One culture style sheet ({@code skycraft/tamriel/styles/<culture>.json}), resolved for one kit style: per wealth
 * tier the material variants (overrides on the kit palette), the roofs, footprints and body shapes the tier builds,
 * its storeys, and the decoration rules (timber framing, porches, balconies, outside stairs, shutters, flower boxes,
 * chimneys, annexes, banners). Immutable once loaded; {@link Styles} caches them.
 */
public final class Style {
    public final String culture;
    public final String name;
    /** Material variants per tier (at least one each). */
    final Map<Wealth, List<Variant>> tiers = new EnumMap<>(Wealth.class);
    final Map<Wealth, Map<String, Integer>> roofs = new EnumMap<>(Wealth.class);
    final Map<Wealth, Map<String, Integer>> footprints = new EnumMap<>(Wealth.class);
    final Map<Wealth, Map<String, Integer>> bodies = new EnumMap<>(Wealth.class);
    final Map<Wealth, int[]> storeys = new EnumMap<>(Wealth.class);
    /** Roofs of big halls (inns, temples, longhouses, keeps). */
    Map<String, Integer> hallRoofs = new LinkedHashMap<>();
    /** Body of big halls ({@code rect} or an exotic shell). */
    String hallBody = "rect";
    final Map<String, Double> rules = new LinkedHashMap<>();
    String windows = "";
    int pitch = 0;
    boolean stilts, horns, hearth = true;
    final List<String> faith = new ArrayList<>();
    final List<String> crops = new ArrayList<>();
    final List<String> animals = new ArrayList<>();
    /** Banner / awning colours. */
    final List<String> colors = new ArrayList<>();
    /** Weights of settlement forms (layout types) this culture builds. */
    final Map<String, Integer> forms = new LinkedHashMap<>();

    Style(String culture, String name) {
        this.culture = culture;
        this.name = name;
    }

    /** A material variant: overrides on the settlement's kit palette. */
    public static final class Variant {
        final Map<String, String> set = new LinkedHashMap<>();
        Boolean timber;
        int pitch;

        /** The kit palette with this variant's materials. */
        public Palette apply(Palette kit) {
            Palette p = kit.copy();
            for (Map.Entry<String, String> e : set.entrySet()) {
                String v = e.getValue();
                switch (e.getKey()) {
                    case "wall" -> p.wall = Materials.block(v, Blocks.SPRUCE_PLANKS);
                    case "wall2" -> p.wall2 = Materials.block(v, Blocks.SPRUCE_PLANKS);
                    case "low" -> p.wallLow = Materials.block(v, Blocks.COBBLESTONE);
                    case "foundation" -> p.foundation = Materials.block(v, Blocks.COBBLESTONE);
                    case "floor" -> p.floor = Materials.block(v, Blocks.SPRUCE_PLANKS);
                    case "floor2" -> p.floor2 = Materials.block(v, Blocks.SPRUCE_PLANKS);
                    case "post" -> p.post = Materials.block(v, Blocks.SPRUCE_LOG);
                    case "beam" -> p.beam = Materials.block(v, Blocks.STRIPPED_SPRUCE_LOG);
                    case "window" -> p.window = Materials.block(v, Blocks.GLASS_PANE);
                    case "column" -> p.column = Materials.block(v, Blocks.STRIPPED_SPRUCE_LOG);
                    case "light" -> p.light = Materials.block(v, Blocks.LANTERN);
                    case "roof" -> {
                        Materials.Family f = Materials.family(v);
                        p.roof = f.stairs();
                        p.roofSlab = f.slab();
                        p.roofFull = f.full();
                    }
                    case "roofFull" -> p.roofFull = Materials.block(v, Blocks.SPRUCE_PLANKS);
                    case "trim" -> {
                        Materials.Family f = Materials.family(v);
                        p.trim = f.stairs();
                        p.trimSlab = f.slab();
                    }
                    case "wood" -> {
                        Materials.Wood w = Materials.wood(v);
                        p.door = w.door();
                        p.fence = w.fence();
                        p.gate = w.gate();
                        p.trapdoor = w.trapdoor();
                        p.sign = w.sign();
                        p.planks = w.planks();
                        p.slab = w.slab();
                        p.stairs = w.stairs();
                        if (!set.containsKey("post")) p.post = w.log();
                        if (!set.containsKey("beam")) p.beam = w.stripped();
                    }
                    case "accent" -> {
                        p.accent = Materials.wool(v);
                        p.carpet = Materials.carpet(v);
                        p.bed = Materials.bed(v);
                    }
                    case "carpet2" -> p.carpet2 = Materials.carpet(v);
                    default -> {
                    }
                }
            }
            if (timber != null) p.timber = timber;
            if (pitch > 0) p.pitch = pitch;
            return p;
        }
    }

    // ------------------------------------------------------------------ queries

    public List<Variant> variants(Wealth w) {
        List<Variant> v = tiers.get(w);
        if (v != null && !v.isEmpty()) return v;
        for (Wealth o : Wealth.VALUES) if (tiers.get(o) != null && !tiers.get(o).isEmpty()) return tiers.get(o);
        return List.of(new Variant());
    }

    public double rule(String key, double fallback) {
        return rules.getOrDefault(key, fallback);
    }

    String pick(Map<Wealth, Map<String, Integer>> table, Wealth w, String fallback, RandomSource r) {
        Map<String, Integer> m = table.get(w);
        if (m == null || m.isEmpty()) {
            for (int i = w.ordinal(); i >= 0 && (m == null || m.isEmpty()); i--) m = table.get(Wealth.VALUES[i]);
        }
        return weighted(m, fallback, r);
    }

    static String weighted(Map<String, Integer> m, String fallback, RandomSource r) {
        if (m == null || m.isEmpty()) return fallback;
        int total = 0;
        for (int v : m.values()) total += Math.max(0, v);
        if (total <= 0) return fallback;
        int k = r.nextInt(total);
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            k -= Math.max(0, e.getValue());
            if (k < 0) return e.getKey();
        }
        return fallback;
    }

    public int[] storeys(Wealth w) {
        int[] s = storeys.get(w);
        return s != null ? s : new int[]{1, w.atLeast(Wealth.WEALTHY) ? 2 : 1};
    }

    public String faith(RandomSource r) {
        return faith.isEmpty() ? "mara" : faith.get(r.nextInt(faith.size()));
    }

    public List<String> faiths() {
        return faith;
    }

    public List<String> crops() {
        return crops;
    }

    public List<String> animals() {
        return animals;
    }

    public String color(int i) {
        return colors.isEmpty() ? "red" : colors.get(Math.floorMod(i, colors.size()));
    }

    // ------------------------------------------------------------------ parsing

    /** Reads (or layers on top of an earlier sheet) the keys of one JSON object. */
    void read(JsonObject o) {
        if (o.has("tiers")) {
            JsonObject t = o.getAsJsonObject("tiers");
            for (Wealth w : Wealth.VALUES) {
                if (!t.has(w.id())) continue;
                List<Variant> list = new ArrayList<>();
                for (JsonElement e : t.getAsJsonArray(w.id())) list.add(variant(e.getAsJsonObject()));
                tiers.put(w, list);
            }
        }
        readTable(o, "roofs", roofs);
        readTable(o, "footprints", footprints);
        readTable(o, "bodies", bodies);
        if (o.has("storeys")) {
            JsonObject s = o.getAsJsonObject("storeys");
            for (Wealth w : Wealth.VALUES) {
                if (!s.has(w.id())) continue;
                JsonArray a = s.getAsJsonArray(w.id());
                storeys.put(w, new int[]{a.get(0).getAsInt(), a.get(a.size() - 1).getAsInt()});
            }
        }
        if (o.has("halls")) hallRoofs = weights(o.getAsJsonObject("halls"));
        if (o.has("hallBody")) hallBody = o.get("hallBody").getAsString();
        if (o.has("rules")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("rules").entrySet()) {
                JsonElement v = e.getValue();
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) rules.put(e.getKey(), v.getAsDouble());
                else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) rules.put(e.getKey(), v.getAsBoolean() ? 1.0 : 0.0);
            }
        }
        if (o.has("windows")) windows = o.get("windows").getAsString();
        if (o.has("pitch")) pitch = o.get("pitch").getAsInt();
        if (o.has("stilts")) stilts = o.get("stilts").getAsBoolean();
        if (o.has("horns")) horns = o.get("horns").getAsBoolean();
        if (o.has("hearth")) hearth = o.get("hearth").getAsBoolean();
        readList(o, "faith", faith);
        readList(o, "crops", crops);
        readList(o, "animals", animals);
        readList(o, "colors", colors);
        if (o.has("forms")) {
            forms.clear();
            forms.putAll(weights(o.getAsJsonObject("forms")));
        }
    }

    private static void readList(JsonObject o, String key, List<String> out) {
        if (!o.has(key)) return;
        out.clear();
        for (JsonElement e : o.getAsJsonArray(key)) out.add(e.getAsString());
    }

    private static void readTable(JsonObject o, String key, Map<Wealth, Map<String, Integer>> out) {
        if (!o.has(key)) return;
        JsonObject t = o.getAsJsonObject(key);
        for (Wealth w : Wealth.VALUES) if (t.has(w.id())) out.put(w, weights(t.getAsJsonObject(w.id())));
    }

    private static Map<String, Integer> weights(JsonObject o) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) m.put(e.getKey(), e.getValue().getAsInt());
        return m;
    }

    private static Variant variant(JsonObject o) {
        Variant v = new Variant();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            switch (e.getKey()) {
                case "timber" -> v.timber = e.getValue().getAsBoolean();
                case "pitch" -> v.pitch = e.getValue().getAsInt();
                case "name" -> {
                }
                default -> v.set.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return v;
    }

    /** Copy for layering a sub-style over the culture defaults. */
    Style derive(String name) {
        Style s = new Style(culture, name);
        tiers.forEach((k, v) -> s.tiers.put(k, new ArrayList<>(v)));
        roofs.forEach((k, v) -> s.roofs.put(k, new LinkedHashMap<>(v)));
        footprints.forEach((k, v) -> s.footprints.put(k, new LinkedHashMap<>(v)));
        bodies.forEach((k, v) -> s.bodies.put(k, new LinkedHashMap<>(v)));
        storeys.forEach((k, v) -> s.storeys.put(k, v.clone()));
        s.hallRoofs = new LinkedHashMap<>(hallRoofs);
        s.hallBody = hallBody;
        s.rules.putAll(rules);
        s.windows = windows;
        s.pitch = pitch;
        s.stilts = stilts;
        s.horns = horns;
        s.hearth = hearth;
        s.faith.addAll(faith);
        s.crops.addAll(crops);
        s.animals.addAll(animals);
        s.colors.addAll(colors);
        s.forms.putAll(forms);
        return s;
    }

    /** A window block for a style's {@code windows} keyword ({@code pane}, {@code fence}, {@code bars}...), or null. */
    static BlockState windowBlock(String kind, Palette p) {
        return switch (kind) {
            case "pane" -> B.s(Blocks.GLASS_PANE);
            case "bars" -> B.s(Blocks.IRON_BARS);
            case "fence" -> p.fence;
            case "tinted" -> B.s(Blocks.BROWN_STAINED_GLASS_PANE);
            default -> null;
        };
    }
}
