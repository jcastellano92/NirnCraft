package com.skycraft.fauna.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * {@code skycraft:bear}: territorial; charges anyone who comes within ~8 blocks (4 when sneaking) and fights back
 * when hurt. Variant 0 = brown bear, 1 = cave bear (underground / deep taiga), 2 = snow bear (cold biomes).
 */
public class BearEntity extends FaunaAnimal {
    public static final int BROWN = 0;
    public static final int CAVE = 1;
    public static final int SNOW = 2;

    public BearEntity(EntityType<? extends BearEntity> type, Level level) {
        super(type, level);
        this.xpReward = 8;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.ATTACK_DAMAGE, 7.0)
                .add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.4)
                .add(Attributes.FOLLOW_RANGE, 28.0);
    }

    @Override
    protected int chooseVariant(ServerLevelAccessor level, BlockPos pos) {
        if (isCold(level, pos)) return SNOW;
        if (!level.canSeeSky(pos) || pos.getY() < level.getSeaLevel() - 8) return CAVE;
        return level.getBiome(pos).value().getBaseTemperature() < 0.35f && this.random.nextInt(3) == 0 ? CAVE : BROWN;
    }

    @Override
    protected Component getTypeName() {
        return switch (getVariant()) {
            case CAVE -> Component.translatable("entity.skycraft.bear.cave");
            case SNOW -> Component.translatable("entity.skycraft.bear.snow");
            default -> super.getTypeName();
        };
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.3, true));
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 12f));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
                p -> {
                    if (!canAttackPlayers(p)) return false;
                    // Once engaged or provoked, maintain pursuit up to full follow range (28 blocks)
                    if (this.getTarget() == p || this.getLastHurtByMob() == p) {
                        return this.distanceToSqr(p) <= 784.0;
                    }
                    double acquireRange = p.isDiscrete() ? 9.0 : (p.isSprinting() ? 20.0 : 16.0);
                    return this.distanceToSqr(p) <= acquireRange * acquireRange;
                }));
        this.targetSelector.addGoal(3, new com.skycraft.creatures.ai.SmartPartyTargetGoal(this));
        this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, net.minecraft.world.entity.animal.Animal.class, 60, true, false,
                e -> e instanceof DeerEntity || e instanceof ElkEntity));
    }

    @Override
    public void setTarget(@Nullable LivingEntity target) {
        if (target != null && getTarget() == null && !this.level().isClientSide) {
            playSound(com.skycraft.audio.AudioSounds.creature("bear", "roar"), 1.2f, 1.0f); // the warning roar before the charge
        }
        super.setTarget(target);
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 0.8f;
    }

    @Override
    @Nullable
    protected SoundEvent getAmbientSound() {
        return com.skycraft.audio.AudioSounds.creature("bear", "ambient");
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return com.skycraft.audio.AudioSounds.creature("bear", "hurt");
    }

    @Override
    protected SoundEvent getDeathSound() {
        return com.skycraft.audio.AudioSounds.creature("bear", "death");
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.POLAR_BEAR_STEP, 0.15f, 0.9f);
    }
}
