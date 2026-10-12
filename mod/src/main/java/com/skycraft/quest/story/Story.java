package com.skycraft.quest.story;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skycraft.Skycraft;
import com.skycraft.core.Currency;
import com.skycraft.core.Holds;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.magic.MagicData;
import com.skycraft.magic.shout.Shout;
import com.skycraft.quest.Faction;
import com.skycraft.quest.Factions;
import com.skycraft.quest.Locate;
import com.skycraft.quest.MainQuest;
import com.skycraft.quest.Objective;
import com.skycraft.quest.Quest;
import com.skycraft.quest.QuestConfig;
import com.skycraft.quest.QuestItems;
import com.skycraft.quest.QuestSpawner;
import com.skycraft.quest.QuestStore;
import com.skycraft.quest.Quests;
import com.skycraft.quest.party.Party;
import com.skycraft.quest.party.PartyManager;
import com.skycraft.tamriel.Regions;
import com.skycraft.world.WorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runtime of the data-driven story quests ({@link StoryData}): availability, building a {@link Quest} from its
 * definition (places, NPCs, spawns, rewards), journal entries, chaining, actions, party-linked progress of the
 * main quest, stage jumps for testing and the "skip this step" fallback against broken states.
 *
 * <p>Per-player state in {@code module("quest")}: {@code story} (quest id -> times completed) and
 * {@code story_flags} (choices and milestones, e.g. {@code helgen_ralof}, {@code thane_whiterun}).</p>
 */
public final class Story {
    /** {@link Quest#extra} keys. */
    public static final String DEF = "story";
    public static final String JOURNAL = "journal";
    public static final String VARS = "vars";
    public static final String REWARDS = "story_rewards";
    /** {@link Objective#data} keys. */
    public static final String NPC = "npcId";
    public static final String RELIC = "relic";
    public static final String FLEE = "fleeAt";
    public static final String CHOICES = "choices";
    public static final String AT_PLAYER = "atPlayer";
    public static final String BEGIN = "begin";
    public static final String SKIP_AT = "skip_at";
    public static final String ACTIONS = "actions";

    private static final String DONE = "story";
    private static final String FLAGS = "story_flags";
    private static boolean linking;

    private Story() {}

    // ------------------------------------------------------------------ player state

    public static boolean completed(PlayerData d, String questId) {
        return d.module("quest").getCompound(DONE).getInt(questId) > 0;
    }

    private static void markCompleted(PlayerData d, String questId) {
        CompoundTag mod = d.module("quest");
        CompoundTag done = mod.getCompound(DONE);
        done.putInt(questId, done.getInt(questId) + 1);
        mod.put(DONE, done);
        d.markDirty();
    }

    public static boolean hasFlag(PlayerData d, String flag) {
        return d.module("quest").getCompound(FLAGS).getBoolean(flag);
    }

    public static void setFlag(PlayerData d, String flag, boolean value) {
        CompoundTag mod = d.module("quest");
        CompoundTag flags = mod.getCompound(FLAGS);
        if (value) flags.putBoolean(flag, true);
        else flags.remove(flag);
        mod.put(FLAGS, flags);
        d.markDirty();
    }

    /** Clears every story completion and flag of the player (admin reset). */
    public static void reset(PlayerData d) {
        d.module("quest").remove(DONE);
        d.module("quest").remove(FLAGS);
        d.markDirty();
    }

    // ------------------------------------------------------------------ conditions

    /**
     * Evaluates one requirement for a player: {@code quest:<id>}, {@code flag:<f>}, {@code level:<n>},
     * {@code faction:<id>[:rank]}, {@code word:<shout>}, {@code dragonborn}, {@code night}, {@code day},
     * {@code item:<id>}, {@code relic:<id>}, {@code souls:<n>}, {@code discover:<place>}, {@code region:<id>},
     * {@code province:<id>}, {@code dim:<id>}, {@code helgen}; a leading {@code !} negates it.
     */
    public static boolean test(ServerPlayer p, String cond) {
        if (cond == null || cond.isEmpty()) return true;
        if (cond.indexOf('|') > 0) {
            // any of several: "quest:col_first_lessons|faction:college"
            for (String part : cond.split("\\|")) if (test(p, part)) return true;
            return false;
        }
        if (cond.startsWith("!")) return !test(p, cond.substring(1));
        PlayerData d = SkyData.get(p);
        int colon = cond.indexOf(':');
        String kind = colon < 0 ? cond : cond.substring(0, colon);
        String arg = colon < 0 ? "" : cond.substring(colon + 1);
        switch (kind) {
            case "quest":
                return completed(d, arg);
            case "active":
                return Quests.find(p.server, p.getUUID(), arg) != null && Quests.find(p.server, p.getUUID(), arg).quest().isActive();
            case "flag":
                return hasFlag(d, arg);
            case "level":
                return d.getLevel() >= parseInt(arg, 1);
            case "faction": {
                String[] parts = arg.split(":");
                Faction f = Faction.byId(parts[0]);
                if (f == null) return false;
                return Factions.rank(d, f) >= (parts.length > 1 ? parseInt(parts[1], 0) : 0);
            }
            case "word": {
                if (arg.isEmpty()) {
                    for (Shout s : Shout.VALUES) if (MagicData.wordsLearned(p, s) > 0) return true;
                    return false;
                }
                Shout s = Shout.byId(arg);
                return s != null && MagicData.wordsLearned(p, s) > 0;
            }
            case "race": {
                // race:dunmer or race:altmer,bosmer,khajiit
                com.skycraft.core.Race race = d.getRace();
                if (race == null) return false;
                for (String r : arg.split(",")) if (race.id().equals(r.trim())) return true;
                return false;
            }
            case "skill": {
                // skill:mysticism:25
                String[] parts = arg.split(":");
                for (com.skycraft.core.Skill s : com.skycraft.core.Skill.VALUES) {
                    if (s.id().equals(parts[0])) return d.getSkill(s) >= (parts.length > 1 ? parseInt(parts[1], 1) : 1);
                }
                return false;
            }
            case "false":
                return false;
            case "dragonborn":
                return MagicData.tag(p).getBoolean("dragonborn");
            case "souls":
                return MagicData.dragonSouls(p) >= parseInt(arg, 1);
            case "night":
                return Factions.isNight(p.level());
            case "day":
                return !Factions.isNight(p.level());
            case "item": {
                Item item = item(arg);
                return item != null && Quests.count(p, item) > 0;
            }
            case "relic":
                return countRelic(p, arg) > 0;
            case "discover": {
                CompoundTag loc = WorldData.find(d, WorldData.placeLocId(arg));
                return loc != null && !loc.getBoolean("known");
            }
            case "region":
                return Holds.migrate(arg).equals(Holds.holdAt(p.level(), p.blockPosition()));
            case "province":
                return arg.equals(Regions.at(p.level(), p.blockPosition()).province());
            case "dim":
                return p.level().dimension().location().toString().equals(arg);
            case "end_dragon_slain": {
                ServerLevel end = p.server.getLevel(Level.END);
                return end != null && end.getDragonFight() != null && end.getDragonFight().hasPreviouslyKilledDragon();
            }
            case "enemy_forts":
                return CivilWar.enemyFort(p.server, arg, p.getRandom()) != null;
            case "entity":
                return entityTypeExists(arg);
            case "mod":
                return net.minecraftforge.fml.ModList.get().isLoaded(arg);
            case "curse":
                // curse:vampire | curse:werewolf | curse:vampire_lord | curse:sanguinare (curse module)
                return switch (arg) {
                    case "vampire" -> com.skycraft.curse.Curses.isVampire(p);
                    case "werewolf" -> com.skycraft.curse.Curses.isWerewolf(p);
                    case "vampire_lord" -> com.skycraft.curse.Curses.hasVampireLord(p);
                    case "sanguinare" -> com.skycraft.curse.Curses.hasSanguinare(p);
                    default -> false;
                };
            case "control": {
                // control:<region>:<side>
                int c = arg.lastIndexOf(':');
                return c > 0 && CivilWar.controller(p.server, arg.substring(0, c)).equals(arg.substring(c + 1));
            }
            case "true":
                return true;
            default:
                Skycraft.LOGGER.debug("Unknown story condition {}", cond);
                return false;
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static boolean requirementsMet(ServerPlayer p, StoryData.QuestDef def) {
        for (String r : def.requires) if (!test(p, r)) return false;
        return true;
    }

    /** The player's (or their party's) running copy of a story quest, or null. */
    @Nullable
    public static Quests.Ref activeRef(ServerPlayer p, String questId) {
        for (Quests.Ref r : Quests.active(p.server, p.getUUID())) if (r.quest().id.equals(questId)) return r;
        return null;
    }

    /** Whether the player can take this quest now. */
    public static boolean available(ServerPlayer p, StoryData.QuestDef def) {
        if (def.isMain() && !QuestConfig.MAIN_QUEST.get()) return false;
        if (!def.repeatable && completed(SkyData.get(p), def.id)) return false;
        if (activeRef(p, def.id) != null) return false;
        return requirementsMet(p, def);
    }

    // ------------------------------------------------------------------ starting

    /** Starts a story quest for the player (personally for the main quest, else for their party). */
    public static boolean start(ServerPlayer starter, StoryData.QuestDef def) {
        Quest q = build(starter, def);
        if (q == null) return false;
        MinecraftServer server = starter.server;
        String key = def.isMain() ? Quests.personalKey(starter.getUUID()) : Quests.keyForNew(server, starter.getUUID());
        // one copy per owner: a finished one with the same id leaves the history
        QuestStore.get(server).quests(key).removeIf(old -> old.id.equals(q.id) && !old.isActive());
        if (!Quests.start(server, key, q, starter)) return false;
        if (def.stage > 0) setMainStage(starter, def.stage);
        if (!def.onStart.isEmpty()) runActions(server, key, q, def.onStart, starter);
        return true;
    }

    /** Starts every auto-start quest whose requirements the player now meets. Every five seconds per player. */
    public static void tickPlayer(ServerPlayer p) {
        if (SkyData.get(p).getRace() == null) return;
        for (StoryData.QuestDef def : StoryData.quests()) {
            if (!"auto".equals(def.start) || !available(p, def)) continue;
            if (!def.isMain() && !Quests.canTakeMore(p)) continue;
            start(p, def);
        }
    }

    private static void setMainStage(ServerPlayer p, int stage) {
        PlayerData d = SkyData.get(p);
        CompoundTag mod = d.module("quest");
        if (mod.getInt(MainQuest.STAGE) < stage) {
            mod.putInt(MainQuest.STAGE, stage);
            d.markDirty();
        }
    }

    // ------------------------------------------------------------------ building

    /** Builds the quest for {@code starter}; null if a dynamic part (a fort to attack...) can't be resolved. */
    @Nullable
    public static Quest build(ServerPlayer starter, StoryData.QuestDef def) {
        ServerLevel level = starter.server.overworld();
        CompoundTag vars = new CompoundTag();
        for (Map.Entry<String, String> v : def.vars.entrySet()) {
            String value = resolveVar(starter.server, v.getValue(), vars, starter.getRandom());
            if (value == null) return null;
            vars.putString(v.getKey(), value);
        }
        Quest q = new Quest(def.line + "_story", def.category, def.title.c(), def.desc.c());
        q.id = def.id;
        q.extra.putString(DEF, def.id);
        q.extra.put(VARS, vars);
        if (!def.giver.isEmpty()) {
            StoryData.NpcDef giver = StoryData.npc(def.giver);
            if (giver != null) q.giverName = giver.name.c();
            BlockPos a = QuestNpcs.anchor(starter.server, def.giver);
            if (a != null) q.giverPos = a;
        }
        q.hold = !def.region.isEmpty() ? def.region : Holds.holdAt(starter.level(), starter.blockPosition());
        for (int i = 0; i < def.objectives.size(); i++) {
            Objective o = objective(starter.server, level, def, i, def.objectives.get(i), vars);
            if (o == null) return null;
            q.add(o);
        }
        // rewards
        StoryData.Rewards r = def.rewards;
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < r.items.size(); i++) {
            ItemStack s = rewardItem(r.items.get(i), "story.skycraft.q." + def.id + ".r" + i);
            if (!s.isEmpty()) items.add(s);
        }
        q.reward(r.gold, items.toArray(new ItemStack[0]));
        Faction f = Faction.byId(def.faction);
        if (f != null) q.faction(f.id, Math.max(1, r.factionPoints), r.join);
        CompoundTag sr = new CompoundTag();
        if (r.perks > 0) sr.putInt("perks", r.perks);
        if (r.souls > 0) sr.putInt("souls", r.souls);
        if (!r.house.isEmpty()) sr.putString("house", r.house);
        if (!r.title.isEmpty()) sr.putString("title", r.title);
        ListTag words = new ListTag();
        for (String w : r.words) words.add(StringTag.valueOf(w));
        if (!words.isEmpty()) sr.put("words", words);
        ListTag flags = new ListTag();
        for (String fl : r.flags) flags.add(StringTag.valueOf(fl));
        if (!flags.isEmpty()) sr.put("flags", flags);
        if (def.stage > 0) sr.putInt("stage", def.stage);
        if (!r.loot.isEmpty()) sr.putString("loot", r.loot);
        if (!r.unique.isEmpty()) sr.putString("unique", r.unique);
        q.extra.put(REWARDS, sr);
        if (!def.desc.isEmpty()) addJournal(q, def.desc.c());
        return q;
    }

    @Nullable
    private static String resolveVar(MinecraftServer server, String expr, CompoundTag vars, RandomSource r) {
        if (expr.startsWith("enemy_fort:")) return CivilWar.enemyFort(server, expr.substring(11), r);
        if (expr.startsWith("fort_region:")) return CivilWar.fortRegion(sub(vars, expr.substring(12)));
        if (expr.startsWith("land:")) return Discoveries.landSite(expr.substring(5));
        if (expr.startsWith("pick:")) {
            String[] opts = expr.substring(5).split(",");
            return opts[r.nextInt(opts.length)].trim();
        }
        return sub(vars, expr);
    }

    /** Replaces {@code @name} tokens with quest variables. */
    static String sub(CompoundTag vars, String s) {
        if (s == null || s.indexOf('@') < 0) return s;
        String out = s;
        for (String k : vars.getAllKeys()) out = out.replace("@" + k, vars.getString(k));
        return out;
    }

    private static Component placeName(String id) {
        return QuestPlaces.name(id);
    }

    @Nullable
    private static Objective objective(MinecraftServer server, ServerLevel level, StoryData.QuestDef def, int idx,
                                       StoryData.ObjDef od, CompoundTag vars) {
        String placeId = sub(vars, od.place);
        Optional<QuestPlaces.Spot> spot = QuestPlaces.find(placeId);
        if (!placeId.isEmpty() && spot.isEmpty()) Skycraft.LOGGER.warn("Story quest {} names unknown place {}", def.id, placeId);
        BlockPos pos = spot.map(s -> new BlockPos(s.x() + od.dx, Locate.surfaceY(level, s.x() + od.dx, s.z() + od.dz), s.z() + od.dz)).orElse(null);
        String dim = !od.dim.isEmpty() ? od.dim : spot.map(QuestPlaces.Spot::dim).orElse("minecraft:overworld");
        int radius = od.radius >= 0 ? od.radius : spot.map(s -> Math.max(16, Math.min(48, s.radius() / 2))).orElse(24);
        String npcSpec = od.npc;
        String firstNpc = npcSpec.isEmpty() ? "" : npcSpec.split("\\|")[0];
        Component npcName = firstNpc.isEmpty() ? Component.empty() : StoryData.speaker(firstNpc);
        Component where = placeId.isEmpty() ? Component.empty() : placeName(placeId);
        String label = !od.label.isEmpty() ? od.label : !placeId.isEmpty() ? where.getString() : npcName.getString();

        Objective o;
        switch (od.type) {
            case "go" -> {
                o = new Objective(Objective.Type.GO_TO, text(od, "go", where));
                if (pos != null) o.at(pos, dim, radius).guessY();
                else if (!od.dim.isEmpty()) {
                    o.dim = od.dim;
                    o.target = "dimension";
                }
            }
            case "dimension" -> {
                o = new Objective(Objective.Type.GO_TO, text(od, "dimension", Component.literal(od.dim)));
                o.dim = od.dim;
                o.target = "dimension";
            }
            case "talk", "deliver" -> {
                boolean deliver = od.type.equals("deliver");
                Component thing = relicOrItemName(od);
                o = new Objective(deliver ? Objective.Type.FETCH : Objective.Type.TALK_TO,
                        deliver ? text(od, "deliver", thing, npcName) : text(od, "talk", npcName));
                o.data.putString(NPC, npcSpec);
                BlockPos a = firstNpc.isEmpty() ? pos : QuestNpcs.anchor(server, firstNpc);
                if (a != null) o.at(a, firstNpc.isEmpty() ? dim : QuestNpcs.anchorDim(server, firstNpc), 0).guessY();
                o.topic = !od.topic.isEmpty() ? od.topic.c() : deliver ? Component.translatable("story.skycraft.default.deliver_topic", thing)
                        : Component.translatable("story.skycraft.default.topic", def.title.c());
                o.reply = !od.reply.isEmpty() ? od.reply.c() : Component.translatable("story.skycraft.default.reply");
                if (deliver) {
                    if (!od.relic.isEmpty()) o.data.putString(RELIC, od.relic);
                    else o.target = od.item;
                    o.count(od.count);
                }
                if (!od.choices.isEmpty()) {
                    ListTag list = new ListTag();
                    for (StoryData.Choice c : od.choices) {
                        CompoundTag t = new CompoundTag();
                        t.putString("npc", c.npc());
                        t.putString("label", Quest.writeComponent(c.label().c()));
                        t.putString("reply", Quest.writeComponent(c.reply().c()));
                        t.putString("actions", c.actions());
                        list.add(t);
                    }
                    o.data.put(CHOICES, list);
                }
            }
            case "kill" -> {
                o = new Objective(Objective.Type.KILL_TARGET, text(od, "kill", spawnName(def, idx, od), where));
                if (pos != null) o.at(pos, dim, 0).guessY();
                if (od.fleeAt > 0) o.data.putFloat(FLEE, od.fleeAt);
            }
            case "kill_type" -> {
                o = new Objective(Objective.Type.KILL_TYPE_COUNT, text(od, "kill_type", Component.literal(String.valueOf(od.count))));
                o.target(od.entity).count(od.count);
                if (pos != null) o.at(pos, dim, Math.max(radius, 64)).guessY();
            }
            case "clear" -> {
                o = new Objective(Objective.Type.CLEAR_AREA, text(od, "clear", where));
                if (pos != null) o.at(pos, dim, Math.max(radius, 32)).guessY();
            }
            case "retrieve" -> {
                o = new Objective(Objective.Type.RETRIEVE, text(od, "retrieve", relicOrItemName(od), where));
                if (pos != null) o.at(pos, dim, Math.max(6, Math.min(radius, 24))).guessY();
                if (!od.relic.isEmpty()) o.data.putString(RELIC, od.relic);
                else o.target = od.item;
            }
            case "escort" -> {
                o = new Objective(Objective.Type.ESCORT, text(od, "escort", npcName, where));
                o.data.putString(NPC, firstNpc);
                if (pos != null) o.at(pos, dim, Math.max(12, Math.min(radius, 32))).guessY();
            }
            case "shout" -> {
                Shout s = Shout.byId(od.shout);
                Component shoutName = s != null ? s.displayName() : Component.literal(od.shout);
                o = new Objective(Objective.Type.CONDITION, text(od, "shout", shoutName));
                o.target = "shout:" + od.shout;
                if (pos != null) o.at(pos, dim, Math.max(radius, 24)).guessY();
            }
            case "discover" -> {
                o = new Objective(Objective.Type.CONDITION, text(od, "discover", where));
                o.target = "story:discover:" + placeId;
                if (pos != null) o.at(pos, dim, radius).guessY();
            }
            case "wait" -> {
                o = new Objective(Objective.Type.CONDITION, text(od, "wait", Component.literal(String.valueOf(od.hours))));
                o.target = "wait:" + Math.max(1, od.hours) * 1000L;
                if (pos != null) o.at(pos, dim, radius).guessY();
            }
            case "word" -> {
                Shout s = Shout.byId(od.shout);
                o = new Objective(Objective.Type.CONDITION, text(od, "word", s != null ? s.displayName() : Component.literal(od.shout)));
                o.target = od.shout.isEmpty() ? "word" : "story:word:" + od.shout;
                if (pos != null) o.at(pos, dim, radius).guessY();
            }
            case "flag" -> {
                o = new Objective(Objective.Type.CONDITION, text(od, "flag"));
                o.target = "story:flag:" + od.flag;
                if (pos != null) o.at(pos, dim, radius).guessY();
            }
            case "crime" -> {
                o = new Objective(Objective.Type.CONDITION, text(od, "crime", Component.literal(String.valueOf(od.count))));
                o.target = "crime:" + od.cond;
                o.count(od.count);
                if (pos != null) o.at(pos, dim, radius).guessY();
            }
            case "cond" -> {
                o = new Objective(Objective.Type.CONDITION, text(od, "cond"));
                o.target = "story:" + od.cond;
                if (pos != null) o.at(pos, dim, radius).guessY();
            }
            default -> {
                Skycraft.LOGGER.warn("Story quest {} has unknown objective type {}", def.id, od.type);
                o = new Objective(Objective.Type.GO_TO, text(od, "go", where));
                if (pos != null) o.at(pos, dim, radius).guessY();
            }
        }
        // enemies, guardians and scenery
        int count = 0;
        for (int j = 0; j < od.spawns.size(); j++) {
            CompoundTag spec = spawnSpec(def, idx, j, od.spawns.get(j));
            o.spawn(spec);
            if (!spec.getBoolean("decor")) count += Math.max(1, spec.getInt("count"));
        }
        if (o.type == Objective.Type.CLEAR_AREA) o.count(od.count > 1 ? od.count : Math.max(1, count));
        if (!od.placement.isEmpty()) o.placement(od.placement);
        if (od.atPlayer) {
            o.data.putBoolean(AT_PLAYER, true);
            o.hasPos = false;
            o.dim = dim;
            // a monster of that kind may die anywhere around the party, not just at the spawn spot
            if (o.type == Objective.Type.KILL_TYPE_COUNT) o.radius = 512;
        }
        if (!od.actions.isEmpty()) o.onComplete(sub(vars, od.actions));
        if (!od.journal.isEmpty()) o.data.putString(JOURNAL, Quest.writeComponent(od.journal.c()));
        o.label(label);
        return o;
    }

    private static Component text(StoryData.ObjDef od, String kind, Object... args) {
        if (!od.text.isEmpty()) return od.text.c(args);
        return Component.translatable("story.skycraft.default." + kind, args);
    }

    private static Component relicOrItemName(StoryData.ObjDef od) {
        if (!od.relic.isEmpty()) {
            StoryData.RelicDef r = StoryData.relic(od.relic);
            return r != null ? r.name().c() : Component.literal(od.relic);
        }
        Item item = item(od.item);
        return item != null ? item.getDescription() : Component.literal(od.item);
    }

    private static Component spawnName(StoryData.QuestDef def, int idx, StoryData.ObjDef od) {
        for (int j = 0; j < od.spawns.size(); j++) {
            JsonObject s = od.spawns.get(j);
            if (s.has("target") && s.get("target").getAsBoolean() && s.has("name")) {
                return Component.translatableWithFallback(spawnNameKey(def, idx, j, s), s.get("name").getAsString());
            }
        }
        return Component.translatable("story.skycraft.default.foe");
    }

    /** A named boss ({@code bosses} block) keeps its own name key; other spawns are keyed by where they sit. */
    private static String spawnNameKey(StoryData.QuestDef def, int idx, int j, JsonObject s) {
        return s.has("name_key") ? s.get("name_key").getAsString() : "story.skycraft.q." + def.id + ".o" + idx + ".s" + j;
    }

    private static CompoundTag spawnSpec(StoryData.QuestDef def, int idx, int j, JsonObject s) {
        CompoundTag t = new CompoundTag();
        t.putString("type", StoryData.str(s, "type", "skycraft:bandit"));
        t.putInt("count", s.has("count") ? s.get("count").getAsInt() : 1);
        if (s.has("name")) {
            t.putString("name", Quest.writeComponent(Component.translatableWithFallback(spawnNameKey(def, idx, j, s), s.get("name").getAsString())));
        }
        Bosses.extraSpec(s, t);
        for (String b : new String[]{"target", "boss", "glow", "decor", "hostile", "invulnerable"}) {
            if (s.has(b) && s.get(b).getAsBoolean()) t.putBoolean(b, true);
        }
        if (s.has("hp")) t.putFloat("hp", s.get("hp").getAsFloat());
        if (s.has("dmg")) t.putFloat("dmg", s.get("dmg").getAsFloat());
        if (s.has("armor")) {
            String a = s.get("armor").getAsString();
            int color = a.equals("imperial") ? CivilWar.IMPERIAL_RED : a.equals("stormcloak") ? CivilWar.STORMCLOAK_BLUE
                    : Integer.parseInt(a.replace("#", ""), 16);
            t.putInt("armor", color);
        }
        if (s.has("weapon")) t.putString("weapon", s.get("weapon").getAsString());
        if (s.has("if")) t.putString("if", s.get("if").getAsString());
        if (s.has("circle")) {
            var a = s.getAsJsonArray("circle");
            t.putIntArray("circle", new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
        }
        return t;
    }

    /** A reward item: {@code "ns:id"}, {@code "ns:id*3"} or {@code {"item","count","name","enchant":{id:lvl},"color"}}. */
    static ItemStack rewardItem(JsonElement e, String nameKey) {
        if (e.isJsonPrimitive()) return stack(e.getAsString());
        JsonObject o = e.getAsJsonObject();
        ItemStack s = stack(StoryData.str(o, "item", ""));
        if (s.isEmpty()) return s;
        if (o.has("count")) s.setCount(Math.max(1, Math.min(s.getMaxStackSize(), o.get("count").getAsInt())));
        if (o.has("color")) s.getOrCreateTagElement("display").putInt("color", Integer.parseInt(o.get("color").getAsString().replace("#", ""), 16));
        if (o.has("name")) s.setHoverName(Component.translatableWithFallback(nameKey, o.get("name").getAsString()));
        if (o.has("enchant")) {
            for (Map.Entry<String, JsonElement> en : o.getAsJsonObject("enchant").entrySet()) {
                ResourceLocation id = ResourceLocation.tryParse(en.getKey().contains(":") ? en.getKey() : "minecraft:" + en.getKey());
                Enchantment ench = id == null ? null : ForgeRegistries.ENCHANTMENTS.getValue(id);
                if (ench != null) s.enchant(ench, en.getValue().getAsInt());
            }
        }
        return s;
    }

    static ItemStack stack(String spec) {
        if (spec == null || spec.isEmpty()) return ItemStack.EMPTY;
        int count = 1;
        String id = spec;
        int star = spec.indexOf('*');
        if (star > 0) {
            id = spec.substring(0, star);
            count = parseInt(spec.substring(star + 1), 1);
        }
        if (id.startsWith("relic:")) return relicStack(id.substring(6));
        // a legendary unique of the loot module (a place quest's lost heirloom)
        if (id.startsWith("unique:")) return com.skycraft.loot.LegendaryUniques.create(id.substring(7));
        Item item = item(id);
        if (item == null) return ItemStack.EMPTY;
        return new ItemStack(item, Math.max(1, Math.min(item.getMaxStackSize(), count)));
    }

    @Nullable
    static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item item = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
        return item == null || item == Items.AIR ? null : item;
    }

    // ------------------------------------------------------------------ relics

    public static ItemStack relicStack(String id) {
        ItemStack s = new ItemStack(QuestItems.QUEST_RELIC.get());
        CompoundTag tag = s.getOrCreateTag();
        tag.putString(QuestItems.RELIC_TAG, id);
        tag.putBoolean("skycraft_quest", true);
        StoryData.RelicDef def = StoryData.relic(id);
        if (def != null && def.icon() > 0) tag.putInt("CustomModelData", def.icon());
        return s;
    }

    public static int countRelic(Player p, String id) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (isRelic(s, id)) n += s.getCount();
        for (ItemStack s : p.getInventory().offhand) if (isRelic(s, id)) n += s.getCount();
        return n;
    }

    public static boolean isRelic(ItemStack s, String id) {
        return s.is(QuestItems.QUEST_RELIC.get()) && s.getTag() != null && id.equals(s.getTag().getString(QuestItems.RELIC_TAG));
    }

    public static void removeRelic(Player p, String id, int amount) {
        for (List<ItemStack> list : List.of(p.getInventory().items, p.getInventory().offhand)) {
            for (ItemStack s : list) {
                if (amount <= 0) return;
                if (isRelic(s, id)) {
                    int take = Math.min(amount, s.getCount());
                    s.shrink(take);
                    amount -= take;
                }
            }
        }
    }

    // ------------------------------------------------------------------ journal

    public static void addJournal(Quest q, Component entry) {
        ListTag list = q.extra.getList(JOURNAL, Tag.TAG_STRING);
        list.add(StringTag.valueOf(Quest.writeComponent(entry)));
        q.extra.put(JOURNAL, list);
    }

    @Nullable
    public static StoryData.QuestDef defOf(Quest q) {
        String id = q.extra.getString(DEF);
        return id.isEmpty() ? null : StoryData.quest(id);
    }

    // ------------------------------------------------------------------ lifecycle hooks (called by Quests)

    /**
     * An objective became current: remember when (for "wait" and the skip fallback), find its NPC's real spot, and
     * if it points at a named place of the world, the party hears of it (it appears on their map and compass).
     */
    public static void onBegin(MinecraftServer server, String key, Quest q, Objective o) {
        long now = server.overworld().getGameTime();
        o.data.putLong(BEGIN, now);
        long skip = 24000L * QuestConfig.SKIP_DAYS.get();
        o.data.putLong(SKIP_AT, skip > 0 ? now + skip : 0);
        String npc = o.data.getString(NPC);
        if (!npc.isEmpty() && (o.type == Objective.Type.TALK_TO || o.type == Objective.Type.FETCH)) {
            String first = npc.split("\\|")[0];
            BlockPos at = QuestNpcs.lastPos(server, first);
            if (at != null) {
                o.at(at, QuestNpcs.anchorDim(server, first), 0);
                o.yKnown = QuestNpcs.find(server, first) != null;
            }
        }
        if (o.hasPos && "minecraft:overworld".equals(o.dim)) {
            com.skycraft.tamriel.Place place = null;
            long best = Long.MAX_VALUE;
            for (com.skycraft.tamriel.Place p : com.skycraft.tamriel.Places.overlapping(o.pos.getX(), o.pos.getZ(), o.pos.getX(), o.pos.getZ())) {
                long d = p.distSqr(o.pos.getX(), o.pos.getZ());
                if (d <= (long) p.radius() * p.radius() && d < best) {
                    best = d;
                    place = p;
                }
            }
            if (place != null) for (ServerPlayer m : Quests.onlineMembers(server, key)) com.skycraft.world.Discovery.revealPlace(m, place);
        }
    }

    /** An objective was completed: journal entry, and party members on the same main quest step complete it too. */
    public static void onObjectiveCompleted(MinecraftServer server, String key, Quest q, Objective o, @Nullable ServerPlayer actor) {
        if (o.data.contains(JOURNAL)) addJournal(q, Quest.readComponent(o.data.getString(JOURNAL)));
        if (linking || !q.isMain() || !q.extra.contains(DEF) || !key.startsWith("p:")) return;
        int idx = q.objectives.indexOf(o);
        UUID owner;
        try {
            owner = UUID.fromString(key.substring(2));
        } catch (IllegalArgumentException e) {
            return;
        }
        Party party = PartyManager.get(server).partyOf(owner);
        if (party == null) return;
        ServerPlayer anchor = actor != null ? actor : server.getPlayerList().getPlayer(owner);
        if (anchor == null) return;
        linking = true;
        try {
            for (UUID id : party.memberIds()) {
                if (id.equals(owner)) continue;
                ServerPlayer m = server.getPlayerList().getPlayer(id);
                if (m == null || m.level() != anchor.level() || m.distanceToSqr(anchor) > 128 * 128) continue;
                String mk = Quests.personalKey(id);
                Quest mq = QuestStore.get(server).find(mk, q.id);
                if (mq == null || !mq.isActive()) continue;
                Objective cur = mq.current();
                if (cur != null && mq.objectives.indexOf(cur) == idx) Quests.completeObjective(server, mk, mq, cur, m);
            }
        } finally {
            linking = false;
        }
    }

    /**
     * Party play: a main-quest enemy of this step was already spawned for a party member nearby, so this member's
     * copy waits instead of spawning a second dragon; it completes through {@link #onObjectiveCompleted}.
     */
    public static boolean partnerSpawned(MinecraftServer server, String key, Quest q, Objective o) {
        if (!q.isMain() || !key.startsWith("p:")) return false;
        UUID owner;
        try {
            owner = UUID.fromString(key.substring(2));
        } catch (IllegalArgumentException e) {
            return false;
        }
        Party party = PartyManager.get(server).partyOf(owner);
        if (party == null) return false;
        int idx = q.objectives.indexOf(o);
        for (UUID id : party.memberIds()) {
            if (id.equals(owner)) continue;
            Quest mq = QuestStore.get(server).find(Quests.personalKey(id), q.id);
            if (mq == null || !mq.isActive()) continue;
            Objective cur = mq.current();
            if (cur != null && mq.objectives.indexOf(cur) == idx && cur.spawned && !cur.spawnedIds.isEmpty()
                    && cur.pos.distSqr(o.pos) < 128 * 128) return true;
        }
        return false;
    }

    /** The quest was completed (rewards are already paid): run its closing actions; the next auto quests follow. */
    public static void onQuestCompleted(MinecraftServer server, String key, Quest q) {
        StoryData.QuestDef def = defOf(q);
        if (def == null) return;
        if (!def.outro.isEmpty()) addJournal(q, def.outro.c());
        if (!def.onComplete.isEmpty()) runActions(server, key, q, sub(q.extra.getCompound(VARS), def.onComplete), null);
        for (ServerPlayer m : Quests.onlineMembers(server, key)) tickPlayer(m);
    }

    /** Story rewards beyond gold and items, per rewarded player (also delivered later to offline members). */
    public static void grantStoryRewards(ServerPlayer p, Quest q) {
        StoryData.QuestDef def = defOf(q);
        if (def == null) return;
        PlayerData d = SkyData.get(p);
        markCompleted(d, def.id);
        CompoundTag r = q.extra.getCompound(REWARDS);
        for (Tag t : r.getList("flags", Tag.TAG_STRING)) setFlag(d, t.getAsString(), true);
        if (r.getInt("perks") > 0) {
            d.setPerkPoints(d.getPerkPoints() + r.getInt("perks"));
            Notifier.message(p, Component.translatable("story.skycraft.reward.perks", r.getInt("perks")).withStyle(ChatFormatting.GOLD));
        }
        if (r.getInt("souls") > 0) {
            MagicData.setDragonSouls(p, MagicData.dragonSouls(p) + r.getInt("souls"));
            Notifier.message(p, Component.translatable("story.skycraft.reward.souls", r.getInt("souls")).withStyle(ChatFormatting.GOLD));
        }
        for (Tag t : r.getList("words", Tag.TAG_STRING)) teachWords(p, t.getAsString());
        // loot tiers roll for each rewarded player at their own loot level; a unique is everyone's own copy
        if (!r.getString("loot").isEmpty()) Bosses.giveLoot(p, r.getString("loot"));
        if (!r.getString("unique").isEmpty()) Bosses.giveUnique(p, r.getString("unique"));
        if (!r.getString("house").isEmpty()) grantHouse(p, r.getString("house"));
        if (!r.getString("title").isEmpty()) {
            d.module("quest").putString("title", r.getString("title"));
            Notifier.message(p, Component.translatable("story.skycraft.reward.title",
                    Component.translatable("story.skycraft.title." + r.getString("title"))).withStyle(ChatFormatting.GOLD));
        }
        // the coarse main-quest stage rises when the next stage's first quest starts (see start) and at the very end
        d.markDirty();
    }

    /** Marks a quest done with its lasting effects (flags, words) but without gold or items: migrations and admin jumps. */
    public static void grantStoryRewardsSilently(ServerPlayer p, Quest q) {
        StoryData.QuestDef def = defOf(q);
        if (def == null) return;
        PlayerData d = SkyData.get(p);
        markCompleted(d, def.id);
        CompoundTag r = q.extra.getCompound(REWARDS);
        for (Tag t : r.getList("flags", Tag.TAG_STRING)) setFlag(d, t.getAsString(), true);
        for (Tag t : r.getList("words", Tag.TAG_STRING)) teachWords(p, t.getAsString());
        if (r.getInt("stage") > 0) setMainStage(p, r.getInt("stage"));
    }

    /** {@code shout:learned[:unlocked]}, e.g. {@code unrelenting_force:2:2}. Never lowers what the player knows. */
    static void teachWords(ServerPlayer p, String spec) {
        String[] parts = spec.split(":");
        Shout s = Shout.byId(parts[0]);
        if (s == null) {
            Skycraft.LOGGER.debug("Story reward names unknown shout {}", parts[0]);
            return;
        }
        int learned = parts.length > 1 ? parseInt(parts[1], 1) : 1;
        int unlocked = parts.length > 2 ? parseInt(parts[2], 0) : 0;
        int had = MagicData.wordsLearned(p, s);
        if (learned > had) {
            MagicData.setWordsLearned(p, s, learned);
            for (int i = had; i < learned; i++) {
                Notifier.title(p, Component.translatable("story.skycraft.reward.word"), s.wordWithTranslation(i));
            }
        }
        if (unlocked > MagicData.wordsUnlocked(p, s)) MagicData.setWordsUnlocked(p, s, Math.min(unlocked, Math.max(learned, had)));
        p.level().playSound(null, p.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1f, 0.7f);
    }

    /** The deed to a house: free when nobody owns it yet, else its price in gold. */
    static void grantHouse(ServerPlayer p, String houseId) {
        com.skycraft.economy.Houses.HouseDef house = null;
        for (com.skycraft.economy.Houses.HouseDef h : com.skycraft.economy.Houses.HOUSES) if (h.id().equals(houseId)) house = h;
        if (house == null) return;
        if (com.skycraft.economy.Houses.isHouseOwner(p, house)) return;
        long before = Currency.balance(p);
        Currency.give(p, house.cost());
        if (!com.skycraft.economy.Houses.purchaseHouse(p, p.serverLevel(), house, null)) {
            // already somebody's: the gold stays as the reward
            Notifier.message(p, Component.translatable("story.skycraft.reward.house_gold", house.name(), house.cost()));
            return;
        }
        if (Currency.balance(p) > before) Currency.take(p, Currency.balance(p) - before);
    }

    // ------------------------------------------------------------------ actions

    /**
     * Runs a {@code ;}-separated action list: {@code give:}, {@code relic:<id>}, {@code take:<relic>},
     * {@code scene:<id>}, {@code flag:<f>}, {@code unflag:<f>}, {@code start:<quest>}, {@code word:<spec>},
     * {@code souls:<n>}, {@code teleport_dim:<dim>}, {@code flight:<place>}, {@code effect:<id>:<sec>[:<amp>]},
     * {@code control:<region>:<side>}, {@code relocate:<npc>:<place>[:dx:dz]}, {@code summon:<npc>},
     * {@code flyover:<entity>:<name>}, {@code helgen}, {@code thunder}, {@code loot:<tier>}, {@code unique:<id>},
     * {@code boss:<id>}.
     */
    public static void runActions(MinecraftServer server, String key, Quest q, String actions, @Nullable ServerPlayer actor) {
        if (actions == null || actions.isEmpty()) return;
        List<ServerPlayer> online = Quests.onlineMembers(server, key);
        ServerPlayer receiver = actor != null ? actor : online.isEmpty() ? null : online.get(0);
        CompoundTag vars = q.extra.getCompound(VARS);
        for (String raw : actions.split(";")) {
            String a = sub(vars, raw.trim());
            if (a.isEmpty()) continue;
            int colon = a.indexOf(':');
            String kind = colon < 0 ? a : a.substring(0, colon);
            String arg = colon < 0 ? "" : a.substring(colon + 1);
            try {
                runAction(server, key, q, kind, arg, receiver, online);
            } catch (RuntimeException e) {
                Skycraft.LOGGER.error("Story action {} of quest {} failed", a, q.id, e);
            }
        }
    }

    private static void runAction(MinecraftServer server, String key, Quest q, String kind, String arg,
                                  @Nullable ServerPlayer receiver, List<ServerPlayer> online) {
        switch (kind) {
            case "give" -> {
                if (receiver == null) return;
                for (String part : arg.split(",")) {
                    ItemStack s = stack(part.trim());
                    if (s.isEmpty()) continue;
                    Notifier.message(receiver, Component.translatable("quest.skycraft.item_found", s.getHoverName()));
                    Quests.give(receiver, s);
                }
            }
            case "relic" -> {
                if (receiver == null || countRelic(receiver, arg) > 0) return;
                ItemStack s = relicStack(arg);
                Notifier.message(receiver, Component.translatable("quest.skycraft.item_found", s.getHoverName()));
                Quests.give(receiver, s);
            }
            case "take" -> {
                for (ServerPlayer m : online) if (countRelic(m, arg) > 0) {
                    removeRelic(m, arg, 1);
                    return;
                }
            }
            case "scene" -> Scenes.play(server, audience(online, receiver), arg);
            case "flag" -> {
                for (ServerPlayer m : online) setFlag(SkyData.get(m), arg, true);
            }
            case "unflag" -> {
                for (ServerPlayer m : online) setFlag(SkyData.get(m), arg, false);
            }
            case "start" -> {
                StoryData.QuestDef def = StoryData.quest(arg);
                if (def != null && receiver != null && available(receiver, def)) start(receiver, def);
            }
            case "word" -> {
                for (ServerPlayer m : audience(online, receiver)) teachWords(m, arg);
            }
            case "souls" -> {
                for (ServerPlayer m : audience(online, receiver)) MagicData.setDragonSouls(m, MagicData.dragonSouls(m) + parseInt(arg, 1));
            }
            case "teleport_dim" -> {
                ServerLevel dest = QuestNpcs.level(server, arg);
                if (dest == null) return;
                // realms get their landings (the Deadlands through the nearest gate); elsewhere the vanilla way
                for (ServerPlayer m : audience(online, receiver)) if (m.level() != dest) com.skycraft.realms.RealmTravel.travel(m, dest);
            }
            case "flight" -> {
                Optional<QuestPlaces.Spot> spot = QuestPlaces.find(arg);
                if (spot.isEmpty()) return;
                for (ServerPlayer m : audience(online, receiver)) teleportTo(m, spot.get().x(), spot.get().z());
            }
            case "effect" -> {
                // effect:<id>:<seconds>[:<amplifier>], the id in the minecraft namespace or written ns/path
                String[] p = arg.split(":");
                ResourceLocation id = ResourceLocation.tryParse(p[0].contains("/") ? p[0].replaceFirst("/", ":") : "minecraft:" + p[0]);
                MobEffect effect = id == null ? null : ForgeRegistries.MOB_EFFECTS.getValue(id);
                if (effect == null) return;
                int secs = p.length > 1 ? parseInt(p[1], 10) : 10;
                int amp = p.length > 2 ? parseInt(p[2], 0) : 0;
                for (ServerPlayer m : audience(online, receiver)) m.addEffect(new MobEffectInstance(effect, secs * 20, amp));
            }
            case "control" -> {
                int c = arg.lastIndexOf(':');
                if (c > 0) CivilWar.setController(server, arg.substring(0, c), arg.substring(c + 1));
            }
            case "relocate" -> {
                String[] p = arg.split(":");
                Optional<QuestPlaces.Spot> spot = p.length > 1 ? QuestPlaces.find(p[1]) : Optional.empty();
                if (spot.isEmpty()) return;
                int x = spot.get().x() + (p.length > 3 ? parseInt(p[2], 0) : 0);
                int z = spot.get().z() + (p.length > 3 ? parseInt(p[3], 0) : 0);
                ServerLevel level = QuestNpcs.level(server, spot.get().dim());
                int y = level == null ? 64 : Locate.surfaceY(level, x, z);
                QuestNpcs.relocate(server, p[0], new BlockPos(x, y, z), spot.get().dim());
            }
            case "summon" -> {
                if (receiver != null) QuestNpcs.summonNear(receiver, arg);
            }
            case "flyover" -> {
                if (receiver != null) flyover(receiver, arg);
            }
            case "helgen" -> {
                if (receiver != null) helgen(receiver);
            }
            case "intro" -> {
                // the short opening: a scene plus a single distant dragon flyby, once per player (config: quest intro)
                if (QuestConfig.INTRO.get()) {
                    for (ServerPlayer m : audience(online, receiver)) {
                        if (hasFlag(SkyData.get(m), "intro_played")) continue;
                        setFlag(SkyData.get(m), "intro_played", true);
                        Scenes.intro(m, "skycraft:alduin");
                    }
                }
            }
            case "absorb_soul" -> {
                // the first dragon's soul, for whoever wasn't close enough when it died (absorption needs 64 blocks)
                for (ServerPlayer m : audience(online, receiver)) {
                    if (MagicData.tag(m).getBoolean("dragonborn")) continue;
                    MagicData.tag(m).putBoolean("dragonborn", true);
                    MagicData.setDragonSouls(m, MagicData.dragonSouls(m) + 1);
                    teachWords(m, "unrelenting_force:1:1");
                    Notifier.title(m, Component.translatable("notify.skycraft.dragon_soul"), Component.translatable("notify.skycraft.dragonborn"));
                }
            }
            // the curse module owns lycanthropy and vampirism (it keeps the quest module's beast_blood flag in step)
            case "beast_blood" -> {
                for (ServerPlayer m : audience(online, receiver)) com.skycraft.curse.Curses.grantBeastBlood(m);
            }
            case "cure_beast_blood" -> {
                for (ServerPlayer m : audience(online, receiver)) com.skycraft.curse.Curses.cure(m, com.skycraft.curse.CurseType.LYCANTHROPY);
            }
            case "vampire_lord" -> {
                if (receiver != null) com.skycraft.curse.Curses.grantVampireLord(receiver);
            }
            case "cure" -> {
                // cure:vampirism | cure:lycanthropy, for whoever nearby carries it
                com.skycraft.curse.CurseType type = com.skycraft.curse.CurseType.byId(arg);
                if (type == null) return;
                for (ServerPlayer m : audience(online, receiver)) {
                    boolean has = type == com.skycraft.curse.CurseType.VAMPIRISM
                            ? com.skycraft.curse.Curses.isVampire(m) || com.skycraft.curse.Curses.hasSanguinare(m) : com.skycraft.curse.Curses.isWerewolf(m);
                    if (has) com.skycraft.curse.Curses.cure(m, type);
                }
            }
            case "retire" -> {
                // a quest NPC killed by the story leaves the world, but only in single player: on a shared server
                // another player may still need them for their own part of the story
                if (server.isSingleplayer() && !server.isPublished()) QuestNpcs.retire(server, arg);
            }
            case "victory" -> {
                for (ServerPlayer m : online) MainQuest.victory(m);
            }
            // factions: join:<id>, points:<id>:<n>, expel:<id>; imprison:<place> sends the player to that city's jail
            case "join" -> {
                Faction f = Faction.byId(arg);
                if (f != null) for (ServerPlayer m : audience(online, receiver)) Factions.join(m, f);
            }
            case "points" -> {
                int c = arg.lastIndexOf(':');
                Faction f = c > 0 ? Faction.byId(arg.substring(0, c)) : null;
                if (f != null) for (ServerPlayer m : audience(online, receiver)) Factions.addPoints(m, f, parseInt(arg.substring(c + 1), 1));
            }
            case "expel" -> {
                Faction f = Faction.byId(arg);
                if (f != null) for (ServerPlayer m : audience(online, receiver)) Factions.expel(m, f, "faction.skycraft.reason.betrayal");
            }
            case "imprison" -> {
                if (receiver != null) com.skycraft.crime.Jail.imprison(receiver, arg);
            }
            // side quests: loot:<tier> rolls a loot-module reward, unique:<id> hands over a legendary unique,
            // boss:<id> raises a named boss of a bosses block next to the party (an ambush)
            case "loot" -> {
                for (ServerPlayer m : audience(online, receiver)) Bosses.giveLoot(m, arg);
            }
            case "unique" -> {
                if (receiver != null) Bosses.giveUnique(receiver, arg);
            }
            case "boss" -> {
                if (receiver != null) Bosses.ambush(receiver, key, q, arg);
            }
            case "thunder" -> {
                for (ServerPlayer m : audience(online, receiver)) {
                    m.level().playSound(null, m.blockPosition(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 1.2f, 0.6f);
                }
            }
            default -> Skycraft.LOGGER.warn("Unknown story action {} in quest {}", kind, q.id);
        }
    }

    private static List<ServerPlayer> audience(List<ServerPlayer> online, @Nullable ServerPlayer receiver) {
        if (receiver == null) return online;
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer m : online) if (m == receiver || m.level() == receiver.level() && m.distanceToSqr(receiver) < 96 * 96) out.add(m);
        if (!out.contains(receiver)) out.add(receiver);
        return out;
    }

    /** Teleports a player to the surface at x/z in the overworld (loads the chunk). */
    static void teleportTo(ServerPlayer p, int x, int z) {
        ServerLevel level = p.server.overworld();
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        p.teleportTo(level, x + 0.5, y + 0.1, z + 0.5, p.getYRot(), p.getXRot());
    }

    /** A dragon circles overhead for half a minute and leaves ({@code type:name}). */
    static void flyover(ServerPlayer p, String spec) {
        String[] parts = spec.split(":", 3);
        String type = parts.length >= 2 ? parts[0] + ":" + parts[1] : "skycraft:dragon";
        Entity e = QuestSpawner.create(p.serverLevel(), type);
        if (e == null) return;
        Vec3 c = p.position();
        e.moveTo(c.x, c.y + 40, c.z, 0, 0);
        if (parts.length == 3) e.setCustomName(Component.translatableWithFallback("story.skycraft.npc." + parts[2].toLowerCase(Locale.ROOT),
                Character.toUpperCase(parts[2].charAt(0)) + parts[2].substring(1)));
        if (e instanceof LivingEntity le) le.setSilent(false);
        if (!p.serverLevel().addFreshEntity(e)) return;
        Scenes.circle(e, c, 36, 30, 20 * 30);
    }

    /** The opening: the prisoner's cart arrives at Helgen (unless disabled or already done). */
    static void helgen(ServerPlayer p) {
        PlayerData d = SkyData.get(p);
        if (hasFlag(d, "helgen_arrived")) return;
        setFlag(d, "helgen_arrived", true);
        if (!QuestConfig.HELGEN_START.get() || p.level().dimension() != Level.OVERWORLD) return;
        Optional<QuestPlaces.Spot> spot = QuestPlaces.find("helgen");
        if (spot.isEmpty()) return;
        teleportTo(p, spot.get().x() + 6, spot.get().z() + 30);
        p.addEffect(new MobEffectInstance(net.minecraft.world.effect.MobEffects.BLINDNESS, 60, 0));
    }

    // ------------------------------------------------------------------ testing and recovery

    /**
     * Jumps the player's story quest {@code questId} to objective {@code stage} (0-based), starting it if needed.
     * Skipped objectives hand out their relics and items so later steps work. Stage past the end completes it.
     */
    public static boolean jumpTo(ServerPlayer p, String questId, int stage) {
        MinecraftServer server = p.server;
        Quests.Ref ref = activeRef(p, questId);
        if (ref == null) {
            StoryData.QuestDef def = StoryData.quest(questId);
            if (def == null) return false;
            if (!start(p, def)) return false;
            ref = activeRef(p, questId);
            if (ref == null) return false;
        }
        Quest q = ref.quest();
        for (int i = 0; i < q.objectives.size(); i++) {
            Objective o = q.objectives.get(i);
            if (i < stage && !o.done) {
                o.done = true;
                o.progress = o.required;
                for (String a : o.onComplete.split(";")) {
                    String t = a.trim();
                    if (t.startsWith("give:") || t.startsWith("relic:") || t.startsWith("flag:") || t.startsWith("word:")) {
                        runActions(server, ref.key(), q, t, p);
                    }
                }
                if (o.type == Objective.Type.RETRIEVE && o.data.contains(RELIC)) runActions(server, ref.key(), q, "relic:" + o.data.getString(RELIC), p);
            } else if (i >= stage && o.done) {
                o.done = false;
                o.progress = 0;
                o.spawned = false;
                o.spawnedIds.clear();
            }
        }
        Objective cur = q.current();
        if (cur == null) {
            Quests.completeQuest(server, ref.key(), q);
        } else {
            onBegin(server, ref.key(), q, cur);
            Quests.changed(server, ref.key());
        }
        return true;
    }

    /** "Skip this step": allowed once the objective has been current for the configured number of days. */
    public static boolean skip(ServerPlayer p, String questId) {
        Quests.Ref ref = Quests.find(p.server, p.getUUID(), questId);
        if (ref == null || !ref.quest().isActive()) return false;
        Objective o = ref.quest().current();
        if (o == null) return false;
        long at = o.data.getLong(SKIP_AT);
        if (at <= 0 || p.server.overworld().getGameTime() < at) {
            Notifier.message(p, Component.translatable("story.skycraft.skip.not_yet"));
            return false;
        }
        Notifier.message(p, Component.translatable("story.skycraft.skip.done", o.text).withStyle(ChatFormatting.GRAY));
        if (o.data.contains(RELIC) && (o.type == Objective.Type.FETCH)) removeRelic(p, o.data.getString(RELIC), 1);
        if (o.type == Objective.Type.RETRIEVE && o.data.contains(RELIC)) runActions(p.server, ref.key(), ref.quest(), "relic:" + o.data.getString(RELIC), p);
        Quests.completeObjective(p.server, ref.key(), ref.quest(), o, p);
        return true;
    }

    // ------------------------------------------------------------------ shouts

    /** Hook from the magic module: a player shouted. Completes "shout X (here)" objectives of their quests. */
    public static void onShout(ServerPlayer p, String shoutId) {
        MinecraftServer server = p.server;
        String dim = p.level().dimension().location().toString();
        for (Quests.Ref ref : Quests.active(server, p.getUUID())) {
            Objective o = ref.quest().current();
            if (o == null || o.type != Objective.Type.CONDITION || !o.target.equals("shout:" + shoutId)) continue;
            if (o.hasPos && (!o.dim.equals(dim) || Locate.horizontalDist(p.blockPosition(), o.pos) > Math.max(16, o.radius))) continue;
            Quests.completeObjective(server, ref.key(), ref.quest(), o, p);
        }
    }

    /** The player who owns a personal key, if online. */
    @Nullable
    static ServerPlayer ownerOf(MinecraftServer server, String key) {
        if (!key.startsWith("p:")) return null;
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(key.substring(2)));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Spawn-time condition of a spawn spec ({@code "if": "flag:helgen_ralof"}): any online owner meets it. */
    public static boolean spawnAllowed(MinecraftServer server, String key, CompoundTag spec) {
        String cond = spec.getString("if");
        if (cond.isEmpty()) return true;
        for (ServerPlayer m : Quests.onlineMembers(server, key)) if (test(m, cond)) return true;
        return false;
    }

    /** Validates entity ids used by story spawns (logs unknown ones once at server start). */
    public static void validateEntities() {
        for (StoryData.QuestDef def : StoryData.quests()) {
            for (StoryData.ObjDef od : def.objectives) {
                for (JsonObject s : od.spawns) {
                    String type = StoryData.str(s, "type", "");
                    if (type.startsWith("npc:")) continue;
                    if (QuestSpawner.resolveType(type).isEmpty()) {
                        Skycraft.LOGGER.debug("Story quest {} spawns {} which isn't registered (a fallback is used)", def.id, type);
                    }
                }
            }
        }
    }

    static boolean entityTypeExists(String id) {
        return EntityType.byString(id).isPresent();
    }
}
