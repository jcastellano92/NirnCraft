package com.skycraft.dungeons.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * {@code skycraft:dwarven_spider}: a small scuttling Dwemer sentry. Its bite carries a shock, and it zaps intruders
 * from a few blocks away with a spark of soul-lightning.
 */
public class DwarvenSpider extends Automaton {
    private int zapCooldown = 40;

    public DwarvenSpider(EntityType<? extends DwarvenSpider> type, Level level) {
        super(type, level);
        this.xpReward = 6;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 18.0)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.MOVEMENT_SPEED, 0.32)
                .add(Attributes.ARMOR, 6.0)
                .add(Attributes.FOLLOW_RANGE, 24.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new LeapAtTargetGoal(this, 0.3f));
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.15, true));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.7));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0f));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, Automaton.class).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        LivingEntity target = this.getTarget();
        if (zapCooldown > 0) zapCooldown--;
        if (target != null && target.isAlive() && zapCooldown <= 0) {
            double d = this.distanceTo(target);
            if (d > 2.5 && d < 8.0 && this.hasLineOfSight(target)) {
                zap(target, 3.0f);
                zapCooldown = 60 + this.random.nextInt(40);
            }
        }
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        boolean hit = super.doHurtTarget(level, target);
        if (hit && target instanceof LivingEntity living) {
            living.setInvulnerableTime(0);
            com.skycraft.platform.Effects.hurt(living, this.damageSources().indirectMagic(this, this), 2.0f);
            if (this.level() instanceof ServerLevel server) {
                server.sendParticles(ParticleTypes.ELECTRIC_SPARK, target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                        8, 0.25, 0.3, 0.25, 0.05);
            }
        }
        return hit;
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 1.5f;
    }
}
