package com.nirncraft.economy;

import com.nirncraft.SkyConfig;
import com.nirncraft.core.Currency;
import com.nirncraft.core.Notifier;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.core.Skill;
import com.nirncraft.dialogue.Dialogue;
import com.nirncraft.skills.Progression;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;

/**
 * Skyrim skill trainers. Villagers train the skill of their trade; the trainer's mastery depends on their level
 * (novice/apprentice 50, journeyman/expert 75, master 90). Each lesson costs {@code level^2 * 0.2 + 5 * level} gold
 * and only {@link SkyConfig#MAX_TRAININGS_PER_LEVEL} lessons are allowed per character level.
 */
public final class Trainers {
    private Trainers() {}

    private static Skill pick(LivingEntity npc, Skill... options) {
        return options[Math.floorMod(npc.getUUID().hashCode(), options.length)];
    }

    /** The skill this NPC trains, or null if they are no trainer. */
    public static Skill skillFor(LivingEntity npc) {
        if (npc == null || !npc.isAlive()) return null;
        if (com.nirncraft.roads.Carriages.isCarriageDriver(npc)) return null;
        if (npc instanceof WanderingTrader || Merchants.isCaravan(npc)) return pick(npc, Skill.PICKPOCKET, Skill.LOCKPICKING, Skill.SPEECH);
        if (npc.isBaby()) return null;
        // villagers and NirnCraft townsfolk (whose trade maps to a profession, see Merchants#profession)
        net.minecraft.resources.ResourceKey<VillagerProfession> p = Merchants.profession(npc);
        if (p == null) return null;
        if (p == VillagerProfession.WEAPONSMITH) return pick(npc, Skill.ONE_HANDED, Skill.TWO_HANDED);
        if (p == VillagerProfession.ARMORER) return pick(npc, Skill.HEAVY_ARMOR, Skill.BLOCK);
        if (p == VillagerProfession.TOOLSMITH) return pick(npc, Skill.SMITHING, Skill.MINING);
        if (p == VillagerProfession.FLETCHER) return Skill.ARCHERY;
        if (p == VillagerProfession.LEATHERWORKER) return Skill.LIGHT_ARMOR;
        if (p == VillagerProfession.CLERIC) return pick(npc, Skill.RESTORATION, Skill.ALCHEMY);
        if (p == VillagerProfession.LIBRARIAN) {
            return pick(npc, Skill.DESTRUCTION, Skill.CONJURATION, Skill.ILLUSION, Skill.ALTERATION, Skill.ENCHANTING);
        }
        if (p == VillagerProfession.CARTOGRAPHER) return Skill.SPEECH;
        if (p == VillagerProfession.BUTCHER) return Skill.HUNTING;
        if (p == VillagerProfession.FISHERMAN) return Skill.FISHING;
        if (p == VillagerProfession.FARMER) return Skill.WOODCUTTING;
        if (p == VillagerProfession.MASON) return Skill.MINING;
        if (p == VillagerProfession.SHEPHERD) return Skill.SNEAK;
        return null;
    }

    /** Highest skill level this trainer can teach up to. */
    public static int maxLevel(LivingEntity npc) {
        int lvl = Merchants.level(npc);
        if (lvl >= 5) return 90;
        if (lvl >= 3) return 75;
        return 50;
    }

    /** Cost of the next lesson for a player whose skill is at {@code level}. */
    public static int cost(int level) {
        return (int) Math.round(level * level * 0.2 + 5.0 * level);
    }

    public static void train(ServerPlayer player, LivingEntity npc) {
        Skill skill = skillFor(npc);
        if (skill == null || !Barter.canReach(player, npc)) return;
        PlayerData data = SkyData.get(player);
        int level = data.getSkill(skill);
        if (level >= maxLevel(npc) || level >= Skill.MAX_LEVEL) {
            Dialogue.open(player, npc, Component.translatable("dialogue.nirncraft.economy.trainer.too_skilled", skill.displayName()));
            return;
        }
        int max = SkyConfig.MAX_TRAININGS_PER_LEVEL.get();
        if (data.getTrainingsThisLevel() >= max) {
            Dialogue.open(player, npc, Component.translatable("dialogue.nirncraft.economy.trainer.limit"));
            return;
        }
        int cost = cost(level);
        if (!Currency.take(player, cost)) {
            Dialogue.open(player, npc, Component.translatable("dialogue.nirncraft.economy.trainer.no_gold"));
            return;
        }
        Progression.increaseSkill(player, skill, 1);
        data.setTrainingsThisLevel(data.getTrainingsThisLevel() + 1);
        data.addStat("trainings", 1);
        Notifier.message(player, Component.translatable("message.nirncraft.economy.trained", skill.displayName(),
                data.getSkill(skill), data.getTrainingsThisLevel(), max));
        int line = Math.floorMod(player.getRandom().nextInt(), 3);
        Dialogue.open(player, npc, Component.translatable("dialogue.nirncraft.economy.trainer.done." + line));
    }
}
