package com.skycraft.fauna.entity;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code skycraft:deer}: timid, bolts from players (a crouching hunter gets much closer), wolves and big predators.
 * Males (half of them) grow antlers and drop Small Antlers.
 */
public class DeerEntity extends FaunaAnimal {
    public DeerEntity(EntityType<? extends DeerEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 12.0)
                .add(Attributes.MOVEMENT_SPEED, 0.26)
                .add(Attributes.FOLLOW_RANGE, 20.0);
    }

    /** Distance at which a standing player spooks the animal. */
    protected float spookDistance() {
        return 16f;
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new PanicGoal(this, 2.1));
        // sneaking hunters are only noticed up close
        this.goalSelector.addGoal(2, new AvoidEntityGoal<>(this, Player.class,
                (LivingEntity p) -> !p.isDiscrete() || this.distanceToSqr(p) < 25.0, spookDistance(), 1.5, 2.0,
                EntitySelector.NO_CREATIVE_OR_SPECTATOR::test));
        this.goalSelector.addGoal(3, new AvoidEntityGoal<>(this, Wolf.class, 12f, 1.5, 2.0));
        this.goalSelector.addGoal(3, new AvoidEntityGoal<>(this, net.minecraft.world.entity.LivingEntity.class, 14f, 1.5, 2.0,
                e -> e instanceof com.skycraft.creatures.entity.SkyCreature sc && sc.spec().prey != null));
        this.goalSelector.addGoal(3, new AvoidEntityGoal<>(this, SabreCatEntity.class, 16f, 1.5, 2.0));
        this.goalSelector.addGoal(3, new AvoidEntityGoal<>(this, BearEntity.class, 12f, 1.5, 2.0));
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.9));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 12f));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    @Override
    public int getAmbientSoundInterval() {
        return 400;
    }

    @Override
    @Nullable
    protected SoundEvent getAmbientSound() {
        return this.random.nextInt(3) == 0 ? SoundEvents.GOAT_AMBIENT : null;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.GOAT_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.GOAT_DEATH;
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 1.15f;
    }
}
