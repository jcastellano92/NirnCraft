package com.skycraft.quest.faction;

import com.skycraft.core.Currency;
import com.skycraft.core.Holds;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.crime.Bounty;
import com.skycraft.crime.Crimes;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.quest.Faction;
import com.skycraft.quest.Factions;
import com.skycraft.quest.Locate;
import com.skycraft.quest.Objective;
import com.skycraft.quest.Quest;
import com.skycraft.quest.QuestDialogue;
import com.skycraft.quest.QuestSpawner.Spawn;
import com.skycraft.quest.RadiantQuests;
import com.skycraft.quest.story.Story;
import com.skycraft.quest.story.StoryData;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Conversations with the factions' people. Hall staff (and the questline heads living in a hall) take new members
 * (starting the faction's joining quest, or swearing them in when its story is done), hand out work (the next
 * chapter of the faction's story, else a radiant hall contract), explain what membership offers and how to rise,
 * and take back the expelled for a penance. Guards defer to senior members of the power that rules their region
 * (the Legion in Cyrodiil, House Hlaalu in Balmora, the Thalmor in Summerset...): once a day such a member may have
 * a small bounty overlooked.
 */
public final class FactionDialogue {
    private FactionDialogue() {}

    public static void register() {
        Dialogue.registerProvider(FactionDialogue::addOptions);
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable(key, args);
    }

    static void addOptions(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        Faction f = FactionStaff.factionOf(npc);
        if (f != null) staffTopics(player, npc, f, out);
        if (Crimes.isGuard(npc)) guardTopics(player, out);
    }

    // ------------------------------------------------------------------ hall staff

    static void staffTopics(ServerPlayer player, LivingEntity npc, Faction f, List<DialogueOption> out) {
        PlayerData data = SkyData.get(player);
        boolean member = Factions.isMember(data, f);
        if (!member && f.joinable) {
            if (FactionEffects.expelled(data, f)) {
                long cost = penance(f);
                out.add(new DialogueOption("faction.amends", tr("dialogue.skycraft.faction.amends", Currency.formatCompact(cost)), 118,
                        (p, n) -> amends(p, n, f)));
            } else {
                out.add(new DialogueOption("faction.join", tr("dialogue.skycraft.faction.join", f.displayName()), 118,
                        (p, n) -> join(p, n, f)));
            }
        }
        if (member) {
            out.add(new DialogueOption("faction.work", tr("dialogue.skycraft.faction.work", f.displayName()), 119, (p, n) -> work(p, n, f)));
            out.add(new DialogueOption("faction.rank", tr("dialogue.skycraft.faction.rank", f.displayName()), 122, (p, n) -> rank(p, n, f)));
        }
        out.add(new DialogueOption("faction.benefits", tr("dialogue.skycraft.faction.benefits", f.displayName()), 121,
                (p, n) -> Dialogue.open(p, n, f.benefits())));
    }

    static long penance(Faction f) {
        return 250L * f.ranks;
    }

    /** Whether the player may join now; tells them why not otherwise. */
    static boolean admissible(ServerPlayer p, LivingEntity npc, Faction f) {
        PlayerData data = SkyData.get(p);
        for (Faction rival : f.exclusive()) {
            if (Factions.isMember(data, rival)) {
                Dialogue.open(p, npc, tr("faction.skycraft.reply.rival", rival.displayName()));
                return false;
            }
        }
        String req = FactionData.requirement(f);
        for (String part : req.split(";")) {
            if (!part.isEmpty() && !Story.test(p, part)) {
                Dialogue.open(p, npc, tr("faction.skycraft.reply.not_ready", f.howToJoin()));
                return false;
            }
        }
        return true;
    }

    /** The faction's joining quest the player could take now, if its story has one. */
    @Nullable
    static StoryData.QuestDef joinQuest(ServerPlayer p, Faction f) {
        for (StoryData.QuestDef def : StoryData.quests()) {
            if (f.id.equals(def.faction) && def.rewards.join && Story.available(p, def)) return def;
        }
        return null;
    }

    static void join(ServerPlayer p, LivingEntity npc, Faction f) {
        if (Factions.isMember(SkyData.get(p), f) || !admissible(p, npc, f)) return;
        StoryData.QuestDef def = joinQuest(p, f);
        if (def != null) {
            if (Story.start(p, def)) {
                Dialogue.open(p, npc, def.pitch.isEmpty() ? def.desc.c() : def.pitch.c());
                return;
            }
            Dialogue.open(p, npc, tr("quest.skycraft.reply.too_busy"));
            return;
        }
        if (Factions.join(p, f)) Dialogue.open(p, npc, tr("faction.skycraft.reply.welcome", f.displayName()));
    }

    static void amends(ServerPlayer p, LivingEntity npc, Faction f) {
        PlayerData data = SkyData.get(p);
        if (!FactionEffects.expelled(data, f)) return;
        long cost = penance(f);
        if (!Currency.take(p, cost)) {
            Dialogue.open(p, npc, tr("faction.skycraft.reply.amends_poor", Currency.formatCompact(cost)));
            return;
        }
        FactionEffects.forgive(data, f);
        Dialogue.open(p, npc, tr("faction.skycraft.reply.amends", f.displayName()));
    }

    static void rank(ServerPlayer p, LivingEntity npc, Faction f) {
        PlayerData data = SkyData.get(p);
        int r = Factions.rank(data, f);
        if (r < 0) return;
        if (r >= f.maxRank()) {
            Dialogue.open(p, npc, tr("faction.skycraft.reply.top_rank", f.rankName(r)));
            return;
        }
        Dialogue.open(p, npc, tr("faction.skycraft.reply.next_rank", f.rankName(r), f.rankName(r + 1),
                Factions.points(data, f), Faction.pointsFor(r + 1)));
    }

    /** The next chapter of the faction's story, else a radiant contract from the hall. */
    static void work(ServerPlayer p, LivingEntity npc, Faction f) {
        for (StoryData.QuestDef def : StoryData.quests()) {
            // chapters offered in conversation (auto ones begin on their own when their time comes)
            if (!f.id.equals(def.faction) || def.rewards.join || def.repeatable || "auto".equals(def.start)) continue;
            if (Story.available(p, def) && Story.start(p, def)) {
                Dialogue.open(p, npc, def.pitch.isEmpty() ? def.desc.c() : def.pitch.c());
                return;
            }
        }
        QuestDialogue.offer(p, npc, "faction_" + f.id, () -> contract(p, npc, f));
    }

    // ------------------------------------------------------------------ radiant hall contracts

    enum Kind { BANDITS, NECROMANCER, WRIT, UNDEAD, BEASTS }

    static Kind kind(Faction f) {
        return switch (f) {
            case MAGES_GUILD, COLLEGE, HOUSE_TELVANNI, PSIJIC_ORDER, THALMOR, ALDMERI_DOMINION -> Kind.NECROMANCER;
            case THIEVES_GUILD, DARK_BROTHERHOOD, MORAG_TONG, CAMONNA_TONG, SHADOWSCALES, RENRIJRA_KRIN -> Kind.WRIT;
            case TRIBUNAL_TEMPLE, IMPERIAL_CULT, KNIGHTS_OF_THE_NINE, VIGILANTS_OF_STENDARR, MOON_PRIESTS, GREYBEARDS -> Kind.UNDEAD;
            case ASHLANDERS, GREEN_PACT, ORC_STRONGHOLDS, FORSWORN, COMPANIONS -> Kind.BEASTS;
            default -> Kind.BANDITS;
        };
    }

    @Nullable
    static RadiantQuests.Offer contract(ServerPlayer p, LivingEntity npc, Faction f) {
        if (!(p.level() instanceof net.minecraft.server.level.ServerLevel level)) return null;
        RandomSource r = p.getRandom();
        Kind kind = kind(f);
        BlockPos spot = Locate.randomSpot(level, r, npc.blockPosition(), 200, 500);
        String dim = level.dimension().location().toString();
        int lvl = SkyData.get(p).getLevel();
        long gold = Math.max(150, Math.min(1200, Math.round(250 * (1 + lvl / 20.0))));
        String k = kind.name().toLowerCase(java.util.Locale.ROOT);
        Component target = tr("faction.skycraft.contract.target." + k + "." + r.nextInt(4));
        Quest q = new Quest("faction_contract", Quest.Category.FACTION, tr("faction.skycraft.contract.title", f.displayName(), target),
                tr("faction.skycraft.contract.desc." + k, npc.getDisplayName(), target));
        q.giver = npc.getUUID();
        q.giverName = npc.getDisplayName();
        q.giverPos = npc.blockPosition();
        q.giverDim = dim;
        q.hold = Holds.holdAt(level, npc.blockPosition());
        Objective o = new Objective(Objective.Type.KILL_TARGET, tr("faction.skycraft.contract.objective", target))
                .at(spot, dim, 0).guessY().placement("surface").label(target.getString());
        switch (kind) {
            case BANDITS -> o.spawn(Spawn.of("skycraft:bandit_chief", 1).name(target).target().boss().health(2f).build())
                    .spawn(Spawn.of("skycraft:bandit", 3).build());
            case NECROMANCER -> o.spawn(Spawn.of("skycraft:necromancer", 1).name(target).target().boss().health(2f).build())
                    .spawn(Spawn.of("minecraft:skeleton", 3).build());
            case WRIT -> o.spawn(Spawn.of("npc:thug", 1).name(target).target().health(1.5f).build())
                    .spawn(Spawn.of("npc:thug", 2).build());
            case UNDEAD -> o.spawn(Spawn.of("skycraft:draugr_deathlord", 1).name(target).target().boss().health(1.5f).build())
                    .spawn(Spawn.of("skycraft:draugr", 3).build());
            case BEASTS -> o.spawn(Spawn.of("skycraft:troll", 1).name(target).target().boss().health(1.5f).build())
                    .spawn(Spawn.of("skycraft:wolf", 3).build());
        }
        q.add(o);
        q.reward(gold);
        q.faction(f.id, 2, false);
        return new RadiantQuests.Offer(q, tr("faction.skycraft.contract.pitch." + k, target, com.skycraft.quest.Names.direction(npc.blockPosition(), spot), gold));
    }

    // ------------------------------------------------------------------ guards

    /** The faction whose word carries weight with this region's guards, if the player ranks high enough in it. */
    @Nullable
    static Faction pull(ServerPlayer p, String hold) {
        PlayerData data = SkyData.get(p);
        Region region = Regions.byId(Holds.migrate(hold)).orElse(null);
        if (region == null) return null;
        for (Faction f : Faction.VALUES) {
            FactionData.Info info = FactionData.info(f);
            if (info == null || Factions.rank(data, f) < 3) continue;
            for (String g : info.governs()) {
                boolean ok = switch (g) {
                    case "imperial", "stormcloak" -> g.equals(com.skycraft.quest.story.CivilWar.controller(p.server, region.id()));
                    default -> g.startsWith("province/") ? region.province().equals(g.substring(9)) : region.id().equals(g);
                };
                if (ok) return f;
            }
        }
        return null;
    }

    static void guardTopics(ServerPlayer player, List<DialogueOption> out) {
        String hold = Holds.holdAt(player.level(), player.blockPosition());
        int bounty = Bounty.get(player, hold);
        if (bounty <= 0) return;
        Faction f = pull(player, hold);
        if (f == null) return;
        int rank = Factions.rank(SkyData.get(player), f);
        out.add(new DialogueOption("faction.pull", tr("dialogue.skycraft.faction.pull", f.rankName(rank), f.displayName()), 7,
                (p, n) -> usePull(p, n, f, hold)));
    }

    static void usePull(ServerPlayer p, LivingEntity guard, Faction f, String hold) {
        PlayerData data = SkyData.get(p);
        int rank = Factions.rank(data, f);
        int bounty = Bounty.get(p, hold);
        if (rank < 3 || bounty <= 0) return;
        CompoundTag mod = data.module("quest");
        long day = p.level().getDayTime() / 24000L;
        if (mod.getLong("faction_pull_day") == day + 1) {
            Dialogue.open(p, guard, tr("faction.skycraft.reply.pull_used"));
            return;
        }
        if (bounty > 60 * rank) {
            Dialogue.open(p, guard, tr("faction.skycraft.reply.pull_too_much", f.displayName()));
            return;
        }
        mod.putLong("faction_pull_day", day + 1);
        Bounty.clear(p, hold);
        data.markDirty();
        Dialogue.open(p, guard, tr("faction.skycraft.reply.pull_ok", f.displayName()));
    }
}
