package com.skycraft.creatures.spawn;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skycraft.Skycraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.registries.ForgeRegistries;

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
 * night or any; {@code place}: surface, cave, water or any; {@code levels}: the region level band
 * ({@code Leveling.regionLevel}) the group appears in. Unknown entity ids and tags are skipped, so data can name
 * creatures of other modules or mods safely.
 */
public final class SpawnTables extends SimpleJsonResourceReloadListener {
    public enum Kind { HOSTILE, WILDLIFE }

    public enum Time { ANY, DAY, NIGHT }

    public enum Placement { SURFACE, CAVE, WATER, ANY }

    public record Member(EntityType<?> type, int min, int max) {}

    public record Group(Kind kind, int weight, int minLevel, int maxLevel, Time time, Placement place, List<Member> members) {}

    public record Rule(Set<String> provinces, Set<String> regions, EnumSet<BiomeFamily> families,
                       List<TagKey<Biome>> biomeTags, Set<ResourceLocation> biomeIds, List<Group> groups) {}

    private static volatile List<Rule> RULES = List.of();

    public SpawnTables() {
        super(new com.google.gson.Gson(), "region_spawns");
    }

    public static List<Rule> rules() {
        return RULES;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
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
        Set<ResourceLocation> ids = new java.util.HashSet<>();
        for (String b : strings(o, "biomes")) {
            if (b.startsWith("#")) tags.add(TagKey.create(Registries.BIOME, new ResourceLocation(b.substring(1))));
            else ids.add(new ResourceLocation(b));
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
        } else {
            member(g).ifPresent(members::add);
        }
        if (members.isEmpty()) return Optional.empty();
        int[] levels = range(g, "levels", 1, 999);
        Kind kind = Kind.valueOf(GsonHelper.getAsString(g, "kind", "hostile").toUpperCase(java.util.Locale.ROOT));
        Time time = Time.valueOf(GsonHelper.getAsString(g, "time", "any").toUpperCase(java.util.Locale.ROOT));
        Placement place = Placement.valueOf(GsonHelper.getAsString(g, "place", "surface").toUpperCase(java.util.Locale.ROOT));
        return Optional.of(new Group(kind, Math.max(1, GsonHelper.getAsInt(g, "weight", 10)), levels[0], levels[1], time, place, List.copyOf(members)));
    }

    private static Optional<Member> member(JsonObject m) {
        ResourceLocation id = new ResourceLocation(GsonHelper.getAsString(m, "entity"));
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
        if (type == null || !ForgeRegistries.ENTITY_TYPES.containsKey(id)) return Optional.empty();
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
