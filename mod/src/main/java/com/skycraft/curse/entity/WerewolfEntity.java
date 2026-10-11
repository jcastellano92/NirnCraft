package com.skycraft.curse.entity;

import com.skycraft.curse.CurseAnims;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LeapAtTargetGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * {@code skycraft:werewolf}: a cursed hunter in Beast Form. Hunts people and game at night, howls when it finds prey
 * (townsfolk nearby flee), leaps and claws, and feeds on what it kills. Wild ones ({@link #setWild}) are gone by dawn.
 * Also the Companions' Wolf Spirit, Sinding, and the werewolves the Totem of the Moon calls.
 */
public class WerewolfEntity extends CurseMonster {
    private boolean wild;
    private long nextHowl;
    private int feeding;

    public WerewolfEntity(EntityType<? extends WerewolfEntity> type, Level level) {
        super(type, level);
        this.xpReward = 30;
        this.setMaxUpStep(1.1f);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 70.0)
                .add(Attributes.ATTACK_DAMAGE, 8.0)
                .add(Attributes.MOVEMENT_SPEED, 0.33)
                .add(Attributes.FOLLOW_RANGE, 40.0)
                .add(Attributes.ARMOR, 5.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.5);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new LeapAtTargetGoal(this, 0.45f));
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.35, true));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.9));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12f));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, NpcEntity.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, true));
        this.targetSelector.addGoal(5, new NearestAttackableTargetGoal<>(this, Animal.class, 20, true, false, e -> this.getHealth() < this.getMaxHealth()));
    }

    public void setWild(boolean wild) {
        this.wild = wild;
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        playAnim(CurseAnims.Anim.ATTACK);
        boolean hit = super.doHurtTarget(target);
        if (hit && target instanceof LivingEntity living && this.random.nextFloat() < 0.25f) {
            living.addEffect(new MobEffectInstance(com.skycraft.registry.ModEffects.BLEEDING.get(), 60, 0), this);
        }
        return hit;
    }

    @Override
    public void setTarget(LivingEntity target) {
        LivingEntity previous = this.getTarget();
        super.setTarget(target);
        if (!this.level().isClientSide && target != null && previous == null && this.level().getGameTime() >= nextHowl) {
            howl();
        }
    }

    private void howl() {
        nextHowl = this.level().getGameTime() + 20 * 30;
        playAnim(CurseAnims.Anim.HOWL);
        this.playSound(SoundEvents.WOLF_HOWL, 2.0f, 0.55f);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide) return;
        // feeding on a fresh kill heals
        if (feeding > 0 && --feeding % 10 == 0) this.heal(3f);
        // wild werewolves are gone with the night
        if (wild && this.level().isDay() && this.tickCount % 40 == 0 && this.getTarget() == null) {
            if (this.level() instanceof ServerLevel sl) sl.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY() + 1, getZ(), 10, 0.4, 0.8, 0.4, 0.01);
            this.discard();
        }
    }

    @Override
    public void awardKillScore(Entity killed, int score, DamageSource source) {
        super.awardKillScore(killed, score, source);
        if (!this.level().isClientSide && killed instanceof LivingEntity) {
            feeding = 60;
            playAnim(CurseAnims.Anim.FEED);
            this.playSound(SoundEvents.GENERIC_EAT, 1f, 0.6f);
        }
    }

    @Override
    public boolean removeWhenFarAway(double distanceSqr) {
        return wild;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.WOLF_GROWL;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.WOLF_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.WOLF_DEATH;
    }

    @Override
    public float getVoicePitch() {
        return 0.55f + this.random.nextFloat() * 0.1f;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Wild", wild);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        wild = tag.getBoolean("Wild");
    }
}
