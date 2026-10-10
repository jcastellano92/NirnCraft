package com.skycraft.creatures.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** {@code skycraft:wispmother}: spectral frost queens haunting ancient ruins and misty bluffs. */
public class WispmotherEntity extends SkyHumanoid {
    private int frostCooldown = 40;

    public WispmotherEntity(EntityType<? extends WispmotherEntity> type, Level level) {
        super(type, level);
        this.xpReward = 30;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 48.0)
                .add(Attributes.ATTACK_DAMAGE, 3.5)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.5);
    }

    @Override
    protected int skinCount() {
        return 1;
    }

    @Override
    protected void equip(RandomSource random, DifficultyInstance difficulty) {
        // Ethereal ghostly form
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide) {
            Vec3 pos = this.position().add((this.random.nextDouble() - 0.5) * 0.5, this.getBbHeight() * 0.6, (this.random.nextDouble() - 0.5) * 0.5);
            this.level().addParticle(ParticleTypes.CLOUD, pos.x, pos.y, pos.z, 0, 0.02, 0);
        }
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive()) return;

        double distSqr = this.distanceToSqr(target);
        if (--frostCooldown <= 0 && distSqr < 144.0 && this.hasLineOfSight(target)) {
            frostCooldown = 50 + this.random.nextInt(25);
            target.hurt(this.damageSources().indirectMagic(this, this), 3.0f);
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1), this);
            if (target.canFreeze()) {
                target.setTicksFrozen(Math.min(target.getTicksRequiredToFreeze() + 60, target.getTicksFrozen() + 30));
            }
            this.playSound(SoundEvents.PLAYER_HURT_FREEZE, 1.2f, 0.8f);
        }
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit && target instanceof LivingEntity living) {
            living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 1), this);
        }
        return hit;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getAmbientSound() {
        return com.skycraft.audio.AudioSounds.creature("wisp", "ambient");
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getHurtSound(net.minecraft.world.damagesource.DamageSource source) {
        return com.skycraft.audio.AudioSounds.creature("wisp", "ambient");
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getDeathSound() {
        return com.skycraft.audio.AudioSounds.creature("wisp", "ambient");
    }
}
