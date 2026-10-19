package com.nirncraft.quest.story;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nirncraft.NirnCraft;
import com.nirncraft.core.SkyData;
import com.nirncraft.quest.Locate;
import com.nirncraft.quest.Names;
import com.nirncraft.quest.Objective;
import com.nirncraft.quest.Quest;
import com.nirncraft.quest.QuestSpawner.Spawn;
import com.nirncraft.quest.RadiantQuests;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.TamrielMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Data-driven radiant quests in the manner of Daggerfall's quest archetypes (pest-ridden houses, named-creature
 * hunts, kidnappings, haunted homes, timed couriers, stolen heirlooms, purges of profane shrines, night watches...),
 * flavored per culture: {@code nirncraft/quests/radiant_templates.json}. {@link ProvinceRadiant} draws from them
 * besides its built-in work.
 *
 * <p>Template: {@code {"id","cultures":["breton",...|"*"],"category":"bounty|misc|side","weight","gold","min_level",
 * "title","desc","pitch","names":[...],"steps":[{"do":"clear|kill|retrieve|go|wait|courier|return|bring","at":"wild|dungeon",
 * "foe":["type","Name"] or {"<culture>":["type","Name"],"default":[...]},"count":[min,max],"hp","relic","hours","text"}]}}.
 * Texts take {@code %1$s} direction, {@code %2$s} site, {@code %3$s} gold, {@code %4$s} named target, {@code %5$s} region,
 * {@code %6$s} destination town; lang keys {@code story.nirncraft.radiant.<id>.title|desc|pitch|s<n>}.</p>
 */
public final class RadiantTemplates {
    private static final String FILE = "/nirncraft/quests/radiant_templates.json";

    private record Template(String id, JsonObject json, List<String> cultures, int weight, int minLevel) {
    }

    @Nullable
    private static List<Template> templates;

    private RadiantTemplates() {}

    private static synchronized List<Template> all() {
        if (templates != null) return templates;
        List<Template> list = new ArrayList<>();
        JsonElement root = StoryData.read(FILE);
        if (root != null && root.isJsonObject()) {
            for (JsonElement e : root.getAsJsonObject().getAsJsonArray("templates")) {
                JsonObject o = e.getAsJsonObject();
                List<String> cultures = new ArrayList<>();
                for (JsonElement c : arr(o, "cultures")) cultures.add(c.getAsString());
                list.add(new Template(StoryData.str(o, "id", "?"), o, cultures, o.has("weight") ? o.get("weight").getAsInt() : 1,
                        o.has("min_level") ? o.get("min_level").getAsInt() : 1));
            }
        }
        NirnCraft.LOGGER.info("Loaded {} radiant quest templates", list.size());
        templates = list;
        return list;
    }

    public static int count() {
        return all().size();
    }

    /** Every template built once for a stand-in at a fixed spot (selftest): ids whose build failed. */
    public static List<String> selftest(ServerPlayer p, LivingEntity npc) {
        List<String> bad = new ArrayList<>();
        for (Template t : all()) {
            for (JsonElement e : arr(t.json, "steps")) {
                JsonObject s = e.getAsJsonObject();
                if (s.has("relic") && StoryData.relic(s.get("relic").getAsString()) == null) bad.add(t.id + " (unknown relic " + s.get("relic") + ")");
                for (String k : new String[]{"foe", "escort"}) {
                    if (!s.has(k)) continue;
                    List<JsonElement> foes = new ArrayList<>();
                    if (s.get(k).isJsonObject()) foes.addAll(s.getAsJsonObject(k).asMap().values());
                    else foes.add(s.get(k));
                    for (JsonElement f : foes) {
                        String type = f.getAsJsonArray().get(0).getAsString();
                        if (type.startsWith("npc:") ? com.nirncraft.society.NpcRole.byId(type.substring(4)) == null
                                : com.nirncraft.quest.QuestSpawner.resolveType(type).isEmpty()) bad.add(t.id + " (unknown entity " + type + ")");
                    }
                }
            }
            try {
                if (build(p, npc, TamrielMap.region(npc.getBlockX(), npc.getBlockZ()), "imperial", t, p.getRandom(), true) == null) bad.add(t.id);
            } catch (RuntimeException e) {
                bad.add(t.id + " (" + e + ")");
            }
        }
        return bad;
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    /** A weighted pick among the templates of this culture (and the universal ones), built for the player. */
    @Nullable
    public static RadiantQuests.Offer offer(ServerPlayer p, LivingEntity npc, Region region, String culture, RandomSource r) {
        int level = SkyData.get(p).getLevel();
        List<Template> pool = new ArrayList<>();
        int total = 0;
        for (Template t : all()) {
            if (level < t.minLevel) continue;
            if (!t.cultures.contains("*") && !t.cultures.contains(culture)) continue;
            pool.add(t);
            total += t.weight;
        }
        if (total <= 0) return null;
        int roll = r.nextInt(total);
        for (Template t : pool) {
            roll -= t.weight;
            if (roll < 0) return build(p, npc, region, culture, t, r, false);
        }
        return null;
    }

    private static Component txt(Template t, String part, Object... args) {
        return Component.translatableWithFallback("story.nirncraft.radiant." + t.id + "." + part, StoryData.str(t.json, part, t.id), args);
    }

    @Nullable
    private static RadiantQuests.Offer build(ServerPlayer p, LivingEntity npc, Region region, String culture, Template t,
                                             RandomSource r, boolean dry) {
        ServerLevel level = p.level();
        String dim = level.dimension().identifier().toString();
        JsonArray steps = arr(t.json, "steps");
        // where: the first step that needs a site decides it
        BlockPos site = null;
        Place town = null;
        for (JsonElement e : steps) {
            JsonObject s = e.getAsJsonObject();
            String what = StoryData.str(s, "do", "go");
            if (what.equals("courier") && town == null) {
                town = otherTown(region, npc.blockPosition(), r);
                if (town == null) return null;
            } else if (site == null && !what.equals("return") && !what.equals("bring")) {
                site = "dungeon".equals(StoryData.str(s, "at", "wild")) && !dry ? Locate.dungeon(level, p.blockPosition(), r) : null;
                if (site == null || TamrielMap.inTamriel(site.getX(), site.getZ())
                        && !region.province().equals(TamrielMap.region(site.getX(), site.getZ()).province())) {
                    site = wildSpot(level, p, r, region.province());
                }
            }
        }
        if (site == null) site = npc.blockPosition();
        Component dir = Names.direction(npc.blockPosition(), site);
        Component siteName = Component.literal(Names.ruin(site));
        JsonArray names = arr(t.json, "names");
        Component target = Component.literal(Names.contractTarget(r));
        if (!names.isEmpty()) {
            int i = r.nextInt(names.size());
            target = Component.translatableWithFallback("story.nirncraft.radiant." + t.id + ".n" + i, names.get(i).getAsString());
        }
        Component townName = town != null ? town.name() : region.name();
        int base = t.json.has("gold") ? t.json.get("gold").getAsInt() : 250;
        long gold = Math.max(100, Math.min(2500, Math.round(base * (1 + SkyData.get(p).getLevel() / 20.0))));
        Object[] args = {dir, siteName, gold, target, region.name(), townName};

        Quest.Category cat;
        try {
            cat = Quest.Category.valueOf(StoryData.str(t.json, "category", "misc").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            cat = Quest.Category.MISC;
        }
        Quest q = new Quest("radiant_" + t.id, cat, txt(t, "title", args), txt(t, "desc", args));
        q.giver = npc.getUUID();
        q.giverName = npc.getDisplayName();
        q.giverPos = npc.blockPosition();
        q.giverDim = dim;
        q.hold = region.id();
        int n = 0;
        for (JsonElement e : steps) {
            JsonObject s = e.getAsJsonObject();
            String what = StoryData.str(s, "do", "go");
            Component text = Component.translatableWithFallback("story.nirncraft.radiant." + t.id + ".s" + n, StoryData.str(s, "text", what), args);
            n++;
            String[] foe = foe(s, culture);
            int count = count(s, r);
            Objective o;
            switch (what) {
                case "clear" -> {
                    o = new Objective(Objective.Type.CLEAR_AREA, text).at(site, dim, 32).guessY().count(count)
                            .spawn(spawn(foe, count, s, false).build()).label(siteName.getString());
                    if ("dungeon".equals(StoryData.str(s, "at", "wild"))) o.placement("structure");
                }
                case "kill" -> {
                    Spawn boss = spawn(foe, 1, s, true).name(target).target().boss();
                    o = new Objective(Objective.Type.KILL_TARGET, text).at(site, dim, 0).guessY().spawn(boss.build()).label(siteName.getString());
                    if (s.has("escort")) o.spawn(spawn(foe(escortJson(s), culture), count, s, false).build());
                    if ("dungeon".equals(StoryData.str(s, "at", "wild"))) o.placement("structure");
                }
                case "retrieve" -> {
                    o = new Objective(Objective.Type.RETRIEVE, text).at(site, dim, 12).guessY().label(siteName.getString());
                    o.data.putString(Story.RELIC, StoryData.str(s, "relic", "radiant_foreign"));
                    if (foe != null) o.spawn(spawn(foe, count, s, false).build());
                    if ("dungeon".equals(StoryData.str(s, "at", "wild"))) o.placement("structure");
                }
                case "wait" -> o = new Objective(Objective.Type.CONDITION, text).at(site, dim, 24).guessY()
                        .target("wait:" + Math.max(1, s.has("hours") ? s.get("hours").getAsInt() : 2) * 1000L).label(siteName.getString());
                case "courier" -> {
                    BlockPos at = new BlockPos(town.x(), Locate.surfaceY(level, town.x(), town.z()), town.z());
                    q.add(new Objective(Objective.Type.GO_TO, text).at(at, dim, 80).guessY().label(townName.getString()));
                    o = new Objective(Objective.Type.TALK_TO, Component.translatable("quest.nirncraft.obj.deliver")).filter("any")
                            .at(at, dim, Math.max(96, town.radius())).guessY()
                            .topic(Component.translatable("dialogue.nirncraft.story.parcel"), Component.translatable("quest.nirncraft.reply.thanks"))
                            .label(townName.getString());
                }
                case "bring" -> {
                    o = new Objective(Objective.Type.FETCH, text).npc(npc.getUUID()).at(npc.blockPosition(), dim, 0)
                            .topic(Component.translatable("dialogue.nirncraft.story.bring"), Component.translatable("quest.nirncraft.reply.thanks"))
                            .label(npc.getDisplayName().getString());
                    o.data.putString(Story.RELIC, StoryData.str(s, "relic", "radiant_foreign"));
                }
                case "return" -> o = new Objective(Objective.Type.TALK_TO, text).npc(npc.getUUID()).at(npc.blockPosition(), dim, 0)
                        .topic(Component.translatable("dialogue.nirncraft.story.done"), Component.translatable("quest.nirncraft.reply.thanks"))
                        .label(npc.getDisplayName().getString());
                default -> o = new Objective(Objective.Type.GO_TO, text).at(site, dim, 24).guessY().label(siteName.getString());
            }
            q.add(o);
        }
        q.reward(gold);
        return new RadiantQuests.Offer(q, txt(t, "pitch", args));
    }

    private static JsonObject escortJson(JsonObject s) {
        JsonObject o = new JsonObject();
        o.add("foe", s.get("escort"));
        return o;
    }

    @Nullable
    private static String[] foe(JsonObject s, String culture) {
        if (!s.has("foe")) return null;
        JsonElement f = s.get("foe");
        if (f.isJsonObject()) f = f.getAsJsonObject().has(culture) ? f.getAsJsonObject().get(culture) : f.getAsJsonObject().get("default");
        if (f == null || !f.isJsonArray()) return null;
        JsonArray a = f.getAsJsonArray();
        return new String[]{a.get(0).getAsString(), a.size() > 1 ? a.get(1).getAsString() : ""};
    }

    private static int count(JsonObject s, RandomSource r) {
        if (!s.has("count")) return 3;
        JsonElement c = s.get("count");
        if (c.isJsonArray()) {
            int lo = c.getAsJsonArray().get(0).getAsInt(), hi = c.getAsJsonArray().get(1).getAsInt();
            return lo + r.nextInt(Math.max(1, hi - lo + 1));
        }
        return Math.max(1, c.getAsInt());
    }

    private static Spawn spawn(@Nullable String[] foe, int count, JsonObject s, boolean target) {
        String type = foe != null ? foe[0] : "nirncraft:bandit";
        Spawn sp = Spawn.of(type, count);
        if (!target && foe != null && !foe[1].isEmpty()) sp.name(Component.literal(foe[1]));
        if (target && s.has("hp")) sp.health(s.get("hp").getAsFloat());
        if (s.has("glow") && s.get("glow").getAsBoolean()) sp.glow();
        return sp;
    }

    /** A spot 150..450 blocks away, in the same province when possible. */
    private static BlockPos wildSpot(ServerLevel level, ServerPlayer p, RandomSource r, String province) {
        BlockPos best = p.blockPosition();
        for (int i = 0; i < 8; i++) {
            BlockPos s = Locate.randomSpot(level, r, p.blockPosition(), 150, 450);
            best = s;
            if (province.equals(TamrielMap.region(s.getX(), s.getZ()).province()) && TamrielMap.elevation(s.getX(), s.getZ()) > TamrielMap.SEA_LEVEL + 1) return s;
        }
        return best;
    }

    @Nullable
    private static Place otherTown(Region region, BlockPos from, RandomSource r) {
        List<Place> pool = new ArrayList<>();
        for (Place p : Places.all()) {
            if (!p.type().isSettlement()) continue;
            String prov = p.regionId().contains("/") ? p.regionId().substring(0, p.regionId().indexOf('/')) : p.regionId();
            if (!prov.equals(region.province()) || p.distSqr(from.getX(), from.getZ()) < 200L * 200L) continue;
            pool.add(p);
        }
        return pool.isEmpty() ? null : pool.get(r.nextInt(pool.size()));
    }
}
