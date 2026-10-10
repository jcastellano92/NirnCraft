package com.skycraft.fauna.entity;

import com.skycraft.Skycraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * {@code skycraft:mammoth}: huge, neutral herd beast of the plains and tundra. Mammoths keep close to their herd
 * leader, or to a giant (the giants' herds), and the whole herd retaliates when one is attacked.
 */
public class MammothEntity extends FaunaAnimal {
    private static final ResourceLocation GIANT_ID = new ResourceLocation(Skycraft.MODID, "giant");
    @Nullable
    private static EntityType<?> giantType;
    private static boolean giantLookedUp;

    public MammothEntity(EntityType<? extends MammothEntity> type, Level level) {
        super(type, level);
        this.xpReward = 15;
        this.setMaxUpStep(1.0f);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 120.0)
                .add(Attributes.ATTACK_DAMAGE, 12.0)
                .add(Attributes.ATTACK_KNOCKBACK, 1.5)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.MOVEMENT_SPEED, 0.2)
                .add(Attributes.FOLLOW_RANGE, 20.0);
    }

    /** Entity tag of the mammoths spawned with a roaming giant: they despawn like it, when no player is near. */
    public static final String HERD_TAG = "skycraft.giant_herd";

    /** Animals never despawn; a roaming giant's herd does (unless someone leashed or named one). */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return this.getTags().contains(HERD_TAG) && !this.isLeashed() && !this.hasCustomName();
    }

    /** The creatures module's giant type, if registered. */
    @Nullable
    public static EntityType<?> giantType() {
        if (!giantLookedUp) {
            giantLookedUp = true;
            giantType = ForgeRegistries.ENTITY_TYPES.containsKey(GIANT_ID) ? ForgeRegistries.ENTITY_TYPES.getValue(GIANT_ID) : null;
        }
        return giantType;
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.2, true));
        this.goalSelector.addGoal(4, new HerdGoal());
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.6));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 12f));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers());
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit) target.setDeltaMovement(target.getDeltaMovement().add(0, 0.45, 0)); // tossed by the tusks
        return hit;
    }

    @Override
    public int getAmbientSoundInterval() {
        return 360;
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 0.55f;
    }

    @Override
    protected float getSoundVolume() {
        return 1.6f;
    }

    @Override
    @Nullable
    protected SoundEvent getAmbientSound() {
        return SoundEvents.RAVAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.RAVAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.RAVAGER_DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.RAVAGER_STEP, 0.4f, 0.7f);
    }

    /** Walks back to the herd leader (a nearby giant, else the herd's oldest mammoth) when it strays too far. */
    private class HerdGoal extends Goal {
        @Nullable
        private LivingEntity leader;
        private int recheck;

        HerdGoal() {
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (--recheck > 0 || getTarget() != null) return false;
            recheck = 60 + getRandom().nextInt(40);
            leader = findLeader();
            return leader != null && distanceToSqr(leader) > 14 * 14;
        }

        @Nullable
        private LivingEntity findLeader() {
            EntityType<?> giant = giantType();
            if (giant != null) {
                List<LivingEntity> giants = level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(40),
                        e -> e.getType() == giant && e.isAlive());
                if (!giants.isEmpty()) return giants.stream().min(Comparator.comparingDouble((LivingEntity e) -> distanceToSqr(e))).orElse(null);
            }
            List<MammothEntity> herd = level().getEntitiesOfClass(MammothEntity.class, getBoundingBox().inflate(32), Entity::isAlive);
            MammothEntity oldest = herd.stream().max(Comparator.comparingInt((MammothEntity m) -> m.tickCount)
                    .thenComparing(m -> m.getUUID())).orElse(null);
            return oldest == MammothEntity.this ? null : oldest;
        }

        @Override
        public boolean canContinueToUse() {
            return leader != null && leader.isAlive() && getTarget() == null && distanceToSqr(leader) > 8 * 8 && !getNavigation().isDone();
        }

        @Override
        public void start() {
            if (leader != null) getNavigation().moveTo(leader, 0.8);
        }

        @Override
        public void stop() {
            leader = null;
            getNavigation().stop();
        }
    }
}
