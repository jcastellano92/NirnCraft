package com.skycraft.scatter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import com.skycraft.scatter.Terrain.Ground;
import com.skycraft.tamriel.PlaceType;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The data-driven rule table: {@code skycraft/scatter/kinds.json} (what each kind of prop is and where it may stand)
 * and {@code skycraft/scatter/rules.json} (what each zone gets and how much). Immutable once loaded; thread-safe.
 */
public final class ScatterRules {
    /** One element of a data-defined prop. */
    public static final class Entry {
        /** fill, block, item, chest, barrel, skull, banner. */
        public String type = "block";
        public int[] p = {0, 0, 0};
        public int[] p2;
        public String[] blocks = new String[0];
        public String item;
        public String loot;
        public String facing = "south";
        public String color = "$banner";
        public float chance = 1f;
        public int count = 1;
        public float damage;
    }

    /** A cluster kind: a handful of other kinds laid out around a centre (a camp's leavings). */
    public static final class Cluster {
        public int min = 3, max = 6, radius = 6;
        public List<String> members = new ArrayList<>();
    }

    public static final class Kind {
        public String id;
        public boolean human;
        public EnumSet<Ground> ground = EnumSet.allOf(Ground.class);
        public Set<String> provinces = Set.of();
        public Set<String> denyProvinces = Set.of();
        public int minSlope = 0, maxSlope = 2;
        /** Clearance (blocks) kept from other props of the chunk. */
        public int radius = 3;
        /** May stand in or touch water (shore props). */
        public boolean wet;
        /** Required distance band to water (blocks, from the map); -1 = no requirement. */
        public int waterMin = -1, waterMax = -1;
        public float minElev = -1e9f, maxElev = 1e9f;
        public float rarity = 1f;
        /** Per-column ground following (fences) instead of one rigid base level. */
        public boolean follow;
        /** Java builder id ({@link Builders}); null = data entries. */
        public String builder;
        public JsonObject params = new JsonObject();
        public List<List<Entry>> variants = new ArrayList<>();
        public Cluster cluster;
        /** Items the prop counts for against the chunk cap. */
        public int items = 1;
        /** World reason this stands here (required), the zones it may be drawn in (required) and its trace of story. */
        public String purpose, clue;
        public Set<String> needs = Set.of();
        /** Fewer than three related props and no anchor structure: capped per chunk ({@link Caps#isolated}). */
        public boolean isolated;
    }

    /** One weighted draw of a zone: a density per chunk, scaled by humanity or remoteness, over a weighted kind list. */
    public static final class Group {
        public String scale = "none";
        public double density;
        public final List<Kind> kinds = new ArrayList<>();
        public final List<Double> weights = new ArrayList<>();
        public double total;
    }

    /** The leavings placed beside real, planned roads after paving ({@link Waysides}). */
    public static final class Wayside {
        public double density = 1.0;
        public final List<Kind> kinds = new ArrayList<>();
        public final List<Double> weights = new ArrayList<>();
        public double total;
    }

    public static final class Caps {
        public int chunk = 12, perKind = 2, attempts = 5, isolated = 1;
    }

    public final Map<String, Kind> kinds = new LinkedHashMap<>();
    public final EnumMap<ScatterZone, List<Group>> zones = new EnumMap<>(ScatterZone.class);
    public final Caps caps = new Caps();
    public final Wayside wayside = new Wayside();
    /** Blocks beyond a settlement's footprint over which its human traces fade to nothing, per size class. */
    public final EnumMap<PlaceType, Integer> reach = new EnumMap<>(PlaceType.class);

    private static final class Holder {
        static final ScatterRules INSTANCE = load();
    }

    public static ScatterRules get() {
        return Holder.INSTANCE;
    }

    private ScatterRules() {}

    private static JsonObject read(String path) {
        try (InputStream in = ScatterRules.class.getResourceAsStream(path)) {
            if (in == null) return new JsonObject();
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Skycraft scatter: could not read {}", path, e);
            return new JsonObject();
        }
    }

    private static ScatterRules load() {
        ScatterRules r = new ScatterRules();
        JsonObject kinds = read("/skycraft/scatter/kinds.json");
        for (Map.Entry<String, JsonElement> e : kinds.entrySet()) {
            if (e.getKey().startsWith("_")) continue;
            try {
                Kind pk = parseKind(e.getKey(), e.getValue().getAsJsonObject());
                if (pk.purpose == null || pk.purpose.isBlank() || pk.needs.isEmpty()) {
                    Skycraft.LOGGER.warn("Skycraft scatter: kind {} refused: no purpose/needs", pk.id);
                    continue;
                }
                r.kinds.put(e.getKey(), pk);
            } catch (RuntimeException ex) {
                Skycraft.LOGGER.warn("Skycraft scatter: bad kind {}", e.getKey(), ex);
            }
        }
        JsonObject rules = read("/skycraft/scatter/rules.json");
        if (rules.has("caps")) {
            JsonObject c = rules.getAsJsonObject("caps");
            r.caps.chunk = intOf(c, "chunk", 12);
            r.caps.perKind = intOf(c, "perKind", 2);
            r.caps.attempts = intOf(c, "attempts", 5);
        }
        r.reach.put(PlaceType.CAPITAL, 260);
        r.reach.put(PlaceType.CITY, 220);
        r.reach.put(PlaceType.TOWN, 160);
        r.reach.put(PlaceType.VILLAGE, 110);
        r.reach.put(PlaceType.FARM, 80);
        if (rules.has("reach")) {
            for (Map.Entry<String, JsonElement> e : rules.getAsJsonObject("reach").entrySet()) {
                r.reach.put(PlaceType.byId(e.getKey()), e.getValue().getAsInt());
            }
        }
        if (rules.has("wayside")) {
            JsonObject w = rules.getAsJsonObject("wayside");
            if (w.has("density")) r.wayside.density = w.get("density").getAsDouble();
            for (Map.Entry<String, JsonElement> ke : w.getAsJsonObject("kinds").entrySet()) {
                Kind k = r.kinds.get(ke.getKey());
                if (k == null) continue;
                r.wayside.kinds.add(k);
                r.wayside.weights.add(ke.getValue().getAsDouble() * k.rarity);
                r.wayside.total += ke.getValue().getAsDouble() * k.rarity;
            }
        }
        if (rules.has("zones")) {
            for (Map.Entry<String, JsonElement> e : rules.getAsJsonObject("zones").entrySet()) {
                ScatterZone z = ScatterZone.byId(e.getKey());
                if (z == null) {
                    Skycraft.LOGGER.warn("Skycraft scatter: unknown zone {}", e.getKey());
                    continue;
                }
                List<Group> groups = new ArrayList<>();
                for (JsonElement ge : e.getValue().getAsJsonArray()) {
                    JsonObject go = ge.getAsJsonObject();
                    Group g = new Group();
                    g.scale = go.has("scale") ? go.get("scale").getAsString() : "none";
                    g.density = go.get("density").getAsDouble();
                    for (Map.Entry<String, JsonElement> ke : go.getAsJsonObject("kinds").entrySet()) {
                        Kind k = r.kinds.get(ke.getKey());
                        if (k == null) {
                            Skycraft.LOGGER.warn("Skycraft scatter: zone {} names unknown kind {}", e.getKey(), ke.getKey());
                            continue;
                        }
                        double w = ke.getValue().getAsDouble() * k.rarity;
                        if (w <= 0) continue;
                        g.kinds.add(k);
                        g.weights.add(w);
                        g.total += w;
                    }
                    groups.add(g);
                }
                r.zones.put(z, groups);
            }
        }
        // clusters must name real kinds
        for (Kind k : r.kinds.values()) {
            if (k.cluster == null) continue;
            k.cluster.members.removeIf(m -> {
                boolean missing = !r.kinds.containsKey(m);
                if (missing) Skycraft.LOGGER.warn("Skycraft scatter: cluster {} names unknown kind {}", k.id, m);
                return missing;
            });
        }
        Skycraft.LOGGER.info("Skycraft scatter: {} kinds, {} zones", r.kinds.size(), r.zones.size());
        return r;
    }

    private static int intOf(JsonObject o, String k, int d) {
        return o.has(k) ? o.get(k).getAsInt() : d;
    }

    private static Set<String> strings(JsonObject o, String k) {
        Set<String> out = new HashSet<>();
        if (o.has(k)) for (JsonElement e : o.getAsJsonArray(k)) out.add(e.getAsString());
        return out;
    }

    private static Kind parseKind(String id, JsonObject o) {
        Kind k = new Kind();
        k.id = id;
        Set<String> tags = strings(o, "tags");
        k.human = tags.contains("human");
        if (o.has("ground")) {
            k.ground = EnumSet.noneOf(Ground.class);
            for (JsonElement e : o.getAsJsonArray("ground")) k.ground.add(Ground.byId(e.getAsString()));
        }
        k.provinces = strings(o, "provinces");
        k.denyProvinces = strings(o, "denyProvinces");
        k.minSlope = intOf(o, "minSlope", 0);
        k.maxSlope = intOf(o, "maxSlope", 2);
        k.radius = intOf(o, "radius", 3);
        k.wet = o.has("wet") && o.get("wet").getAsBoolean();
        k.waterMin = intOf(o, "waterMin", -1);
        k.waterMax = intOf(o, "waterMax", -1);
        if (o.has("minElev")) k.minElev = o.get("minElev").getAsFloat();
        if (o.has("maxElev")) k.maxElev = o.get("maxElev").getAsFloat();
        k.rarity = o.has("rarity") ? o.get("rarity").getAsFloat() : 1f;
        k.follow = o.has("follow") && o.get("follow").getAsBoolean();
        k.items = intOf(o, "items", 1);
        if (o.has("builder")) k.builder = o.get("builder").getAsString();
        if (o.has("params")) k.params = o.getAsJsonObject("params");
        if (o.has("variants")) {
            for (JsonElement v : o.getAsJsonArray("variants")) {
                List<Entry> entries = new ArrayList<>();
                for (JsonElement ee : v.getAsJsonArray()) entries.add(parseEntry(ee.getAsJsonObject()));
                k.variants.add(entries);
            }
        }
        if (o.has("cluster")) {
            JsonObject c = o.getAsJsonObject("cluster");
            Cluster cl = new Cluster();
            cl.min = intOf(c, "min", 3);
            cl.max = intOf(c, "max", 6);
            cl.radius = intOf(c, "radius", 6);
            for (JsonElement m : c.getAsJsonArray("members")) cl.members.add(m.getAsString());
            k.cluster = cl;
        }
        k.purpose = o.has("purpose") ? o.get("purpose").getAsString() : null;
        k.clue = o.has("clue") ? o.get("clue").getAsString() : null;
        k.needs = strings(o, "needs");
        boolean anchor = o.has("anchor") && o.get("anchor").getAsBoolean();
        if (k.builder == null && k.cluster == null && !anchor) {
            int most = 0;
            for (List<Entry> v : k.variants) {
                int n = 0;
                for (Entry en : v) {
                    n += en.p2 == null ? 1 : (Math.abs(en.p2[0] - en.p[0]) + 1) * (Math.abs(en.p2[1] - en.p[1]) + 1) * (Math.abs(en.p2[2] - en.p[2]) + 1);
                }
                most = Math.max(most, n);
            }
            k.isolated = most < 3;
        }
        if (k.builder == null && k.variants.isEmpty() && k.cluster == null) {
            throw new IllegalArgumentException("kind " + id + " has no builder, variants or cluster");
        }
        return k;
    }

    private static int[] ints(JsonArray a) {
        int[] r = new int[a.size()];
        for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsInt();
        return r;
    }

    private static Entry parseEntry(JsonObject o) {
        Entry e = new Entry();
        if (o.has("fill")) {
            e.type = "fill";
            int[] f = ints(o.getAsJsonArray("fill"));
            e.p = new int[]{f[0], f[1], f[2]};
            e.p2 = new int[]{f[3], f[4], f[5]};
        } else if (o.has("chest")) {
            e.type = "chest";
            e.p = ints(o.getAsJsonArray("chest"));
        } else if (o.has("barrel")) {
            e.type = "barrel";
            e.p = ints(o.getAsJsonArray("barrel"));
        } else if (o.has("skull")) {
            e.type = "skull";
            e.p = ints(o.getAsJsonArray("skull"));
        } else if (o.has("banner")) {
            e.type = "banner";
            e.p = ints(o.getAsJsonArray("banner"));
        } else if (o.has("item")) {
            e.type = "item";
            e.p = ints(o.getAsJsonArray("p"));
            e.item = o.get("item").getAsString();
        } else {
            e.p = ints(o.getAsJsonArray("p"));
        }
        if (o.has("b")) {
            JsonElement b = o.get("b");
            if (b.isJsonArray()) {
                JsonArray a = b.getAsJsonArray();
                e.blocks = new String[a.size()];
                for (int i = 0; i < e.blocks.length; i++) e.blocks[i] = a.get(i).getAsString();
            } else {
                e.blocks = new String[]{b.getAsString()};
            }
        }
        if (o.has("loot")) e.loot = o.get("loot").getAsString();
        if (o.has("facing")) e.facing = o.get("facing").getAsString();
        if (o.has("color")) e.color = o.get("color").getAsString();
        if (o.has("chance")) e.chance = o.get("chance").getAsFloat();
        if (o.has("count")) e.count = o.get("count").getAsInt();
        if (o.has("damage")) e.damage = o.get("damage").getAsFloat();
        return e;
    }
}
