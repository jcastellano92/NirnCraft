package com.nirncraft.quest;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.dialogue.Dialogue;
import com.nirncraft.quest.party.Parties;
import com.nirncraft.quest.party.Party;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /party create|invite|accept|decline|leave|kick|promote|list|chat} and
 * {@code /quest list|track|abandon} plus operator tools under {@code /quest admin}.
 */
public final class QuestCommands {
    private static final List<String> ADMIN_KINDS = List.of("bounty", "hunt", "companions", "companions_trial", "college",
            "thieves", "dark_brotherhood", "imperial_legion", "stormcloaks", "legion_trial", "stormcloak_trial", "villager");

    private QuestCommands() {}

    private static ServerPlayer player(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return c.getSource().getPlayerOrException();
    }

    private static int ok(boolean result) {
        return result ? 1 : 0;
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("party")
                .executes(c -> list(player(c)))
                .then(Commands.literal("create").executes(c -> ok(Parties.create(player(c)))))
                .then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> ok(Parties.invite(player(c), EntityArgument.getPlayer(c, "player"))))))
                .then(Commands.literal("accept").executes(c -> ok(Parties.accept(player(c), null))))
                .then(Commands.literal("decline").executes(c -> ok(Parties.decline(player(c), null))))
                .then(Commands.literal("leave").executes(c -> ok(Parties.leave(player(c)))))
                .then(Commands.literal("kick").then(Commands.argument("member", StringArgumentType.word())
                        .suggests(QuestCommands::suggestMembers)
                        .executes(c -> {
                            ServerPlayer p = player(c);
                            UUID target = memberByName(p, StringArgumentType.getString(c, "member"));
                            if (target == null) return fail(c, "party.nirncraft.not_member");
                            return ok(Parties.kick(p, target));
                        })))
                .then(Commands.literal("promote").then(Commands.argument("member", StringArgumentType.word())
                        .suggests(QuestCommands::suggestMembers)
                        .executes(c -> {
                            ServerPlayer p = player(c);
                            UUID target = memberByName(p, StringArgumentType.getString(c, "member"));
                            if (target == null) return fail(c, "party.nirncraft.not_member");
                            return ok(Parties.promote(p, target));
                        })))
                .then(Commands.literal("list").executes(c -> list(player(c))))
                .then(Commands.literal("chat").then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(c -> {
                            Parties.chat(player(c), StringArgumentType.getString(c, "message"));
                            return 1;
                        }))));

        d.register(Commands.literal("quest")
                .executes(c -> listQuests(player(c)))
                .then(Commands.literal("list").executes(c -> listQuests(player(c))))
                .then(Commands.literal("track").then(Commands.argument("id", StringArgumentType.word())
                        .suggests(QuestCommands::suggestQuests)
                        .executes(c -> track(player(c), StringArgumentType.getString(c, "id")))))
                .then(Commands.literal("untrack").executes(c -> track(player(c), "")))
                .then(Commands.literal("abandon").then(Commands.argument("id", StringArgumentType.word())
                        .suggests(QuestCommands::suggestQuests)
                        .executes(c -> {
                            Quests.abandon(player(c), StringArgumentType.getString(c, "id"));
                            return 1;
                        })))
                .then(Commands.literal("admin").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.literal("start").then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("kind", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(ADMIN_KINDS, b))
                                        .executes(c -> adminStart(c, EntityArgument.getPlayer(c, "player"), StringArgumentType.getString(c, "kind"))))))
                        .then(Commands.literal("complete").then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("id", StringArgumentType.word()).executes(c -> {
                                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
                                    Quests.Ref ref = Quests.find(p.level().getServer(), p.getUUID(), StringArgumentType.getString(c, "id"));
                                    if (ref == null) return fail(c, "quest.nirncraft.cmd.unknown");
                                    Quests.completeQuest(p.level().getServer(), ref.key(), ref.quest());
                                    return 1;
                                }))))
                        .then(Commands.literal("faction").then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("faction", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(Faction.VALUES).map(f -> f.id), b))
                                        .then(Commands.argument("rank", IntegerArgumentType.integer(-1, 10)).executes(c -> {
                                            ServerPlayer p = EntityArgument.getPlayer(c, "player");
                                            Faction f = Faction.byId(StringArgumentType.getString(c, "faction"));
                                            if (f == null) return fail(c, "quest.nirncraft.cmd.unknown");
                                            Factions.setRank(p, f, IntegerArgumentType.getInteger(c, "rank"));
                                            c.getSource().sendSuccess(() -> Component.literal("Set " + f.id + " rank of "
                                                    + p.getGameProfile().name()), true);
                                            return 1;
                                        })))))
                        .then(Commands.literal("mainstage").then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("stage", IntegerArgumentType.integer(1, MainQuest.FINAL_STAGE)).executes(c -> {
                                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
                                    int stage = IntegerArgumentType.getInteger(c, "stage");
                                    QuestStore.get(p.level().getServer()).quests(Quests.personalKey(p.getUUID()))
                                            .removeIf(q -> q.isMain() && q.isActive());
                                    Quests.changed(p.level().getServer(), Quests.personalKey(p.getUUID()));
                                    MainQuest.startStage(p, stage);
                                    return 1;
                                }))))
                        .then(Commands.literal("reset").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                            ServerPlayer p = EntityArgument.getPlayer(c, "player");
                            QuestStore.get(p.level().getServer()).remove(Quests.personalKey(p.getUUID()));
                            PlayerData data = SkyData.get(p);
                            net.minecraft.nbt.CompoundTag mod = data.module("quest");
                            for (String k : new ArrayList<>(mod.keySet())) mod.remove(k);
                            data.markDirty();
                            Quests.markPlayerDirty(p.getUUID());
                            c.getSource().sendSuccess(() -> Component.literal("Reset quests of " + p.getGameProfile().name()), true);
                            return 1;
                        })))));
        d.register(Commands.literal("nirncraft-board")
                .executes(c -> boardList(c, null))
                .then(Commands.literal("list")
                        .executes(c -> boardList(c, null))
                        .then(Commands.argument("settlement", StringArgumentType.word())
                                .suggests(QuestCommands::suggestBoardSettlements)
                                .executes(c -> boardList(c, StringArgumentType.getString(c, "settlement")))))
                .then(Commands.literal("accept")
                        .then(Commands.argument("index", IntegerArgumentType.integer(0, 6))
                                .executes(c -> boardAccept(c, null, IntegerArgumentType.getInteger(c, "index"))))
                        .then(Commands.argument("settlement", StringArgumentType.word())
                                .suggests(QuestCommands::suggestBoardSettlements)
                                .then(Commands.argument("index", IntegerArgumentType.integer(0, 6))
                                        .executes(c -> boardAccept(c, StringArgumentType.getString(c, "settlement"),
                                                IntegerArgumentType.getInteger(c, "index"))))))
                .then(Commands.literal("selftest").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                        .executes(QuestCommands::boardSelftest)));

        com.nirncraft.quest.story.StoryCommands.register(d);
    }

    private static int fail(CommandContext<CommandSourceStack> c, String key) {
        c.getSource().sendFailure(Component.translatable(key));
        return 0;
    }

    // ------------------------------------------------------------------ party

    @Nullable
    private static UUID memberByName(ServerPlayer player, String name) {
        Party party = Parties.partyOf(player);
        if (party == null) return null;
        for (Map.Entry<UUID, String> e : party.members.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    private static CompletableFuture<Suggestions> suggestMembers(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        ServerPlayer p = c.getSource().getPlayer();
        Party party = p == null ? null : Parties.partyOf(p);
        if (party == null) return Suggestions.empty();
        return SharedSuggestionProvider.suggest(party.members.values(), b);
    }

    private static int list(ServerPlayer player) {
        Party party = Parties.partyOf(player);
        if (party == null) {
            player.sendSystemMessage(Component.translatable("party.nirncraft.not_in_party").withStyle(ChatFormatting.RED));
            for (Parties.Invite i : Parties.invitesFor(player.level().getServer(), player.getUUID())) {
                player.sendSystemMessage(Component.translatable("party.nirncraft.invite_received", i.inviterName()).withStyle(ChatFormatting.GOLD));
            }
            return 0;
        }
        player.sendSystemMessage(Component.translatable("party.nirncraft.list_header", party.members.size(), QuestConfig.MAX_PARTY_SIZE.get())
                .withStyle(ChatFormatting.GOLD));
        party.members.forEach((uuid, name) -> {
            ServerPlayer m = player.level().getServer().getPlayerList().getPlayer(uuid);
            Component status = m == null ? Component.translatable("party.nirncraft.offline")
                    : Component.literal((int) (m.getHealth() * 5) + "/" + (int) (m.getMaxHealth() * 5) + " HP");
            String lead = uuid.equals(party.leader) ? " ★" : "";
            player.sendSystemMessage(Component.literal(" - " + name + lead + "  ").append(status).withStyle(ChatFormatting.YELLOW));
        });
        return 1;
    }

    // ------------------------------------------------------------------ quests

    private static CompletableFuture<Suggestions> suggestQuests(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return Suggestions.empty();
        List<String> ids = new ArrayList<>();
        for (Quests.Ref ref : Quests.active(p.level().getServer(), p.getUUID())) ids.add(ref.quest().id);
        return SharedSuggestionProvider.suggest(ids, b);
    }

    private static int listQuests(ServerPlayer player) {
        List<Quests.Ref> refs = Quests.active(player.level().getServer(), player.getUUID());
        player.sendSystemMessage(Component.translatable("quest.nirncraft.cmd.list_header", refs.size()).withStyle(ChatFormatting.GOLD));
        for (Quests.Ref ref : refs) {
            Quest q = ref.quest();
            Objective o = q.current();
            player.sendSystemMessage(Component.literal(" [" + q.id + "] ").withStyle(ChatFormatting.GRAY)
                    .append(q.title.copy().withStyle(ChatFormatting.YELLOW))
                    .append(o == null ? Component.empty() : Component.literal(" - ").append(o.text).withStyle(ChatFormatting.WHITE)));
        }
        return refs.size();
    }

    private static int track(ServerPlayer player, String id) {
        PlayerData data = SkyData.get(player);
        if (id.isEmpty()) {
            data.module("quest").remove("tracked");
        } else {
            if (Quests.find(player.level().getServer(), player.getUUID(), id) == null) {
                player.sendSystemMessage(Component.translatable("quest.nirncraft.cmd.unknown").withStyle(ChatFormatting.RED));
                return 0;
            }
            data.module("quest").putString("tracked", id);
        }
        data.markDirty();
        return 1;
    }

    /** Starts a radiant quest for testing, given by the nearest NPC (or the player themself). */
    private static int adminStart(CommandContext<CommandSourceStack> c, ServerPlayer p, String kind) {
        LivingEntity giver = nearestNpc(p);
        RadiantQuests.Offer offer = switch (kind.toLowerCase(Locale.ROOT)) {
            case "bounty" -> RadiantQuests.bounty(p, giver);
            case "hunt" -> RadiantQuests.hunt(p, giver);
            case "companions_trial" -> RadiantQuests.companionsTrial(p, giver);
            case "companions" -> RadiantQuests.companionsContract(p, giver);
            case "college" -> RadiantQuests.collegeContract(p, giver);
            case "thieves" -> RadiantQuests.thievesContract(p, giver);
            case "dark_brotherhood" -> RadiantQuests.darkBrotherhoodContract(p, giver);
            case "imperial_legion" -> RadiantQuests.civilWarContract(p, giver, Faction.IMPERIAL_LEGION);
            case "stormcloaks" -> RadiantQuests.civilWarContract(p, giver, Faction.STORMCLOAKS);
            case "legion_trial" -> RadiantQuests.civilWarTrial(p, giver, Faction.IMPERIAL_LEGION);
            case "stormcloak_trial" -> RadiantQuests.civilWarTrial(p, giver, Faction.STORMCLOAKS);
            case "villager" -> QuestDialogue.isTownsperson(giver) ? RadiantQuests.villager(p, giver) : null;
            default -> null;
        };
        if (offer == null) return fail(c, "quest.nirncraft.cmd.no_offer");
        return ok(Quests.start(p, offer.quest()));
    }

    private static LivingEntity nearestNpc(ServerPlayer p) {
        LivingEntity best = p;
        double bestDist = 16 * 16;
        for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(16), Dialogue::canTalk)) {
            double d = e.distanceToSqr(p);
            if (d < bestDist) {
                best = e;
                bestDist = d;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ notice boards (/nirncraft-board)

    private static CompletableFuture<Suggestions> suggestBoardSettlements(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        List<String> ids = new ArrayList<>();
        for (com.nirncraft.tamriel.Place p : com.nirncraft.tamriel.Places.all()) {
            if (NoticeBoards.hasNoticeBoard(p)) ids.add(p.id());
        }
        return SharedSuggestionProvider.suggest(ids, b);
    }

    @Nullable
    private static com.nirncraft.tamriel.Place resolveBoardSettlement(ServerPlayer p, @Nullable String id) {
        if (id != null && !id.isEmpty()) {
            return com.nirncraft.tamriel.Places.byId(id).filter(NoticeBoards::hasNoticeBoard).orElse(null);
        }
        return NoticeBoards.nearestBoardSettlement(p.level(), p.blockPosition(), 2500).orElse(null);
    }

    private static int boardList(CommandContext<CommandSourceStack> c, @Nullable String settlementId) throws CommandSyntaxException {
        ServerPlayer p = player(c);
        com.nirncraft.tamriel.Place settlement = resolveBoardSettlement(p, settlementId);
        if (settlement == null) return fail(c, "quest.nirncraft.board.none_nearby");
        return NoticeBoards.showBoard(p, settlement);
    }

    private static int boardAccept(CommandContext<CommandSourceStack> c, @Nullable String settlementId, int index) throws CommandSyntaxException {
        ServerPlayer p = player(c);
        com.nirncraft.tamriel.Place settlement = resolveBoardSettlement(p, settlementId);
        if (settlement == null) return fail(c, "quest.nirncraft.board.none_nearby");
        return ok(NoticeBoards.accept(p, settlement, index));
    }

    private static int boardSelftest(CommandContext<CommandSourceStack> c) {
        List<String> problems = new ArrayList<>(NoticeBoards.selftest(c.getSource().getServer()));
        for (String sp : com.nirncraft.roads.Signposts.selftest()) {
            problems.add("signpost " + sp);
        }
        c.getSource().sendSuccess(() -> Component.literal("NoticeBoard & Signpost selftest: " + problems.size() + " problems")
                .withStyle(problems.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        for (String p : problems) {
            c.getSource().sendSuccess(() -> Component.literal(" - " + p).withStyle(ChatFormatting.RED), false);
        }
        return problems.isEmpty() ? 1 : 0;
    }
}
