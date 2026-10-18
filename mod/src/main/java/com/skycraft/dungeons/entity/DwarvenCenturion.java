package com.skycraft.dungeons.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * {@code skycraft:dwarven_centurion}: the steam-powered guardian of a Dwemer ruin's great hall. Hammer blows that
 * send intruders flying, and a scalding cone of steam breath that ignores armour. Shows a boss bar.
 */
public class DwarvenCenturion extends Automaton {
    private static final EntityDataAccessor<Boolean> DATA_STEAM = SynchedEntityData.defineId(DwarvenCenturion.class, EntityDataSerializers.BOOLEAN);
    private static final double STEAM_RANGE = 9.0;

    private final ServerBossEvent bossEvent = new ServerBossEvent(java.util.UUID.randomUUID(), Component.translatable("entity.skycraft.dwarven_centurion"),
            BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.NOTCHED_10);
    private int steamTicks;
    private int steamCooldown = 80;

    public DwarvenCenturion(EntityType<? extends DwarvenCenturion> type, Level level) {
        super(type, level);
        this.xpReward = 60;
        this.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT).setBaseValue(1.0);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 220.0)
                .add(Attributes.ATTACK_DAMAGE, 14.0)
                .add(Attributes.ATTACK_KNOCKBACK, 1.5)
                .add(Attributes.MOVEMENT_SPEED, 0.22)
                .add(Attributes.ARMOR, 16.0)
                .add(Attributes.ARMOR_TOUGHNESS, 4.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_STEAM, false);
    }

    public boolean isSteaming() {
        return this.entityData.get(DATA_STEAM);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.0, true) {
            @Override
            public boolean canUse() {
                return steamTicks <= 0 && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return steamTicks <= 0 && super.canContinueToUse();
            }
        });
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.5));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12.0f));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, Automaton.class));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel serverLevel) {
        super.customServerAiStep(serverLevel);
        this.bossEvent.setProgress(this.getHealth() / this.getMaxHealth());
        if (this.tickCount % 20 == 0 && this.level() instanceof ServerLevel sl) {
            double rangeSq = 48.0 * 48.0;
            for (ServerPlayer player : sl.players()) {
                boolean inRange = player.isAlive() && !player.isSpectator() && player.distanceToSqr(this) <= rangeSq;
                boolean tracking = this.bossEvent.getPlayers().contains(player);
                if (inRange && !tracking) {
                    this.bossEvent.addPlayer(player);
                } else if (!inRange && tracking) {
                    this.bossEvent.removePlayer(player);
                }
            }
        }
        LivingEntity target = this.getTarget();
        if (steamCooldown > 0) steamCooldown--;
        if (steamTicks > 0) {
            steamTicks--;
            this.getNavigation().stop();
            if (target != null) this.getLookControl().setLookAt(target, 30.0f, 30.0f);
            breathe();
            if (steamTicks == 0) this.entityData.set(DATA_STEAM, false);
        } else if (target != null && target.isAlive() && steamCooldown <= 0
                && this.distanceToSqr(target) < STEAM_RANGE * STEAM_RANGE && this.hasLineOfSight(target)) {
            steamTicks = 50;
            steamCooldown = 180 + this.random.nextInt(80);
            this.entityData.set(DATA_STEAM, true);
            this.playSound(SoundEvents.LAVA_EXTINGUISH, 2.0f, 0.5f);
        }
    }

    /** One tick of steam breath: a cone of cloud in front of the head; scalds whatever stands in it every 5 ticks. */
    private void breathe() {
        if (!(this.level() instanceof ServerLevel server)) return;
        Vec3 eye = this.getEyePosition();
        Vec3 look = this.getViewVector(1.0f);
        for (int i = 0; i < 6; i++) {
            double dist = 1.0 + this.random.nextDouble() * STEAM_RANGE * 0.8;
            double spread = dist * 0.25;
            Vec3 at = eye.add(look.scale(dist));
            server.sendParticles(ParticleTypes.CLOUD, at.x + (this.random.nextDouble() - 0.5) * spread,
                    at.y + (this.random.nextDouble() - 0.5) * spread, at.z + (this.random.nextDouble() - 0.5) * spread,
                    1, 0.0, 0.0, 0.0, 0.02);
        }
        if (steamTicks % 10 == 0) this.playSound(com.skycraft.audio.AudioSounds.creature("dwarven", "steam"), 1.2f, 0.8f);
        if (steamTicks % 5 != 0) return;
        for (LivingEntity e : server.getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(STEAM_RANGE),
                e -> e != this && e.isAlive() && !(e instanceof Automaton))) {
            Vec3 to = e.position().add(0, e.getBbHeight() * 0.5, 0).subtract(eye);
            double len = to.length();
            if (len > STEAM_RANGE || len < 0.01) continue;
            if (to.scale(1.0 / len).dot(look) < 0.72) continue;
            if (!this.hasLineOfSight(e)) continue;
            e.setInvulnerableTime(0);
            com.skycraft.platform.Effects.hurt(e, this.damageSources().indirectMagic(this, this), 3.0f);
        }
    }

    @Override
    public boolean doHurtTarget(net.minecraft.server.level.ServerLevel serverLevel, Entity target) {
        boolean hit = super.doHurtTarget(serverLevel, target);
        if (hit) {
            this.playSound(SoundEvents.ANVIL_LAND, 0.6f, 0.6f);
            if (this.level() instanceof ServerLevel server) {
                server.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 1.0, target.getZ(), 10, 0.4, 0.4, 0.4, 0.2);
            }
            if (target instanceof LivingEntity living) living.setDeltaMovement(living.getDeltaMovement().add(0, 0.35, 0));
        }
        return hit;
    }

    @Override
    public boolean causeFallDamage(double distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        if (player.distanceToSqr(this) <= 48.0 * 48.0) {
            this.bossEvent.addPlayer(player);
        }
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        this.bossEvent.removePlayer(player);
    }

    @Override
    public void setCustomName(Component name) {
        super.setCustomName(name);
        this.bossEvent.setName(this.getDisplayName());
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input263) {
        super.readAdditionalSaveData(input263);
        net.minecraft.nbt.CompoundTag tag = com.skycraft.platform.ValueTags.read(input263);
        
        if (this.hasCustomName()) this.bossEvent.setName(this.getDisplayName());
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide() && isSteaming()) {
            Vec3 eye = this.getEyePosition();
            Vec3 look = this.getViewVector(1.0f);
            for (int i = 0; i < 3; i++) {
                this.level().addParticle(ParticleTypes.CLOUD, eye.x + look.x, eye.y + look.y - 0.2, eye.z + look.z,
                        look.x * 0.6 + (this.random.nextDouble() - 0.5) * 0.15, look.y * 0.6, look.z * 0.6 + (this.random.nextDouble() - 0.5) * 0.15);
            }
        }
        if (this.level().isClientSide() && this.tickCount % 6 == 0) {
            // exhaust stacks
            Vec3 back = this.getViewVector(1.0f).scale(-0.7);
            this.level().addParticle(ParticleTypes.SMOKE, this.getX() + back.x, this.getY() + this.getBbHeight() + 0.1, this.getZ() + back.z, 0, 0.05, 0);
        }
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 0.6f;
    }
}
