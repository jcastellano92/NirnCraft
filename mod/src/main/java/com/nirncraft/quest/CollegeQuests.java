package com.nirncraft.quest;

import com.nirncraft.core.Holds;
import com.nirncraft.magic.MagicRegistry;
import com.nirncraft.quest.QuestSpawner.Spawn;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * College of Winterhold school storylines:
 * <ul>
 *     <li><b>Destruction</b>: Faralda's Trial of Elemental Fury</li>
 *     <li><b>Restoration</b>: Colette's Trial of the Cleansing Light</li>
 *     <li><b>Alteration</b>: Tolfdir's Trial of Matter and Aetherium</li>
 *     <li><b>Conjuration</b>: Phinis Gestor's Trial of Daedric Binding</li>
 *     <li><b>Illusion</b>: Drevis Neloren's Trial of the Mind's Eye</li>
 * </ul>
 * Completing each unlocks advanced (Adept, Expert, Master) tomes for that school.
 */
public final class CollegeQuests {
    private CollegeQuests() {}

    private static Component tr(String key, Object... args) {
        return Component.translatable(key, args);
    }

    private static Component lit(String text) {
        return Component.literal(text);
    }

    // ------------------------------------------------------------------ 1. Destruction
    public static RadiantQuests.Offer destructionOffer(ServerPlayer player, LivingEntity giver) {
        ServerLevel level = (ServerLevel) player.level();
        String dim = level.dimension().identifier().toString();
        BlockPos at = player.blockPosition();
        BlockPos ruin = Locate.randomSpot(level, player.getRandom(), at, 220, 480);

        Quest q = new Quest("college_destruction", Quest.Category.FACTION,
                tr("quest.nirncraft.college_destruction.title"), tr("quest.nirncraft.college_destruction.desc"));
        q.giver = giver.getUUID();
        q.giverName = giver.getDisplayName();
        q.giverPos = giver.blockPosition();
        q.giverDim = dim;
        q.hold = Holds.holdAt(level, at);
        q.faction(Faction.COLLEGE.id, 25, false);

        Objective o1 = new Objective(Objective.Type.GO_TO, tr("quest.nirncraft.obj.college_dest_1"))
                .at(ruin, dim, 28).guessY().label("Elemental Breach");
        Objective o2 = new Objective(Objective.Type.KILL_TARGET, tr("quest.nirncraft.obj.college_dest_2"))
                .at(ruin, dim, 0).guessY().placement("surface").label("Pyromancer Archmage")
                .spawn(Spawn.of("nirncraft:bandit_chief", 1).name(lit("Pyromancer Archmage")).target().boss().health(2.2f).build())
                .spawn(Spawn.of("minecraft:skeleton", 3).name(lit("Flame Thrall")).build());
        Objective o3 = new Objective(Objective.Type.TALK_TO, tr("quest.nirncraft.obj.college_dest_3"))
                .npc(giver.getUUID()).at(giver.blockPosition(), dim, 0)
                .topic(tr("dialogue.nirncraft.quest.college_dest_return"), tr("quest.nirncraft.reply.college_dest_done"))
                .label(giver.getDisplayName().getString());

        ItemStack tome = MagicRegistry.TOMES.containsKey("fireball")
                ? new ItemStack(MagicRegistry.TOMES.get("fireball").get()) : ItemStack.EMPTY;
        q.add(o1).add(o2).add(o3).reward(500, tome);
        return new RadiantQuests.Offer(q, tr("dialogue.nirncraft.college.dest_start"));
    }

    public static boolean startDestruction(ServerPlayer player, LivingEntity giver) {
        if (!Quests.canTakeMore(player)) return false;
        return Quests.start(player, destructionOffer(player, giver).quest());
    }

    // ------------------------------------------------------------------ 2. Restoration
    public static RadiantQuests.Offer restorationOffer(ServerPlayer player, LivingEntity giver) {
        ServerLevel level = (ServerLevel) player.level();
        String dim = level.dimension().identifier().toString();
        BlockPos at = player.blockPosition();
        BlockPos crypt = Locate.dungeon(level, at, player.getRandom());
        if (crypt == null) crypt = Locate.randomSpot(level, player.getRandom(), at, 200, 450);

        Quest q = new Quest("college_restoration", Quest.Category.FACTION,
                tr("quest.nirncraft.college_restoration.title"), tr("quest.nirncraft.college_restoration.desc"));
        q.giver = giver.getUUID();
        q.giverName = giver.getDisplayName();
        q.giverPos = giver.blockPosition();
        q.giverDim = dim;
        q.hold = Holds.holdAt(level, at);
        q.faction(Faction.COLLEGE.id, 25, false);

        Objective o1 = new Objective(Objective.Type.GO_TO, tr("quest.nirncraft.obj.college_rest_1"))
                .at(crypt, dim, 24).guessY().label("Desecrated Catacombs");
        Objective o2 = new Objective(Objective.Type.KILL_TARGET, tr("quest.nirncraft.obj.college_rest_2"))
                .at(crypt, dim, 0).guessY().placement("structure").label("Tormented Lich")
                .spawn(Spawn.of("nirncraft:draugr_deathlord", 1).name(lit("Tormented Lich")).target().boss().health(2.0f).build())
                .spawn(Spawn.of("minecraft:skeleton", 4).name(lit("Restless Shade")).build());
        Objective o3 = new Objective(Objective.Type.TALK_TO, tr("quest.nirncraft.obj.college_rest_3"))
                .npc(giver.getUUID()).at(giver.blockPosition(), dim, 0)
                .topic(tr("dialogue.nirncraft.quest.college_rest_return"), tr("quest.nirncraft.reply.college_rest_done"))
                .label(giver.getDisplayName().getString());

        ItemStack tome = MagicRegistry.TOMES.containsKey("close_wounds")
                ? new ItemStack(MagicRegistry.TOMES.get("close_wounds").get()) : ItemStack.EMPTY;
        q.add(o1).add(o2).add(o3).reward(500, tome);
        return new RadiantQuests.Offer(q, tr("dialogue.nirncraft.college.rest_start"));
    }

    public static boolean startRestoration(ServerPlayer player, LivingEntity giver) {
        if (!Quests.canTakeMore(player)) return false;
        return Quests.start(player, restorationOffer(player, giver).quest());
    }

    // ------------------------------------------------------------------ 3. Alteration
    public static RadiantQuests.Offer alterationOffer(ServerPlayer player, LivingEntity giver) {
        ServerLevel level = (ServerLevel) player.level();
        String dim = level.dimension().identifier().toString();
        BlockPos at = player.blockPosition();
        BlockPos fracture = Locate.randomSpot(level, player.getRandom(), at, 240, 500);

        Quest q = new Quest("college_alteration", Quest.Category.FACTION,
                tr("quest.nirncraft.college_alteration.title"), tr("quest.nirncraft.college_alteration.desc"));
        q.giver = giver.getUUID();
        q.giverName = giver.getDisplayName();
        q.giverPos = giver.blockPosition();
        q.giverDim = dim;
        q.hold = Holds.holdAt(level, at);
        q.faction(Faction.COLLEGE.id, 25, false);

        Objective o1 = new Objective(Objective.Type.GO_TO, tr("quest.nirncraft.obj.college_alt_1"))
                .at(fracture, dim, 24).guessY().label("Aetherial Fracture");
        Objective o2 = new Objective(Objective.Type.KILL_TARGET, tr("quest.nirncraft.obj.college_alt_2"))
                .at(fracture, dim, 0).guessY().placement("surface").label("Anomalous Construct")
                .spawn(Spawn.of("nirncraft:bandit_chief", 1).name(lit("Anomalous Construct")).target().boss().health(2.4f).build())
                .spawn(Spawn.of("minecraft:spider", 3).build());
        Objective o3 = new Objective(Objective.Type.TALK_TO, tr("quest.nirncraft.obj.college_alt_3"))
                .npc(giver.getUUID()).at(giver.blockPosition(), dim, 0)
                .topic(tr("dialogue.nirncraft.quest.college_alt_return"), tr("quest.nirncraft.reply.college_alt_done"))
                .label(giver.getDisplayName().getString());

        ItemStack tome = MagicRegistry.TOMES.containsKey("ironflesh")
                ? new ItemStack(MagicRegistry.TOMES.get("ironflesh").get()) : ItemStack.EMPTY;
        q.add(o1).add(o2).add(o3).reward(500, tome);
        return new RadiantQuests.Offer(q, tr("dialogue.nirncraft.college.alt_start"));
    }

    public static boolean startAlteration(ServerPlayer player, LivingEntity giver) {
        if (!Quests.canTakeMore(player)) return false;
        return Quests.start(player, alterationOffer(player, giver).quest());
    }

    // ------------------------------------------------------------------ 4. Conjuration
    public static RadiantQuests.Offer conjurationOffer(ServerPlayer player, LivingEntity giver) {
        ServerLevel level = (ServerLevel) player.level();
        String dim = level.dimension().identifier().toString();
        BlockPos at = player.blockPosition();
        BlockPos circle = Locate.randomSpot(level, player.getRandom(), at, 220, 460);

        Quest q = new Quest("college_conjuration", Quest.Category.FACTION,
                tr("quest.nirncraft.college_conjuration.title"), tr("quest.nirncraft.college_conjuration.desc"));
        q.giver = giver.getUUID();
        q.giverName = giver.getDisplayName();
        q.giverPos = giver.blockPosition();
        q.giverDim = dim;
        q.hold = Holds.holdAt(level, at);
        q.faction(Faction.COLLEGE.id, 25, false);

        Objective o1 = new Objective(Objective.Type.GO_TO, tr("quest.nirncraft.obj.college_conj_1"))
                .at(circle, dim, 24).guessY().label("Ritual Circle");
        Objective o2 = new Objective(Objective.Type.KILL_TARGET, tr("quest.nirncraft.obj.college_conj_2"))
                .at(circle, dim, 0).guessY().placement("surface").label("Renegade Conjurer")
                .spawn(Spawn.of("nirncraft:necromancer", 1).name(lit("Renegade Conjurer")).target().boss().health(2.2f).build())
                .spawn(Spawn.of("minecraft:zombie", 4).name(lit("Bound Undead")).build());
        Objective o3 = new Objective(Objective.Type.TALK_TO, tr("quest.nirncraft.obj.college_conj_3"))
                .npc(giver.getUUID()).at(giver.blockPosition(), dim, 0)
                .topic(tr("dialogue.nirncraft.quest.college_conj_return"), tr("quest.nirncraft.reply.college_conj_done"))
                .label(giver.getDisplayName().getString());

        ItemStack tome = MagicRegistry.TOMES.containsKey("conjure_frost_atronach")
                ? new ItemStack(MagicRegistry.TOMES.get("conjure_frost_atronach").get()) : ItemStack.EMPTY;
        q.add(o1).add(o2).add(o3).reward(500, tome);
        return new RadiantQuests.Offer(q, tr("dialogue.nirncraft.college.conj_start"));
    }

    public static boolean startConjuration(ServerPlayer player, LivingEntity giver) {
        if (!Quests.canTakeMore(player)) return false;
        return Quests.start(player, conjurationOffer(player, giver).quest());
    }

    // ------------------------------------------------------------------ 5. Illusion
    public static RadiantQuests.Offer illusionOffer(ServerPlayer player, LivingEntity giver) {
        ServerLevel level = (ServerLevel) player.level();
        String dim = level.dimension().identifier().toString();
        BlockPos at = player.blockPosition();
        BlockPos lair = Locate.randomSpot(level, player.getRandom(), at, 230, 480);

        Quest q = new Quest("college_illusion", Quest.Category.FACTION,
                tr("quest.nirncraft.college_illusion.title"), tr("quest.nirncraft.college_illusion.desc"));
        q.giver = giver.getUUID();
        q.giverName = giver.getDisplayName();
        q.giverPos = giver.blockPosition();
        q.giverDim = dim;
        q.hold = Holds.holdAt(level, at);
        q.faction(Faction.COLLEGE.id, 25, false);

        Objective o1 = new Objective(Objective.Type.GO_TO, tr("quest.nirncraft.obj.college_ill_1"))
                .at(lair, dim, 24).guessY().label("Phantasmal Hollow");
        Objective o2 = new Objective(Objective.Type.KILL_TARGET, tr("quest.nirncraft.obj.college_ill_2"))
                .at(lair, dim, 0).guessY().placement("surface").label("Master of Mirages")
                .spawn(Spawn.of("nirncraft:bandit_chief", 1).name(lit("Master of Mirages")).target().boss().health(2.0f).build())
                .spawn(Spawn.of("minecraft:spider", 3).name(lit("Phantasm")).build());
        Objective o3 = new Objective(Objective.Type.TALK_TO, tr("quest.nirncraft.obj.college_ill_3"))
                .npc(giver.getUUID()).at(giver.blockPosition(), dim, 0)
                .topic(tr("dialogue.nirncraft.quest.college_ill_return"), tr("quest.nirncraft.reply.college_ill_done"))
                .label(giver.getDisplayName().getString());

        ItemStack tome = MagicRegistry.TOMES.containsKey("invisibility")
                ? new ItemStack(MagicRegistry.TOMES.get("invisibility").get()) : ItemStack.EMPTY;
        q.add(o1).add(o2).add(o3).reward(500, tome);
        return new RadiantQuests.Offer(q, tr("dialogue.nirncraft.college.ill_start"));
    }

    public static boolean startIllusion(ServerPlayer player, LivingEntity giver) {
        if (!Quests.canTakeMore(player)) return false;
        return Quests.start(player, illusionOffer(player, giver).quest());
    }
}

