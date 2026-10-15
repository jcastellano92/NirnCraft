package com.skycraft.quest.story;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import com.skycraft.quest.Quest;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The story quests of Tamriel as data: questlines, quests, objectives, rewards, named quest NPCs, quest relics and
 * scripted scenes, loaded once from the class path ({@code skycraft/quests/index.json} lists the files).
 *
 * <p>Every text is written inline in English and gets a stable language key derived from where it sits (see
 * {@link Txt}); {@code tools/quest_lang.js} exports the keys into {@code lang/skycraft/quest_story.json}, so
 * translations and resource packs can replace any line while the data stays the single source.</p>
 *
 * <p>File format (all fields optional unless noted):</p>
 * <pre>
 * { "line": "companions", "category": "faction", "faction": "companions",
 *   "speakers": {"alduin": "Alduin"},
 *   "npcs":   [{"id","name","role","place","offset":[dx,dz],"female","essential","greet"}],
 *   "relics": [{"id","name","desc","icon"}],
 *   "scenes": {"id": [["speaker", "text"], ...]},
 *   "bosses": {"id": {"name","type","hp","dmg","level","weapon","armor","gear":{"head","chest","legs","feet","offhand"},
 *                     "drop":"unique:&lt;id&gt;|loot:&lt;tier&gt;|ns:item","glow"}},
 *   "quests": [{"id" (required),"title","desc","giver","start":"auto|npc:&lt;id&gt;|manual|find","offer","pitch",
 *               "find":{"place","offset","radius","clue":"skeleton|stone|lever|altar|grave|lectern|urn|totem","text"},
 *               "requires":["quest:x","flag:y","!flag:z","level:5","faction:college:1"],"stage":3,
 *               "on_start":"actions","on_complete":"actions","outro":"journal text",
 *               "rewards":{"gold","items":[],"perks","words":[],"house","flags":[],"title","faction_points","join",
 *                          "loot":"minor|standard|major|legendary","unique":"&lt;legendary unique id&gt;"},
 *               "objectives":[{"type" (required),"text","place","offset","radius","npc","topic","reply","journal",
 *                              "item","count","entity","shout","flag","cond","hours","dim","relic","flee_at",
 *                              "spawn":[{spawn spec} or {"boss_id": "&lt;boss&gt;"}],"placement","at_player","label","actions",
 *                              "choices":[{"npc","label","reply","actions"}]}]}] }
 * </pre>
 */
public final class StoryData {
    private static final String ROOT = "/skycraft/quests/";

    /** A line of text with its language key; the inline English is the fallback. */
    public record Txt(String key, String text) {
        public MutableComponent c(Object... args) {
            return Component.translatableWithFallback(key, text, args);
        }

        public boolean isEmpty() {
            return text == null || text.isEmpty();
        }
    }

    public record Choice(String npc, Txt label, Txt reply, String actions) {
    }

    public static final class ObjDef {
        public String type = "go";
        public Txt text, topic, reply, journal;
        public String place = "";
        public int dx, dz;
        public int radius = -1;
        public String npc = "";
        public String item = "";
        public int count = 1;
        public String entity = "";
        public String shout = "";
        public String flag = "";
        public String cond = "";
        public int hours;
        public String dim = "";
        public String relic = "";
        public float fleeAt;
        public final List<JsonObject> spawns = new ArrayList<>();
        public String placement = "";
        public boolean atPlayer;
        public String label = "";
        public String actions = "";
        public final List<Choice> choices = new ArrayList<>();
    }

    public static final class Rewards {
        public long gold;
        public final List<JsonElement> items = new ArrayList<>();
        public int perks;
        public final List<String> words = new ArrayList<>();
        public String house = "";
        public final List<String> flags = new ArrayList<>();
        public String title = "";
        public int factionPoints;
        public boolean join;
        public int souls;
        /** A loot-module reward tier rolled per rewarded player ({@code minor}, {@code standard}, {@code major}, {@code legendary}). */
        public String loot = "";
        /** A legendary unique ({@code loot/LegendaryUniques}) handed to every rewarded player. */
        public String unique = "";
    }

    /**
     * How a hidden quest begins ({@code "start": "find"}): a clue left in the world at a place (a skeleton, a carved
     * stone, a lever...) that starts the quest when a player touches it or stands on it. See {@link Discoveries}.
     */
    public static final class FindDef {
        public String place = "";
        public int dx, dz;
        /** Trigger distance around the clue, in blocks. */
        public int radius = 3;
        public String clue = "stone";
        public Txt text;
    }

    public static final class QuestDef {
        public String id = "";
        public String line = "";
        public Quest.Category category = Quest.Category.SIDE;
        public String faction = "";
        public Txt title, desc, offer, pitch, outro;
        public String giver = "";
        public String start = "manual";
        public final List<String> requires = new ArrayList<>();
        public int stage;
        public String onStart = "";
        public String onComplete = "";
        public final List<ObjDef> objectives = new ArrayList<>();
        public final Rewards rewards = new Rewards();
        public String region = "";
        public int order;
        /** May be taken again after completion (radiant battles). */
        public boolean repeatable;
        /** Values resolved when the quest starts, referenced as {@code @name} in places and actions. */
        public final Map<String, String> vars = new LinkedHashMap<>();
        /** The clue that starts a hidden quest ({@code start: "find"}), or null. */
        @Nullable
        public FindDef find;

        public boolean isMain() {
            return category == Quest.Category.MAIN;
        }

        /** The NPC that offers this quest ({@code start: "npc:<id>"}), or empty. */
        public String offeredBy() {
            return start.startsWith("npc:") ? start.substring(4) : "";
        }
    }

    public static final class NpcDef {
        public String id = "";
        public Txt name, greet;
        public String role = "adventurer";
        public String place = "";
        public int dx, dz;
        public boolean female;
        public boolean essential = true;
        public String dim = "minecraft:overworld";
        /** Optional entity type id to use instead of a society NPC (e.g. a talking dragon from the creatures module). */
        public String entity = "";
        /** Faction id whose guild hall in {@link #place} is this NPC's home (they stand at the hall's head desk). */
        public String hall = "";
    }

    public record RelicDef(String id, Txt name, Txt desc, int icon) {
    }

    public record SceneLine(String speaker, Txt text) {
    }

    /**
     * A unique named foe ({@code bosses} block of a quest file): an existing creature with a name, level, health and
     * damage multipliers, gear and a drop. A spawn spec {@code {"boss_id": "<id>"}} becomes this boss as the
     * objective's target; the action {@code boss:<id>} raises it next to the party.
     */
    public record BossDef(String id, Txt name, JsonObject spec) {
    }

    private static final Map<String, QuestDef> QUESTS = new LinkedHashMap<>();
    private static final Map<String, NpcDef> NPCS = new LinkedHashMap<>();
    private static final Map<String, RelicDef> RELICS = new LinkedHashMap<>();
    private static final Map<String, List<SceneLine>> SCENES = new LinkedHashMap<>();
    private static final Map<String, Txt> SPEAKERS = new LinkedHashMap<>();
    private static final Map<String, List<QuestDef>> BY_LINE = new LinkedHashMap<>();
    private static final Map<String, BossDef> BOSSES = new LinkedHashMap<>();
    private static boolean loaded;

    private StoryData() {}

    // ------------------------------------------------------------------ queries

    public static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        load();
    }

    @Nullable
    public static QuestDef quest(String id) {
        ensureLoaded();
        return QUESTS.get(id);
    }

    public static Collection<QuestDef> quests() {
        ensureLoaded();
        return Collections.unmodifiableCollection(QUESTS.values());
    }

    public static List<QuestDef> line(String line) {
        ensureLoaded();
        return BY_LINE.getOrDefault(line, List.of());
    }

    public static Collection<String> lines() {
        ensureLoaded();
        return Collections.unmodifiableCollection(BY_LINE.keySet());
    }

    @Nullable
    public static NpcDef npc(String id) {
        ensureLoaded();
        return NPCS.get(id);
    }

    public static Collection<NpcDef> npcs() {
        ensureLoaded();
        return Collections.unmodifiableCollection(NPCS.values());
    }

    @Nullable
    public static RelicDef relic(String id) {
        ensureLoaded();
        return RELICS.get(id);
    }

    public static Collection<RelicDef> relics() {
        ensureLoaded();
        return Collections.unmodifiableCollection(RELICS.values());
    }

    public static List<SceneLine> scene(String id) {
        ensureLoaded();
        return SCENES.getOrDefault(id, List.of());
    }

    @Nullable
    public static BossDef boss(String id) {
        ensureLoaded();
        return BOSSES.get(id);
    }

    public static Collection<BossDef> bosses() {
        ensureLoaded();
        return Collections.unmodifiableCollection(BOSSES.values());
    }

    /** Display name of a scene speaker: a quest NPC, a declared speaker or the id itself. */
    public static Component speaker(String id) {
        NpcDef npc = npc(id);
        if (npc != null) return npc.name.c();
        Txt t = SPEAKERS.get(id);
        if (t != null) return t.c();
        return Component.literal(id.isEmpty() ? "" : Character.toUpperCase(id.charAt(0)) + id.substring(1));
    }

    // ------------------------------------------------------------------ loading

    private static void load() {
        JsonElement index = read(ROOT + "index.json");
        if (index == null || !index.isJsonArray()) {
            Skycraft.LOGGER.warn("Story quest index is missing");
            return;
        }
        for (JsonElement f : index.getAsJsonArray()) {
            String file = f.getAsString();
            JsonElement root = read(ROOT + file);
            if (root == null || !root.isJsonObject()) {
                Skycraft.LOGGER.warn("Story quest file {} is missing or not an object", file);
                continue;
            }
            try {
                loadFile(root.getAsJsonObject());
            } catch (RuntimeException e) {
                Skycraft.LOGGER.error("Bad story quest file {}", file, e);
            }
        }
        expandBosses();
        validate();
        Skycraft.LOGGER.info("Loaded {} story quests in {} questlines, {} quest NPCs, {} relics, {} scenes, {} unique bosses",
                QUESTS.size(), BY_LINE.size(), NPCS.size(), RELICS.size(), SCENES.size(), BOSSES.size());
    }

    /**
     * Turns every {@code {"boss_id": "<id>"}} spawn into the boss's full spec (its own fields win over the boss's,
     * so a quest may still say {@code "count"} or {@code "if"}). Bosses may live in any file, hence after loading.
     */
    private static void expandBosses() {
        for (QuestDef q : QUESTS.values()) {
            for (ObjDef o : q.objectives) {
                for (JsonObject s : o.spawns) {
                    if (!s.has("boss_id")) continue;
                    BossDef b = BOSSES.get(str(s, "boss_id", ""));
                    if (b == null) {
                        Skycraft.LOGGER.warn("Story quest {} names unknown boss {}", q.id, str(s, "boss_id", ""));
                        continue;
                    }
                    bossSpec(b, s);
                }
            }
        }
    }

    /** Writes a boss's spec into {@code into} (fields already there are kept). */
    static void bossSpec(BossDef b, JsonObject into) {
        for (Map.Entry<String, JsonElement> e : b.spec().entrySet()) {
            if (!into.has(e.getKey())) into.add(e.getKey(), e.getValue().deepCopy());
        }
        if (!into.has("target")) into.addProperty("target", true);
        into.addProperty("boss", true);
        into.addProperty("name", b.name().text());
        into.addProperty("name_key", b.name().key());
    }

    private static void loadFile(JsonObject root) {
        String line = str(root, "line", "misc");
        Quest.Category category = category(str(root, "category", "side"));
        String faction = str(root, "faction", "");
        String region = str(root, "region", "");

        if (root.has("speakers")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("speakers").entrySet()) {
                SPEAKERS.put(e.getKey(), new Txt("story.skycraft.speaker." + e.getKey(), e.getValue().getAsString()));
            }
        }
        for (JsonElement e : arr(root, "npcs")) {
            JsonObject o = e.getAsJsonObject();
            NpcDef n = new NpcDef();
            n.id = str(o, "id", "");
            n.name = new Txt("story.skycraft.npc." + n.id, str(o, "name", n.id));
            n.greet = new Txt("story.skycraft.npc." + n.id + ".greet", str(o, "greet", ""));
            n.role = str(o, "role", "adventurer");
            n.place = str(o, "place", "");
            int[] off = offset(o);
            n.dx = off[0];
            n.dz = off[1];
            n.female = o.has("female") && o.get("female").getAsBoolean();
            n.essential = !o.has("essential") || o.get("essential").getAsBoolean();
            n.dim = str(o, "dim", "minecraft:overworld");
            n.entity = str(o, "entity", "");
            n.hall = str(o, "hall", "");
            if (NPCS.putIfAbsent(n.id, n) != null) Skycraft.LOGGER.warn("Duplicate quest NPC {}", n.id);
        }
        // fallback anchors for the lore sites this questline visits (same format as places.json)
        if (root.has("places")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("places").entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                QuestPlaces.declare(e.getKey(), o.get("x").getAsInt(), o.get("z").getAsInt(), o.has("r") ? o.get("r").getAsInt() : 48,
                        str(o, "name", e.getKey()), str(o, "dim", "minecraft:overworld"));
            }
        }
        for (JsonElement e : arr(root, "relics")) {
            JsonObject o = e.getAsJsonObject();
            String id = str(o, "id", "");
            RELICS.put(id, new RelicDef(id, new Txt("item.skycraft.relic." + id, str(o, "name", id)),
                    new Txt("item.skycraft.relic." + id + ".desc", str(o, "desc", "")), o.has("icon") ? o.get("icon").getAsInt() : 0));
        }
        if (root.has("scenes") && root.get("scenes").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("scenes").entrySet()) {
                if (!e.getValue().isJsonArray()) continue;
                List<SceneLine> lines = new ArrayList<>();
                int n = 0;
                for (JsonElement l : e.getValue().getAsJsonArray()) {
                    if (l.isJsonArray()) {
                        JsonArray pair = l.getAsJsonArray();
                        if (pair.size() >= 2) {
                            lines.add(new SceneLine(pair.get(0).getAsString(),
                                    new Txt("story.skycraft.scene." + e.getKey() + "." + n++, pair.get(1).getAsString())));
                        }
                    } else if (l.isJsonPrimitive()) {
                        String raw = l.getAsString();
                        int colon = raw.indexOf(':');
                        String spk = colon > 0 ? raw.substring(0, colon).trim() : "narrator";
                        String txt = colon > 0 ? raw.substring(colon + 1).trim() : raw.trim();
                        lines.add(new SceneLine(spk,
                                new Txt("story.skycraft.scene." + e.getKey() + "." + n++, txt)));
                    }
                }
                SCENES.put(e.getKey(), lines);
            }
        }
        if (root.has("bosses")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("bosses").entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject().deepCopy();
                Txt name = new Txt("story.skycraft.boss." + e.getKey(), str(o, "name", e.getKey()));
                o.remove("name");
                if (BOSSES.putIfAbsent(e.getKey(), new BossDef(e.getKey(), name, o)) != null) Skycraft.LOGGER.warn("Duplicate story boss {}", e.getKey());
            }
        }
        int order = 0;
        for (JsonElement e : arr(root, "quests")) {
            QuestDef q = quest(e.getAsJsonObject(), line, category, faction, region);
            q.order = order++;
            if (QUESTS.putIfAbsent(q.id, q) != null) {
                Skycraft.LOGGER.warn("Duplicate story quest {}", q.id);
                continue;
            }
            BY_LINE.computeIfAbsent(line, k -> new ArrayList<>()).add(q);
        }
    }

    private static QuestDef quest(JsonObject o, String line, Quest.Category category, String faction, String region) {
        QuestDef q = new QuestDef();
        q.id = str(o, "id", "");
        String k = "story.skycraft.q." + q.id;
        q.line = line;
        q.category = o.has("category") ? category(str(o, "category", "side")) : category;
        q.faction = str(o, "faction", faction);
        q.region = str(o, "region", region);
        q.title = new Txt(k + ".title", str(o, "title", q.id));
        q.desc = new Txt(k + ".desc", str(o, "desc", ""));
        q.offer = new Txt(k + ".offer", str(o, "offer", ""));
        q.pitch = new Txt(k + ".pitch", str(o, "pitch", ""));
        q.outro = new Txt(k + ".outro", str(o, "outro", ""));
        q.giver = str(o, "giver", "");
        q.start = str(o, "start", "manual");
        for (JsonElement r : arr(o, "requires")) q.requires.add(r.getAsString());
        q.stage = o.has("stage") ? o.get("stage").getAsInt() : 0;
        q.onStart = str(o, "on_start", "");
        q.onComplete = str(o, "on_complete", "");
        q.repeatable = o.has("repeatable") && o.get("repeatable").getAsBoolean();
        if (o.has("vars")) {
            for (Map.Entry<String, JsonElement> v : o.getAsJsonObject("vars").entrySet()) q.vars.put(v.getKey(), v.getValue().getAsString());
        }
        int i = 0;
        for (JsonElement e : arr(o, "objectives")) q.objectives.add(objective(e.getAsJsonObject(), k + ".o" + i++));
        if (o.has("rewards")) {
            JsonObject r = o.getAsJsonObject("rewards");
            Rewards w = q.rewards;
            w.gold = r.has("gold") ? r.get("gold").getAsLong() : 0;
            for (JsonElement it : arr(r, "items")) w.items.add(it);
            w.perks = r.has("perks") ? r.get("perks").getAsInt() : 0;
            for (JsonElement it : arr(r, "words")) w.words.add(it.getAsString());
            w.house = str(r, "house", "");
            for (JsonElement it : arr(r, "flags")) w.flags.add(it.getAsString());
            w.title = str(r, "title", "");
            w.factionPoints = r.has("faction_points") ? r.get("faction_points").getAsInt() : 0;
            w.join = r.has("join") && r.get("join").getAsBoolean();
            w.souls = r.has("souls") ? r.get("souls").getAsInt() : 0;
            w.loot = str(r, "loot", "");
            w.unique = str(r, "unique", "");
        }
        if (o.has("find")) {
            JsonObject f = o.getAsJsonObject("find");
            FindDef d = new FindDef();
            d.place = str(f, "place", "");
            int[] off = offset(f);
            d.dx = off[0];
            d.dz = off[1];
            d.radius = f.has("radius") ? f.get("radius").getAsInt() : 3;
            d.clue = str(f, "clue", "stone");
            d.text = new Txt(k + ".find", str(f, "text", ""));
            q.find = d;
        }
        return q;
    }

    private static ObjDef objective(JsonObject o, String k) {
        ObjDef d = new ObjDef();
        d.type = str(o, "type", "go");
        d.text = new Txt(k + ".text", str(o, "text", ""));
        d.topic = new Txt(k + ".topic", str(o, "topic", ""));
        d.reply = new Txt(k + ".reply", str(o, "reply", ""));
        d.journal = new Txt(k + ".journal", str(o, "journal", ""));
        d.place = str(o, "place", "");
        int[] off = offset(o);
        d.dx = off[0];
        d.dz = off[1];
        d.radius = o.has("radius") ? o.get("radius").getAsInt() : -1;
        d.npc = str(o, "npc", "");
        d.item = str(o, "item", "");
        d.count = o.has("count") ? o.get("count").getAsInt() : 1;
        d.entity = str(o, "entity", "");
        d.shout = str(o, "shout", "");
        d.flag = str(o, "flag", "");
        d.cond = str(o, "cond", "");
        d.hours = o.has("hours") ? o.get("hours").getAsInt() : 0;
        d.dim = str(o, "dim", "");
        d.relic = str(o, "relic", "");
        d.fleeAt = o.has("flee_at") ? o.get("flee_at").getAsFloat() : 0f;
        for (JsonElement s : arr(o, "spawn")) d.spawns.add(s.getAsJsonObject());
        d.placement = str(o, "placement", "");
        d.atPlayer = o.has("at_player") && o.get("at_player").getAsBoolean();
        d.label = str(o, "label", "");
        d.actions = str(o, "actions", "");
        int j = 0;
        for (JsonElement c : arr(o, "choices")) {
            JsonObject co = c.getAsJsonObject();
            String ck = k + ".c" + j++;
            d.choices.add(new Choice(str(co, "npc", ""), new Txt(ck + ".label", str(co, "label", "")),
                    new Txt(ck + ".reply", str(co, "reply", "")), str(co, "actions", "")));
        }
        return d;
    }

    private static void validate() {
        for (QuestDef q : QUESTS.values()) {
            String by = q.offeredBy();
            if (!by.isEmpty() && !NPCS.containsKey(by)) Skycraft.LOGGER.warn("Story quest {} is offered by unknown NPC {}", q.id, by);
            for (String r : q.requires) {
                for (String part : r.split("\\|")) {
                    String c = part.startsWith("!") ? part.substring(1) : part;
                    if (c.startsWith("quest:") && !QUESTS.containsKey(c.substring(6))) {
                        Skycraft.LOGGER.warn("Story quest {} requires unknown quest {}", q.id, c);
                    }
                }
            }
            for (ObjDef o : q.objectives) {
                for (String n : o.npc.split("\\|")) {
                    if (!n.isEmpty() && !NPCS.containsKey(n)) Skycraft.LOGGER.warn("Story quest {} names unknown NPC {}", q.id, n);
                }
                if (!o.relic.isEmpty() && !RELICS.containsKey(o.relic)) Skycraft.LOGGER.warn("Story quest {} names unknown relic {}", q.id, o.relic);
            }
        }
    }

    // ------------------------------------------------------------------ json helpers

    private static Quest.Category category(String s) {
        try {
            return Quest.Category.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Quest.Category.SIDE;
        }
    }

    private static int[] offset(JsonObject o) {
        if (!o.has("offset")) return new int[]{0, 0};
        JsonArray a = o.getAsJsonArray("offset");
        return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt()};
    }

    static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    private static JsonArray arr(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    @Nullable
    static JsonElement read(String path) {
        try (InputStream in = StoryData.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Skycraft.LOGGER.error("Couldn't read {}", path, e);
            return null;
        }
    }
}
