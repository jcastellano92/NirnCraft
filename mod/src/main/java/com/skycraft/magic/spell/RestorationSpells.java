package com.skycraft.magic.spell;

import com.skycraft.combat.CombatHandler;
import com.skycraft.core.Notifier;
import com.skycraft.magic.MagicDamage;
import com.skycraft.magic.MagicFx;
import com.skycraft.magic.MagicScheduler;
import com.skycraft.magic.Targeting;
import com.skycraft.vitals.Vitals;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Restoration spells against the undead (Skyrim and Dawnguard): Sun Fire, the Repel Undead line, Circle of
 * Protection, Bane of the Undead and Vampire's Bane. Single-target Turn Undead spells live in {@link Illusion}
 * (they share the fear AI). "Sun" damage hurts everything, and the undead twice as much.
 */
public final class RestorationSpells {
    /** Max health of undead each spell level can turn (Skyrim: level 6/12/21/30/45, here by health). */
    static final float[] UNDEAD_LIMIT = {24f, 40f, 60f, 100f, 180f};

    private RestorationSpells() {}

    static float undeadLimit(ServerPlayer caster, Spell spell, LivingEntity target) {
        float limit = UNDEAD_LIMIT[spell.tier.ordinal()] * SpellMath.undeadMult(caster, target);
        return spell.dual ? limit * 1.5f : limit;
    }

    /** Whether a turning spell of this level works on {@code e}. */
    static boolean turnable(ServerPlayer caster, Spell spell, LivingEntity e) {
        return e.getMobType() == MobType.UNDEAD && !e.getType().is(CombatHandler.BOSSES) && e.getMaxHealth() <= undeadLimit(caster, spell, e);
    }

    /** Sun damage: holy fire, doubled against the undead (and with Necromage, more). */
    static boolean sunDamage(ServerPlayer caster, LivingEntity target, float amount) {
        if (!Targeting.canHarm(caster, target)) return false;
        float dmg = amount;
        if (target.getMobType() == MobType.UNDEAD) dmg *= 2f * SpellMath.undeadMult(caster, target);
        boolean hurt = Targeting.hurtNoKnockback(target, MagicDamage.source(caster.level(), MagicDamage.SPELL, null, caster), dmg);
        if (hurt) {
            if (target.getMobType() == MobType.UNDEAD) target.setSecondsOnFire(3);
            Vitals.markInCombat(caster);
        }
        return hurt;
    }

    /** Sun Fire: a bolt of sunlight that burns the undead. */
    public static void sunFireHit(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        ServerLevel level = (ServerLevel) proj.level();
        MagicFx.burst(level, pos, Element.HOLY, 1f);
        SpellSounds.impact(level, pos, Element.HOLY, 1f);
        if (target != null && sunDamage(caster, target, spell.magnitude)) SpellEffects.xp(caster, spell);
    }

    /** Repel (Lesser) Undead: every undead up to the spell's level in the target area flees. */
    public static void repelUndeadHit(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        ServerLevel level = (ServerLevel) proj.level();
        MagicFx.burst(level, pos, Element.HOLY, spell.radius);
        MagicFx.sendNear(level, pos, 64, MagicFx.RING, Element.HOLY, pos, pos, -1, Math.round(spell.radius * 10));
        SpellSounds.impact(level, pos, Element.HOLY, 1.2f);
        int n = 0;
        boolean tooStrong = false;
        for (LivingEntity e : Targeting.around(level, pos, spell.radius, e -> e.getMobType() == MobType.UNDEAD && Targeting.canHarm(caster, e))) {
            if (!turnable(caster, spell, e)) {
                tooStrong = true;
                continue;
            }
            Illusion.applyFear(pos, e, spell.duration);
            e.setSecondsOnFire(2);
            MagicFx.send(e, MagicFx.HEAL, Element.HOLY, e.position(), e.position(), e.getId(), 1);
            n++;
        }
        if (n > 0) SpellEffects.xp(caster, spell);
        else if (tooStrong) Notifier.message(caster, Component.translatable("message.skycraft.undead_too_strong"));
    }

    /** Circle of Protection: a ring on the ground that undead up to the spell's level can't enter for 30 seconds. */
    public static Spell.Result circleOfProtection(ServerPlayer p, Spell spell, int tick) {
        Vec3 center = p.position();
        SpellSounds.play(p, SpellSounds.Slot.CAST_HOLY, 1f, 0.8f);
        MagicFx.sound(p, SoundEvents.BEACON_ACTIVATE, 1f, 1.4f);
        MagicScheduler.area(p, center, false, spell.radius, spell.duration, 10, "circle_of_protection", (level, owner, area) -> {
            MagicFx.sendNear(level, area.center, 64, MagicFx.RING, Element.HOLY, area.center.add(0, 0.1, 0), area.center, -1, (int) (area.radius * 10));
            if (owner == null) return;
            for (LivingEntity e : Targeting.around(level, area.center.add(0, 1, 0), area.radius + 1.5,
                    e -> e.getMobType() == MobType.UNDEAD && Targeting.canHarm(owner, e) && turnable(owner, spell, e))) {
                Illusion.applyFear(area.center, e, 80);
                Vec3 out = e.position().subtract(area.center).multiply(1, 0, 1);
                if (out.lengthSqr() > 1e-4) {
                    out = out.normalize().scale(0.6);
                    e.push(out.x, 0.15, out.z);
                    e.hurtMarked = true;
                }
            }
        });
        return SpellEffects.hostilesNear(p, 20) ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    /** Bane of the Undead: every undead around you is set on fire and flees for 30 seconds. */
    public static Spell.Result baneOfTheUndead(ServerPlayer p, Spell spell, int tick) {
        ServerLevel level = p.serverLevel();
        Vec3 c = p.position();
        MagicFx.sendNear(level, c, 96, MagicFx.RING, Element.HOLY, c.add(0, 0.2, 0), c, p.getId(), Math.round(spell.radius * 10));
        MagicFx.burst(level, c.add(0, 1, 0), Element.HOLY, 6f);
        MagicFx.sound(p, SoundEvents.BEACON_ACTIVATE, 2f, 0.7f);
        SpellSounds.world(level, c, SpellSounds.Slot.IMPACT_HOLY, 2f, 0.6f);
        int n = 0;
        for (LivingEntity e : Targeting.around(p, c, spell.radius, e -> e.getMobType() == MobType.UNDEAD && Targeting.canHarm(p, e))) {
            if (!turnable(p, spell, e)) continue;
            Illusion.applyFear(c, e, spell.duration);
            e.setSecondsOnFire(8);
            sunDamage(p, e, spell.magnitude);
            n++;
        }
        return n > 0 ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    /** Vampire's Bane: a burst of sunlight around you (40 points; the undead take double). */
    public static Spell.Result vampiresBane(ServerPlayer p, Spell spell, int tick) {
        ServerLevel level = p.serverLevel();
        Vec3 c = p.position().add(0, 1, 0);
        MagicFx.sendNear(level, c, 96, MagicFx.RING, Element.HOLY, p.position().add(0, 0.2, 0), c, p.getId(), Math.round(spell.radius * 10));
        MagicFx.burst(level, c, Element.HOLY, spell.radius * 0.7f);
        MagicFx.sound(p, SoundEvents.BEACON_ACTIVATE, 2f, 1.6f);
        SpellSounds.world(level, c, SpellSounds.Slot.IMPACT_HOLY, 2f, 0.8f);
        int n = 0;
        for (LivingEntity e : Targeting.around(p, c, spell.radius, e -> Targeting.canHarm(p, e))) {
            double d = e.position().distanceTo(p.position());
            float falloff = (float) (1.0 - 0.5 * Math.min(1.0, d / spell.radius));
            if (sunDamage(p, e, spell.magnitude * falloff)) n++;
        }
        return n > 0 ? Spell.Result.EFFECT : Spell.Result.CAST;
    }
}
