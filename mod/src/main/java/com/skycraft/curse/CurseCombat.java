package com.skycraft.curse;

import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.creatures.entity.DraugrDeathlordEntity;
import com.skycraft.creatures.entity.DraugrEntity;
import com.skycraft.creatures.entity.SkyHumanoid;
import com.skycraft.curse.entity.GargoyleEntity;
import com.skycraft.curse.entity.VampireLordEntity;
import com.skycraft.skills.Progression;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.vitals.ActionHandler;
import com.skycraft.vitals.Vitals;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Fighting as a beast: claw damage for Beast Form and the Vampire Lord (the combat module asks {@link #clawDamage}
 * instead of its weapon formula while a player is transformed), and what counts as a person to feed on.
 */
public final class CurseCombat {
    /** Claw damage in Minecraft health points (Skyrim's werewolf claws: 20 + 4 per level band, here flat). */
    public static final float BEAST_CLAWS = 7f;
    public static final float LORD_CLAWS = 5.5f;

    private CurseCombat() {}

    /** People: townsfolk, bandits and other humanoid fighters, villagers and illagers, players. */
    public static boolean isPerson(@Nullable Entity e) {
        if (e == null) return false;
        if (isUndead(e)) return false;
        return e instanceof Player || e instanceof NpcEntity || e instanceof SkyHumanoid || e instanceof AbstractVillager
                || e instanceof AbstractIllager || e instanceof Witch;
    }

    /** The walking dead and the cursed: nothing to feed on. */
    public static boolean isUndead(@Nullable Entity e) {
        if (e == null) return false;
        if (e instanceof DraugrEntity || e instanceof DraugrDeathlordEntity || e instanceof GargoyleEntity || e instanceof VampireLordEntity) return true;
        return e instanceof LivingEntity l && l.getMobType() == MobType.UNDEAD;
    }

    /**
     * A transformed player's melee hit (called by the combat module's hurt handler in place of the weapon formula).
     * Claws scale with Bestial Strength, power attacks (sprinting) hit harder, and the Vampire Lord's Poison Talons
     * add poison. Trains Unarmed.
     */
    public static float clawDamage(ServerPlayer player, LivingEntity target, float vanillaAmount) {
        Form form = Curses.form(player);
        PlayerData data = SkyData.get(player);
        float amount = form == Form.BEAST ? BEAST_CLAWS : LORD_CLAWS;
        amount += data.getLevel() * (form == Form.BEAST ? 0.12f : 0.1f);
        float mult = 1f;
        if (form == Form.BEAST) mult += 0.25f * CursePerks.rank(player, CursePerks.BESTIAL_STRENGTH);
        if (player.getAttackStrengthScale(0.5f) < 0.75f) mult *= 0.5f;
        boolean power = ActionHandler.consumePowerAttack(player) || player.isSprinting();
        if (power && Vitals.consumeStamina(player, 15f, true)) {
            mult *= 1.6f;
            com.skycraft.combat.CombatHandler.stagger(player, target, 25);
            player.level().playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1f, 0.6f);
        } else {
            Vitals.consumeStamina(player, 4f, true);
        }
        if (form == Form.LORD && CursePerks.has(player, CursePerks.POISON_TALONS)) {
            target.addEffect(new MobEffectInstance(MobEffects.POISON, 20 * 5, 1), player);
        }
        if (form == Form.BEAST && CursePerks.has(player, CursePerks.BLOOD_FRENZY)) {
            // the scent of blood: each hit on a wounded foe hits harder
            if (target.getHealth() < target.getMaxHealth() * 0.5f) mult *= 1.2f;
        }
        CursePackets.anim(player, CurseAnims.Anim.ATTACK);
        player.level().playSound(null, target.blockPosition(), form == Form.BEAST ? SoundEvents.WOLF_GROWL : SoundEvents.PHANTOM_BITE,
                SoundSource.PLAYERS, 0.7f, form == Form.BEAST ? 0.7f : 0.9f);
        float result = amount * mult;
        Progression.addSkillXp(player, Skill.UNARMED, Math.min(result, target.getHealth()) * 5f * 0.2f);
        return result;
    }

    /** Werewolves who kill with Blood Frenzy stay longer in Beast Form. */
    static void onKill(ServerPlayer player, LivingEntity victim) {
        if (Curses.form(player) == Form.BEAST && CursePerks.has(player, CursePerks.BLOOD_FRENZY) && isPerson(victim)) {
            net.minecraft.nbt.CompoundTag t = Curses.tag(player);
            t.putLong(Curses.FORM_UNTIL, t.getLong(Curses.FORM_UNTIL) + 20 * 10);
            Curses.dirty(player);
        }
    }

    /** Message shown when a bite isn't possible. */
    static void tooStrong(ServerPlayer player) {
        Notifier.message(player, Component.translatable("curse.skycraft.lord.too_strong"));
    }
}
