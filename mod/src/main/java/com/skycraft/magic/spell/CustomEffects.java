package com.skycraft.magic.spell;

import com.skycraft.combat.CombatHandler;
import com.skycraft.magic.MagicDamage;
import com.skycraft.magic.MagicFx;
import com.skycraft.magic.MagicRegistry;
import com.skycraft.magic.Targeting;
import com.skycraft.magic.spell.spellmaking.CustomSpells;
import com.skycraft.magic.spell.spellmaking.SpellEffectType;
import com.skycraft.magic.spell.spellmaking.SpellEffectType.Range;
import com.skycraft.registry.ModEffects;
import com.skycraft.vitals.Vitals;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * How the effects of made spells ({@link CustomSpells}) work: each effect reuses the matching tome spell's behaviour
 * (damage with perks, healing, armor, fear AI, soul trap, reflect...). Self and Touch effects land when cast; Target
 * effects ride one {@link SpellProjectile}. A made spell trains its school only when one of its effects did something.
 */
public final class CustomEffects {
    private CustomEffects() {}

    private static float mag(Spell spell, CustomSpells.Effect e) {
        return e.magnitude() * (spell.dual ? Spell.DUAL_MAGNITUDE : 1f);
    }

    public static Spell.Result cast(ServerPlayer p, Spell spell, int tick) {
        CustomSpells.Def def = CustomSpells.def(spell.id);
        if (def == null) return Spell.Result.FAILED;
        boolean trained = false, target = false, touch = false;
        for (CustomSpells.Effect e : def.effects()) {
            if (e.range() == Range.SELF) trained |= apply(p, spell, e, p);
            target |= e.range() == Range.TARGET;
            touch |= e.range() == Range.TOUCH;
        }
        if (touch) {
            Targeting.Ray ray = Targeting.ray(p, spell.radius, e -> !(e instanceof ArmorStand));
            for (CustomSpells.Effect e : def.effects()) {
                if (e.range() == Range.TOUCH) trained |= applyAt(p, spell, e, ray.entity(), ray.end());
            }
            Vec3 hand = Targeting.handPos(p);
            MagicFx.send(p, MagicFx.BEAM, spell.element, hand, ray.end(), p.getId(), ray.entity() != null ? 1 : 0);
        }
        if (target) {
            SpellEffects.launch(p, spell, tick);
        } else {
            SpellSounds.cast(p, spell);
            MagicFx.send(p, MagicFx.AURA, spell.element, p.position(), p.position(), p.getId(), 1);
        }
        return trained ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    public static void impact(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        CustomSpells.Def def = CustomSpells.def(spell.id);
        if (def == null) return;
        ServerLevel level = (ServerLevel) proj.level();
        int area = 0;
        boolean trained = false;
        for (CustomSpells.Effect e : def.effects()) {
            if (e.range() != Range.TARGET) continue;
            area = Math.max(area, e.area());
            trained |= applyAt(caster, spell, e, target, pos);
        }
        MagicFx.burst(level, pos, spell.element, Math.max(0.8f, area));
        SpellSounds.impact(level, pos, spell.element, 1f);
        if (trained) SpellEffects.xp(caster, spell);
    }

    /** Applies a Touch or Target effect where it landed: to the creature hit, or to everything in its area. */
    private static boolean applyAt(ServerPlayer caster, Spell spell, CustomSpells.Effect e, @Nullable LivingEntity hit, Vec3 at) {
        if (e.area() <= 0) return hit != null && apply(caster, spell, e, hit);
        boolean any = false;
        if (caster.level() instanceof ServerLevel level) {
            MagicFx.sendNear(level, at, 64, MagicFx.RING, e.type().element, at, at, -1, e.area() * 10);
        }
        for (LivingEntity t : Targeting.around(caster, at, e.area(), t -> !(t instanceof ArmorStand))) {
            if (t == caster && e.type().harmful()) continue;
            any |= apply(caster, spell, e, t);
        }
        return any;
    }

    /** One effect on one creature. Returns true if it did something (trains the skill). */
    private static boolean apply(ServerPlayer p, Spell spell, CustomSpells.Effect e, LivingEntity t) {
        float mag = mag(spell, e);
        int ticks = e.duration() * 20;
        boolean self = t == p;
        switch (e.type()) {
            case FIRE_DAMAGE, FROST_DAMAGE, SHOCK_DAMAGE -> {
                if (self || !Targeting.canHarm(p, t)) return false;
                Element el = e.type().element;
                float dmg = mag / CombatHandler.SKYRIM_SCALE * SpellMath.damageMult(p, el, t);
                t.invulnerableTime = 0;
                if (!t.hurt(SpellEffects.damage(p, el, null), dmg)) return false;
                SpellEffects.applyElement(p, el, t, dmg, false);
                Vitals.markInCombat(p);
                return true;
            }
            case SUN_DAMAGE -> {
                return !self && RestorationSpells.sunDamage(p, t, mag / CombatHandler.SKYRIM_SCALE);
            }
            case RESTORE_HEALTH -> {
                if (!self && (!Targeting.isHelpable(p, t) || t.getMobType() == MobType.UNDEAD || t instanceof AbstractGolem)) return false;
                return SpellEffects.heal(p, t, mag / CombatHandler.SKYRIM_SCALE * SpellMath.healMult(p)) > 0;
            }
            case TURN_UNDEAD -> {
                if (t.getMobType() != MobType.UNDEAD || t.getType().is(CombatHandler.BOSSES) || t.getMaxHealth() > mag * SpellMath.undeadMult(p, t)) {
                    return false;
                }
                Illusion.applyFear(p.position(), t, ticks);
                t.setSecondsOnFire(2);
                return true;
            }
            case SHIELD -> {
                if (!self) return false;
                MobEffect effect = mag <= 4 ? MagicRegistry.OAKFLESH.get() : mag <= 6 ? MagicRegistry.STONEFLESH.get()
                        : mag <= 8 ? MagicRegistry.IRONFLESH.get() : MagicRegistry.EBONYFLESH.get();
                boolean wasActive = p.hasEffect(effect);
                int rank = com.skycraft.perk.Perks.rank(p, "alteration.mage_armor");
                if (rank > 0 && SpellEffects.wearsNoArmor(p)) mag *= rank >= 3 ? 3f : rank == 2 ? 2.5f : 2f;
                p.addEffect(new MobEffectInstance(effect, ticks, Math.max(0, Math.round(mag) - 1), false, true, true));
                return SpellEffects.buffResult(p, wasActive) == Spell.Result.EFFECT;
            }
            case WATER_BREATHING -> {
                t.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, ticks, 0, false, true, true));
                return t.isInWater();
            }
            case PARALYZE -> {
                if (self || !Targeting.canHarm(p, t) || t.getType().is(CombatHandler.BOSSES)) return false;
                t.addEffect(new MobEffectInstance(ModEffects.PARALYSIS.get(), ticks, 0));
                if (t instanceof Mob mob) mob.setTarget(null);
                Vitals.markInCombat(p);
                return true;
            }
            case LIGHT -> {
                if (!self) return false;
                return Candlelight.light(p, ticks);
            }
            case DETECT_LIFE -> {
                int found = 0;
                com.skycraft.magic.ActiveMagic.note(p, "custom_detect", net.minecraft.network.chat.Component.translatable("spell.skycraft.detect_life"), ticks, 0xB888FF);
                for (LivingEntity o : Targeting.around(p, p.position(), mag, o -> o != p && !(o instanceof ArmorStand))) {
                    o.addEffect(new MobEffectInstance(MobEffects.GLOWING, ticks, 0, false, false));
                    found++;
                }
                return found > 0;
            }
            case SOUL_TRAP -> {
                if (self || !Targeting.canHarm(p, t)) return false;
                SpellEffects.soulTrap(p, t, ticks);
                return true;
            }
            case CALM, FEAR, FRENZY -> {
                if (self) return false;
                String kind = e.type() == SpellEffectType.CALM ? "calm" : e.type() == SpellEffectType.FEAR ? "fear" : "frenzy";
                int pvp = Illusion.onPlayer(p, t, kind);
                if (pvp >= 0) return pvp > 0;
                if (!(t instanceof Mob) || t.getType().is(CombatHandler.BOSSES) || t.getMaxHealth() > mag) return false;
                boolean resistant = t.getMobType() == MobType.UNDEAD || t instanceof AbstractGolem;
                if (resistant && !com.skycraft.perk.Perks.has(p, "illusion.master_of_the_mind")) return false;
                switch (kind) {
                    case "calm" -> Illusion.applyCalm(t, ticks);
                    case "fear" -> Illusion.applyFear(p.position(), t, ticks);
                    default -> Illusion.applyFrenzy(t, ticks);
                }
                MagicFx.send(t, MagicFx.AURA, Element.MIND, t.position(), t.position(), t.getId(), 0);
                return true;
            }
            case INVISIBILITY -> {
                boolean useful = !t.hasEffect(MobEffects.INVISIBILITY) && SpellEffects.hostilesNear(p, 24);
                t.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, ticks, 0, false, false, true));
                if (self) p.getPersistentData().putBoolean(Illusion.INVIS_FLAG, true);
                return useful;
            }
            case MUFFLE -> {
                boolean wasActive = t.hasEffect(MagicRegistry.MUFFLE.get());
                t.addEffect(new MobEffectInstance(MagicRegistry.MUFFLE.get(), ticks, 0, false, true, true));
                return SpellEffects.buffResult(p, wasActive) == Spell.Result.EFFECT;
            }
            case DISPEL -> {
                if (!self && !Targeting.isFriendly(p, t)) return false;
                return Mysticism.cleanse(t) > 0;
            }
            case REFLECT_SPELL, SPELL_ABSORPTION -> {
                MobEffect effect = e.type() == SpellEffectType.REFLECT_SPELL ? MagicRegistry.REFLECT_SPELL.get() : MagicRegistry.SPELL_ABSORPTION.get();
                boolean wasActive = t.hasEffect(effect);
                int chance = Math.max(5, Math.min(80, Math.round(mag)));
                t.addEffect(new MobEffectInstance(effect, ticks, Math.max(0, chance / 5 - 1), false, true, true));
                return SpellEffects.buffResult(p, wasActive) == Spell.Result.EFFECT;
            }
            case ABSORB_HEALTH -> {
                if (self || !Targeting.canHarm(p, t)) return false;
                float before = t.getHealth();
                if (!Targeting.hurtNoKnockback(t, MagicDamage.source(p.level(), MagicDamage.SPELL, null, p), mag / CombatHandler.SKYRIM_SCALE)) return false;
                p.heal(Math.max(0, before - Math.max(0, t.getHealth())));
                Vitals.markInCombat(p);
                return true;
            }
            case WATER_WALKING -> {
                boolean wasActive = t.hasEffect(MagicRegistry.WATER_WALKING.get());
                t.addEffect(new MobEffectInstance(MagicRegistry.WATER_WALKING.get(), ticks, 0, false, true, true));
                return !wasActive && (t.isInWater() || t.level().getFluidState(t.blockPosition().below()).isSource());
            }
            case SLOWFALL -> {
                boolean falling = !t.onGround() && t.fallDistance > 2;
                t.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, ticks, 0, false, true, true));
                t.fallDistance = 0;
                return falling;
            }
            case LEVITATE -> {
                if (!self) return false;
                return Mysticism.startLevitation(p, ticks);
            }
        }
        return false;
    }
}
