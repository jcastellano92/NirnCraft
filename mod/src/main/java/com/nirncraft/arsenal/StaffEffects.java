package com.nirncraft.arsenal;

import com.nirncraft.NirnCraft;
import com.nirncraft.arsenal.entity.StaffBolt;
import com.nirncraft.arsenal.item.StaffKind;
import com.nirncraft.combat.CombatHandler;
import com.nirncraft.core.Currency;
import com.nirncraft.core.Notifier;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.core.Skill;
import com.nirncraft.magic.Targeting;
import com.nirncraft.magic.spell.Illusion;
import com.nirncraft.magic.spell.SpellEffects;
import com.nirncraft.magic.spell.Summons;
import com.nirncraft.registry.ModEffects;
import com.nirncraft.skills.Progression;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Everything staves do: streams are instantaneous ray hits every half second, aimed staves fire {@link StaffBolt}s,
 * and the visuals go to nearby clients as {@link ArsenalPackets.Fx}. Damage scales a little with the staff's magic
 * skill, and every use trains it. Aiming, friend-or-foe, summons and calm/fury states are the magic module's
 * ({@link Targeting}, {@link Summons}, {@link Illusion}), so staves and spells behave the same.
 */
public final class StaffEffects {
    public static final String WABBAJACK_ORIGINAL = "nirncraft_wabbajack_original";
    public static final String WABBAJACK_UNTIL = "nirncraft_wabbajack_until";

    private static final ResourceKey<DamageType> FIRE_SPELL = key("fire_spell");
    private static final ResourceKey<DamageType> FROST_SPELL = key("frost_spell");
    private static final ResourceKey<DamageType> SHOCK_SPELL = key("shock_spell");

    private StaffEffects() {}

    private static ResourceKey<DamageType> key(String path) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(NirnCraft.MODID, path));
    }

    // ------------------------------------------------------------------ helpers

    /** Skill scaling: +0.4% per skill level (x1.06 at 15, x1.4 at 100). */
    public static float power(ServerPlayer player, Skill skill) {
        return 1f + SkyData.get(player).getSkill(skill) * 0.004f;
    }

    /**
     * A damage source of the given NirnCraft spell type (falls back to indirect magic if the type is missing) with no
     * direct entity, so melee/archery rules don't treat it as a weapon hit, credited to {@code cause}.
     */
    public static DamageSource source(Level level, @Nullable ResourceKey<DamageType> type, @Nullable Entity direct, @Nullable Entity cause) {
        var reg = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE);
        Holder<DamageType> holder = null;
        if (type != null) holder = reg.get(type).orElse(null);
        if (holder == null) holder = reg.getOrThrow(DamageTypes.INDIRECT_MAGIC);
        return new DamageSource(holder, direct, cause);
    }

    public static DamageSource fire(Level level, @Nullable Entity direct, Entity cause) {
        return source(level, FIRE_SPELL, direct, cause);
    }

    public static DamageSource frost(Level level, @Nullable Entity direct, Entity cause) {
        return source(level, FROST_SPELL, direct, cause);
    }

    public static DamageSource shock(Level level, @Nullable Entity direct, Entity cause) {
        return source(level, SHOCK_SPELL, direct, cause);
    }

    /** Creatures a staff should not hurt: the caster, its pets, summons, team and party (and their summons). */
    private static boolean friendly(ServerPlayer caster, Entity e) {
        return Targeting.isFriendly(caster, e);
    }

    private static Vec3 handPos(ServerPlayer player) {
        Vec3 look = player.getViewVector(1f);
        Vec3 right = new Vec3(-look.z, 0, look.x).normalize().scale(0.3);
        return player.getEyePosition().add(look.scale(0.6)).add(right).add(0, -0.25, 0);
    }

    private static void sound(ServerPlayer player, Vec3 pos, SoundEvent sound, float volume, float pitch) {
        player.level().playSound(null, pos.x, pos.y, pos.z, sound, SoundSource.PLAYERS, volume, pitch);
    }

    private static void xp(ServerPlayer player, Skill skill, float use) {
        if (use > 0) Progression.addSkillXp(player, skill, use);
    }

    private static boolean resists(ServerPlayer caster, LivingEntity target, Skill skill) {
        if (target.getType().builtInRegistryHolder().is(CombatHandler.BOSSES) || target instanceof Player) return true;
        return target.getMaxHealth() > 30 + SkyData.get(caster).getSkill(skill) * 0.8f;
    }

    // ------------------------------------------------------------------ single casts

    /** Casts a non-streaming staff. Returns true if a charge should be spent. */
    public static boolean cast(ServerPlayer player, ItemStack stack, StaffKind kind) {
        float power = power(player, kind.skill);
        switch (kind) {
            case FIREBALLS -> shoot(player, StaffBolt.Kind.FIREBALL, power, 1.5f, SoundEvents.BLAZE_SHOOT);
            case ICE_STORMS -> shoot(player, StaffBolt.Kind.ICE_STORM, power, 0.55f, SoundEvents.POWDER_SNOW_BREAK);
            case CALM -> shoot(player, StaffBolt.Kind.CALM, power, 1.6f, SoundEvents.AMETHYST_BLOCK_RESONATE);
            case FURY -> shoot(player, StaffBolt.Kind.FURY, power, 1.6f, SoundEvents.EVOKER_PREPARE_ATTACK);
            case PARALYSIS -> shoot(player, StaffBolt.Kind.PARALYSIS, power, 1.8f, SoundEvents.ILLUSIONER_CAST_SPELL);
            case WABBAJACK -> shoot(player, StaffBolt.Kind.WABBAJACK, power, 1.4f, SoundEvents.ILLUSIONER_PREPARE_MIRROR);
            case CHAIN_LIGHTNING -> chainLightning(player, power);
            case CONJURE_FAMILIAR -> conjureFamiliar(player);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static void shoot(ServerPlayer player, StaffBolt.Kind kind, float power, float speed, SoundEvent sound) {
        StaffBolt bolt = new StaffBolt(player.level(), player, kind, power);
        Vec3 hand = handPos(player);
        bolt.setPos(hand.x, hand.y, hand.z);
        bolt.shootFromRotation(player, player.getXRot(), player.getYRot(), 0f, speed, 0.5f);
        player.level().addFreshEntity(bolt);
        sound(player, player.position(), sound, 1f, 0.9f + player.getRandom().nextFloat() * 0.2f);
    }

    /** Bloodskal's energy blade: a crimson crescent that cuts through everything in its path. */
    public static void energyBlade(ServerPlayer player, float power) {
        StaffBolt bolt = new StaffBolt(player.level(), player, StaffBolt.Kind.BLADE, power);
        Vec3 eye = player.getEyePosition().add(player.getViewVector(1f).scale(0.5)).add(0, -0.4, 0);
        bolt.setPos(eye.x, eye.y, eye.z);
        bolt.shootFromRotation(player, player.getXRot(), player.getYRot(), 0f, 1.6f, 0f);
        player.level().addFreshEntity(bolt);
        sound(player, player.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 1f, 0.6f);
        sound(player, player.position(), SoundEvents.ENDER_DRAGON_FLAP, 0.5f, 1.6f);
    }

    private static void chainLightning(ServerPlayer player, float power) {
        ServerLevel level = player.level();
        Targeting.Ray ray = Targeting.ray(player, 32, e -> true);
        LivingEntity first = ray.entity();
        Vec3 from = handPos(player);
        ArsenalPackets.Fx.send(level, ArsenalPackets.Fx.LIGHTNING_ARC, from, ray.end());
        sound(player, ray.end(), SoundEvents.LIGHTNING_BOLT_THUNDER, 0.5f, 1.8f);
        if (first == null || friendly(player, first)) return;
        float damage = 8f * power;
        List<LivingEntity> hit = new ArrayList<>();
        LivingEntity current = first;
        float dealt = 0;
        for (int jump = 0; jump < 3 && current != null; jump++) {
            hit.add(current);
            com.nirncraft.platform.Effects.hurt(current, shock(level, null, player), damage);
            drainMagicka(current, damage * 2.5f);
            dealt += damage;
            damage *= 0.75f;
            LivingEntity from2 = current;
            LivingEntity next = null;
            double best = 36;
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, from2.getBoundingBox().inflate(6),
                    e -> e.isAlive() && !hit.contains(e) && !friendly(player, e) && !(e instanceof Player))) {
                double d = e.distanceToSqr(from2);
                if (d < best) {
                    best = d;
                    next = e;
                }
            }
            if (next != null) {
                ArsenalPackets.Fx.send(level, ArsenalPackets.Fx.LIGHTNING_ARC, from2.position().add(0, from2.getBbHeight() * 0.6, 0),
                        next.position().add(0, next.getBbHeight() * 0.6, 0));
            }
            current = next;
        }
        xp(player, Skill.DESTRUCTION, dealt * CombatHandler.SKYRIM_SCALE * 0.25f);
    }

    /** A familiar for a minute, through the magic module's summon system (same limits and rules as the spell). */
    private static void conjureFamiliar(ServerPlayer player) {
        ServerLevel level = player.level();
        Mob wolf = Summons.conjure(player, net.minecraft.world.entity.EntityTypes.WOLF, "familiar", 6, 20 * 60,
                mob -> mob.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.SPEED), 20 * 60, 0, false, false)));
        if (wolf == null) return;
        Vec3 pos = wolf.position();
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, pos.x, pos.y + 0.5, pos.z, 30, 0.4, 0.5, 0.4, 0.02);
        sound(player, pos, SoundEvents.EVOKER_CAST_SPELL, 1f, 0.8f);
        xp(player, Skill.CONJURATION, 6f);
    }

    // ------------------------------------------------------------------ streams

    /** One tick of a channelled staff ({@code used} = ticks since the player started channelling). */
    public static void stream(ServerPlayer player, ItemStack stack, StaffKind kind, int used) {
        ServerLevel level = player.level();
        boolean beam = kind == StaffKind.HEALING || kind == StaffKind.MAGNUS;
        double range = kind == StaffKind.HEALING ? 2.0 : (beam ? 14 : 8);
        Targeting.Ray ray = kind == StaffKind.HEALING
                ? Targeting.companionRayInFront(player, range)
                : Targeting.ray(player, range, e -> true);
        LivingEntity target = ray.entity();
        if (kind == StaffKind.HEALING && target != null && used % 6 == 0) {
            player.swing(player.getUsedItemHand(), net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
        }
        if (used % 3 == 0) {
            byte fx = switch (kind) {
                case FLAMES -> ArsenalPackets.Fx.FIRE_STREAM;
                case FROSTBITE -> ArsenalPackets.Fx.FROST_STREAM;
                case SPARKS -> ArsenalPackets.Fx.SHOCK_STREAM;
                case HEALING -> ArsenalPackets.Fx.HEAL_BEAM;
                default -> ArsenalPackets.Fx.MAGNUS_BEAM;
            };
            Vec3 endPos = (kind == StaffKind.HEALING && target == null)
                    ? player.position().add(0, player.getBbHeight() * 0.6, 0)
                    : ray.end();
            ArsenalPackets.Fx.send(level, fx, handPos(player), endPos);
        }
        if (used % 20 == 0) {
            SoundEvent s = switch (kind) {
                case FLAMES -> SoundEvents.FIRECHARGE_USE;
                case FROSTBITE -> SoundEvents.POWDER_SNOW_STEP;
                case SPARKS -> SoundEvents.BEEHIVE_WORK;
                case HEALING -> SoundEvents.BEACON_AMBIENT;
                default -> SoundEvents.BEACON_POWER_SELECT;
            };
            sound(player, player.position(), s, 0.7f, kind == StaffKind.MAGNUS ? 0.6f : 1.2f);
        }
        if (used % 10 != 0) return;
        float power = power(player, kind.skill);
        if (kind == StaffKind.HEALING) {
            LivingEntity healTarget = target != null ? target : player;
            if (healTarget.getHealth() < healTarget.getMaxHealth()) {
                float heal = 1.5f * power;
                SpellEffects.heal(player, healTarget, heal);
                level.sendParticles(ParticleTypes.HEART, healTarget.getX(), healTarget.getY() + healTarget.getBbHeight() + 0.2, healTarget.getZ(), 1, 0.2, 0.1, 0.2, 0);
                xp(player, Skill.RESTORATION, heal * CombatHandler.SKYRIM_SCALE * 0.3f);
            }
            return;
        }
        if (target == null) return;
        switch (kind) {
            case FLAMES -> {
                if (friendly(player, target)) return;
                float dmg = 2.0f * power;
                if (com.nirncraft.platform.Effects.hurt(target, fire(level, null, player), dmg)) {
                    target.igniteForSeconds(2);
                    xp(player, Skill.DESTRUCTION, Math.min(dmg, target.getHealth() + dmg) * CombatHandler.SKYRIM_SCALE * 0.25f);
                }
            }
            case FROSTBITE -> {
                if (friendly(player, target)) return;
                float dmg = 1.6f * power;
                if (com.nirncraft.platform.Effects.hurt(target, frost(level, null, player), dmg)) {
                    target.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.SLOWNESS), 40, 1));
                    target.setTicksFrozen(Math.min(target.getTicksRequiredToFreeze() + 40, target.getTicksFrozen() + 20));
                    drainStamina(target, dmg * 3f);
                    xp(player, Skill.DESTRUCTION, dmg * CombatHandler.SKYRIM_SCALE * 0.25f);
                }
            }
            case SPARKS -> {
                if (friendly(player, target)) return;
                float dmg = 1.8f * power;
                if (com.nirncraft.platform.Effects.hurt(target, shock(level, null, player), dmg)) {
                    drainMagicka(target, dmg * 3f);
                    xp(player, Skill.DESTRUCTION, dmg * CombatHandler.SKYRIM_SCALE * 0.25f);
                }
            }
            case MAGNUS -> {
                if (friendly(player, target)) return;
                PlayerData casterData = SkyData.get(player);
                float drained = 0;
                if (target instanceof Player victim) {
                    PlayerData vd = SkyData.get(victim);
                    drained = Math.min(vd.getMagicka(), 10f * power);
                    vd.setMagicka(vd.getMagicka() - drained);
                }
                if (drained < 1f) {
                    float dmg = 1.5f * power;
                    com.nirncraft.platform.Effects.hurt(target, source(level, null, null, player), dmg);
                    drained = 5f;
                }
                casterData.setMagicka(casterData.getMagicka() + drained);
                xp(player, Skill.DESTRUCTION, drained * 0.5f);
            }
            default -> {
            }
        }
    }

    private static void drainMagicka(LivingEntity target, float amount) {
        if (target instanceof Player p) {
            PlayerData d = SkyData.get(p);
            d.setMagicka(d.getMagicka() - amount);
        }
    }

    private static void drainStamina(LivingEntity target, float amount) {
        if (target instanceof Player p) {
            PlayerData d = SkyData.get(p);
            d.setStamina(d.getStamina() - amount);
        }
    }

    // ------------------------------------------------------------------ projectile impacts

    /** A staff projectile hit {@code target} (or the ground at {@code pos} when target is null). */
    public static void impact(StaffBolt bolt, ServerPlayer caster, StaffBolt.Kind kind, @Nullable LivingEntity target, Vec3 pos) {
        ServerLevel level = caster.level();
        if (bolt.level() != level) return;
        float power = bolt.power();
        switch (kind) {
            case FIREBALL -> {
                ArsenalPackets.Fx.send(level, ArsenalPackets.Fx.FIRE_BURST, pos, pos);
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.9f, 1.3f);
                float dealt = 0;
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos, pos).inflate(3.5),
                        e -> e.isAlive() && !friendly(caster, e))) {
                    double d = Math.sqrt(e.distanceToSqr(pos));
                    if (d > 3.5) continue;
                    float dmg = 7f * power * (float) (1.0 - 0.5 * d / 3.5);
                    if (com.nirncraft.platform.Effects.hurt(e, fire(level, bolt, caster), dmg)) {
                        e.igniteForSeconds(4);
                        dealt += dmg;
                    }
                }
                xp(caster, Skill.DESTRUCTION, dealt * CombatHandler.SKYRIM_SCALE * 0.2f);
            }
            case ICE_STORM -> {
                if (target == null || friendly(caster, target)) return;
                float dmg = 4f * power;
                if (com.nirncraft.platform.Effects.hurt(target, frost(level, bolt, caster), dmg)) {
                    target.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.SLOWNESS), 80, 2));
                    target.setTicksFrozen(target.getTicksRequiredToFreeze() + 80);
                    drainStamina(target, dmg * 3f);
                    ArsenalPackets.Fx.send(level, ArsenalPackets.Fx.FROST_BURST, target.position().add(0, 1, 0), target.position());
                    xp(caster, Skill.DESTRUCTION, dmg * CombatHandler.SKYRIM_SCALE * 0.2f);
                }
            }
            case BLADE -> {
                if (target == null || friendly(caster, target)) return;
                float dmg = 7f * power;
                if (com.nirncraft.platform.Effects.hurt(target, source(level, null, bolt, caster), dmg)) {
                    level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, target.getX(), target.getY() + target.getBbHeight() * 0.6, target.getZ(),
                            6, 0.3, 0.3, 0.3, 0.1);
                    xp(caster, Skill.TWO_HANDED, dmg * CombatHandler.SKYRIM_SCALE * 0.15f);
                }
            }
            case CALM -> {
                if (!(target instanceof Mob mob)) return;
                if (resists(caster, mob, Skill.ILLUSION)) {
                    Notifier.message(caster, Component.translatable("message.nirncraft.arsenal.resisted", mob.getDisplayName()));
                    return;
                }
                Illusion.applyCalm(mob, 20 * 30);
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, mob.getX(), mob.getY() + mob.getBbHeight(), mob.getZ(), 10, 0.4, 0.3, 0.4, 0);
                xp(caster, Skill.ILLUSION, 4f);
            }
            case FURY -> {
                int affected = 0;
                for (Mob mob : level.getEntitiesOfClass(Mob.class, new AABB(pos, pos).inflate(4), m -> m.isAlive() && !friendly(caster, m))) {
                    if (resists(caster, mob, Skill.ILLUSION)) continue;
                    Illusion.applyFrenzy(mob, 20 * 20);
                    level.sendParticles(ParticleTypes.ANGRY_VILLAGER, mob.getX(), mob.getY() + mob.getBbHeight(), mob.getZ(), 4, 0.3, 0.2, 0.3, 0);
                    affected++;
                }
                if (affected > 0) xp(caster, Skill.ILLUSION, 3f * affected);
            }
            case PARALYSIS -> {
                if (target == null || friendly(caster, target)) return;
                if (target.getType().builtInRegistryHolder().is(CombatHandler.BOSSES)) {
                    Notifier.message(caster, Component.translatable("message.nirncraft.arsenal.resisted", target.getDisplayName()));
                    return;
                }
                target.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(ModEffects.PARALYSIS.get()), 100, 0));
                if (target instanceof Mob mob) mob.getNavigation().stop();
                level.sendParticles(ParticleTypes.ENCHANTED_HIT, target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                        20, 0.4, 0.5, 0.4, 0.1);
                xp(caster, Skill.ALTERATION, 5f);
            }
            case WABBAJACK -> wabbajack(caster, target, pos, power);
        }
    }

    // ------------------------------------------------------------------ Wabbajack

    private static void wabbajack(ServerPlayer caster, @Nullable LivingEntity target, Vec3 pos, float power) {
        ServerLevel level = caster.level();
        RandomSource r = caster.getRandom();
        level.sendParticles(ParticleTypes.WITCH, pos.x, pos.y + 0.5, pos.z, 30, 0.6, 0.6, 0.6, 0.05);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 1f, 1f);
        xp(caster, Skill.ILLUSION, 3f);
        boolean transformable = target instanceof Mob mob && !mob.getType().builtInRegistryHolder().is(CombatHandler.BOSSES) && !mob.hasCustomName()
                && !(mob instanceof TamableAnimal t && t.isTame()) && !mob.getPersistentData().contains(WABBAJACK_ORIGINAL);
        int roll = r.nextInt(target == null ? 4 : 9);
        if (target == null) {
            switch (roll) {
                case 0 -> goldRain(level, pos, r);
                case 1 -> level.explode(null, pos.x, pos.y, pos.z, 1.5f, Level.ExplosionInteraction.NONE);
                case 2 -> rabbits(level, pos, r);
                default -> lightning(level, caster, pos, false);
            }
            return;
        }
        Notifier.message(caster, Component.translatable("message.nirncraft.arsenal.wabbajack." + roll));
        switch (roll) {
            case 0 -> {
                if (transformable) chicken((Mob) target);
                else target.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.LEVITATION), 60, 1));
            }
            case 1 -> level.explode(caster, target.getX(), target.getY() + 0.5, target.getZ(), 2.0f, Level.ExplosionInteraction.NONE);
            case 2 -> {
                target.heal(target.getMaxHealth());
                level.sendParticles(ParticleTypes.HEART, target.getX(), target.getY() + target.getBbHeight(), target.getZ(), 8, 0.4, 0.3, 0.4, 0);
            }
            case 3 -> goldRain(level, target.position(), r);
            case 4 -> {
                target.igniteForSeconds(8);
                com.nirncraft.platform.Effects.hurt(target, fire(level, null, caster), 4f * power);
            }
            case 5 -> lightning(level, caster, target.position(), true);
            case 6 -> target.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.LEVITATION), 100, 0));
            case 7 -> rabbits(level, target.position(), r);
            default -> {
                if (target instanceof Mob mob && !mob.getType().builtInRegistryHolder().is(CombatHandler.BOSSES)) {
                    Illusion.applyFrenzy(mob, 20 * 15);
                } else {
                    target.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.INVISIBILITY), 200, 0));
                }
            }
        }
    }

    private static void chicken(Mob mob) {
        ServerLevel level = (ServerLevel) mob.level();
        Chicken chicken = net.minecraft.world.entity.EntityTypes.CHICKEN.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        if (chicken == null) return;
        CompoundTag saved;
        try { saved = com.nirncraft.platform.EntityData.save(mob); } catch (IllegalStateException unavailable) { return; }
        chicken.snapTo(mob.getX(), mob.getY(), mob.getZ(), mob.getYRot(), 0f);
        chicken.getPersistentData().put(WABBAJACK_ORIGINAL, saved);
        chicken.getPersistentData().putLong(WABBAJACK_UNTIL, level.getGameTime() + 20 * 30);
        chicken.setPersistenceRequired();
        mob.discard();
        level.addFreshEntity(chicken);
        level.sendParticles(ParticleTypes.POOF, chicken.getX(), chicken.getY() + 0.4, chicken.getZ(), 20, 0.4, 0.4, 0.4, 0.02);
    }

    /** Turns a Wabbajack chicken back into what it was. */
    public static void unchicken(LivingEntity chicken) {
        if (!(chicken.level() instanceof ServerLevel level)) return;
        CompoundTag saved = chicken.getPersistentData().getCompoundOrEmpty(WABBAJACK_ORIGINAL);
        chicken.getPersistentData().remove(WABBAJACK_ORIGINAL);
        EntityType.create(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), saved), level, new net.minecraft.world.entity.EntitySpawnRequest(net.minecraft.world.entity.EntitySpawnReason.CONVERSION, false)).ifPresent(original -> {
            original.snapTo(chicken.getX(), chicken.getY(), chicken.getZ(), chicken.getYRot(), 0f);
            if (original.getUUID().equals(chicken.getUUID()) || level.getEntity(original.getUUID()) != null) {
                original.setUUID(UUID.randomUUID());
            }
            chicken.discard();
            level.addFreshEntity(original);
            level.sendParticles(ParticleTypes.POOF, original.getX(), original.getY() + 0.5, original.getZ(), 20, 0.4, 0.5, 0.4, 0.02);
        });
    }

    private static void goldRain(ServerLevel level, Vec3 pos, RandomSource r) {
        int drops = 4 + r.nextInt(6);
        for (int i = 0; i < drops; i++) {
            ItemEntity item = new ItemEntity(level, pos.x + (r.nextDouble() - 0.5) * 3, pos.y + 3 + r.nextDouble() * 2,
                    pos.z + (r.nextDouble() - 0.5) * 3, Currency.coins(1 + r.nextInt(8)));
            item.setDeltaMovement(0, -0.1, 0);
            level.addFreshEntity(item);
        }
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.CHAIN_FALL, SoundSource.PLAYERS, 1f, 1.5f);
    }

    private static void rabbits(ServerLevel level, Vec3 pos, RandomSource r) {
        int n = 3 + r.nextInt(4);
        for (int i = 0; i < n; i++) {
            Rabbit rabbit = net.minecraft.world.entity.EntityTypes.RABBIT.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
            if (rabbit == null) continue;
            rabbit.snapTo(pos.x + (r.nextDouble() - 0.5) * 2, pos.y + 2 + r.nextDouble() * 2, pos.z + (r.nextDouble() - 0.5) * 2, r.nextFloat() * 360f, 0f);
            level.addFreshEntity(rabbit);
        }
    }

    private static void lightning(ServerLevel level, ServerPlayer caster, Vec3 pos, boolean hurt) {
        LightningBolt bolt = net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        if (bolt == null) return;
        bolt.snapTo(pos.x, pos.y, pos.z);
        bolt.setVisualOnly(true);
        level.addFreshEntity(bolt);
        if (hurt) {
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos, pos).inflate(2.5), e -> e.isAlive() && !friendly(caster, e))) {
                com.nirncraft.platform.Effects.hurt(e, shock(level, null, caster), 6f);
                e.igniteForSeconds(3);
            }
        }
    }
}
