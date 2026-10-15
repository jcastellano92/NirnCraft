package com.skycraft.quest.story;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.skycraft.core.SkyData;
import com.skycraft.quest.Quest;
import com.skycraft.quest.Quests;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Operator tools for testing the story quests:
 * <pre>
 * /skycraft-quest stage &lt;quest&gt; &lt;n&gt; [player]   jump to objective n (0-based; past the end completes it)
 * /skycraft-quest start|complete &lt;quest&gt; [player]
 * /skycraft-quest skip &lt;quest&gt; [player]          skip the current step now
 * /skycraft-quest list [line]                     every story quest with its state for you
 * /skycraft-quest npc &lt;id&gt; summon|goto           bring a quest NPC to you, or go to its home
 * /skycraft-quest goto &lt;place&gt;                   travel to a quest place
 * /skycraft-quest flag &lt;flag&gt; &lt;true|false&gt;
 * /skycraft-quest control imperial|stormcloak|none &lt;region&gt;
 * /skycraft-quest check                           which quest places and NPCs resolve in this world
 * </pre>
 */
public final class StoryCommands {
    private StoryCommands() {}

    private static CompletableFuture<Suggestions> questIds(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        List<String> ids = new ArrayList<>();
        for (StoryData.QuestDef d : StoryData.quests()) ids.add(d.id);
        return SharedSuggestionProvider.suggest(ids, b);
    }

    private static CompletableFuture<Suggestions> npcIds(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        List<String> ids = new ArrayList<>();
        for (StoryData.NpcDef d : StoryData.npcs()) ids.add(d.id);
        return SharedSuggestionProvider.suggest(ids, b);
    }

    private static ServerPlayer target(CommandContext<CommandSourceStack> c, boolean hasPlayer) throws CommandSyntaxException {
        return hasPlayer ? EntityArgument.getPlayer(c, "player") : c.getSource().getPlayerOrException();
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("skycraft-quest").requires(s -> s.hasPermission(2))
                .then(Commands.literal("stage").then(Commands.argument("quest", StringArgumentType.word()).suggests(StoryCommands::questIds)
                        .then(Commands.argument("n", IntegerArgumentType.integer(0, 64))
                                .executes(c -> stage(c, false))
                                .then(Commands.argument("player", EntityArgument.player()).executes(c -> stage(c, true))))))
                .then(Commands.literal("start").then(Commands.argument("quest", StringArgumentType.word()).suggests(StoryCommands::questIds)
                        .executes(c -> start(c, false))
                        .then(Commands.argument("player", EntityArgument.player()).executes(c -> start(c, true)))))
                .then(Commands.literal("complete").then(Commands.argument("quest", StringArgumentType.word()).suggests(StoryCommands::questIds)
                        .executes(c -> complete(c, false))
                        .then(Commands.argument("player", EntityArgument.player()).executes(c -> complete(c, true)))))
                .then(Commands.literal("skip").then(Commands.argument("quest", StringArgumentType.word()).suggests(StoryCommands::questIds)
                        .executes(c -> skip(c, false))
                        .then(Commands.argument("player", EntityArgument.player()).executes(c -> skip(c, true)))))
                .then(Commands.literal("list").executes(c -> list(c, ""))
                        .then(Commands.argument("line", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(StoryData.lines(), b))
                                .executes(c -> list(c, StringArgumentType.getString(c, "line")))))
                .then(Commands.literal("npc").then(Commands.argument("id", StringArgumentType.word()).suggests(StoryCommands::npcIds)
                        .then(Commands.literal("summon").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            Entity e = QuestNpcs.summonNear(p, StringArgumentType.getString(c, "id"));
                            return e != null ? 1 : fail(c, "Couldn't summon that NPC here.");
                        }))
                        .then(Commands.literal("goto").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            BlockPos a = QuestNpcs.lastPos(p.server, StringArgumentType.getString(c, "id"));
                            if (a == null) return fail(c, "Unknown NPC or place.");
                            Story.teleportTo(p, a.getX(), a.getZ());
                            return 1;
                        }))))
                .then(Commands.literal("goto").then(Commands.argument("place", StringArgumentType.word()).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    Optional<QuestPlaces.Spot> s = QuestPlaces.find(StringArgumentType.getString(c, "place"));
                    if (s.isEmpty()) return fail(c, "Unknown place.");
                    Story.teleportTo(p, s.get().x(), s.get().z());
                    return 1;
                })))
                .then(Commands.literal("flag").then(Commands.argument("flag", StringArgumentType.word())
                        .then(Commands.argument("value", BoolArgumentType.bool()).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            Story.setFlag(SkyData.get(p), StringArgumentType.getString(c, "flag"), BoolArgumentType.getBool(c, "value"));
                            return 1;
                        }))))
                .then(Commands.literal("control").then(Commands.argument("side", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("imperial", "stormcloak", "none"), b))
                        .then(Commands.argument("region", StringArgumentType.greedyString())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of(com.skycraft.core.Holds.IDS), b))
                                .executes(c -> {
                                    String side = StringArgumentType.getString(c, "side");
                                    CivilWar.setController(c.getSource().getServer(), StringArgumentType.getString(c, "region").trim(),
                                            "none".equals(side) ? "" : side);
                                    return 1;
                                }))))
                .then(Commands.literal("intro")
                        .then(Commands.literal("status").executes(StoryCommands::introStatus))
                        .then(Commands.literal("selftest").executes(c -> introSelftest(c, false))
                                .then(Commands.literal("rebuild").executes(c -> introSelftest(c, true))))
                        .then(Commands.literal("restart").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            boolean ok = IntroFlow.restart(p);
                            c.getSource().sendSuccess(() -> Component.literal(ok ? "The opening starts again in the cottage." : "No cottage in this world."), true);
                            return ok ? 1 : 0;
                        }).then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                            ServerPlayer p = EntityArgument.getPlayer(c, "player");
                            boolean ok = IntroFlow.restart(p);
                            c.getSource().sendSuccess(() -> Component.literal(ok ? "The opening starts again for " + p.getName().getString() : "No cottage in this world."), true);
                            return ok ? 1 : 0;
                        })))
                        .then(Commands.literal("skip").executes(c -> {
                            IntroFlow.skip(c.getSource().getPlayerOrException());
                            return 1;
                        }))
                        .then(Commands.literal("map").then(Commands.argument("x1", IntegerArgumentType.integer())
                                .then(Commands.argument("z1", IntegerArgumentType.integer())
                                        .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                .then(Commands.argument("z2", IntegerArgumentType.integer()).executes(c -> {
                                                    net.minecraft.server.level.ServerLevel level = c.getSource().getServer().overworld();
                                                    int ax = IntegerArgumentType.getInteger(c, "x1"), az = IntegerArgumentType.getInteger(c, "z1");
                                                    int bx = IntegerArgumentType.getInteger(c, "x2"), bz = IntegerArgumentType.getInteger(c, "z2");
                                                    for (String line : IntroSelftest.map(level, Math.min(ax, bx), Math.min(az, bz),
                                                            Math.min(Math.max(ax, bx), Math.min(ax, bx) + 140), Math.min(Math.max(az, bz), Math.min(az, bz) + 120))) {
                                                        c.getSource().sendSuccess(() -> Component.literal(line), false);
                                                    }
                                                    return 1;
                                                }))))))
                        .then(Commands.literal("column").then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer()).executes(c -> {
                                    net.minecraft.server.level.ServerLevel level = c.getSource().getServer().overworld();
                                    StartCottage.Site site = StartCottage.site(level);
                                    if (site == null) return 0;
                                    int hg = CottageTerrain.groundTop(level, site.helgenX(), site.helgenZ());
                                    String m = CottageTerrain.debugColumn(level, site, new CottageTerrain.Town(site.helgenX(), site.helgenZ(), 60,
                                            hg == CottageTerrain.NO_GROUND ? site.helgenY() : hg), IntegerArgumentType.getInteger(c, "x"),
                                            IntegerArgumentType.getInteger(c, "z"));
                                    c.getSource().sendSuccess(() -> Component.literal(m), false);
                                    return 1;
                                }))))
                        .then(Commands.literal("dump").executes(c -> {
                            net.minecraft.server.level.ServerLevel level = c.getSource().getServer().overworld();
                            StartCottage.Site site = StartCottage.site(level);
                            if (site == null) return 0;
                            for (String line : IntroSelftest.dump(level, site)) c.getSource().sendSuccess(() -> Component.literal(line), false);
                            return 1;
                        }))
                        .then(Commands.literal("rebuild").executes(c -> {
                            StartCottage.rebuild(c.getSource().getServer().overworld());
                            c.getSource().sendSuccess(() -> Component.literal("The cottage is rebuilt; its path and sight cone follow as the chunks load."), true);
                            return 1;
                        })))
                .then(Commands.literal("check").executes(StoryCommands::check))
                .then(Commands.literal("selftest").executes(StoryCommands::selftest)
                        .then(Commands.literal("flow").executes(StoryCommands::selftestFlow))));
    }

    /**
     * Plays every story quest from its first step to completion for a stand-in player (stage jumps through every
     * objective, then the completion and reward path), and reports any quest that throws or doesn't complete.
     * The stand-in's quests and pending rewards are removed afterwards.
     */
    private static int selftestFlow(CommandContext<CommandSourceStack> c) {
        net.minecraft.server.MinecraftServer server = c.getSource().getServer();
        ServerPlayer fake = net.minecraftforge.common.util.FakePlayerFactory.get(server.overworld(),
                new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("skycraft-quest-selftest".getBytes()), "[QuestSelftest]"));
        String key = Quests.personalKey(fake.getUUID());
        com.skycraft.quest.QuestStore store = com.skycraft.quest.QuestStore.get(server);
        List<String> problems = new ArrayList<>();
        int done = 0, steps = 0;
        for (StoryData.QuestDef def : StoryData.quests()) {
            try {
                store.quests(key).removeIf(q -> q.id.equals(def.id));
                for (int i = 0; i <= def.objectives.size(); i++) {
                    if (!Story.jumpTo(fake, def.id, i)) {
                        problems.add(def.id + ": couldn't reach objective " + i);
                        break;
                    }
                    steps++;
                }
                Quest q = store.find(key, def.id);
                if (q == null || q.status != Quest.Status.COMPLETED) problems.add(def.id + ": did not complete");
                else done++;
            } catch (RuntimeException e) {
                problems.add(def.id + ": " + e);
                com.skycraft.Skycraft.LOGGER.error("Story selftest flow failed for {}", def.id, e);
            }
        }
        store.remove(key);
        store.takePending(fake.getUUID());
        int d = done, s = steps;
        c.getSource().sendSuccess(() -> Component.literal("Story flow selftest: " + d + "/" + StoryData.quests().size()
                + " quests played to completion through " + s + " stage jumps, " + problems.size() + " problems"), false);
        for (String p : problems) c.getSource().sendSuccess(() -> Component.literal(" - " + p).withStyle(ChatFormatting.RED), false);
        return problems.isEmpty() ? 1 : 0;
    }

    private static int introStatus(CommandContext<CommandSourceStack> c) {
        net.minecraft.server.level.ServerLevel level = c.getSource().getServer().overworld();
        StartCottage.Site site = StartCottage.site(level);
        String who = "";
        if (c.getSource().getEntity() instanceof ServerPlayer p) who = "you: " + IntroFlow.state(p) + "; ";
        String msg = site == null ? "no cottage site" : who + "cottage " + site.origin() + " floor " + site.floorY() + " facing (" + site.fx() + ","
                + site.fz() + "); prepared " + StartCottage.prepared(level) + "; " + StartCottage.report(level) + "; hidden players " + IntroFlow.hidden().size();
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    /** Headless checks of the cottage and the whole opening for a stand-in player. */
    private static int introSelftest(CommandContext<CommandSourceStack> c, boolean rebuild) {
        List<String> info = new ArrayList<>();
        List<String> problems = IntroSelftest.run(c.getSource().getServer(), info, rebuild);
        problems.addAll(IntroSelftest.extra(c.getSource().getServer(), info));
        for (String s : info) c.getSource().sendSuccess(() -> Component.literal(" " + s), false);
        c.getSource().sendSuccess(() -> Component.literal("Intro selftest: " + problems.size() + " problems"), false);
        for (String p : problems) c.getSource().sendSuccess(() -> Component.literal(" - " + p).withStyle(ChatFormatting.RED), false);
        return problems.isEmpty() ? 1 : 0;
    }

    private static final java.util.Set<String> ACTIONS =java.util.Set.of("give", "relic", "take", "scene", "flag", "unflag", "start",
            "word", "souls", "teleport_dim", "flight", "effect", "control", "relocate", "summon", "flyover", "helgen", "thunder",
            "absorb_soul", "beast_blood", "cure_beast_blood", "vampire_lord", "cure", "retire", "victory",
            "join", "points", "expel", "imprison", "loot", "unique", "boss");

    /**
     * Builds every story quest for a stand-in player and checks what a playthrough would hit: places, NPCs, relics,
     * reward items, spawn entity types, action names and scenes. Reports everything that wouldn't resolve.
     */
    private static int selftest(CommandContext<CommandSourceStack> c) {
        net.minecraft.server.MinecraftServer server = c.getSource().getServer();
        ServerPlayer fake = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(server.overworld());
        List<String> problems = new ArrayList<>();
        int built = 0, objectives = 0, spawns = 0, fallbackSpawns = 0;
        for (StoryData.QuestDef def : StoryData.quests()) {
            Quest q;
            try {
                q = Story.build(fake, def);
            } catch (RuntimeException e) {
                problems.add(def.id + ": build threw " + e);
                continue;
            }
            if (q == null) {
                problems.add(def.id + ": a variable didn't resolve");
                continue;
            }
            built++;
            objectives += q.objectives.size();
            for (int i = 0; i < def.rewards.items.size(); i++) {
                if (Story.rewardItem(def.rewards.items.get(i), "x").isEmpty()) problems.add(def.id + ": reward item " + def.rewards.items.get(i) + " doesn't exist");
            }
            List<String> acts = new ArrayList<>(List.of(def.onStart, def.onComplete));
            for (StoryData.ObjDef od : def.objectives) {
                acts.add(od.actions);
                for (StoryData.Choice ch : od.choices) acts.add(ch.actions());
                if (!od.place.isEmpty() && !od.place.startsWith("@") && QuestPlaces.find(od.place).isEmpty()) problems.add(def.id + ": unknown place " + od.place);
                if (!od.item.isEmpty() && Story.stack(od.item).isEmpty()) problems.add(def.id + ": unknown item " + od.item);
                for (com.google.gson.JsonObject s : od.spawns) {
                    spawns++;
                    String type = StoryData.str(s, "type", "");
                    if (type.startsWith("npc:")) {
                        if (com.skycraft.society.NpcRole.byId(type.substring(4)) == null) problems.add(def.id + ": unknown NPC role " + type);
                    } else if (net.minecraft.world.entity.EntityType.byString(type).isEmpty()) {
                        if (com.skycraft.quest.QuestSpawner.resolveType(type).isPresent()) fallbackSpawns++;
                        else if (!s.has("if")) problems.add(def.id + ": spawn " + type + " has no entity or fallback");
                    }
                }
            }
            for (String list : acts) {
                for (String a : list.split(";")) {
                    String t = a.trim();
                    if (t.isEmpty()) continue;
                    String kind = t.contains(":") ? t.substring(0, t.indexOf(':')) : t;
                    if (!ACTIONS.contains(kind)) problems.add(def.id + ": unknown action " + t);
                    if (kind.equals("scene") && StoryData.scene(t.substring(6)).isEmpty()) problems.add(def.id + ": unknown scene " + t);
                    if (kind.equals("relic") && StoryData.relic(t.substring(6)) == null) problems.add(def.id + ": unknown relic " + t);
                    if (kind.equals("boss") && StoryData.boss(t.substring(5)) == null) problems.add(def.id + ": unknown boss " + t);
                    if (kind.equals("unique") && com.skycraft.loot.LegendaryUniques.get(t.substring(7)) == null) problems.add(def.id + ": unknown unique " + t);
                    if (kind.equals("loot") && !Bosses.TIERS.contains(t.substring(5))) problems.add(def.id + ": unknown loot tier " + t);
                }
            }
            if (!def.rewards.loot.isEmpty() && !Bosses.TIERS.contains(def.rewards.loot)) problems.add(def.id + ": unknown loot tier " + def.rewards.loot);
            if (!def.rewards.unique.isEmpty() && com.skycraft.loot.LegendaryUniques.create(def.rewards.unique).isEmpty()) {
                problems.add(def.id + ": unknown unique " + def.rewards.unique);
            }
            if (def.find != null && Discoveries.spot(def) == null) problems.add(def.id + ": hidden clue at unknown place " + def.find.place);
            if ("find".equals(def.start) && def.find == null) problems.add(def.id + ": start find without a find block");
        }
        for (StoryData.BossDef b : StoryData.bosses()) {
            String type = StoryData.str(b.spec(), "type", "");
            if (type.startsWith("npc:") ? com.skycraft.society.NpcRole.byId(type.substring(4)) == null
                    : com.skycraft.quest.QuestSpawner.resolveType(type).isEmpty()) problems.add("boss " + b.id() + ": unknown entity " + type);
            String drop = StoryData.str(b.spec(), "drop", "");
            if (!drop.isEmpty() && !Bosses.validDrop(drop)) problems.add("boss " + b.id() + ": drop " + drop + " doesn't exist");
            if (b.spec().has("weapon") && Story.item(b.spec().get("weapon").getAsString()) == null) problems.add("boss " + b.id() + ": unknown weapon");
            if (b.spec().has("gear")) {
                for (var e : b.spec().getAsJsonObject("gear").entrySet()) {
                    if (Story.item(e.getValue().getAsString()) == null) problems.add("boss " + b.id() + ": unknown gear " + e.getValue().getAsString());
                }
            }
        }
        for (StoryData.NpcDef n : StoryData.npcs()) {
            if (QuestNpcs.anchor(server, n.id) == null) problems.add("npc " + n.id + ": no home (place " + n.place + ")");
            if (com.skycraft.society.NpcRole.byId(n.role) == null) problems.add("npc " + n.id + ": unknown role " + n.role);
        }
        for (String t : RadiantTemplates.selftest(fake, fake)) problems.add("radiant template " + t + ": didn't build");
        for (String nb : com.skycraft.quest.NoticeBoards.selftest(server)) problems.add("notice board " + nb);
        int b = built, o = objectives, s = spawns, f = fallbackSpawns;
        c.getSource().sendSuccess(() -> Component.literal("Story selftest: built " + b + "/" + StoryData.quests().size() + " quests, " + o
                + " objectives, " + s + " spawn specs (" + f + " use a stand-in entity until their creature exists), "
                + problems.size() + " problems"), false);
        for (String p : problems) c.getSource().sendSuccess(() -> Component.literal(" - " + p).withStyle(ChatFormatting.RED), false);
        return problems.isEmpty() ? 1 : 0;
    }

    private static int fail(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendFailure(Component.literal(msg));
        return 0;
    }

    private static int stage(CommandContext<CommandSourceStack> c, boolean hasPlayer) throws CommandSyntaxException {
        ServerPlayer p = target(c, hasPlayer);
        String id = StringArgumentType.getString(c, "quest");
        int n = IntegerArgumentType.getInteger(c, "n");
        if (StoryData.quest(id) == null) {
            // a non-story quest of the player: same jump on its objectives
            Quests.Ref ref = Quests.find(p.server, p.getUUID(), id);
            if (ref == null) return fail(c, "Unknown quest " + id);
        }
        if (!Story.jumpTo(p, id, n)) return fail(c, "Couldn't set " + id + " to stage " + n);
        c.getSource().sendSuccess(() -> Component.literal("Quest " + id + " is at objective " + n), true);
        return 1;
    }

    private static int start(CommandContext<CommandSourceStack> c, boolean hasPlayer) throws CommandSyntaxException {
        ServerPlayer p = target(c, hasPlayer);
        StoryData.QuestDef def = StoryData.quest(StringArgumentType.getString(c, "quest"));
        if (def == null) return fail(c, "Unknown story quest");
        return Story.start(p, def) ? 1 : fail(c, "Couldn't start it (already active, or too many quests).");
    }

    private static int complete(CommandContext<CommandSourceStack> c, boolean hasPlayer) throws CommandSyntaxException {
        ServerPlayer p = target(c, hasPlayer);
        StoryData.QuestDef def = StoryData.quest(StringArgumentType.getString(c, "quest"));
        if (def == null) return fail(c, "Unknown story quest");
        return Story.jumpTo(p, def.id, def.objectives.size()) ? 1 : fail(c, "Couldn't complete it.");
    }

    private static int skip(CommandContext<CommandSourceStack> c, boolean hasPlayer) throws CommandSyntaxException {
        ServerPlayer p = target(c, hasPlayer);
        Quests.Ref ref = Quests.find(p.server, p.getUUID(), StringArgumentType.getString(c, "quest"));
        if (ref == null || ref.quest().current() == null) return fail(c, "No such active quest.");
        ref.quest().current().data.putLong(Story.SKIP_AT, 1);
        return Story.skip(p, ref.quest().id) ? 1 : 0;
    }

    private static int list(CommandContext<CommandSourceStack> c, String line) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        int n = 0;
        for (StoryData.QuestDef def : line.isEmpty() ? StoryData.quests() : StoryData.line(line)) {
            String state;
            ChatFormatting color;
            Quests.Ref ref = Story.activeRef(p, def.id);
            if (ref != null) {
                Quest q = ref.quest();
                state = "active " + (q.objectives.indexOf(q.current())) + "/" + q.objectives.size();
                color = ChatFormatting.YELLOW;
            } else if (Story.completed(SkyData.get(p), def.id)) {
                state = "done";
                color = ChatFormatting.GREEN;
            } else if (Story.requirementsMet(p, def)) {
                state = "available (" + def.start + ")";
                color = ChatFormatting.AQUA;
            } else {
                state = "locked";
                color = ChatFormatting.DARK_GRAY;
            }
            String s = state;
            p.sendSystemMessage(Component.literal(" " + def.line + " ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(def.id + " ").withStyle(ChatFormatting.WHITE))
                    .append(def.title.c().copy().withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(" - " + s).withStyle(color)));
            n++;
        }
        return n;
    }

    private static int check(CommandContext<CommandSourceStack> c) {
        int places = 0, real = 0, missing = 0;
        List<String> unknown = new ArrayList<>();
        for (StoryData.QuestDef def : StoryData.quests()) {
            for (StoryData.ObjDef od : def.objectives) {
                if (od.place.isEmpty() || od.place.startsWith("@")) continue;
                places++;
                Optional<QuestPlaces.Spot> s = QuestPlaces.find(od.place);
                if (s.isEmpty()) {
                    missing++;
                    if (!unknown.contains(od.place)) unknown.add(od.place);
                } else if (s.get().place() != null) real++;
            }
        }
        int npcs = 0, npcMissing = 0;
        for (StoryData.NpcDef n : StoryData.npcs()) {
            npcs++;
            if (QuestPlaces.find(n.place).isEmpty()) {
                npcMissing++;
                if (!unknown.contains(n.place)) unknown.add(n.place);
            }
        }
        int fr = real, fm = missing, fp = places, fn = npcs, fnm = npcMissing;
        c.getSource().sendSuccess(() -> Component.literal(StoryData.quests().size() + " story quests; " + fp + " place targets ("
                + fr + " world places, " + (fp - fr - fm) + " fallback anchors, " + fm + " unknown); " + fn + " quest NPCs ("
                + fnm + " without a home)"), false);
        if (!unknown.isEmpty()) c.getSource().sendSuccess(() -> Component.literal("Unknown: " + String.join(", ", unknown))
                .withStyle(ChatFormatting.RED), false);
        return 1;
    }
}
