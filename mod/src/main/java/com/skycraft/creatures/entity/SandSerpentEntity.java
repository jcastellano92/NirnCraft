package com.skycraft.creatures.entity;

import com.skycraft.creatures.entity.anim.AnimSlot;
import com.skycraft.creatures.entity.anim.CreatureAnims;
import com.skycraft.creatures.spec.Attacks;
import com.skycraft.creatures.spec.HitEffect;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * {@code skycraft:sand_serpent}: swims unseen through the Alik'r sand (only a plume of dust gives it away), then
 * bursts out beneath its prey, throwing everything near into the air. It dives back under when its prey gets away.
 */
public class SandSerpentEntity extends SkyMonster {
    private static final EntityDataAccessor<Boolean> BURROWED = SynchedEntityData.defineId(SandSerpentEntity.class, EntityDataSerializers.BOOLEAN);
    private int surfaceTicks;

    public SandSerpentEntity(EntityType<? extends SandSerpentEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(BURROWED, false);
    }

    public boolean isBurrowed() {
        return this.entityData.get(BURROWED);
    }

    private void setBurrowed(boolean burrowed) {
        this.entityData.set(BURROWED, burrowed);
        this.setInvisible(burrowed);
    }

    @Override
    public boolean canAttackNow() {
        return !isBurrowed();
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData data, @Nullable CompoundTag tag) {
        data = super.finalizeSpawn(level, difficulty, reason, data, tag);
        if (reason == MobSpawnType.NATURAL) setBurrowed(true);
        return data;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return isBurrowed() && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY) || super.isInvulnerableTo(source);
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        LivingEntity target = this.getTarget();
        if (isBurrowed()) {
            if (target != null && Attacks.gap(this, target) < 3.5) emerge();
        } else {
            surfaceTicks++;
            boolean lost = target == null || Attacks.gap(this, target) > 16;
            if (lost && surfaceTicks > 160) burrow();
        }
    }

    private void emerge() {
        setBurrowed(false);
        surfaceTicks = 0;
        CreatureAnims.play(this, AnimSlot.EMERGE);
        this.playSound(com.skycraft.audio.AudioSounds.creature("sand_serpent", "roar"), 2.0f, 0.95f + this.getRandom().nextFloat() * 0.1f);
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(2.5), e -> Attacks.validVictim(this, e))) {
            Attacks.strike(this, e, 0.8f, HitEffect.knock(0.4, 0.9));
        }
        dust(40);
    }

    private void burrow() {
        setBurrowed(true);
        this.playSound(SoundEvents.SAND_BREAK, 2.0f, 0.6f);
        dust(30);
    }

    private void dust(int count) {
        if (!(this.level() instanceof ServerLevel server)) return;
        BlockState ground = this.level().getBlockState(this.blockPosition().below());
        if (ground.isAir()) return;
        server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), this.getX(), this.getY() + 0.2, this.getZ(),
                count, this.getBbWidth() * 0.6, 0.3, this.getBbWidth() * 0.6, 0.15);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide && isBurrowed() && this.tickCount % 2 == 0 && this.getDeltaMovement().horizontalDistanceSqr() > 1.0E-3) {
            BlockState ground = this.level().getBlockState(this.blockPosition().below());
            if (!ground.isAir()) {
                this.level().addParticle(new BlockParticleOption(ParticleTypes.BLOCK, ground), this.getX(), this.getY() + 0.1, this.getZ(),
                        (this.random.nextDouble() - 0.5) * 0.3, 0.25, (this.random.nextDouble() - 0.5) * 0.3);
            }
        }
    }

    @Override
    protected void playStepSound(net.minecraft.core.BlockPos pos, BlockState state) {
        if (!isBurrowed()) super.playStepSound(pos, state);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Burrowed", isBurrowed());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setBurrowed(tag.getBoolean("Burrowed"));
    }
}
