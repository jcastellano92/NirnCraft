package com.nirncraft.fauna.entity;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code nirncraft:horker}: a slow, blubbery walrus of the cold shores. Lazes about until someone walks within five
 * blocks, then gores them with its tusks; the whole colony joins in.
 */
public class HorkerEntity extends FaunaAnimal {
    public HorkerEntity(EntityType<? extends HorkerEntity> type, Level level) {
        super(type, level);
        this.xpReward = 5;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 30.0)
                .add(Attributes.ATTACK_DAMAGE, 5.0)
                .add(Attributes.MOVEMENT_SPEED, 0.17)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.6)
                .add(Attributes.FOLLOW_RANGE, 12.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.4, true));
        this.goalSelector.addGoal(5, new RandomStrollGoal(this, 0.7, 240));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8f));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
                (p, targetLevel263) -> canAttackPlayers(p) && this.distanceToSqr(p) < 25.0));
    }

    @Override
    public boolean canBreatheUnderwater() {
        return true;
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 0.7f;
    }

    @Override
    @Nullable
    protected SoundEvent getAmbientSound() {
        return com.nirncraft.audio.AudioSounds.creature("horker", "ambient");
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return com.nirncraft.audio.AudioSounds.creature("horker", "hurt");
    }

    @Override
    protected SoundEvent getDeathSound() {
        return com.nirncraft.audio.AudioSounds.creature("horker", "death");
    }
}
