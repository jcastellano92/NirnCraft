package com.skycraft.fauna.entity;

import com.skycraft.combat.CombatHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * {@code skycraft:sabre_cat}: a fast, hostile great cat that stalks players and game and opens with a pounce.
 * Variant 0 = tawny sabre cat, 1 = snowy sabre cat (spawned in cold biomes).
 */
public class SabreCatEntity extends FaunaAnimal {
    public static final int TAWNY = 0;
    public static final int SNOWY = 1;

    private int pounceCooldown;
    private boolean pouncing;

    public SabreCatEntity(EntityType<? extends SabreCatEntity> type, Level level) {
        super(type, level);
        this.xpReward = 8;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 30.0)
                .add(Attributes.ATTACK_DAMAGE, 6.0)
                .add(Attributes.MOVEMENT_SPEED, 0.32)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.2)
                .add(Attributes.FOLLOW_RANGE, 24.0);
    }

    @Override
    protected int chooseVariant(ServerLevelAccessor level, BlockPos pos) {
        return isCold(level, pos) ? SNOWY : TAWNY;
    }

    @Override
    protected Component getTypeName() {
        return getVariant() == SNOWY ? Component.translatable("entity.skycraft.sabre_cat.snowy") : super.getTypeName();
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new PounceGoal());
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.35, true));
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 10f));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false, this::canAttackPlayers));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Animal.class, 40, true, false,
                e -> e instanceof DeerEntity || e instanceof Sheep || e instanceof Goat || e instanceof Rabbit));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) return;
        if (pounceCooldown > 0) pounceCooldown--;
        LivingEntity target = getTarget();
        if (pouncing && target != null && target.isAlive() && getBoundingBox().inflate(0.4).intersects(target.getBoundingBox())) {
            pouncing = false;
            float damage = (float) getAttributeValue(Attributes.ATTACK_DAMAGE) * 1.5f;
            if (target.hurt(damageSources().mobAttack(this), damage)) {
                doEnchantDamageEffects(this, target);
                CombatHandler.stagger(this, target, 20);
            }
            setLastHurtMob(target);
        }
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return distance > 6 && super.causeFallDamage(distance - 3, multiplier, source); // lands on its feet
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 0.6f;
    }

    @Override
    @Nullable
    protected SoundEvent getAmbientSound() {
        return getTarget() != null || this.random.nextInt(4) == 0 ? com.skycraft.audio.AudioSounds.creature("sabre_cat", "ambient") : null;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return com.skycraft.audio.AudioSounds.creature("sabre_cat", "hurt");
    }

    @Override
    protected SoundEvent getDeathSound() {
        return com.skycraft.audio.AudioSounds.creature("sabre_cat", "death");
    }

    /** Leaps at a target 3-7 blocks away; landing on it deals heavy damage and staggers. */
    private class PounceGoal extends Goal {
        PounceGoal() {
            setFlags(EnumSet.of(Flag.JUMP, Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = getTarget();
            if (target == null || !target.isAlive() || !onGround() || pounceCooldown > 0) return false;
            double d = distanceToSqr(target);
            return d > 9.0 && d < 49.0 && getSensing().hasLineOfSight(target) && getRandom().nextInt(4) == 0;
        }

        @Override
        public boolean canContinueToUse() {
            return !onGround();
        }

        @Override
        public void start() {
            LivingEntity target = getTarget();
            if (target == null) return;
            Vec3 d = target.position().subtract(position());
            Vec3 h = new Vec3(d.x, 0, d.z);
            double len = h.length();
            if (len > 1e-4) h = h.scale(Math.min(1.3, len * 0.19) / len);
            setDeltaMovement(h.x, 0.48, h.z);
            pouncing = true;
            pounceCooldown = 60 + getRandom().nextInt(50);
            playSound(com.skycraft.audio.AudioSounds.creature("sabre_cat", "ambient"), 1.2f, 0.9f);
        }

        @Override
        public void stop() {
            pouncing = false;
        }
    }
}
