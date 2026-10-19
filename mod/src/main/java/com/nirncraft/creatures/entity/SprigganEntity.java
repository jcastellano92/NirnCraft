package com.nirncraft.creatures.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
import org.jetbrains.annotations.Nullable;

/** {@code nirncraft:spriggan}: nature spirits inhabiting Skyrim's forests. Can tap into deep roots to regenerate rapidly. */
public class SprigganEntity extends SkyHumanoid implements Ranked {
    private boolean usedHealing = false;

    public SprigganEntity(EntityType<? extends SprigganEntity> type, Level level) {
        super(type, level);
        this.xpReward = 22;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 42.0)
                .add(Attributes.ATTACK_DAMAGE, 4.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.ARMOR, 4.0);
    }

    @Override
    protected int skinCount() {
        return 1;
    }

    @Override
    protected void equip(RandomSource random, DifficultyInstance difficulty) {
        // Natural wooden claws, no manufactured armor
    }

    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel serverLevel263) {
        super.customServerAiStep(serverLevel263);
        // Taproot emergency healing burst when below 40% health
        if (!usedHealing && this.getHealth() < this.getMaxHealth() * 0.4f && this.isAlive()) {
            usedHealing = true;
            this.heal(20.0f);
            this.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.REGENERATION), 120, 1), this);
            this.playSound(SoundEvents.ENCHANTMENT_TABLE_USE, 1.2f, 0.9f);
            if (this.level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, this.getX(), this.getY() + 1.0, this.getZ(), 20, 0.5, 0.5, 0.5, 0.05);
            }
        }
    }

    @Override
    public boolean doHurtTarget(net.minecraft.server.level.ServerLevel serverLevel263, Entity target) {
        boolean hit = super.doHurtTarget(serverLevel263, target);
        if (hit && target instanceof LivingEntity living) {
            // Nature swarm venom
            living.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.POISON), 60, 0), this);
        }
        return hit;
    }

    @Override
    @Nullable
    public Component rankName(int level) {
        String rank = level >= 28 ? "matron" : level >= 20 ? "earth_mother" : null;
        return rank == null ? null : Component.translatable("entity.nirncraft.spriggan." + rank);
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getAmbientSound() {
        return com.nirncraft.audio.AudioSounds.creature("spriggan", "ambient");
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getHurtSound(net.minecraft.world.damagesource.DamageSource source) {
        return com.nirncraft.audio.AudioSounds.creature("spriggan", "hurt");
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getDeathSound() {
        return com.nirncraft.audio.AudioSounds.creature("spriggan", "death");
    }
}
