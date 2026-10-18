package com.skycraft.quest;

import com.skycraft.core.Currency;
import com.skycraft.core.Holds;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.network.NotifyKind;
import com.skycraft.network.SkyNetwork;
import com.skycraft.quest.QuestSpawner.Spawn;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Multi-step Skyrim side questlines with real stories and moral choices, plus a dedicated mission arc:
 * <ul>
 *     <li><b>The Golden Claw</b> (Lucan Valerius / Bleak Falls Barrow / Arvel the Swift) — Return or Keep</li>
 *     <li><b>In My Time of Need</b> (Saadia vs Kematu and the Alik'r Warriors) — Protect or Betray</li>
 *     <li><b>Waking Nightmare</b> (Erandur / Nightcaller Temple / Skull of Corruption) — Purify or Claim</li>
 *     <li><b>The Forsworn Conspiracy</b> (Market murder / Investigation / Framed in Cidhna Mine / Escape)</li>
 * </ul>
 */
public final class SideQuests {
    private SideQuests() {}

    private static Component tr(String key, Object... args) {
        return Component.translatable(key, args);
    }

    private static Component lit(String text) {
        return Component.literal(text);
    }

    // ==================================================================
    // 1. THE GOLDEN CLAW
    // ==================================================================

    public static RadiantQuests.Offer goldenClawOffer(ServerPlayer player, LivingEntity giver) {
        ServerLevel level = (ServerLevel) player.level();
        String dim = level.dimension().identifier().toString();
        BlockPos at = player.blockPosition();
        BlockPos dungeon = com.skycraft.tamriel.Places.byId("bleak_falls_barrow")
                .filter(p -> level.dimension() == net.minecraft.world.level.Level.OVERWORLD && p.distSqr(at.getX(), at.getZ()) < 4000L * 4000L)
                .map(p -> new BlockPos(p.x(), Locate.surfaceY(level, p.x(), p.z()), p.z())).orElse(null);
        if (dungeon == null) dungeon = Locate.mountainDungeon(level, at, player.getRandom());
        if (dungeon == null) dungeon = Locate.dungeon(level, at, player.getRandom());
        if (dungeon == null) dungeon = Locate.randomSpot(level, player.getRandom(), at, 200, 450);
        Locate.rumor(player, dungeon);

        Quest q = new Quest("side_golden_claw", Quest.Category.SIDE,
                tr("quest.skycraft.side_golden_claw.title"), tr("quest.skycraft.side_golden_claw.desc"));
        q.giver = giver.getUUID();
        q.giverName = giver.getDisplayName();
        q.giverPos = giver.blockPosition();
        q.giverDim = dim;
        q.hold = Holds.holdAt(level, at);

        Objective o1 = new Objective(Objective.Type.GO_TO, tr("quest.skycraft.obj.golden_claw_1"))
                .at(dungeon, dim, 32).guessY().label("Bleak Falls Barrow");

        Objective o2 = new Objective(Objective.Type.KILL_TARGET, tr("quest.skycraft.obj.golden_claw_2"))
                .at(dungeon, dim, 0).guessY().placement("structure").label("Arvel the Swift")
                .spawn(Spawn.of("skycraft:bandit_chief", 1).name(lit("Arvel the Swift")).target().boss().health(1.8f).build())
                .spawn(Spawn.of("skycraft:bandit", 3).build());
        o2.onComplete = "give:skycraft:ancient_tome";

        Objective o3 = new Objective(Objective.Type.TALK_TO, tr("quest.skycraft.obj.golden_claw_3"))
                .npc(giver.getUUID()).at(giver.blockPosition(), dim, 0)
                .topic(tr("dialogue.skycraft.quest.golden_claw_return"), tr("quest.skycraft.reply.golden_claw_thanks"))
                .label(giver.getDisplayName().getString());

        q.add(o1).add(o2).add(o3).reward(400);
        return new RadiantQuests.Offer(q, tr("dialogue.skycraft.quest.pitch_golden_claw"));
    }

    public static boolean startGoldenClaw(ServerPlayer player, LivingEntity giver) {
        if (!Quests.canTakeMore(player)) return false;
        return Quests.start(player, goldenClawOffer(player, giver).quest());
    }

    // ==================================================================
    // 2. IN MY TIME OF NEED (Saadia vs Kematu)
    // ==================================================================

    public static RadiantQuests.Offer timeOfNeedOffer(ServerPlayer player, LivingEntity giver) {
        ServerLevel level = (ServerLevel) player.level();
        String dim = level.dimension().identifier().toString();
        BlockPos at = player.blockPosition();
        BlockPos den = Locate.randomSpot(level, player.getRandom(), at, 250, 500);

        Quest q = new Quest("side_time_of_need", Quest.Category.SIDE,
                tr("quest.skycraft.side_time_of_need.title"), tr("quest.skycraft.side_time_of_need.desc"));
        q.giver = giver.getUUID();
        q.giverName = giver.getDisplayName();
        q.giverPos = giver.blockPosition();
        q.giverDim = dim;
        q.hold = Holds.holdAt(level, at);

        Objective o1 = new Objective(Objective.Type.TALK_TO, tr("quest.skycraft.obj.time_of_need_1"))
                .filter("villager").topic(tr("dialogue.skycraft.quest.saadia_ask"), tr("dialogue.skycraft.quest.saadia_reply"));

        Objective o2 = new Objective(Objective.Type.GO_TO, tr("quest.skycraft.obj.time_of_need_2"))
                .at(den, dim, 24).guessY().label("Swindler's Den");

        Objective o3 = new Objective(Objective.Type.KILL_TARGET, tr("quest.skycraft.obj.time_of_need_3"))
                .at(den, dim, 0).guessY().placement("surface").label("Kematu")
                .spawn(Spawn.of("skycraft:bandit_chief", 1).name(lit("Kematu")).target().boss().health(2.0f).build())
                .spawn(Spawn.of("skycraft:bandit", 4).name(lit("Alik'r Warrior")).build());

        ItemStack scimitar = new ItemStack(Items.IRON_SWORD);
        scimitar.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, tr("item.skycraft.reward.alikr_scimitar"));
        scimitar.enchant(com.skycraft.platform.EnchantmentsAccess.holder(Enchantments.SHARPNESS), 2);

        q.add(o1).add(o2).add(o3).reward(500, scimitar);
        return new RadiantQuests.Offer(q, tr("dialogue.skycraft.quest.pitch_time_of_need"));
    }

    public static boolean startTimeOfNeed(ServerPlayer player, LivingEntity giver) {
        if (!Quests.canTakeMore(player)) return false;
        return Quests.start(player, timeOfNeedOffer(player, giver).quest());
    }

    // ==================================================================
    // 3. WAKING NIGHTMARE (Erandur / Vaermina)
    // ==================================================================

    public static RadiantQuests.Offer wakingNightmareOffer(ServerPlayer player, LivingEntity giver) {
        ServerLevel level = (ServerLevel) player.level();
        String dim = level.dimension().identifier().toString();
        BlockPos at = player.blockPosition();
        BlockPos temple = Locate.randomSpot(level, player.getRandom(), at, 300, 600);

        Quest q = new Quest("side_waking_nightmare", Quest.Category.SIDE,
                tr("quest.skycraft.side_waking_nightmare.title"), tr("quest.skycraft.side_waking_nightmare.desc"));
        q.giver = giver.getUUID();
        q.giverName = giver.getDisplayName();
        q.giverPos = giver.blockPosition();
        q.giverDim = dim;
        q.hold = Holds.holdAt(level, at);

        Objective o1 = new Objective(Objective.Type.GO_TO, tr("quest.skycraft.obj.waking_nightmare_1"))
                .at(temple, dim, 32).guessY().label("Nightcaller Temple");

        Objective o2 = new Objective(Objective.Type.CLEAR_AREA, tr("quest.skycraft.obj.waking_nightmare_2"))
                .at(temple, dim, 28).guessY().count(6)
                .spawn(Spawn.of("skycraft:draugr", 4).name(lit("Tormented Phantom")).build())
                .spawn(Spawn.of("minecraft:witch", 2).name(lit("Vaermina Priestess")).build())
                .label("Temple Sanctum");

        Objective o3 = new Objective(Objective.Type.TALK_TO, tr("quest.skycraft.obj.waking_nightmare_3"))
                .topic(tr("dialogue.skycraft.quest.waking_nightmare_ritual"), tr("dialogue.skycraft.quest.waking_nightmare_done"))
                .filter("any");

        q.add(o1).add(o2).add(o3).reward(600);
        return new RadiantQuests.Offer(q, tr("dialogue.skycraft.quest.pitch_waking_nightmare"));
    }

    public static boolean startWakingNightmare(ServerPlayer player, LivingEntity giver) {
        if (!Quests.canTakeMore(player)) return false;
        return Quests.start(player, wakingNightmareOffer(player, giver).quest());
    }

    // ==================================================================
    // 4. DEDICATED MISSION ARC: THE FORSWORN CONSPIRACY
    // ==================================================================

    public static RadiantQuests.Offer forswornOffer(ServerPlayer player, LivingEntity giver) {
        ServerLevel level = (ServerLevel) player.level();
        String dim = level.dimension().identifier().toString();
        BlockPos at = player.blockPosition();
        BlockPos ruins = Locate.randomSpot(level, player.getRandom(), at, 250, 500);

        Quest q = new Quest("arc_forsworn_conspiracy", Quest.Category.SIDE,
                tr("quest.skycraft.arc_forsworn_conspiracy.title"), tr("quest.skycraft.arc_forsworn_conspiracy.desc"));
        q.giver = giver.getUUID();
        q.giverName = giver.getDisplayName();
        q.giverPos = giver.blockPosition();
        q.giverDim = dim;
        q.hold = "skyrim/the_reach";

        Objective o1 = new Objective(Objective.Type.TALK_TO, tr("quest.skycraft.obj.forsworn_1"))
                .filter("any").topic(tr("dialogue.skycraft.quest.forsworn_investigate"), tr("dialogue.skycraft.quest.forsworn_shrine"));

        Objective o2 = new Objective(Objective.Type.CLEAR_AREA, tr("quest.skycraft.obj.forsworn_2"))
                .at(at.offset(16, 0, 16), dim, 24).count(3)
                .spawn(Spawn.of("skycraft:bandit", 3).name(lit("Silver-Blood Thug")).build())
                .label("Market Ambush");

        Objective o3 = new Objective(Objective.Type.GO_TO, tr("quest.skycraft.obj.forsworn_3"))
                .at(ruins, dim, 24).guessY().label("Cidhna Mine");

        Objective o4 = new Objective(Objective.Type.TALK_TO, tr("quest.skycraft.obj.forsworn_4"))
                .filter("any").topic(tr("dialogue.skycraft.quest.madanach_breakout"), tr("dialogue.skycraft.quest.madanach_reward"));

        ItemStack armorReward = new ItemStack(Items.CHAINMAIL_CHESTPLATE);
        armorReward.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, tr("item.skycraft.reward.armor_old_gods"));
        armorReward.enchant(com.skycraft.platform.EnchantmentsAccess.holder(Enchantments.PROJECTILE_PROTECTION), 3);

        q.add(o1).add(o2).add(o3).add(o4).reward(1000, armorReward);
        return new RadiantQuests.Offer(q, tr("dialogue.skycraft.quest.pitch_forsworn"));
    }

    public static boolean startForswornConspiracy(ServerPlayer player, LivingEntity giver) {
        if (!Quests.canTakeMore(player)) return false;
        return Quests.start(player, forswornOffer(player, giver).quest());
    }

    // ==================================================================
    // DIALOGUE OPTIONS INTEGRATION
    // ==================================================================

    public static void addSideQuestTopics(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        PlayerData data = SkyData.get(player);
        if (data.getLevel() < 1 || !QuestDialogue.canOfferQuest(player, npc)) return;
        String holdBase = Holds.base(Holds.holdAt(player.level(), npc.blockPosition()));

        // The Golden Claw — offered strictly by the designated Riverwood merchant during shop hours
        if (!hasSideQuest(player, "side_golden_claw")) {
            boolean isMerchant = com.skycraft.economy.Merchants.isMerchant(npc);
            boolean openHours = com.skycraft.economy.Shop.isOpen(player.level());
            boolean inRiverwood = com.skycraft.tamriel.Places.byId("riverwood")
                    .map(p -> player.level().dimension() == net.minecraft.world.level.Level.OVERWORLD
                            && p.distSqr(npc.blockPosition().getX(), npc.blockPosition().getZ())
                            <= (long) (p.radius() + 48) * (p.radius() + 48))
                    .orElse(false);
            if (isMerchant && openHours && inRiverwood) {
                QuestStore qs = QuestStore.get(player.level().getServer());
                java.util.UUID designated = qs.getDesignatedGiver("side_golden_claw");
                if (designated == null) {
                    qs.setDesignatedGiver("side_golden_claw", npc.getUUID());
                    designated = npc.getUUID();
                }
                if (designated.equals(npc.getUUID())) {
                    out.add(new DialogueOption("quest.side.golden_claw",
                            QuestDialogue.questLabel(tr("dialogue.skycraft.quest.ask_golden_claw")), 320,
                            (pl, n) -> QuestDialogue.offer(pl, n, "side_golden_claw", () -> goldenClawOffer(pl, n))));
                    return;
                }
            }
        }

        // In My Time of Need — offered in Whiterun Hold by caravan traders or soldiers
        if (!hasSideQuest(player, "side_time_of_need") && data.getLevel() >= 3 && "whiterun".equals(holdBase)) {
            boolean isGuardOrTrader = com.skycraft.economy.Merchants.isCaravan(npc)
                    || (npc instanceof com.skycraft.society.entity.NpcEntity ne
                    && (ne.role() == com.skycraft.society.NpcRole.IMPERIAL_SOLDIER || ne.role() == com.skycraft.society.NpcRole.STORMCLOAK_SOLDIER));
            if (isGuardOrTrader) {
                out.add(new DialogueOption("quest.side.time_of_need",
                        QuestDialogue.questLabel(tr("dialogue.skycraft.quest.ask_time_of_need")), 321,
                        (pl, n) -> QuestDialogue.offer(pl, n, "side_time_of_need", () -> timeOfNeedOffer(pl, n))));
                return;
            }
        }

        // Waking Nightmare — offered in The Pale (Dawnstar) by priests or innkeepers
        if (!hasSideQuest(player, "side_waking_nightmare") && data.getLevel() >= 5 && "the_pale".equals(holdBase)) {
            boolean isPriestOrInn = (npc instanceof com.skycraft.society.entity.NpcEntity ne
                    && (ne.role() == com.skycraft.society.NpcRole.PRIEST || ne.role() == com.skycraft.society.NpcRole.INNKEEPER));
            if (isPriestOrInn) {
                out.add(new DialogueOption("quest.side.waking_nightmare",
                        QuestDialogue.questLabel(tr("dialogue.skycraft.quest.ask_waking_nightmare")), 322,
                        (pl, n) -> QuestDialogue.offer(pl, n, "side_waking_nightmare", () -> wakingNightmareOffer(pl, n))));
                return;
            }
        }

        // The Forsworn Conspiracy Arc — offered in The Reach by guards, innkeepers, or miners
        if (!hasSideQuest(player, "arc_forsworn_conspiracy") && data.getLevel() >= 6 && "the_reach".equals(holdBase)) {
            boolean isMiner = npc instanceof com.skycraft.society.entity.NpcEntity ne && ne.role() == com.skycraft.society.NpcRole.MINER;
            if (isMiner || QuestDialogue.isGuard(npc) || QuestDialogue.isInnkeeper(npc)) {
                out.add(new DialogueOption("quest.arc.forsworn",
                        QuestDialogue.questLabel(tr("dialogue.skycraft.quest.ask_forsworn")), 323,
                        (pl, n) -> QuestDialogue.offer(pl, n, "arc_forsworn_conspiracy", () -> forswornOffer(pl, n))));
            }
        }
    }

    public static boolean hasSideQuest(ServerPlayer player, String questId) {
        return Quests.find(player.level().getServer(), player.getUUID(), questId) != null;
    }
}

