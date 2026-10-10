package com.skycraft.creatures.entity;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.level.Level;

/** {@code skycraft:frostbite_spider}: large arachnids infesting Skyrim's caves and frozen passes. */
public class FrostbiteSpiderEntity extends Spider {
    public FrostbiteSpiderEntity(EntityType<? extends FrostbiteSpiderEntity> type, Level level) {
        super(type, level);
        this.xpReward = 12;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 30.0)
                .add(Attributes.ATTACK_DAMAGE, 4.0)
                .add(Attributes.MOVEMENT_SPEED, 0.30)
                .add(Attributes.ARMOR, 2.0);
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit && target instanceof LivingEntity living) {
            // Frostbite bite slows and poisons
            living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 1), this);
            living.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 0), this);
            if (living.canFreeze()) {
                living.setTicksFrozen(Math.min(living.getTicksRequiredToFreeze() + 80, living.getTicksFrozen() + 30));
            }
        }
        return hit;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getAmbientSound() {
        return com.skycraft.audio.AudioSounds.creature("frostbite_spider", "ambient");
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getHurtSound(net.minecraft.world.damagesource.DamageSource source) {
        return com.skycraft.audio.AudioSounds.creature("frostbite_spider", "hurt");
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getDeathSound() {
        return com.skycraft.audio.AudioSounds.creature("frostbite_spider", "death");
    }
}
