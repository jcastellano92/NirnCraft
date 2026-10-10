package com.skycraft.society;

import com.skycraft.core.Currency;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.Race;
import com.skycraft.core.Skill;
import com.skycraft.core.SkyData;
import com.skycraft.perk.Perks;
import com.skycraft.skills.Progression;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Skyrim-style Speech skill checks (Persuade, Intimidate, Bribe).
 * Chances scale with the player's Speech skill, level, race, and perks.
 * Successful checks award Speech skill XP.
 */
public final class SpeechChecks {
    private SpeechChecks() {}

    public static boolean checkPersuade(ServerPlayer player, int difficulty) {
        return checkPersuade(player, difficulty, true);
    }

    /** {@code awardXp} false: a repeat success on the same person teaches nothing new (no Speech XP farming). */
    public static boolean checkPersuade(ServerPlayer player, int difficulty, boolean awardXp) {
        PlayerData data = SkyData.get(player);
        int baseSkill = data.getSkill(Skill.SPEECH);
        int bonus = 0;
        if (Perks.has(player, "speech.persuasion")) bonus += 30;
        if (data.getRace() == Race.IMPERIAL) bonus += 10;
        int effective = baseSkill + bonus;

        boolean success = effective >= difficulty || player.getRandom().nextFloat() < (float) effective / (difficulty * 1.35f);
        if (success) {
            if (awardXp) Progression.addSkillXp(player, Skill.SPEECH, difficulty * 2.5f);
            Notifier.message(player, Component.translatable("society.skycraft.speech.persuade_success"));
            player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5f, 1.6f);
        } else {
            Notifier.message(player, Component.translatable("society.skycraft.speech.persuade_fail"));
        }
        return success;
    }

    public static boolean checkIntimidate(ServerPlayer player, int difficulty) {
        PlayerData data = SkyData.get(player);
        int baseSkill = data.getSkill(Skill.SPEECH);
        int bonus = 0;
        if (Perks.has(player, "speech.intimidation")) bonus += 35;
        if (data.getLevel() >= 15) bonus += 15;
        int effective = baseSkill + bonus;

        boolean success = effective >= difficulty || player.getRandom().nextFloat() < (float) effective / (difficulty * 1.45f);
        if (success) {
            Progression.addSkillXp(player, Skill.SPEECH, difficulty * 2.5f);
            Notifier.message(player, Component.translatable("society.skycraft.speech.intimidate_success"));
            player.playNotifySound(SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.4f, 1.4f);
        } else {
            Notifier.message(player, Component.translatable("society.skycraft.speech.intimidate_fail"));
        }
        return success;
    }

    public static boolean checkBribe(ServerPlayer player, int cost) {
        if (!Currency.take(player, cost)) {
            Notifier.message(player, Component.translatable("message.skycraft.not_enough_gold"));
            return false;
        }
        Progression.addSkillXp(player, Skill.SPEECH, Math.min(120f, cost * 0.4f));
        Notifier.message(player, Component.translatable("society.skycraft.speech.bribe_success", cost));
        return true;
    }
}

