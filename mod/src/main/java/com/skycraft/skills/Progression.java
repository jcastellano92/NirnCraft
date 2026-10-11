package com.skycraft.skills;

import com.skycraft.SkyConfig;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.Race;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.network.NotifyKind;
import com.skycraft.perk.Perk;
import com.skycraft.perk.Perks;
import com.skycraft.registry.ModEffects;
import com.skycraft.vitals.Vitals;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Skyrim leveling: using a skill grants skill XP; each skill increase grants character XP equal to the new
 * skill level; each character level grants a perk point and a +10 Health/Magicka/Stamina choice.
 *
 * <p>All modules award XP through {@link #addSkillXp(ServerPlayer, Skill, float)} with a Skyrim "use value"
 * (e.g. damage dealt in Skyrim units, gold value of an item crafted, or a fixed amount per action).</p>
 */
public final class Progression {
    private static final java.util.List<XpModifier> MODIFIERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Multiplies skill XP gains, e.g. Standing Stones (+20% combat skills) or fortify effects. Return 1 for no change. */
    @FunctionalInterface
    public interface XpModifier {
        float multiplier(ServerPlayer player, Skill skill);
    }

    public static void registerXpModifier(XpModifier modifier) {
        MODIFIERS.add(modifier);
    }

    private Progression() {}

    /**
     * Awards skill XP for a skill use.
     *
     * @param useValue Skyrim-style use value; multiplied by the skill's use multiplier and the configured rate.
     */
    public static void addSkillXp(ServerPlayer player, Skill skill, float useValue) {
        // creative/spectator players don't train skills
        if (useValue <= 0 || player.isCreative() || player.isSpectator()) return;
        PlayerData data = SkyData.get(player);
        float mult = (float) (double) SkyConfig.SKILL_XP_RATE.get();
        if (player.hasEffect(ModEffects.WELL_RESTED.get())) mult *= 1.10f;
        if (player.hasEffect(ModEffects.LOVERS_COMFORT.get())) mult *= 1.15f;
        for (XpModifier modifier : MODIFIERS) mult *= modifier.multiplier(player, skill);
        float gained = useValue * skill.useMult * mult;
        addRawSkillXp(player, data, skill, gained);
    }

    /** Adds already-scaled skill XP (used by training and skill books). */
    public static void addRawSkillXp(ServerPlayer player, PlayerData data, Skill skill, float xp) {
        int level = data.getSkill(skill);
        if (level >= Skill.MAX_LEVEL) return;
        float total = data.getSkillXp(skill) + xp;
        int levelsGained = 0;
        while (level < Skill.MAX_LEVEL && total >= skill.xpToNext(level)) {
            total -= skill.xpToNext(level);
            level++;
            levelsGained++;
            onSkillIncreased(player, data, skill, level);
        }
        if (level >= Skill.MAX_LEVEL) total = 0;
        data.setSkill(skill, level);
        data.setSkillXp(skill, total);
    }

    /** Directly raises a skill by whole levels (trainers, skill books). Grants character XP like normal increases. */
    public static void increaseSkill(ServerPlayer player, Skill skill, int levels) {
        PlayerData data = SkyData.get(player);
        for (int i = 0; i < levels; i++) {
            int level = data.getSkill(skill);
            if (level >= Skill.MAX_LEVEL) break;
            data.setSkill(skill, level + 1);
            onSkillIncreased(player, data, skill, level + 1);
        }
        data.setSkillXp(skill, 0);
    }

    private static void onSkillIncreased(ServerPlayer player, PlayerData data, Skill skill, int newLevel) {
        data.setSkill(skill, newLevel);
        Notifier.send(player, NotifyKind.SKILL_UP,
                Component.translatable("notify.skycraft.skill_increased", skill.displayName(), newLevel),
                Component.empty(), skill.ordinal(), 0f);
        player.level().playSound(null, player.blockPosition(), com.skycraft.world.WorldSounds.UI_SKILL_UP.get(), SoundSource.PLAYERS, 0.85f, 1.0f);
        data.addStat("skill_increases", 1);
        addCharacterXp(player, data, newLevel * (float) (double) SkyConfig.LEVEL_XP_RATE.get());
    }

    private static void addCharacterXp(ServerPlayer player, PlayerData data, float xp) {
        float total = data.getLevelXp() + xp;
        int level = data.getLevel();
        while (total >= PlayerData.levelXpToNext(level)) {
            total -= PlayerData.levelXpToNext(level);
            level++;
            data.setLevel(level);
            data.setPendingLevelUps(data.getPendingLevelUps() + 1);
            data.setPerkPoints(data.getPerkPoints() + SkyConfig.PERK_POINTS_PER_LEVEL.get());
            data.setTrainingsThisLevel(0);
            Notifier.send(player, NotifyKind.LEVEL_UP,
                    Component.translatable("notify.skycraft.level_up", level),
                    Component.translatable("notify.skycraft.level_up_hint"), level, 0f);
            player.level().playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.6f, 0.8f);
        }
        data.setLevelXp(total);
    }

    /** Applies one pending level-up: +10 to Health (0), Magicka (1) or Stamina (2), and fully restores it. */
    public static void applyLevelUpChoice(ServerPlayer player, int which) {
        PlayerData data = SkyData.get(player);
        if (data.getPendingLevelUps() <= 0 || which < 0 || which > 2) return;
        data.setPendingLevelUps(data.getPendingLevelUps() - 1);
        data.addAttributePoint(which);
        Vitals.refreshAttributes(player);
        switch (which) {
            case 0 -> player.setHealth(player.getMaxHealth());
            case 1 -> data.setMagicka(data.maxMagicka());
            default -> data.setStamina(data.maxStamina());
        }
    }

    /** Sets the player's race (in the race's default look). Only allowed once, unless reset by an operator. */
    public static void chooseRace(ServerPlayer player, Race race) {
        chooseRace(player, race, -1);
    }

    /** Sets the player's race and look (-1: the race's default look). Only allowed once, unless reset by an operator. */
    public static void chooseRace(ServerPlayer player, Race race, int look) {
        PlayerData data = SkyData.get(player);
        if (data.getRace() != null) return;
        look = look < 0 ? com.skycraft.society.NpcLook.defaultFor(race, player.getUUID().getLeastSignificantBits())
                : com.skycraft.society.NpcLook.sanitizePlayer(look, race);
        data.module("race").putInt("look", look);
        data.setRace(race);
        race.startingSkills().forEach((skill, lvl) -> {
            if (data.getSkill(skill) <= Skill.BASE_LEVEL) data.setSkill(skill, lvl);
        });
        Vitals.refreshAttributes(player);
        data.setMagicka(data.maxMagicka());
        Notifier.title(player, race.displayName(), Component.translatable("notify.skycraft.race_chosen"));
    }

    /** Skyrim "Make Legendary": resets a level-100 skill to 15 and refunds its perks. */
    public static void makeLegendary(ServerPlayer player, Skill skill) {
        PlayerData data = SkyData.get(player);
        if (data.getSkill(skill) < Skill.MAX_LEVEL) return;
        int refund = 0;
        for (Perk perk : Perks.of(skill)) {
            refund += data.getPerkRank(perk.id());
            data.setPerkRank(perk.id(), 0);
        }
        data.setPerkPoints(data.getPerkPoints() + refund);
        data.setSkill(skill, Skill.BASE_LEVEL);
        data.setSkillXp(skill, 0);
        data.setLegendary(skill, data.getLegendary(skill) + 1);
        Vitals.refreshAttributes(player);
        Notifier.title(player, Component.translatable("notify.skycraft.legendary", skill.displayName()), Component.empty());
    }
}
