package com.skycraft.creatures.entity;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.level.Level;

/** {@code skycraft:hagraven}: monstrous bird-witches of Reach folklore that sacrifice humanity for dark magic. */
public class HagravenEntity extends SkyHumanoid {
    private int fireballCooldown = 50;

    public HagravenEntity(EntityType<? extends HagravenEntity> type, Level level) {
        super(type, level);
        this.xpReward = 25;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 45.0)
                .add(Attributes.ATTACK_DAMAGE, 4.0)
                .add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.ARMOR, 2.0);
    }

    @Override
    protected int skinCount() {
        return 1;
    }

    @Override
    protected void equip(RandomSource random, DifficultyInstance difficulty) {
        // Natural bird-witch talons
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive()) return;

        double distSqr = this.distanceToSqr(target);
        if (--fireballCooldown <= 0 && distSqr < 256.0 && this.hasLineOfSight(target)) {
            fireballCooldown = 60 + this.random.nextInt(30);
            double dx = target.getX() - this.getX();
            double dy = target.getY(0.5) - this.getY(0.5);
            double dz = target.getZ() - this.getZ();
            SmallFireball fireball = new SmallFireball(this.level(), this, dx, dy, dz);
            fireball.setPosRaw(this.getX(), this.getEyeY() - 0.1, this.getZ());
            this.level().addFreshEntity(fireball);
            this.playSound(SoundEvents.BLAZE_SHOOT, 1.0f, 0.9f);
        }
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getAmbientSound() {
        return com.skycraft.audio.AudioSounds.creature("hagraven", "ambient");
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getHurtSound(net.minecraft.world.damagesource.DamageSource source) {
        return com.skycraft.audio.AudioSounds.creature("hagraven", "hurt");
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getDeathSound() {
        return com.skycraft.audio.AudioSounds.creature("hagraven", "death");
    }
}
