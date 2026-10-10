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

    public static boolean startGoldenClaw(ServerPlayer player, LivingEntity giver) {
        if (!Quests.canTakeMore(player)) return false;
        ServerLevel level = (ServerLevel) player.level();
        String dim = level.dimension().location().toString();
        BlockPos at = player.blockPosition();
        // the real Bleak Falls Barrow when the world has it within reach, else a barrow up a mountain nearby
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

        // Step 1: Ascend the mountain to Bleak Falls Barrow
        Objective o1 = new Objective(Objective.Type.GO_TO, tr("quest.skycraft.obj.golden_claw_1"))
                .at(dungeon, dim, 32).guessY().label("Bleak Falls Barrow");

        // Step 2: Slay Arvel the Swift and recover the Claw
        Objective o2 = new Objective(Objective.Type.KILL_TARGET, tr("quest.skycraft.obj.golden_claw_2"))
                .at(dungeon, dim, 0).guessY().placement("structure").label("Arvel the Swift")
                .spawn(Spawn.of("skycraft:bandit_chief", 1).name(lit("Arvel the Swift")).target().boss().health(1.8f).build())
                .spawn(Spawn.of("skycraft:bandit", 3).build());
        o2.onComplete = "give:skycraft:ancient_tome";

        // Step 3: Return to Lucan in Riverwood
        Objective o3 = new Objective(Objective.Type.TALK_TO, tr("quest.skycraft.obj.golden_claw_3"))
                .npc(giver.getUUID()).at(giver.blockPosition(), dim, 0)
                .topic(tr("dialogue.skycraft.quest.golden_claw_return"), tr("quest.skycraft.reply.golden_claw_thanks"))
                .label(giver.getDisplayName().getString());

        q.add(o1).add(o2).add(o3).reward(400);
        return Quests.start(player, q);
    }

    // ==================================================================
    // 2. IN MY TIME OF NEED (Saadia vs Kematu)
    // ==================================================================

    public static boolean startTimeOfNeed(ServerPlayer player, LivingEntity giver) {
        if (!Quests.canTakeMore(player)) return false;
        ServerLevel level = (ServerLevel) player.level();
        String dim = level.dimension().location().toString();
        BlockPos at = player.blockPosition();
        BlockPos den = Locate.randomSpot(level, player.getRandom(), at, 250, 500);

        Quest q = new Quest("side_time_of_need", Quest.Category.SIDE,
                tr("quest.skycraft.side_time_of_need.title"), tr("quest.skycraft.side_time_of_need.desc"));
        q.giver = giver.getUUID();
        q.giverName = giver.getDisplayName();
        q.giverPos = giver.blockPosition();
        q.giverDim = dim;
        q.hold = Holds.holdAt(level, at);

        // Step 1: Speak to the tavern maid Saadia
        Objective o1 = new Objective(Objective.Type.TALK_TO, tr("quest.skycraft.obj.time_of_need_1"))
                .filter("villager").topic(tr("dialogue.skycraft.quest.saadia_ask"), tr("dialogue.skycraft.quest.saadia_reply"));

        // Step 2: Confront Kematu at Swindler's Den
        Objective o2 = new Objective(Objective.Type.GO_TO, tr("quest.skycraft.obj.time_of_need_2"))
                .at(den, dim, 24).guessY().label("Swindler's Den");

        // Step 3: Slay Kematu and his Alik'r warriors
        Objective o3 = new Objective(Objective.Type.KILL_TARGET, tr("quest.skycraft.obj.time_of_need_3"))
                .at(den, dim, 0).guessY().placement("surface").label("Kematu")
                .spawn(Spawn.of("skycraft:bandit_chief", 1).name(lit("Kematu")).target().boss().health(2.0f).build())
                .spawn(Spawn.of("skycraft:bandit", 4).name(lit("Alik'r Warrior")).build());

        ItemStack scimitar = new ItemStack(Items.IRON_SWORD);
        scimitar.setHoverName(tr("item.skycraft.reward.alikr_scimitar"));
        scimitar.enchant(Enchantments.SHARPNESS, 2);

        q.add(o1).add(o2).add(o3).reward(500, scimitar);
        return Quests.start(player, q);
    }

    // ==================================================================
    // 3. WAKING NIGHTMARE (Erandur / Vaermina)
    // ==================================================================

    public static boolean startWakingNightmare(ServerPlayer player, LivingEntity giver) {
        if (!Quests.canTakeMore(player)) return false;
        ServerLevel level = (ServerLevel) player.level();
        String dim = level.dimension().location().toString();
        BlockPos at = player.blockPosition();
        BlockPos temple = Locate.randomSpot(level, player.getRandom(), at, 300, 600);

        Quest q = new Quest("side_waking_nightmare", Quest.Category.SIDE,
                tr("quest.skycraft.side_waking_nightmare.title"), tr("quest.skycraft.side_waking_nightmare.desc"));
        q.giver = giver.getUUID();
        q.giverName = giver.getDisplayName();
        q.giverPos = giver.blockPosition();
        q.giverDim = dim;
        q.hold = Holds.holdAt(level, at);

        // Step 1: Reach the Corrupted Nightcaller Temple
        Objective o1 = new Objective(Objective.Type.GO_TO, tr("quest.skycraft.obj.waking_nightmare_1"))
                .at(temple, dim, 32).guessY().label("Nightcaller Temple");

        // Step 2: Purge the Tormented Phantoms
        Objective o2 = new Objective(Objective.Type.CLEAR_AREA, tr("quest.skycraft.obj.waking_nightmare_2"))
                .at(temple, dim, 28).guessY().count(6)
                .spawn(Spawn.of("skycraft:draugr", 4).name(lit("Tormented Phantom")).build())
                .spawn(Spawn.of("minecraft:witch", 2).name(lit("Vaermina Priestess")).build())
                .label("Temple Sanctum");

        // Step 3: Complete the Banishment Ritual with Erandur
        Objective o3 = new Objective(Objective.Type.TALK_TO, tr("quest.skycraft.obj.waking_nightmare_3"))
                .topic(tr("dialogue.skycraft.quest.waking_nightmare_ritual"), tr("dialogue.skycraft.quest.waking_nightmare_done"))
                .filter("any");

        q.add(o1).add(o2).add(o3).reward(600);
        return Quests.start(player, q);
    }

    // ==================================================================
    // 4. DEDICATED MISSION ARC: THE FORSWORN CONSPIRACY
    // ==================================================================

    public static boolean startForswornConspiracy(ServerPlayer player, LivingEntity giver) {
        if (!Quests.canTakeMore(player)) return false;
        ServerLevel level = (ServerLevel) player.level();
        String dim = level.dimension().location().toString();
        BlockPos at = player.blockPosition();
        BlockPos ruins = Locate.randomSpot(level, player.getRandom(), at, 250, 500);

        Quest q = new Quest("arc_forsworn_conspiracy", Quest.Category.SIDE,
                tr("quest.skycraft.arc_forsworn_conspiracy.title"), tr("quest.skycraft.arc_forsworn_conspiracy.desc"));
        q.giver = giver.getUUID();
        q.giverName = giver.getDisplayName();
        q.giverPos = giver.blockPosition();
        q.giverDim = dim;
        q.hold = "skyrim/the_reach";

        // Part 1: Investigate the Market Murder and read Margret's Note
        Objective o1 = new Objective(Objective.Type.TALK_TO, tr("quest.skycraft.obj.forsworn_1"))
                .filter("any").topic(tr("dialogue.skycraft.quest.forsworn_investigate"), tr("dialogue.skycraft.quest.forsworn_shrine"));

        // Part 2: Slay Silver-Blood Thugs attempting to silence the truth
        Objective o2 = new Objective(Objective.Type.CLEAR_AREA, tr("quest.skycraft.obj.forsworn_2"))
                .at(at.offset(16, 0, 16), dim, 24).count(3)
                .spawn(Spawn.of("skycraft:bandit", 3).name(lit("Silver-Blood Thug")).build())
                .label("Market Ambush");

        // Part 3: Framed & Sent to Cidhna Mine: Infiltrate the deep mines
        Objective o3 = new Objective(Objective.Type.GO_TO, tr("quest.skycraft.obj.forsworn_3"))
                .at(ruins, dim, 24).guessY().label("Cidhna Mine");

        // Part 4: Confront Madanach, the King in Rags
        Objective o4 = new Objective(Objective.Type.TALK_TO, tr("quest.skycraft.obj.forsworn_4"))
                .filter("any").topic(tr("dialogue.skycraft.quest.madanach_breakout"), tr("dialogue.skycraft.quest.madanach_reward"));

        ItemStack armorReward = new ItemStack(Items.CHAINMAIL_CHESTPLATE);
        armorReward.setHoverName(tr("item.skycraft.reward.armor_old_gods"));
        armorReward.enchant(Enchantments.PROJECTILE_PROTECTION, 3);

        q.add(o1).add(o2).add(o3).add(o4).reward(1000, armorReward);
        return Quests.start(player, q);
    }

    // ==================================================================
    // DIALOGUE OPTIONS INTEGRATION
    // ==================================================================

    public static void addSideQuestTopics(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        PlayerData data = SkyData.get(player);
        if (data.getLevel() < 1) return;

        // The Golden Claw — offered strictly by the first merchant encountered in the world (like Lucan) during shop hours
        if (!hasSideQuest(player, "side_golden_claw")) {
            boolean isMerchant = com.skycraft.economy.Merchants.isMerchant(npc);
            boolean openHours = com.skycraft.economy.Shop.isOpen(player.level());
            if (isMerchant && openHours) {
                QuestStore qs = QuestStore.get(player.server);
                java.util.UUID designated = qs.getDesignatedGiver("side_golden_claw");
                if (designated == null) {
                    qs.setDesignatedGiver("side_golden_claw", npc.getUUID());
                    designated = npc.getUUID();
                }
                if (designated.equals(npc.getUUID())) {
                    out.add(new DialogueOption("quest.side.golden_claw",
                            tr("dialogue.skycraft.quest.ask_golden_claw"), 320, (pl, n) -> {
                        if (startGoldenClaw(pl, n)) {
                            Dialogue.open(pl, n, tr("dialogue.skycraft.quest.pitch_golden_claw"));
                        } else {
                            Dialogue.open(pl, n, tr("quest.skycraft.reply.too_busy"));
                        }
                    }));
                }
            }
        }

        // In My Time of Need — offered by guards or wandering traders (Redguard search)
        if (!hasSideQuest(player, "side_time_of_need") && data.getLevel() >= 3) {
            boolean isGuardOrTrader = npc instanceof net.minecraft.world.entity.npc.WanderingTrader
                    || (npc instanceof com.skycraft.society.entity.NpcEntity ne
                    && (ne.role() == com.skycraft.society.NpcRole.IMPERIAL_SOLDIER || ne.role() == com.skycraft.society.NpcRole.STORMCLOAK_SOLDIER));
            if (isGuardOrTrader) {
                out.add(new DialogueOption("quest.side.time_of_need",
                        tr("dialogue.skycraft.quest.ask_time_of_need"), 321, (pl, n) -> {
                    if (startTimeOfNeed(pl, n)) {
                        Dialogue.open(pl, n, tr("dialogue.skycraft.quest.pitch_time_of_need"));
                    } else {
                        Dialogue.open(pl, n, tr("quest.skycraft.reply.too_busy"));
                    }
                }));
            }
        }

        // Waking Nightmare — offered by priests or innkeepers troubled by nightmares
        if (!hasSideQuest(player, "side_waking_nightmare") && data.getLevel() >= 5) {
            boolean isPriestOrInn = (npc instanceof com.skycraft.society.entity.NpcEntity ne
                    && (ne.role() == com.skycraft.society.NpcRole.PRIEST || ne.role() == com.skycraft.society.NpcRole.INNKEEPER));
            if (isPriestOrInn) {
                out.add(new DialogueOption("quest.side.waking_nightmare",
                        tr("dialogue.skycraft.quest.ask_waking_nightmare"), 322, (pl, n) -> {
                    if (startWakingNightmare(pl, n)) {
                        Dialogue.open(pl, n, tr("dialogue.skycraft.quest.pitch_waking_nightmare"));
                    } else {
                        Dialogue.open(pl, n, tr("quest.skycraft.reply.too_busy"));
                    }
                }));
            }
        }

        // The Forsworn Conspiracy Arc — offered in The Reach or by miners
        if (!hasSideQuest(player, "arc_forsworn_conspiracy") && data.getLevel() >= 6) {
            boolean inReachOrMiner = "the_reach".equals(Holds.base(Holds.holdAt(player.serverLevel(), player.blockPosition())))
                    || (npc instanceof com.skycraft.society.entity.NpcEntity ne && ne.role() == com.skycraft.society.NpcRole.MINER);
            if (inReachOrMiner) {
                out.add(new DialogueOption("quest.arc.forsworn",
                        tr("dialogue.skycraft.quest.ask_forsworn"), 323, (pl, n) -> {
                    if (startForswornConspiracy(pl, n)) {
                        Dialogue.open(pl, n, tr("dialogue.skycraft.quest.pitch_forsworn"));
                    } else {
                        Dialogue.open(pl, n, tr("quest.skycraft.reply.too_busy"));
                    }
                }));
            }
        }
    }

    public static boolean hasSideQuest(ServerPlayer player, String questId) {
        return Quests.find(player.server, player.getUUID(), questId) != null;
    }
}

