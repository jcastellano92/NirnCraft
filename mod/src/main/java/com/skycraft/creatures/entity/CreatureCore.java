package com.skycraft.creatures.entity;

import com.skycraft.creatures.ai.CreatureAttackGoal;
import com.skycraft.creatures.ai.FleeWhenHurtGoal;
import com.skycraft.creatures.ai.HoverWanderGoal;
import com.skycraft.creatures.spec.CreatureSpec;
import com.skycraft.magic.spell.Summons;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.control.SmoothSwimmingLookControl;
import net.minecraft.world.entity.ai.control.SmoothSwimmingMoveControl;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomSwimmingGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** Behaviour shared by {@link SkyMonster} and {@link SkyBeast}, driven by the creature's {@link CreatureSpec}. */
public final class CreatureCore {
    private CreatureCore() {}

    /** Move control for flyers and swimmers, or null to keep the default (assign it in the constructor). */
    @Nullable
    public static MoveControl moveControl(Mob mob, CreatureSpec spec) {
        return switch (spec.move) {
            case FLY, HOVER -> new FlyingMoveControl(mob, 20, true);
            case SWIM -> new SmoothSwimmingMoveControl(mob, 85, 10, 0.1f, 0.5f, false);
            default -> null;
        };
    }

    @Nullable
    public static LookControl lookControl(Mob mob, CreatureSpec spec) {
        return spec.move == CreatureSpec.Move.SWIM ? new SmoothSwimmingLookControl(mob, 10) : null;
    }

    @Nullable
    public static PathNavigation navigation(Mob mob, Level level, CreatureSpec spec) {
        switch (spec.move) {
            case FLY, HOVER -> {
                FlyingPathNavigation nav = new FlyingPathNavigation(mob, level);
                nav.setCanOpenDoors(false);
                nav.setCanFloat(true);
                nav.getNodeEvaluator().setCanPassDoors(true);
                return nav;
            }
            case SWIM -> {
                return new WaterBoundPathNavigation(mob, level);
            }
            default -> {
                GroundPathNavigation nav = new GroundPathNavigation(mob, level);
                nav.setCanFloat(true);
                return nav;
            }
        }
    }

    public static void registerGoals(PathfinderMob mob, CreatureSpec spec, GoalSelector goalSelector, GoalSelector targetSelector) {
        Goals goals = new Goals(goalSelector, targetSelector);
        if (spec.move != CreatureSpec.Move.SWIM) goals.goal(0, new FloatGoal(mob));
        if (spec.temper == CreatureSpec.Temper.PASSIVE && spec.move != CreatureSpec.Move.FLY && spec.move != CreatureSpec.Move.HOVER) {
            goals.goal(1, new PanicGoal(mob, 1.6));
            goals.goal(2, new AvoidEntityGoal<>(mob, Player.class, 12f, 1.3, 1.6,
                    e -> !(e instanceof Player p && (p.isCreative() || p.isSpectator())) && !e.isCrouching()));
        }
        if (spec.fleeBelow > 0 && spec.move != CreatureSpec.Move.FLY) goals.goal(1, new FleeWhenHurtGoal(mob, spec.fleeBelow, 1.5));
        if (!spec.attacks.isEmpty()) goals.goal(3, new CreatureAttackGoal(mob, spec));
        switch (spec.move) {
            case FLY -> goals.goal(5, new HoverWanderGoal(mob, 1.0, 12, 28, 22));
            case HOVER -> goals.goal(5, new HoverWanderGoal(mob, 0.8, 1, 4, 10));
            case SWIM -> goals.goal(5, new RandomSwimmingGoal(mob, 1.0, 20));
            default -> goals.goal(5, new WaterAvoidingRandomStrollGoal(mob, 0.8));
        }
        goals.goal(6, new LookAtPlayerGoal(mob, Player.class, 10f));
        goals.goal(7, new RandomLookAroundGoal(mob));

        if (spec.temper != CreatureSpec.Temper.PASSIVE && !spec.attacks.isEmpty()) {
            goals.target(1, new LoyalHurtByTargetGoal(mob).setAlertOthers());
            if (spec.temper == CreatureSpec.Temper.HOSTILE || spec.temper == CreatureSpec.Temper.TERRITORIAL) {
                float range = spec.aggroRange;
                float keepRange = Math.max(range * 1.8f, 28f);
                goals.target(2, new NearestAttackableTargetGoal<>(mob, Player.class, 10, true, false,
                        (e, targetLevel263) -> {
                            if (Summons.isCompanion(mob) || mob.level().getDifficulty() == Difficulty.PEACEFUL) return false;
                            if (mob.getTarget() == e || mob.getLastHurtByMob() == e) {
                                return e.distanceToSqr(mob) <= (double) keepRange * keepRange;
                            }
                            float effRange = (e instanceof Player p && p.isSprinting()) ? range * 1.25f
                                    : (e instanceof Player p && p.isCrouching()) ? range * 0.65f : range;
                            return e.distanceToSqr(mob) <= (double) effRange * effRange;
                        }));
            }
            goals.target(3, new com.skycraft.creatures.ai.SmartPartyTargetGoal(mob));
            if (spec.prey != null) {
                goals.target(4, new NearestAttackableTargetGoal<>(mob, LivingEntity.class, 40, true, false,
                        (e, targetLevel263) -> !Summons.isCompanion(mob) && spec.prey.test(e)));
            }
        }
    }

    private record Goals(GoalSelector goals, GoalSelector targets) {
        void goal(int priority, Goal goal) {
            goals.addGoal(priority, goal);
        }

        void target(int priority, Goal goal) {
            targets.addGoal(priority, goal);
        }
    }

    /** Damage multiplier by element (fire, frost, shock, poison). */
    public static float damageMultiplier(CreatureSpec spec, DamageSource source) {
        if (source.is(DamageTypeTags.IS_FIRE)) return spec.fireMult;
        if (source.is(DamageTypeTags.IS_FREEZING) || source.is(com.skycraft.magic.MagicDamage.FROST)) return spec.frostMult;
        if (source.is(DamageTypeTags.IS_LIGHTNING) || source.is(com.skycraft.magic.MagicDamage.SHOCK)) return spec.shockMult;
        return 1f;
    }

    /** Server-side tick work: regeneration and the spec's hook. */
    public static void serverTick(Mob mob, CreatureSpec spec, int lastFireHurt) {
        if (spec.regen > 0 && mob.tickCount % 20 == 0 && mob.isAlive() && mob.getHealth() < mob.getMaxHealth()
                && !(spec.fireStopsRegen && (mob.isOnFire() || mob.tickCount - lastFireHurt < 100))) {
            mob.heal(spec.regen);
        }
        if (spec.poisonMult == 0 && mob.hasEffect(com.skycraft.platform.Effects.holder(MobEffects.POISON))) mob.removeEffect(com.skycraft.platform.Effects.holder(MobEffects.POISON));
        if (spec.serverTick != null) spec.serverTick.accept(mob);
    }

    @Nullable
    public static Component rankName(CreatureSpec spec, int level) {
        String rank = spec.rankFor(level);
        return rank == null ? null : Component.translatable("entity.skycraft." + spec.id + "." + rank);
    }

    @Nullable
    public static Component variantName(CreatureSpec spec, int variant) {
        if (variant < 0 || variant >= spec.variantNames.length) return null;
        String v = spec.variantNames[variant];
        return v == null || v.isEmpty() ? null : Component.translatable("entity.skycraft." + spec.id + "." + v);
    }

    /** Scales base health and damage for the creature's variant (e.g. ice wolves, giant mudcrabs). */
    public static void applyVariantPower(Mob mob, CreatureSpec spec, int variant) {
        if (spec.variantPower == null || spec.tinted || variant < 0 || variant >= spec.variantPower.length) return;
        float mult = spec.variantPower[variant];
        var health = mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (health != null) health.setBaseValue(spec.health * mult);
        var damage = mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        if (damage != null) damage.setBaseValue(spec.damage * (1 + (mult - 1) * 0.6));
        mob.setHealth(mob.getMaxHealth());
    }

    /** Retaliation that never turns a summon or ally against the player it fights for. */
    static final class LoyalHurtByTargetGoal extends HurtByTargetGoal {
        LoyalHurtByTargetGoal(PathfinderMob mob) {
            super(mob);
        }

        @Override
        public boolean canUse() {
            if (!super.canUse()) return false;
            java.util.UUID owner = Summons.ownerOf(this.mob);
            LivingEntity attacker = this.mob.getLastHurtByMob();
            return owner == null || attacker == null || !owner.equals(attacker.getUUID()) && !owner.equals(Summons.ownerOf(attacker));
        }
    }

    /** Region-spawned, unnamed, unleashed creatures may despawn. */
    public static boolean mayDespawn(Mob mob, CreatureSpec spec) {
        return !spec.persistent && mob.entityTags().contains(SkyCreature.REGION_SPAWN_TAG) && !mob.hasCustomName() && !mob.isLeashed()
                && !Summons.isCompanion(mob);
    }
}
