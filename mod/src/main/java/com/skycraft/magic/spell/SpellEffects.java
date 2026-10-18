package com.skycraft.magic.spell;

import com.skycraft.combat.CombatHandler;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.magic.MagicDamage;
import com.skycraft.magic.MagicFx;
import com.skycraft.magic.MagicRegistry;
import com.skycraft.magic.MagicScheduler;
import com.skycraft.magic.Targeting;
import com.skycraft.perk.Perks;
import com.skycraft.registry.ModEffects;
import com.skycraft.skills.Progression;
import com.skycraft.vitals.Vitals;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.AbstractGolem;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Implementations of Destruction, Restoration and Alteration spells (and shared damage/heal helpers). */
public final class SpellEffects {
    private SpellEffects() {}

    // ================================================================== shared helpers

    public static void xp(ServerPlayer player, Spell spell) {
        Progression.addSkillXp(player, spell.school.skill, spell.cost * (spell.dual ? Spell.DUAL_COST : 1f));
    }

    /** The cast sound of a spell (respects Quiet Casting). See {@link SpellSounds}. */
    public static void castSound(ServerPlayer p, Spell spell) {
        SpellSounds.cast(p, spell);
    }

    /**
     * Whether hostile creatures are around (within {@code radius}) or the player is in combat: self-buffs only train
     * their skill when they are useful, so casting armor or Muffle into an empty room grants nothing.
     */
    public static boolean hostilesNear(ServerPlayer p, double radius) {
        if (Vitals.inCombat(p)) return true;
        return !p.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(radius),
                m -> m.isAlive() && (m instanceof Enemy || m.getTarget() == p) && !Summons.isCompanion(m)).isEmpty();
    }

    /** Result of a self-buff: trains the skill only when not just refreshing an active buff and enemies are about. */
    public static Spell.Result buffResult(ServerPlayer p, boolean wasActive) {
        return !wasActive && hostilesNear(p, 16) ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    public static DamageSource damage(ServerPlayer caster, Element element, @Nullable Entity direct) {
        ResourceKey<DamageType> key = MagicDamage.forElement(element);
        return MagicDamage.source(caster.level(), key, direct, caster);
    }

    /**
     * Deals one hit of elemental spell damage with all perk effects (augmented, necromage, impact, intense flames,
     * deep freeze, disintegrate). Returns true if the target was hurt.
     */
    public static boolean hit(ServerPlayer caster, Spell spell, LivingEntity target, float baseDamage, @Nullable Entity direct,
                              boolean beam, boolean knockback) {
        if (!Targeting.canHarm(caster, target)) return false;
        float dmg = baseDamage * SpellMath.damageMult(caster, spell, target);
        DamageSource source = damage(caster, spell.element, direct);
        boolean hurt;
        if (knockback) {
            target.setInvulnerableTime(0);
            hurt = com.skycraft.platform.Effects.hurt(target, source, dmg);
        } else {
            hurt = Targeting.hurtNoKnockback(target, source, dmg);
        }
        if (!hurt) return false;
        Vitals.markInCombat(caster);
        applyElement(caster, spell.element, target, dmg, beam);
        // Impact: dual-cast destruction spells stagger.
        if (!beam && spell.dual && spell.school == School.DESTRUCTION && Perks.has(caster, "destruction.impact") && target.isAlive()) {
            CombatHandler.stagger(caster, target, 20);
        }
        lowHealthPerks(caster, spell.element, target, source);
        return true;
    }

    /** Fire burns, frost slows and drains stamina, shock drains magicka. */
    public static void applyElement(ServerPlayer caster, Element element, LivingEntity target, float dmg, boolean beam) {
        switch (element) {
            case FIRE -> target.igniteForSeconds(beam ? 3 : 5);
            case FROST -> {
                target.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.SLOWNESS), beam ? 30 : 80, beam ? 1 : 2));
                int frozen = Math.min(target.getTicksRequiredToFreeze() - 2, target.getTicksFrozen() + (beam ? 10 : 60));
                target.setTicksFrozen(Math.max(target.getTicksFrozen(), frozen));
                if (target instanceof ServerPlayer tp) {
                    PlayerData data = SkyData.get(tp);
                    data.setStamina(data.getStamina() - dmg * CombatHandler.SKYRIM_SCALE);
                }
            }
            case SHOCK -> {
                if (target instanceof ServerPlayer tp) {
                    PlayerData data = SkyData.get(tp);
                    data.setMagicka(data.getMagicka() - dmg * CombatHandler.SKYRIM_SCALE * 0.5f);
                }
            }
            default -> {
            }
        }
    }

    private static void lowHealthPerks(ServerPlayer caster, Element element, LivingEntity target, DamageSource source) {
        if (!target.isAlive() || target.getType().builtInRegistryHolder().is(CombatHandler.BOSSES)) return;
        float pct = target.getHealth() / target.getMaxHealth();
        switch (element) {
            case FIRE -> {
                if (pct < 0.2f && Perks.has(caster, "destruction.intense_flames") && !(target instanceof Player)) {
                    Illusion.applyFear(caster.position(), target, 100);
                }
            }
            case FROST -> {
                if (pct < 0.2f && Perks.has(caster, "destruction.deep_freeze")) {
                    target.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(ModEffects.PARALYSIS.get()), 60));
                }
            }
            case SHOCK -> {
                if (pct < 0.15f && Perks.has(caster, "destruction.disintegrate")) {
                    Vec3 at = target.position().add(0, target.getBbHeight() / 2, 0);
                    Targeting.hurtNoKnockback(target, source, target.getHealth() + 50f);
                    if (!target.isAlive()) {
                        MagicFx.burst((ServerLevel) target.level(), at, Element.NONE, 1.5f);
                        target.level().playSound(null, at.x, at.y, at.z, SoundEvents.SAND_BREAK, SoundSource.PLAYERS, 1.2f, 0.6f);
                        Notifier.message(caster, Component.translatable("message.skycraft.disintegrated"));
                    }
                }
            }
            default -> {
            }
        }
    }

    /** Damages everything hostile in a sphere with distance falloff. Returns the number of creatures hit. */
    public static int areaDamage(ServerPlayer caster, Spell spell, Vec3 center, double radius, float damage, @Nullable Entity direct, double push) {
        int hits = 0;
        for (LivingEntity e : Targeting.around(caster, center, radius, e -> Targeting.canHarm(caster, e))) {
            double d = e.position().add(0, e.getBbHeight() / 2, 0).distanceTo(center);
            float falloff = (float) (1.0 - 0.5 * Math.min(1.0, d / radius));
            if (hit(caster, spell, e, damage * falloff, direct, false, push > 0)) {
                hits++;
                if (push > 0 && e.isAlive()) {
                    Vec3 dir = e.position().subtract(center).multiply(1, 0, 1);
                    if (dir.lengthSqr() < 1e-4) dir = new Vec3(0, 0, 0);
                    else dir = dir.normalize();
                    e.push(dir.x * push * falloff, 0.25 * push, dir.z * push * falloff);
                    e.syncVelocity = true;
                }
            }
        }
        return hits;
    }

    // ================================================================== projectiles

    public static Spell.Result launch(ServerPlayer p, Spell spell, int tick) {
        SpellProjectile proj = new SpellProjectile(p.level(), p, spell);
        Vec3 hand = Targeting.handPos(p);
        proj.setPos(hand.x, hand.y - 0.2, hand.z);
        proj.shootFromRotation(p, p.getXRot(), p.getYRot(), 0f, spell.speed, 0.4f);
        p.level().addFreshEntity(proj);
        castSound(p, spell);
        if (spell.hostile) Vitals.markInCombat(p);
        return Spell.Result.CAST;
    }

    private static void impactSound(ServerLevel level, Vec3 pos, Element element, float volume) {
        SpellSounds.impact(level, pos, element, volume);
    }

    /** Firebolt, Ice Spike, Lightning Bolt, Incinerate, Icy Spear, Thunderbolt. */
    public static void elementalHit(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        ServerLevel level = (ServerLevel) proj.level();
        MagicFx.burst(level, pos, spell.element, spell.tier.ordinal() >= Tier.EXPERT.ordinal() ? 1.4f : 0.8f);
        impactSound(level, pos, spell.element, 1f);
        if (target != null && hit(caster, spell, target, spell.magnitude, proj, false, true)) xp(caster, spell);
    }

    /** Fireball: an explosion of fire that doesn't break blocks. */
    public static void explosion(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        ServerLevel level = (ServerLevel) proj.level();
        MagicFx.burst(level, pos, spell.element, spell.radius);
        MagicFx.worldSound(level, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.4f, 1.15f);
        MagicFx.worldSound(level, pos, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1f, 0.6f);
        if (areaDamage(caster, spell, pos, spell.radius, spell.magnitude, proj, 0.8) > 0) xp(caster, spell);
    }

    /** Ice Storm: a slow freezing whirlwind that hurts everything it passes through. */
    public static void stormFlight(SpellProjectile proj, ServerPlayer caster, Spell spell) {
        if (proj.tickCount % 10 != 0) return;
        ServerLevel level = (ServerLevel) proj.level();
        Vec3 c = proj.position().add(0, 0.5, 0);
        MagicFx.worldSound(level, c, SoundEvents.POWDER_SNOW_STEP, SoundSource.PLAYERS, 1.2f, 0.6f);
        int hits = 0;
        for (LivingEntity e : Targeting.around(proj, c, spell.radius + 0.5, e -> Targeting.canHarm(caster, e))) {
            if (hit(caster, spell, e, spell.magnitude / 2f, null, true, false)) hits++;
        }
        if (hits > 0 && !proj.getPersistentData().getBooleanOr("skycraft_xp", false)) {
            proj.getPersistentData().putBoolean("skycraft_xp", true);
            xp(caster, spell);
        }
    }

    public static void stormEnd(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        ServerLevel level = (ServerLevel) proj.level();
        MagicFx.burst(level, pos, Element.FROST, 2f);
        impactSound(level, pos, Element.FROST, 0.8f);
    }

    /** Chain Lightning: hits the target, then arcs to two more enemies nearby. */
    public static void chainLightning(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        ServerLevel level = (ServerLevel) proj.level();
        MagicFx.burst(level, pos, Element.SHOCK, 1.2f);
        impactSound(level, pos, Element.SHOCK, 1f);
        if (target == null || !hit(caster, spell, target, spell.magnitude, proj, false, true)) return;
        xp(caster, spell);
        List<LivingEntity> struck = new ArrayList<>();
        struck.add(target);
        LivingEntity current = target;
        float dmg = spell.magnitude;
        for (int arc = 0; arc < 2; arc++) {
            LivingEntity from = current;
            LivingEntity next = null;
            double best = Double.MAX_VALUE;
            for (LivingEntity e : Targeting.around(from, from.position(), spell.radius, e -> Targeting.canHarm(caster, e) && !struck.contains(e))) {
                double d = e.distanceToSqr(from);
                if (d < best && from.hasLineOfSight(e)) {
                    best = d;
                    next = e;
                }
            }
            if (next == null) break;
            dmg *= 0.75f;
            Vec3 a = from.position().add(0, from.getBbHeight() * 0.6, 0);
            Vec3 b = next.position().add(0, next.getBbHeight() * 0.6, 0);
            MagicFx.sendNear(level, a, 64, MagicFx.ARC, Element.SHOCK, a, b, -1, 0);
            hit(caster, spell, next, dmg, proj, false, true);
            struck.add(next);
            current = next;
        }
        if (struck.size() > 1) MagicFx.worldSound(level, pos, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 0.6f, 1.9f);
    }

    // ================================================================== concentration beams

    /** Flames, Frostbite, Sparks: a 12-block stream that hurts 4 times a second. */
    public static Spell.Result beam(ServerPlayer p, Spell spell, int tick) {
        Targeting.Ray ray = Targeting.ray(p, spell.radius, e -> Targeting.canHarm(p, e));
        Vec3 hand = Targeting.handPos(p);
        if (tick % 2 == 0) MagicFx.send(p, MagicFx.BEAM, spell.element, hand, ray.end(), p.getId(), ray.entity() != null || ray.hitBlock() ? 1 : 0);
        if (tick % 8 == 0) {
            switch (spell.element) {
                case FIRE -> MagicFx.sound(p, hand, tick == 0 ? com.skycraft.audio.AudioSounds.spell("fire") : com.skycraft.audio.AudioSounds.spell("fire_stream"), 0.9f, 0.95f + p.getRandom().nextFloat() * 0.1f);
                case FROST -> MagicFx.sound(p, hand, com.skycraft.audio.AudioSounds.spell("frost_stream"), 0.9f, 0.95f + p.getRandom().nextFloat() * 0.1f);
                case SHOCK -> MagicFx.sound(p, hand, com.skycraft.audio.AudioSounds.spell("shock_stream"), 0.8f, 0.95f + p.getRandom().nextFloat() * 0.1f);
                default -> {
                }
            }
        }
        if (ray.entity() != null && tick % 5 == 0) {
            if (hit(p, spell, ray.entity(), spell.magnitude / 4f, null, true, false)) {
                if (spell.dual && tick % 20 == 0 && Perks.has(p, "destruction.impact") && ray.entity().isAlive()) {
                    CombatHandler.stagger(p, ray.entity(), 15);
                }
                return Spell.Result.EFFECT;
            }
        }
        return Spell.Result.CAST;
    }

    // ================================================================== master destruction

    public static Spell.Result fireStorm(ServerPlayer p, Spell spell, int tick) {
        ServerLevel level = p.level();
        Vec3 c = p.position().add(0, 1, 0);
        MagicFx.sendNear(level, c, 96, MagicFx.RING, Element.FIRE, c, c, p.getId(), Math.round(spell.radius * 10));
        MagicFx.burst(level, c, Element.FIRE, spell.radius * 0.6f);
        MagicFx.worldSound(level, c, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 2f, 0.7f);
        MagicFx.worldSound(level, c, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 2f, 0.5f);
        Vitals.markInCombat(p);
        return areaDamage(p, spell, c, spell.radius, spell.magnitude, null, 1.2) > 0 ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    public static Spell.Result blizzard(ServerPlayer p, Spell spell, int tick) {
        MagicFx.worldSound(p.level(), p.position(), SoundEvents.POWDER_SNOW_BREAK, SoundSource.PLAYERS, 2f, 0.4f);
        MagicFx.worldSound(p.level(), p.position(), SoundEvents.ELYTRA_FLYING, SoundSource.PLAYERS, 0.6f, 0.5f);
        Vitals.markInCombat(p);
        MagicScheduler.area(p, p.position(), true, spell.radius, spell.duration, 5, "blizzard", (level, owner, area) -> {
            if (owner == null) return;
            if (area.age % 10 == 0) {
                MagicFx.sendNear(level, area.center, 96, MagicFx.RING, Element.FROST, area.center.add(0, 0.2, 0), area.center, owner.getId(), (int) (area.radius * 10));
            }
            if (area.age % 10 != 0) return;
            if (area.age % 40 == 0) MagicFx.worldSound(level, area.center, SoundEvents.POWDER_SNOW_STEP, SoundSource.PLAYERS, 1.5f, 0.4f);
            for (LivingEntity e : Targeting.around(owner, area.center.add(0, 1, 0), area.radius, e -> Targeting.canHarm(owner, e))) {
                hit(owner, spell, e, spell.magnitude / 2f, null, true, false);
            }
        });
        return Spell.Result.EFFECT;
    }

    // ================================================================== restoration

    /** Heals and, with Respite, restores stamina. Returns the amount healed. */
    public static float heal(ServerPlayer caster, LivingEntity target, float amount) {
        float before = target.getHealth();
        target.heal(amount);
        float healed = target.getHealth() - before;
        if (Perks.has(caster, "restoration.respite") && target instanceof Player tp) {
            PlayerData data = SkyData.get(tp);
            if (data.getStamina() < data.maxStamina()) {
                data.setStamina(data.getStamina() + amount * CombatHandler.SKYRIM_SCALE);
                healed = Math.max(healed, 0.01f);
            }
        }
        return healed;
    }

    public static Spell.Result healingConcentration(ServerPlayer p, Spell spell, int tick) {
        Targeting.Ray front = Targeting.companionRayInFront(p, 2.0);
        LivingEntity ally = front.entity();
        if (ally != null) {
            Vec3 hand = Targeting.handPos(p);
            if (tick % 6 == 0) p.swing(SpellCasting.castingHand(), net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
            if (tick % 2 == 0) MagicFx.send(p, MagicFx.BEAM, Element.HOLY, hand, front.end(), p.getId(), 1);
            if (tick % 6 == 0) MagicFx.send(ally, MagicFx.HEAL, Element.HOLY, ally.position(), ally.position(), ally.getId(), 0);
            if (tick % 20 == 0) MagicFx.sound(p, SoundEvents.AMETHYST_BLOCK_CHIME, 0.6f, 1.4f);
            float healed = heal(p, ally, spell.magnitude / 20f * SpellMath.healMult(p));
            return healed > 0 ? Spell.Result.EFFECT : Spell.Result.CAST;
        }
        if (tick % 4 == 0) MagicFx.send(p, MagicFx.HEAL, Element.HOLY, p.position(), p.position(), p.getId(), 0);
        if (tick % 20 == 0) MagicFx.sound(p, SoundEvents.AMETHYST_BLOCK_CHIME, 0.6f, 1.4f);
        float healed = heal(p, p, spell.magnitude / 20f * SpellMath.healMult(p));
        return healed > 0 ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    public static Spell.Result healSelf(ServerPlayer p, Spell spell, int tick) {
        Targeting.Ray front = Targeting.companionRayInFront(p, 2.0);
        LivingEntity ally = front.entity();
        if (ally != null) {
            Vec3 hand = Targeting.handPos(p);
            p.swing(SpellCasting.castingHand(), net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
            MagicFx.send(p, MagicFx.BEAM, Element.HOLY, hand, front.end(), p.getId(), 1);
            MagicFx.send(ally, MagicFx.HEAL, Element.HOLY, ally.position(), ally.position(), ally.getId(), 2);
            MagicFx.sound(p, SoundEvents.AMETHYST_BLOCK_CHIME, 1f, 1.1f);
            MagicFx.sound(p, SoundEvents.BEACON_POWER_SELECT, 0.4f, 1.8f);
            float healed = heal(p, ally, spell.magnitude * SpellMath.healMult(p));
            return healed > 0 ? Spell.Result.EFFECT : Spell.Result.CAST;
        }
        MagicFx.send(p, MagicFx.HEAL, Element.HOLY, p.position(), p.position(), p.getId(), 2);
        MagicFx.sound(p, SoundEvents.AMETHYST_BLOCK_CHIME, 1f, 1.1f);
        MagicFx.sound(p, SoundEvents.BEACON_POWER_SELECT, 0.4f, 1.8f);
        float healed = heal(p, p, spell.magnitude * SpellMath.healMult(p));
        return healed > 0 ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    public static Spell.Result healOther(ServerPlayer p, Spell spell, int tick) {
        Targeting.Ray ray = Targeting.ray(p, spell.radius, e -> Targeting.isHelpable(p, e));
        Vec3 hand = Targeting.handPos(p);
        if (tick % 6 == 0) p.swing(SpellCasting.castingHand(), net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
        if (tick % 2 == 0) MagicFx.send(p, MagicFx.BEAM, Element.HOLY, hand, ray.end(), p.getId(), ray.entity() != null ? 1 : 0);
        LivingEntity t = ray.entity();
        if (t == null) return Spell.Result.CAST;
        if (!Targeting.isCompanionOrParty(p, t) && (com.skycraft.platform.Effects.isUndead(t) || t instanceof AbstractGolem)) return Spell.Result.CAST;
        if (tick % 6 == 0) MagicFx.send(t, MagicFx.HEAL, Element.HOLY, t.position(), t.position(), t.getId(), 0);
        float healed = heal(p, t, spell.magnitude / 20f * SpellMath.healMult(p));
        return healed > 0 ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    public static Spell.Result grandHealing(ServerPlayer p, Spell spell, int tick) {
        ServerLevel level = p.level();
        float amount = spell.magnitude * SpellMath.healMult(p);
        float total = heal(p, p, amount);
        MagicFx.send(p, MagicFx.HEAL, Element.HOLY, p.position(), p.position(), p.getId(), 3);
        for (LivingEntity e : Targeting.around(p, p.position(), spell.radius, e -> e != p && !(e instanceof Enemy)
                && !com.skycraft.platform.Effects.isUndead(e) && !(e instanceof ArmorStand) && Targeting.isHelpable(p, e))) {
            total += heal(p, e, amount);
            MagicFx.send(e, MagicFx.HEAL, Element.HOLY, e.position(), e.position(), e.getId(), 2);
        }
        Vec3 c = p.position().add(0, 0.1, 0);
        MagicFx.sendNear(level, c, 64, MagicFx.RING, Element.HOLY, c, c, p.getId(), Math.round(spell.radius * 10));
        MagicFx.sound(p, SoundEvents.BEACON_ACTIVATE, 0.8f, 1.6f);
        MagicFx.sound(p, SoundEvents.AMETHYST_BLOCK_CHIME, 1f, 0.9f);
        return total > 0 ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    /** Guardian Circle: a ward on the ground that heals allies inside and burns and repels the undead. */
    public static Spell.Result guardianCircle(ServerPlayer p, Spell spell, int tick) {
        Vec3 center = p.position();
        MagicFx.sound(p, SoundEvents.BEACON_ACTIVATE, 1f, 1.2f);
        float heal = spell.magnitude * SpellMath.healMult(p);
        float threshold = Illusion.threshold(p, Tier.MASTER, null, "fear");
        MagicScheduler.area(p, center, false, spell.radius, spell.duration, 10, "guardian_circle", (level, owner, area) -> {
            MagicFx.sendNear(level, area.center, 64, MagicFx.RING, Element.HOLY, area.center.add(0, 0.1, 0), area.center, -1, (int) (area.radius * 10));
            if (area.age % 20 != 0) return;
            for (LivingEntity e : Targeting.around(level, area.center.add(0, 1, 0), area.radius, e -> !(e instanceof ArmorStand))) {
                if (com.skycraft.platform.Effects.isUndead(e)) {
                    if (owner != null && Targeting.canHarm(owner, e)) {
                        Targeting.hurtNoKnockback(e, MagicDamage.source(level, MagicDamage.SPELL, null, owner),
                                2f * SpellMath.undeadMult(owner, e));
                        if (e.getMaxHealth() <= threshold) Illusion.applyFear(area.center, e, 60);
                        e.igniteForSeconds(2);
                    }
                } else if (owner != null && Targeting.isHelpable(owner, e)) {
                    if (heal(owner, e, heal) > 0) MagicFx.send(e, MagicFx.HEAL, Element.HOLY, e.position(), e.position(), e.getId(), 0);
                }
            }
        });
        return Spell.Result.EFFECT;
    }

    /** Lesser, Steadfast and Greater Ward: holding one raises a barrier that absorbs spell damage from the front. */
    public static Spell.Result ward(ServerPlayer p, Spell spell, int tick) {
        if (tick == 0) {
            SpellCasting.raiseWard(p, spell.magnitude * (1 + 0.25f * Perks.rank(p, "restoration.ward_absorb")));
            MagicFx.sound(p, SoundEvents.BEACON_POWER_SELECT, 0.8f, 1.6f);
        }
        // Wards train Restoration by what they absorb (SpellCasting#absorbWithWard), never by just being held.
        if (tick % 3 == 0) MagicFx.send(p, MagicFx.WARD, Element.HOLY, p.getEyePosition(), p.getViewVector(1f), p.getId(), 0);
        return Spell.Result.CAST;
    }

    // ================================================================== alteration

    private static final Map<String, java.util.function.Supplier<MobEffect>> FLESH = new HashMap<>();

    static {
        FLESH.put("oakflesh", () -> MagicRegistry.OAKFLESH.get());
        FLESH.put("stoneflesh", () -> MagicRegistry.STONEFLESH.get());
        FLESH.put("ironflesh", () -> MagicRegistry.IRONFLESH.get());
        FLESH.put("ebonyflesh", () -> MagicRegistry.EBONYFLESH.get());
    }

    public static boolean wearsNoArmor(Player p) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (!p.getItemBySlot(slot).isEmpty()) return false;
        }
        return true;
    }

    /** Oakflesh..Ebonyflesh: armor bonus; Mage Armor multiplies it when no armor is worn. */
    public static Spell.Result flesh(ServerPlayer p, Spell spell, int tick) {
        MobEffectInstance current = p.getEffect(com.skycraft.platform.Effects.holder(FLESH.get(spell.id).get()));
        boolean wasActive = current != null && current.getDuration() > spell.duration / 2;
        for (java.util.function.Supplier<MobEffect> e : FLESH.values()) p.removeEffect(com.skycraft.platform.Effects.holder(e.get()));
        float points = spell.magnitude;
        int rank = Perks.rank(p, "alteration.mage_armor");
        if (rank > 0 && wearsNoArmor(p)) points *= rank >= 3 ? 3f : rank == 2 ? 2.5f : 2f;
        int amp = Math.max(0, Math.round(points) - 1);
        p.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(FLESH.get(spell.id).get()), SpellMath.alterationDuration(p, spell), amp, false, true, true));
        MagicFx.send(p, MagicFx.AURA, Element.NATURE, p.position(), p.position(), p.getId(), spell.tier.ordinal());
        SoundEvent sound = switch (spell.id) {
            case "oakflesh" -> SoundEvents.WOOD_PLACE;
            case "stoneflesh" -> SoundEvents.STONE_PLACE;
            case "ironflesh" -> SoundEvents.ANVIL_LAND;
            default -> SoundEvents.AMETHYST_BLOCK_PLACE;
        };
        MagicFx.sound(p, sound, spell.id.equals("ironflesh") ? 0.3f : 1f, 0.8f);
        MagicFx.sound(p, SoundEvents.EVOKER_CAST_SPELL, 0.6f, 1.2f);
        return buffResult(p, wasActive);
    }

    public static Spell.Result detectLife(ServerPlayer p, Spell spell, int tick) {
        int duration = SpellMath.alterationDuration(p, spell);
        com.skycraft.magic.ActiveMagic.note(p, spell, duration);
        int found = 0;
        for (LivingEntity e : Targeting.around(p, p.position(), spell.radius, e -> e != p && !(e instanceof ArmorStand)
                && !com.skycraft.platform.Effects.isUndead(e) && !(e instanceof AbstractGolem))) {
            e.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.GLOWING), duration, 0, false, false));
            found++;
        }
        MagicFx.send(p, MagicFx.AURA, Element.ARCANE, p.position(), p.position(), p.getId(), 4);
        MagicFx.sound(p, SoundEvents.AMETHYST_BLOCK_CHIME, 1f, 0.7f);
        MagicFx.sound(p, SoundEvents.BEACON_AMBIENT, 1f, 1.5f);
        return found > 0 ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    public static Spell.Result waterbreathing(ServerPlayer p, Spell spell, int tick) {
        // trains the skill only when you actually need the air
        boolean useful = p.isUnderWater() || p.isInWater();
        p.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.WATER_BREATHING), SpellMath.alterationDuration(p, spell), 0, false, true, true));
        MagicFx.send(p, MagicFx.AURA, Element.FROST, p.position(), p.position(), p.getId(), 1);
        MagicFx.sound(p, SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE, 1f, 1.2f);
        return useful ? Spell.Result.EFFECT : Spell.Result.CAST;
    }

    /** Transmute: one piece of iron (ingot, raw iron or ore) becomes gold. */
    public static Spell.Result transmute(ServerPlayer p, Spell spell, int tick) {
        if (transmuteOne(p)) {
            MagicFx.send(p, MagicFx.AURA, Element.ARCANE, p.position(), p.position(), p.getId(), 2);
            MagicFx.sound(p, SoundEvents.AMETHYST_BLOCK_CHIME, 1f, 1.5f);
            MagicFx.sound(p, SoundEvents.ANVIL_USE, 0.3f, 1.8f);
            return Spell.Result.EFFECT;
        }
        Notifier.message(p, Component.translatable("message.skycraft.nothing_to_transmute"));
        return Spell.Result.FAILED;
    }

    /**
     * The inventory half of {@link #transmute}: turns one piece of iron the player carries (slots, offhand or the
     * overflow pack, through {@code inventory.Inv}) into its gold, ingots first. False when there is none.
     */
    public static boolean transmuteOne(ServerPlayer p) {
        Item[][] pairs = {
                {Items.IRON_INGOT, Items.GOLD_INGOT},
                {Items.RAW_IRON, Items.RAW_GOLD},
                {Items.IRON_ORE, Items.GOLD_ORE},
                {Items.DEEPSLATE_IRON_ORE, Items.DEEPSLATE_GOLD_ORE},
        };
        for (Item[] pair : pairs) {
            if (com.skycraft.inventory.Inv.take(p, pair[0], 1) < 1) continue;
            com.skycraft.inventory.Inv.giveOrDrop(p, new ItemStack(pair[1]));
            return true;
        }
        return false;
    }

    public static void paralyzeHit(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        ServerLevel level = (ServerLevel) proj.level();
        MagicFx.burst(level, pos, Element.ARCANE, 0.8f);
        impactSound(level, pos, Element.ARCANE, 1f);
        if (target != null && paralyze(caster, spell, target)) xp(caster, spell);
    }

    public static void massParalysisHit(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        ServerLevel level = (ServerLevel) proj.level();
        MagicFx.burst(level, pos, Element.ARCANE, spell.radius);
        MagicFx.sendNear(level, pos, 64, MagicFx.RING, Element.ARCANE, pos, pos, -1, Math.round(spell.radius * 10));
        MagicFx.worldSound(level, pos, SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 1.5f, 0.6f);
        int n = 0;
        for (LivingEntity e : Targeting.around(caster, pos, spell.radius, e -> Targeting.canHarm(caster, e))) {
            if (paralyze(caster, spell, e)) n++;
        }
        if (n > 0) xp(caster, spell);
    }

    private static boolean paralyze(ServerPlayer caster, Spell spell, LivingEntity target) {
        if (!Targeting.canHarm(caster, target) || target.getType().builtInRegistryHolder().is(CombatHandler.BOSSES)) return false;
        target.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(ModEffects.PARALYSIS.get()), SpellMath.alterationDuration(caster, spell) * (spell.dual ? 3 : 2) / 2, 0));
        if (target instanceof net.minecraft.world.entity.Mob mob) mob.setTarget(null);
        target.setDeltaMovement(0, Math.min(0, target.getDeltaMovement().y), 0);
        MagicFx.send(target, MagicFx.AURA, Element.ARCANE, target.position(), target.position(), target.getId(), 0);
        Vitals.markInCombat(caster);
        return true;
    }

    // ================================================================== conjuration (projectiles)

    public static void soulTrapHit(SpellProjectile proj, ServerPlayer caster, Spell spell, @Nullable LivingEntity target, Vec3 pos) {
        ServerLevel level = (ServerLevel) proj.level();
        MagicFx.burst(level, pos, Element.SOUL, 0.8f);
        impactSound(level, pos, Element.SOUL, 1f);
        if (target == null || !Targeting.canHarm(caster, target)) return;
        soulTrap(caster, target, spell.duration);
        xp(caster, spell);
    }

    public static void soulTrap(ServerPlayer caster, LivingEntity target, int ticks) {
        target.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MagicRegistry.SOUL_TRAP.get()), ticks, 0));
        target.getPersistentData().store(SOUL_TRAPPER, net.minecraft.core.UUIDUtil.CODEC, caster.getUUID());
    }

    /** Persistent-data key on a soul-trapped creature: the UUID of the player whose gems get its soul. */
    public static final String SOUL_TRAPPER = "skycraft_soul_trapper";
}
