package com.nirncraft.society;

import com.nirncraft.core.Currency;
import com.nirncraft.core.Notifier;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.Race;
import com.nirncraft.core.Skill;
import com.nirncraft.core.SkyData;
import com.nirncraft.perk.Perks;
import com.nirncraft.skills.Progression;
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
            Notifier.message(player, Component.translatable("society.nirncraft.speech.persuade_success"));
            com.nirncraft.magic.MagicFx.soundToPlayer(player, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5f, 1.6f);
        } else {
            Notifier.message(player, Component.translatable("society.nirncraft.speech.persuade_fail"));
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
            Notifier.message(player, Component.translatable("society.nirncraft.speech.intimidate_success"));
            com.nirncraft.magic.MagicFx.soundToPlayer(player, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.4f, 1.4f);
        } else {
            Notifier.message(player, Component.translatable("society.nirncraft.speech.intimidate_fail"));
        }
        return success;
    }

    public static boolean checkBribe(ServerPlayer player, int cost) {
        if (!Currency.take(player, cost)) {
            Notifier.message(player, Component.translatable("message.nirncraft.not_enough_gold"));
            return false;
        }
        Progression.addSkillXp(player, Skill.SPEECH, Math.min(120f, cost * 0.4f));
        Notifier.message(player, Component.translatable("society.nirncraft.speech.bribe_success", cost));
        return true;
    }
}

