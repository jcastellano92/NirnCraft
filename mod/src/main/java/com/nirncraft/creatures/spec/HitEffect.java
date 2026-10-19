package com.nirncraft.creatures.spec;

import com.nirncraft.combat.CombatHandler;
import com.nirncraft.magic.MagicDamage;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** Something extra a creature's hit does to its victim (poison, frostbite, burning, shock, knockback...). */
@FunctionalInterface
public interface HitEffect {
    void apply(Mob attacker, LivingEntity victim);

    static HitEffect slow(int ticks, int amp) {
        return (a, v) -> v.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.SLOWNESS), ticks, amp), a);
    }

    static HitEffect poison(int ticks, int amp) {
        return (a, v) -> v.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.POISON), ticks, amp), a);
    }

    static HitEffect weakness(int ticks) {
        return (a, v) -> v.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.WEAKNESS), ticks, 0), a);
    }

    static HitEffect blind(int ticks) {
        return (a, v) -> v.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.BLINDNESS), ticks, 0), a);
    }

    /** Frostbite: slows and chills (powder-snow freezing). */
    static HitEffect frost(int ticks) {
        return (a, v) -> {
            v.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.SLOWNESS), ticks, 1), a);
            if (v.canFreeze()) v.setTicksFrozen(Math.min(v.getTicksRequiredToFreeze() + 80, v.getTicksFrozen() + ticks / 2));
        };
    }

    static HitEffect burn(int seconds) {
        return (a, v) -> v.igniteForSeconds(seconds);
    }

    /** Extra shock damage (a spell damage type: wards and magic resistance apply). */
    static HitEffect shock(float damage) {
        return (a, v) -> CombatHandler.secondaryHurt(v, MagicDamage.source(a.level(), MagicDamage.SHOCK, null, a), damage);
    }

    /** Extra fire damage. */
    static HitEffect fire(float damage) {
        return (a, v) -> {
            if (CombatHandler.secondaryHurt(v, MagicDamage.source(a.level(), MagicDamage.FIRE, null, a), damage)) v.igniteForSeconds(3);
        };
    }

    /** Extra frost damage. */
    static HitEffect chill(float damage) {
        return (a, v) -> {
            CombatHandler.secondaryHurt(v, MagicDamage.source(a.level(), MagicDamage.FROST, null, a), damage);
            v.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.SLOWNESS), 60, 0), a);
        };
    }

    /** Pushes the victim away (and up). */
    static HitEffect knock(double strength, double up) {
        return (a, v) -> {
            Vec3 d = new Vec3(v.getX() - a.getX(), 0, v.getZ() - a.getZ());
            if (d.lengthSqr() < 1.0E-4) d = Vec3.directionFromRotation(0, a.getYRot());
            d = d.normalize().scale(strength * (1.0 - v.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE)));
            v.setDeltaMovement(v.getDeltaMovement().add(d.x, up, d.z));
            v.syncVelocity = true;
        };
    }

    static HitEffect stagger(int ticks) {
        return (a, v) -> CombatHandler.stagger(a, v, ticks);
    }

    /** Heals the attacker by a share of its attack damage (vampiric creatures). */
    static HitEffect drain(float share) {
        return (a, v) -> a.heal((float) a.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) * share);
    }

    /** Applies with a chance. */
    static HitEffect chance(float chance, HitEffect effect) {
        return (a, v) -> {
            if (a.getRandom().nextFloat() < chance) effect.apply(a, v);
        };
    }
}
