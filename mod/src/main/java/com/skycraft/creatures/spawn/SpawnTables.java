package com.skycraft.creatures.spawn;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skycraft.Skycraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Skyrim-style leveled lists for the open world: {@code data/<ns>/region_spawns/*.json}. Each file holds one rule or
 * an array of rules:
 * <pre>
 * { "provinces": ["skyrim"], "regions": ["skyrim/the_reach"], "families": ["frozen", "mountain"],
 *   "biomes": ["#minecraft:is_forest", "skycraft:reach_crags"],
 *   "groups": [
 *     { "kind": "hostile", "weight": 10, "levels": [1, 30], "time": "night", "place": "surface",
 *       "entity": "skycraft:wolf", "count": [2, 4] },
 *     { "kind": "hostile", "weight": 3, "members": [ {"entity": "skycraft:forsworn", "count": [2, 3]},
 *                                                    {"entity": "skycraft:hagraven", "count": [0, 1]} ] }
 *   ] }
 * </pre>
 * Every condition is optional (empty = any). {@code kind}: hostile or wildlife (separate budgets); {@code time}: day,
 * night, dawn, dusk or any; {@code place}: surface, cave, water or any; {@code levels}: the region level band
 * ({@code Leveling.regionLevel}) the group appears in. Optional ecology fields on a group: {@code minY}/{@code maxY}
 * (surface height band), {@code weather} (clear, rain, storm), {@code road} (on, off), {@code townMin}/{@code townMax}
 * (blocks from the nearest settlement), {@code nearPlace}/{@code nearDist} (within reach of a place type such as
 * dungeon_fixed, fort or landmark) and {@code tag} (a label for tools). Unknown entity ids and tags are skipped, so data can name
 * creatures of other modules or mods safely. A group with {@code "quiet": true} and no entity is a "nothing happens"
 * entry: when the weighted pick lands on it the attempt ends quietly (no spawn, no cooldown). That is the data-side
 * knob for peaceful stretches: the more quiet weight a table carries, the fewer of its attempts become encounters.
 */
public final class SpawnTables extends SimpleJsonResourceReloadListener<JsonElement> {
    public enum Kind { HOSTILE, WILDLIFE }

    /** DAWN (first light) and DUSK are slices of DAY; DAY itself is any daylight. */
    public enum Time { ANY, DAY, NIGHT, DAWN, DUSK }

    public enum Weather { ANY, CLEAR, RAIN, STORM }

    /** ON: only on or beside a road; OFF: never on or beside one; ANY: no opinion. */
    public enum RoadRule { ANY, ON, OFF }

    /**
     * Optional ecology conditions of a group (all default to "no opinion"): surface height band, weather, road rule,
     * distance band from the nearest settlement (blocks; {@code townMax} 0 = unlimited), {@code nearPlaces} (place
     * types, e.g. barrows are dungeon_fixed; the group only appears within {@code nearDist} blocks of one) and a
     * free-text {@code tag} (predator, passive, humanoid, undead, rare...) that the ecology report reads.
     */
    public record Where(int minY, int maxY, Weather weather, RoadRule road, int townMin, int townMax, String tag,
                        List<com.skycraft.tamriel.PlaceType> nearPlaces, int nearDist) {
        public static final Where ANYWHERE = new Where(Integer.MIN_VALUE, Integer.MAX_VALUE, Weather.ANY, RoadRule.ANY, 0, 0, "",
                List.of(), 0);

        public boolean needsTown() {
            return townMin > 0 || townMax > 0;
        }
    }

    public enum Placement { SURFACE, CAVE, WATER, ANY }

    public record Member(EntityType<?> type, int min, int max) {}

    public record Group(Kind kind, int weight, int minLevel, int maxLevel, Time time, Placement place, List<Member> members,
                        Where where) {}

    public record Rule(Set<String> provinces, Set<String> regions, EnumSet<BiomeFamily> families,
                       List<TagKey<Biome>> biomeTags, Set<Identifier> biomeIds, List<Group> groups) {}

    private static volatile List<Rule> RULES = List.of();

    public SpawnTables() {
        super(net.minecraft.util.ExtraCodecs.JSON, net.minecraft.resources.FileToIdConverter.json("region_spawns"));
    }

    public static List<Rule> rules() {
        return RULES;
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        List<Rule> out = new ArrayList<>();
        files.forEach((id, json) -> {
            try {
                if (json.isJsonArray()) {
                    for (JsonElement e : json.getAsJsonArray()) out.add(parseRule(e.getAsJsonObject()));
                } else {
                    out.add(parseRule(json.getAsJsonObject()));
                }
            } catch (RuntimeException ex) {
                Skycraft.LOGGER.warn("Bad region spawn file {}: {}", id, ex.toString());
            }
        });
        RULES = List.copyOf(out);
        int groups = out.stream().mapToInt(r -> r.groups().size()).sum();
        Skycraft.LOGGER.info("Loaded {} region spawn rules ({} groups)", out.size(), groups);
    }

    private static Set<String> strings(JsonObject o, String key) {
        Set<String> s = new java.util.HashSet<>();
        if (o.has(key)) for (JsonElement e : o.getAsJsonArray(key)) s.add(e.getAsString());
        return s;
    }

    private static Rule parseRule(JsonObject o) {
        EnumSet<BiomeFamily> fams = EnumSet.noneOf(BiomeFamily.class);
        for (String f : strings(o, "families")) fams.add(BiomeFamily.byId(f));
        List<TagKey<Biome>> tags = new ArrayList<>();
        Set<Identifier> ids = new java.util.HashSet<>();
        for (String b : strings(o, "biomes")) {
            if (b.startsWith("#")) tags.add(TagKey.create(Registries.BIOME, Identifier.parse(b.substring(1))));
            else ids.add(Identifier.parse(b));
        }
        List<Group> groups = new ArrayList<>();
        for (JsonElement e : GsonHelper.getAsJsonArray(o, "groups", new JsonArray())) {
            parseGroup(e.getAsJsonObject()).ifPresent(groups::add);
        }
        return new Rule(strings(o, "provinces"), strings(o, "regions"), fams, tags, ids, groups);
    }

    private static Optional<Group> parseGroup(JsonObject g) {
        List<Member> members = new ArrayList<>();
        if (g.has("members")) {
            for (JsonElement m : g.getAsJsonArray("members")) member(m.getAsJsonObject()).ifPresent(members::add);
        } else if (g.has("entity")) {
            member(g).ifPresent(members::add);
        }
        boolean quiet = GsonHelper.getAsBoolean(g, "quiet", false);
        if (members.isEmpty() && !quiet) return Optional.empty();
        int[] levels = range(g, "levels", 1, 999);
        Kind kind = Kind.valueOf(GsonHelper.getAsString(g, "kind", "hostile").toUpperCase(java.util.Locale.ROOT));
        Time time = Time.valueOf(GsonHelper.getAsString(g, "time", "any").toUpperCase(java.util.Locale.ROOT));
        Placement place = Placement.valueOf(GsonHelper.getAsString(g, "place", "surface").toUpperCase(java.util.Locale.ROOT));
        Where where = new Where(GsonHelper.getAsInt(g, "minY", Integer.MIN_VALUE), GsonHelper.getAsInt(g, "maxY", Integer.MAX_VALUE),
                Weather.valueOf(GsonHelper.getAsString(g, "weather", "any").toUpperCase(java.util.Locale.ROOT)),
                RoadRule.valueOf(GsonHelper.getAsString(g, "road", "any").toUpperCase(java.util.Locale.ROOT)),
                GsonHelper.getAsInt(g, "townMin", 0), GsonHelper.getAsInt(g, "townMax", 0), GsonHelper.getAsString(g, "tag", ""),
                nearPlaces(g), GsonHelper.getAsInt(g, "nearDist", 200));
        return Optional.of(new Group(kind, Math.max(1, GsonHelper.getAsInt(g, "weight", 10)), levels[0], levels[1], time, place,
                List.copyOf(members), where));
    }

    private static List<com.skycraft.tamriel.PlaceType> nearPlaces(JsonObject g) {
        List<com.skycraft.tamriel.PlaceType> out = new ArrayList<>();
        if (g.has("nearPlace")) {
            for (JsonElement e : g.getAsJsonArray("nearPlace")) out.add(com.skycraft.tamriel.PlaceType.byId(e.getAsString()));
        }
        return List.copyOf(out);
    }

    private static Optional<Member> member(JsonObject m) {
        Identifier id = Identifier.parse(GsonHelper.getAsString(m, "entity"));
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
        if (type == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return Optional.empty();
        int[] count = range(m, "count", 1, 1);
        return Optional.of(new Member(type, count[0], Math.max(count[0], count[1])));
    }

    private static int[] range(JsonObject o, String key, int defMin, int defMax) {
        if (!o.has(key)) return new int[]{defMin, defMax};
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive()) {
            int v = e.getAsInt();
            return new int[]{v, v};
        }
        JsonArray a = e.getAsJsonArray();
        return new int[]{a.get(0).getAsInt(), a.get(a.size() - 1).getAsInt()};
    }
}
