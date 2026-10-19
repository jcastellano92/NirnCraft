package com.nirncraft.fauna.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** {@code nirncraft:mudcrab}: small, armored, bad-tempered shore crab. Pinches anyone within eight blocks. */
public class MudcrabEntity extends FaunaAnimal {
    public MudcrabEntity(EntityType<? extends MudcrabEntity> type, Level level) {
        super(type, level);
        this.xpReward = 2;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 8.0)
                .add(Attributes.ATTACK_DAMAGE, 2.0)
                .add(Attributes.ARMOR, 4.0)
                .add(Attributes.MOVEMENT_SPEED, 0.22)
                .add(Attributes.FOLLOW_RANGE, 10.0);
    }

    @Override
    protected void registerGoals() {
        // no FloatGoal: mudcrabs walk along river and lake beds
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.25, true));
        this.goalSelector.addGoal(5, new RandomStrollGoal(this, 0.7));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 6f));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
                (p, targetLevel263) -> canAttackPlayers(p) && this.distanceToSqr(p) < 64.0));
    }

    @Override
    public boolean canBreatheUnderwater() {
        return true;
    }

    /** One in ten is a giant mudcrab: twice as big, much tougher. */
    @Override
    protected int chooseVariant(net.minecraft.world.level.ServerLevelAccessor level, BlockPos pos) {
        return this.random.nextInt(10) == 0 ? 1 : 0;
    }

    public boolean isGiant() {
        return getVariant() == 1;
    }

    @Override
    @org.jetbrains.annotations.Nullable
    public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(net.minecraft.world.level.ServerLevelAccessor level,
                                                                   net.minecraft.world.DifficultyInstance difficulty,
                                                                   net.minecraft.world.entity.EntitySpawnReason reason,
                                                                   @org.jetbrains.annotations.Nullable net.minecraft.world.entity.SpawnGroupData data) {
        data = super.finalizeSpawn(level, difficulty, reason, data);
        if (isGiant()) {
            var hp = this.getAttribute(Attributes.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(36.0);
            var dmg = this.getAttribute(Attributes.ATTACK_DAMAGE);
            if (dmg != null) dmg.setBaseValue(5.0);
            this.setHealth(this.getMaxHealth());
            this.xpReward = 8;
        }
        this.refreshDimensions();
        return data;
    }

    @Override
    protected net.minecraft.world.entity.EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
        net.minecraft.world.entity.EntityDimensions d = super.getDefaultDimensions(pose);
        return isGiant() ? d.scale(2.2f) : d;
    }

    @Override
    public void onSyncedDataUpdated(net.minecraft.network.syncher.EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (VARIANT.equals(key)) this.refreshDimensions();
    }

    @Override
    protected net.minecraft.network.chat.Component getTypeName() {
        return isGiant() ? net.minecraft.network.chat.Component.translatable("entity.nirncraft.mudcrab.giant") : super.getTypeName();
    }

    /** Giant mudcrabs click and crunch deeper. */
    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * (isGiant() ? 0.7f : 1.0f);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return com.nirncraft.audio.AudioSounds.creature("mudcrab", "ambient");
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return com.nirncraft.audio.AudioSounds.creature("mudcrab", "hurt");
    }

    @Override
    protected SoundEvent getDeathSound() {
        return com.nirncraft.audio.AudioSounds.creature("mudcrab", "death");
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.SPIDER_STEP, 0.1f, 1.5f);
    }
}
