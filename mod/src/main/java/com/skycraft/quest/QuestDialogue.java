package com.skycraft.quest;

import com.skycraft.Skycraft;
import com.skycraft.core.Currency;
import com.skycraft.core.Holds;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Every quest-related conversation topic: turn-ins (TALK_TO / FETCH), radiant offers (accept / decline), guard
 * bounties and monster hunts, faction joining and contracts, and the Thieves Guild's bounty service.
 */
public final class QuestDialogue {
    public static final TagKey<EntityType<?>> GUARDS = TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Skycraft.MODID, "guards"));
    /** An offer stays open this long (ticks) after the NPC pitched it. */
    private static final long OFFER_TICKS = 20L * 300;

    private static final Map<UUID, Conversation> CONV = new HashMap<>();
    private static Boolean guardsRegistered;

    private record Conversation(UUID npc, @Nullable RadiantQuests.Offer offer,
                                @Nullable BiConsumer<ServerPlayer, LivingEntity> customAccept,
                                String dailyKey, boolean bountyMenu, long expires) {
    }

    private QuestDialogue() {}

    public static void register() {
        Dialogue.registerProvider(QuestDialogue::addOptions);
    }

    public static void forget(UUID player) {
        CONV.remove(player);
    }

    public static void reset() {
        CONV.clear();
        guardsRegistered = null;
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable(key, args);
    }

    public static Component questLabel(Component label) {
        if (label == null) return Component.empty();
        String s = label.getString();
        if (s.startsWith("\u25C6 ") || s.startsWith("\u2022 ")) return label;
        return Component.literal("\u25C6 ").withStyle(net.minecraft.ChatFormatting.GOLD).append(label);
    }

    private static DialogueOption opt(String id, String labelKey, int order, BiConsumer<ServerPlayer, LivingEntity> action, Object... args) {
        Component label = tr(labelKey, args);
        if (!"quest.decline".equals(id) && !"quest.rumors".equals(id)) {
            label = questLabel(label);
        }
        return new DialogueOption(id, label, order, action);
    }

    // ------------------------------------------------------------------ NPC kinds

    /** Guards (creatures module), or — if that module isn't installed — armorers and unemployed villagers. */
    public static boolean isGuard(LivingEntity e) {
        if (e.getType().builtInRegistryHolder().is(GUARDS)) return true;
        if (guardsRegistered == null) guardsRegistered = com.skycraft.worldgen.RegistryTypes.entity("skycraft:guard").isPresent();
        if (!guardsRegistered && e instanceof Villager v) {
            net.minecraft.resources.ResourceKey<VillagerProfession> prof = v.getVillagerData().profession().unwrapKey().orElseThrow();
            return prof == VillagerProfession.ARMORER || prof == VillagerProfession.NONE;
        }
        return false;
    }

    private static boolean isProfession(LivingEntity e, net.minecraft.resources.ResourceKey<VillagerProfession> prof) {
        return com.skycraft.economy.Merchants.profession(e) == prof;
    }

    /** Villagers and Skycraft's settled townsfolk (adults with a trade or a home in a settlement). */
    public static boolean isTownsperson(LivingEntity e) {
        if (e instanceof Villager v) return !v.isBaby();
        return e instanceof com.skycraft.society.entity.NpcEntity n && n.isAlive() && n.role().townsfolk() && !n.isChild();
    }

    /** Innkeepers and tavern keepers in settlements and roadside inns. */
    public static boolean isInnkeeper(LivingEntity e) {
        if (e instanceof com.skycraft.society.entity.NpcEntity n && n.isAlive() && !n.isChild()) {
            return n.role() == com.skycraft.society.NpcRole.INNKEEPER || n.role() == com.skycraft.society.NpcRole.BARD;
        }
        return isProfession(e, VillagerProfession.BUTCHER);
    }

    public static boolean matchesNpc(Objective o, LivingEntity npc) {
        if (o.npc != null) return o.npc.equals(npc.getUUID());
        // a named story NPC ("hadvar|ralof"): whichever entity is that NPC right now
        if (o.data.contains(com.skycraft.quest.story.Story.NPC)) {
            return com.skycraft.quest.story.QuestNpcs.matches(npc, o.data.getStringOr(com.skycraft.quest.story.Story.NPC, ""));
        }
        boolean typeOk = switch (o.npcFilter) {
            case "villager" -> isTownsperson(npc);
            case "guard" -> isGuard(npc);
            case "trader" -> com.skycraft.economy.Merchants.isCaravan(npc);
            case "any" -> npc instanceof AbstractVillager || isGuard(npc) || Dialogue.canTalk(npc);
            default -> false;
        };
        if (!typeOk) return false;
        if (o.hasPos && o.radius > 0) {
            if (!npc.level().dimension().identifier().toString().equals(o.dim)) return false;
            return Locate.horizontalDist(npc.blockPosition(), o.pos) <= o.radius;
        }
        return true;
    }

    /**
     * True if this NPC has not already offered/given a quest today and the player does not already have an active
     * quest from or targeting this specific NPC. Prevents one NPC from acting as an endless quest dispenser.
     */
    public static boolean canOfferQuest(ServerPlayer player, LivingEntity npc) {
        if (usedToday(npc, "any_quest")) return false;
        for (Quests.Ref ref : Quests.active(player.level().getServer(), player.getUUID())) {
            Quest q = ref.quest();
            if (npc.getUUID().equals(q.giver)) return false;
            Objective o = q.current();
            if (o != null && (o.type == Objective.Type.TALK_TO || o.type == Objective.Type.FETCH)) {
                if (o.npc != null && o.npc.equals(npc.getUUID())) return false;
                if (o.data.contains(com.skycraft.quest.story.Story.NPC)
                        && com.skycraft.quest.story.QuestNpcs.matches(npc, o.data.getStringOr(com.skycraft.quest.story.Story.NPC, ""))) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ provider

    static void addOptions(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        String hold = com.skycraft.core.Holds.holdAt(player.level(), player.blockPosition());
        if (com.skycraft.crime.Crimes.isGuard(npc) && com.skycraft.crime.Bounty.get(player, hold) > 0) return;
        MinecraftServer server = player.level().getServer();
        // 1. turn-ins for current objectives
        for (Quests.Ref ref : Quests.active(server, player.getUUID())) {
            Quest q = ref.quest();
            Objective o = q.current();
            if (o == null || (o.type != Objective.Type.TALK_TO && o.type != Objective.Type.FETCH)) continue;
            if (!matchesNpc(o, npc) || o.data.contains(com.skycraft.quest.story.Story.CHOICES)) continue; // choices: StoryDialogue
            String questId = q.id;
            Component topic = o.topic != null ? o.topic : tr(o.type == Objective.Type.FETCH ? "dialogue.skycraft.quest.deliver" : "dialogue.skycraft.quest.turn_in", q.title);
            out.add(new DialogueOption("quest.turnin." + questId, questLabel(topic), 100, (pl, n) -> turnIn(pl, n, questId)));
        }
        // 2. an open offer from this NPC
        Conversation c = CONV.get(player.getUUID());
        if (c != null && c.npc().equals(npc.getUUID()) && player.level().getGameTime() < c.expires()) {
            if (c.offer() != null || c.customAccept() != null) {
                out.add(opt("quest.accept", "dialogue.skycraft.quest.accept", 150, QuestDialogue::accept));
                out.add(opt("quest.decline", "dialogue.skycraft.quest.decline", 151, QuestDialogue::decline));
                return;
            }
            if (c.bountyMenu()) addBountyOptions(player, out);
        }
        // 3. topics by kind of NPC (each NPC offers at most one quest and never while already holding a quest for the player)
        PlayerData data = SkyData.get(player);
        boolean guard = isGuard(npc);
        boolean canOffer = canOfferQuest(player, npc);
        if (isTownsperson(npc) && canOffer
                && com.skycraft.quest.story.QuestNpcs.idOf(npc).isEmpty()
                && com.skycraft.quest.faction.FactionStaff.factionOf(npc) == null) {
            net.minecraft.resources.ResourceKey<VillagerProfession> prof = com.skycraft.economy.Merchants.profession(npc);
            boolean hasFavor = prof != null && prof != VillagerProfession.NONE
                    && !usedToday(npc, "help")
                    && !Quests.hasActiveKind(server, player.getUUID(), "villager_")
                    && ((npc.getUUID().getLeastSignificantBits() ^ day(npc)) & 7L) == 0L;
            if (hasFavor) {
                out.add(opt("quest.help", "dialogue.skycraft.quest.help", 300,
                        (pl, n) -> ask(pl, n, "help", () -> RadiantQuests.villager(pl, n))));
            }
        }
        if (guard && canOffer && !Quests.hasActiveKind(server, player.getUUID(), "bounty_")) {
            out.add(opt("quest.bounty", "dialogue.skycraft.quest.bounties", 310,
                    (pl, n) -> ask(pl, n, "bounty", () -> RadiantQuests.bounty(pl, n))));
        }
        if (com.skycraft.economy.Merchants.isCaravan(npc)) {
            thievesTopics(player, data, out);
            darkBrotherhoodTopics(player, data, out);
        }
        SideQuests.addSideQuestTopics(player, npc, out);
        // Notice-board contracts belong on the settlement Notice Board screen, never in random NPC dialogue menus.
        com.skycraft.quest.story.StoryDialogue.addOptions(player, npc, out);
        if (canOffer) {
            com.skycraft.quest.story.ProvinceRadiant.addOptions(player, npc, out, guard);
        }
    }

    /** Offers a generated quest from another module (province radiants, side quests), with the usual accept/decline flow. */
    public static void offer(ServerPlayer player, LivingEntity npc, String dailyKey, Supplier<RadiantQuests.Offer> generator) {
        ask(player, npc, dailyKey, generator);
    }

    /**
     * Pitches a data-driven {@link com.skycraft.quest.story.StoryData.QuestDef} with an explicit Accept / Decline
     * confirmation step instead of auto-starting on topic click.
     */
    public static void offerStory(ServerPlayer player, LivingEntity npc, com.skycraft.quest.story.StoryData.QuestDef def,
                                  @Nullable Runnable onAccepted) {
        if (!com.skycraft.quest.story.Story.available(player, def)) {
            Dialogue.open(player, npc, tr("quest.skycraft.reply.no_work"));
            return;
        }
        if (!def.isMain() && !Quests.canTakeMore(player)) {
            Dialogue.open(player, npc, tr("quest.skycraft.reply.too_busy"));
            return;
        }
        long now = player.level().getGameTime();
        Component pitch = def.pitch.isEmpty() ? def.desc.c() : def.pitch.c();
        CONV.put(player.getUUID(), new Conversation(npc.getUUID(), null, (pl, n) -> {
            if (!com.skycraft.quest.story.Story.available(pl, def)) {
                Dialogue.open(pl, n, tr("quest.skycraft.reply.no_work"));
                return;
            }
            if (!def.isMain() && !Quests.canTakeMore(pl)) {
                Dialogue.open(pl, n, tr("quest.skycraft.reply.too_busy"));
                return;
            }
            if (com.skycraft.quest.story.Story.start(pl, def)) {
                markToday(n, "any_quest");
                if (onAccepted != null) onAccepted.run();
                Dialogue.open(pl, n, tr("quest.skycraft.reply.accepted"));
            } else {
                Dialogue.open(pl, n, tr("quest.skycraft.reply.too_busy"));
            }
        }, "story_" + def.id, false, now + OFFER_TICKS));
        Dialogue.open(player, npc, pitch);
    }

    // ------------------------------------------------------------------ offers

    private static long day(LivingEntity npc) {
        return npc.level().getDefaultClockTime() / 24000L;
    }

    private static boolean usedToday(LivingEntity npc, String key) {
        CompoundTag pd = npc.getPersistentData();
        String k = "skycraft_quest_day_" + key;
        return com.skycraft.platform.NbtData.hasType(pd, k, Tag.TAG_LONG) && pd.getLongOr(k, 0L) == day(npc);
    }

    private static void markToday(LivingEntity npc, String key) {
        npc.getPersistentData().putLong("skycraft_quest_day_" + key, day(npc));
    }

    /**
     * The NPC pitches a quest generated by {@code generator}. With a non-empty {@code dailyKey} the NPC makes only one
     * such offer per in-game day.
     */
    private static void ask(ServerPlayer player, LivingEntity npc, String dailyKey, Supplier<RadiantQuests.Offer> generator) {
        long now = player.level().getGameTime();
        Conversation c = CONV.get(player.getUUID());
        if (c != null && c.npc().equals(npc.getUUID()) && c.offer() != null && now < c.expires() && c.dailyKey().equals(dailyKey)) {
            Dialogue.open(player, npc, c.offer().pitch());
            return;
        }
        if ((!dailyKey.isEmpty() && usedToday(npc, dailyKey)) || usedToday(npc, "any_quest")) {
            Dialogue.open(player, npc, tr("quest.skycraft.reply.no_work"));
            return;
        }
        if (!Quests.canTakeMore(player)) {
            Dialogue.open(player, npc, tr("quest.skycraft.reply.too_busy"));
            return;
        }
        RadiantQuests.Offer offer;
        try {
            offer = generator.get();
        } catch (Exception e) {
            Skycraft.LOGGER.error("Quest generation failed", e);
            offer = null;
        }
        if (!dailyKey.isEmpty()) markToday(npc, dailyKey);
        markToday(npc, "any_quest");
        if (offer == null) {
            Dialogue.open(player, npc, tr("quest.skycraft.reply.nothing"));
            return;
        }
        CONV.put(player.getUUID(), new Conversation(npc.getUUID(), offer, null, dailyKey, false, now + OFFER_TICKS));
        Dialogue.open(player, npc, offer.pitch());
    }

    private static void accept(ServerPlayer player, LivingEntity npc) {
        Conversation c = CONV.remove(player.getUUID());
        if (c == null || !c.npc().equals(npc.getUUID())) return;
        if (c.customAccept() != null) {
            c.customAccept().accept(player, npc);
            return;
        }
        if (c.offer() == null) return;
        if (Quests.start(player, c.offer().quest())) {
            markToday(npc, "any_quest");
            Dialogue.open(player, npc, tr("quest.skycraft.reply.accepted"));
        } else {
            Dialogue.open(player, npc, tr("quest.skycraft.reply.too_busy"));
        }
    }

    private static void decline(ServerPlayer player, LivingEntity npc) {
        CONV.remove(player.getUUID());
        markToday(npc, "any_quest");
        Dialogue.open(player, npc, tr("quest.skycraft.reply.declined"));
    }

    // ------------------------------------------------------------------ turn-ins

    private static void turnIn(ServerPlayer player, LivingEntity npc, String questId) {
        MinecraftServer server = player.level().getServer();
        Quests.Ref ref = Quests.find(server, player.getUUID(), questId);
        if (ref == null || !ref.quest().isActive()) return;
        Quest q = ref.quest();
        Objective o = q.current();
        if (o == null || !matchesNpc(o, npc)) return;
        String relic = o.data.getStringOr(com.skycraft.quest.story.Story.RELIC, "");
        if (o.type == Objective.Type.FETCH && !relic.isEmpty()) {
            int have = com.skycraft.quest.story.Story.countRelic(player, relic);
            if (have < o.required) {
                Dialogue.open(player, npc, tr("quest.skycraft.reply.not_enough", have, o.required, Component.translatable("item.skycraft.relic." + relic)));
                return;
            }
            com.skycraft.quest.story.Story.removeRelic(player, relic, o.required);
        } else if (o.type == Objective.Type.FETCH) {
            Identifier id = Identifier.tryParse(o.target);
            Item item = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
            if (item == null || item == Items.AIR) return;
            int have = Quests.count(player, item);
            if (have < o.required) {
                Dialogue.open(player, npc, tr("quest.skycraft.reply.not_enough", have, o.required, new net.minecraft.world.item.ItemStack(item).getHoverName()));
                return;
            }
            Quests.remove(player, item, o.required);
        }
        if (o.data.contains("consume")) {
            Identifier id = Identifier.tryParse(o.data.getStringOr("consume",""));
            Item item = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
            if (item != null && item != Items.AIR && Quests.count(player, item) > 0) Quests.remove(player, item, 1);
        }
        Component reply = o.reply;
        Quests.completeObjective(server, ref.key(), q, o, player);
        Dialogue.open(player, npc, reply);
    }

    // ------------------------------------------------------------------ factions

    private static boolean hasFactionQuest(ServerPlayer player, Faction f) {
        for (Quests.Ref ref : Quests.active(player.level().getServer(), player.getUUID())) {
            if (ref.quest().faction.equals(f.id)) return true;
        }
        return false;
    }

    /** Offers a faction quest unless one is already running. */
    private static void factionAsk(ServerPlayer player, LivingEntity npc, Faction f, Supplier<RadiantQuests.Offer> generator) {
        if (hasFactionQuest(player, f)) {
            Dialogue.open(player, npc, tr("quest.skycraft.reply.finish_first", f.displayName()));
            return;
        }
        ask(player, npc, "", generator);
    }

    private static void companionsTopics(ServerPlayer player, PlayerData data, List<DialogueOption> out) {
        Faction f = Faction.COMPANIONS;
        if (!Factions.isMember(data, f)) {
            out.add(opt("quest.companions.join", "dialogue.skycraft.faction.companions.join", 400,
                    (pl, n) -> factionAsk(pl, n, f, () -> RadiantQuests.companionsTrial(pl, n))));
        } else {
            out.add(opt("quest.companions.work", "dialogue.skycraft.faction.work", 400,
                    (pl, n) -> factionAsk(pl, n, f, () -> RadiantQuests.companionsContract(pl, n)), f.displayName()));
        }
    }

    private static void collegeTopics(ServerPlayer player, PlayerData data, List<DialogueOption> out) {
        Faction f = Faction.COLLEGE;
        if (!Factions.isMember(data, f)) {
            out.add(opt("quest.college.join", "dialogue.skycraft.faction.college.join", 401, (pl, n) -> {
                int spells = SkyData.get(pl).module("magic").getListOrEmpty("spells").size();
                if (spells < 3) {
                    Dialogue.open(pl, n, tr("quest.skycraft.reply.college_refuse", spells));
                } else if (Factions.join(pl, f)) {
                    Dialogue.open(pl, n, tr("quest.skycraft.reply.college_welcome"));
                }
            }));
        } else {
            out.add(opt("quest.college.work", "dialogue.skycraft.faction.work", 401,
                    (pl, n) -> factionAsk(pl, n, f, () -> RadiantQuests.collegeContract(pl, n)), f.displayName()));

            if (!data.module("quest").getBooleanOr("college_destruction_completed",false) && !Quests.hasActiveKind(player.level().getServer(), player.getUUID(), "college_destruction")) {
                out.add(opt("quest.college.dest", "dialogue.skycraft.college.dest", 401, (pl, n) -> {
                    if (CollegeQuests.startDestruction(pl, n)) {
                        Dialogue.open(pl, n, tr("dialogue.skycraft.college.dest_start"));
                    }
                }));
            }
            if (!data.module("quest").getBooleanOr("college_restoration_completed",false) && !Quests.hasActiveKind(player.level().getServer(), player.getUUID(), "college_restoration")) {
                out.add(opt("quest.college.rest", "dialogue.skycraft.college.rest", 401, (pl, n) -> {
                    if (CollegeQuests.startRestoration(pl, n)) {
                        Dialogue.open(pl, n, tr("dialogue.skycraft.college.rest_start"));
                    }
                }));
            }
            if (!data.module("quest").getBooleanOr("college_alteration_completed",false) && !Quests.hasActiveKind(player.level().getServer(), player.getUUID(), "college_alteration")) {
                out.add(opt("quest.college.alt", "dialogue.skycraft.college.alt", 401, (pl, n) -> {
                    if (CollegeQuests.startAlteration(pl, n)) {
                        Dialogue.open(pl, n, tr("dialogue.skycraft.college.alt_start"));
                    }
                }));
            }
            if (!data.module("quest").getBooleanOr("college_conjuration_completed",false) && !Quests.hasActiveKind(player.level().getServer(), player.getUUID(), "college_conjuration")) {
                out.add(opt("quest.college.conj", "dialogue.skycraft.college.conj", 401, (pl, n) -> {
                    if (CollegeQuests.startConjuration(pl, n)) {
                        Dialogue.open(pl, n, tr("dialogue.skycraft.college.conj_start"));
                    }
                }));
            }
            if (!data.module("quest").getBooleanOr("college_illusion_completed",false) && !Quests.hasActiveKind(player.level().getServer(), player.getUUID(), "college_illusion")) {
                out.add(opt("quest.college.ill", "dialogue.skycraft.college.ill", 401, (pl, n) -> {
                    if (CollegeQuests.startIllusion(pl, n)) {
                        Dialogue.open(pl, n, tr("dialogue.skycraft.college.ill_start"));
                    }
                }));
            }
        }
    }

    private static void bardsTopics(ServerPlayer player, PlayerData data, List<DialogueOption> out) {
        Faction f = Faction.BARDS_COLLEGE;
        if (!Factions.isMember(data, f)) {
            out.add(opt("quest.bards.join", "dialogue.skycraft.faction.bards_college.join", 402,
                    (pl, n) -> factionAsk(pl, n, f, () -> RadiantQuests.bardsTrial(pl, n))));
        } else {
            out.add(opt("quest.bards.work", "dialogue.skycraft.faction.work", 402,
                    (pl, n) -> factionAsk(pl, n, f, () -> RadiantQuests.bardsContract(pl, n)), f.displayName()));
        }
    }

    private static void civilWarTopics(ServerPlayer player, PlayerData data, List<DialogueOption> out) {
        for (Faction f : new Faction[]{Faction.IMPERIAL_LEGION, Faction.STORMCLOAKS}) {
            Faction rival = f.rival();
            if (Factions.isMember(data, f)) {
                out.add(opt("quest." + f.id + ".work", "dialogue.skycraft.faction.civil_war.work", 403,
                        (pl, n) -> factionAsk(pl, n, f, () -> RadiantQuests.civilWarContract(pl, n, f)), f.displayName()));
            } else if (rival != null && !Factions.isMember(data, rival) && !hasFactionQuest(player, rival)) {
                out.add(opt("quest." + f.id + ".join", "dialogue.skycraft.faction." + f.id + ".join", 403,
                        (pl, n) -> factionAsk(pl, n, f, () -> RadiantQuests.civilWarTrial(pl, n, f))));
            }
        }
    }

    private static void thievesTopics(ServerPlayer player, PlayerData data, List<DialogueOption> out) {
        Faction f = Faction.THIEVES_GUILD;
        if (!Factions.isMember(data, f)) {
            out.add(opt("quest.thieves.join", "dialogue.skycraft.faction.thieves_guild.join", 404, (pl, n) -> {
                int stolen = SkyData.get(pl).module("crime").getIntOr("items_stolen",0);
                if (stolen < 5) {
                    Dialogue.open(pl, n, tr("quest.skycraft.reply.thieves_refuse"));
                } else if (Factions.join(pl, f)) {
                    Dialogue.open(pl, n, tr("quest.skycraft.reply.thieves_welcome"));
                }
            }));
            return;
        }
        out.add(opt("quest.thieves.work", "dialogue.skycraft.faction.thieves_guild.work", 404,
                (pl, n) -> factionAsk(pl, n, f, () -> RadiantQuests.thievesContract(pl, n))));
        out.add(opt("quest.thieves.bounty", "dialogue.skycraft.faction.thieves_guild.bounty", 405, (pl, n) -> {
            if (bounties(pl).keySet().isEmpty()) {
                Dialogue.open(pl, n, tr("quest.skycraft.reply.no_bounty"));
                return;
            }
            CONV.put(pl.getUUID(), new Conversation(n.getUUID(), null, null, "", true, pl.level().getGameTime() + OFFER_TICKS));
            Dialogue.open(pl, n, tr("quest.skycraft.reply.bounty_menu"));
        }));
    }

    private static CompoundTag bounties(ServerPlayer player) {
        return SkyData.get(player).module("crime").getCompoundOrEmpty("bounty");
    }

    private static long bountyCost(int bounty) {
        return (bounty + 1) / 2;
    }

    private static void addBountyOptions(ServerPlayer player, List<DialogueOption> out) {
        CompoundTag b = bounties(player);
        int order = 160;
        for (String hold : b.keySet()) {
            int amount = b.getIntOr(hold, 0);
            if (amount <= 0) continue;
            long cost = bountyCost(amount);
            out.add(new DialogueOption("quest.clear_bounty." + hold,
                    tr("dialogue.skycraft.faction.thieves_guild.clear", Holds.displayName(hold), amount, cost), order++, (pl, n) -> {
                PlayerData data = SkyData.get(pl);
                CompoundTag crime = data.module("crime");
                CompoundTag current = crime.getCompoundOrEmpty("bounty");
                int now = current.getIntOr(hold, 0);
                if (now <= 0) return;
                long price = bountyCost(now);
                if (!Currency.take(pl, price)) {
                    Dialogue.open(pl, n, tr("quest.skycraft.reply.cant_afford", price));
                    return;
                }
                current.remove(hold);
                crime.put("bounty", current);
                data.markDirty();
                CONV.remove(pl.getUUID());
                Dialogue.open(pl, n, tr("quest.skycraft.reply.bounty_cleared", Holds.displayName(hold)));
            }));
        }
    }

    private static void darkBrotherhoodTopics(ServerPlayer player, PlayerData data, List<DialogueOption> out) {
        Faction f = Faction.DARK_BROTHERHOOD;
        if (!Factions.isNight(player.level())) return;
        if (!Factions.isMember(data, f)) {
            if (!data.module("quest").getBooleanOr("db_whispered",false)) return;
            out.add(opt("quest.db.join", "dialogue.skycraft.faction.dark_brotherhood.join", 406, (pl, n) -> {
                if (SkyData.get(pl).module("crime").getIntOr("murders",0) < 1) {
                    Dialogue.open(pl, n, tr("quest.skycraft.reply.db_refuse"));
                } else if (Factions.join(pl, f)) {
                    Dialogue.open(pl, n, tr("quest.skycraft.reply.db_welcome"));
                }
            }));
            return;
        }
        out.add(opt("quest.db.work", "dialogue.skycraft.faction.dark_brotherhood.work", 406,
                (pl, n) -> factionAsk(pl, n, f, () -> RadiantQuests.darkBrotherhoodContract(pl, n))));
    }
}
