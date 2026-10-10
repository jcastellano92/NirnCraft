package com.skycraft.dungeons.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Shared traits of Dwemer automatons: metal bodies that don't breathe, bleed, burn or sicken, clank when they
 * walk, and fight with soul-powered lightning and steam. Drops (Dwarven scrap and metal ingots) come from their
 * loot tables.
 */
public abstract class Automaton extends Monster {
    protected Automaton(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    public boolean canBeAffected(MobEffectInstance effect) {
        var e = effect.getEffect();
        if (e == MobEffects.POISON || e == MobEffects.WITHER || e == MobEffects.REGENERATION || e == MobEffects.HUNGER
                || e == MobEffects.CONFUSION || e == MobEffects.BLINDNESS) return false;
        return super.canBeAffected(effect);
    }

    @Override
    public boolean canBreatheUnderwater() {
        return true;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return this.random.nextInt(3) == 0 ? com.skycraft.audio.AudioSounds.creature("dwarven", "steam") : com.skycraft.audio.AudioSounds.creature("dwarven", "clank");
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return com.skycraft.audio.AudioSounds.creature("dwarven", "hurt");
    }

    @Override
    protected SoundEvent getDeathSound() {
        return com.skycraft.audio.AudioSounds.creature("dwarven", "death");
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.IRON_GOLEM_STEP, 0.35f, 1.4f);
    }

    @Override
    public int getAmbientSoundInterval() {
        return 200;
    }

    /** A crackling bolt from this automaton to {@code target}: armour-piercing shock damage and a trail of sparks. */
    protected void zap(LivingEntity target, float damage) {
        if (!(this.level() instanceof ServerLevel server)) return;
        Vec3 from = this.position().add(0, this.getBbHeight() * 0.6, 0);
        Vec3 to = target.position().add(0, target.getBbHeight() * 0.5, 0);
        Vec3 step = to.subtract(from);
        int n = Math.max(4, (int) (step.length() * 3));
        for (int i = 0; i <= n; i++) {
            Vec3 at = from.add(step.scale(i / (double) n));
            server.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 1, 0.05, 0.05, 0.05, 0.0);
        }
        this.playSound(SoundEvents.BEE_STING, 0.8f, 1.8f);
        target.invulnerableTime = 0;
        target.hurt(this.damageSources().indirectMagic(this, this), damage);
    }
}
