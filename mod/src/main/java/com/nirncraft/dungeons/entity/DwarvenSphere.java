package com.nirncraft.dungeons.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
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
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.level.Level;

/**
 * {@code nirncraft:dwarven_sphere}: rolls through the halls as a bronze ball, then unfolds into a warrior with a
 * blade arm and a crossbow arm. Shoots bolts at range, slashes up close, and folds back up when it loses its prey.
 */
public class DwarvenSphere extends Automaton {
    private static final EntityDataAccessor<Boolean> DATA_OPEN = SynchedEntityData.defineId(DwarvenSphere.class, EntityDataSerializers.BOOLEAN);
    private static final int UNFOLD_TICKS = 16;

    private int unfold;
    private int idleTicks;
    private int boltCooldown = 30;
    /** Client animation progress 0 (ball) .. 1 (standing). */
    public float openAnim;
    public float openAnimO;
    /** Client: accumulated roll angle for the ball. */
    public float rollAngle;
    public float rollAngleO;

    public DwarvenSphere(EntityType<? extends DwarvenSphere> type, Level level) {
        super(type, level);
        this.xpReward = 12;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.ATTACK_DAMAGE, 6.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.ARMOR, 10.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.3)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_OPEN, false);
    }

    public boolean isOpen() {
        return this.entityData.get(DATA_OPEN);
    }

    private void setOpen(boolean open) {
        if (open != isOpen()) {
            this.entityData.set(DATA_OPEN, open);
            this.playSound(open ? SoundEvents.PISTON_EXTEND : SoundEvents.PISTON_CONTRACT, 0.8f, 0.7f);
            if (open) unfold = UNFOLD_TICKS;
        }
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_OPEN.equals(key)) this.refreshDimensions();
    }

    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        EntityDimensions base = super.getDefaultDimensions(pose);
        return isOpen() ? base : EntityDimensions.scalable(base.width(), base.width());
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.1, true) {
            @Override
            public boolean canUse() {
                return unfold <= 0 && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return unfold <= 0 && super.canContinueToUse();
            }
        });
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.8));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 10.0f));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, Automaton.class).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    /** Rolling is faster than walking. */
    @Override
    public void setSpeed(float speed) {
        super.setSpeed(isOpen() ? speed : speed * 1.5f);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            openAnimO = openAnim;
            openAnim += ((isOpen() ? 1.0f : 0.0f) - openAnim) * 0.18f;
            rollAngleO = rollAngle;
            double moved = Math.sqrt((getX() - xo) * (getX() - xo) + (getZ() - zo) * (getZ() - zo));
            if (!isOpen()) rollAngle += (float) moved * 2.2f;
        }
    }

    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel serverLevel) {
        super.customServerAiStep(serverLevel);
        LivingEntity target = this.getTarget();
        boolean engaged = target != null && target.isAlive() && this.distanceToSqr(target) < 14 * 14;
        if (engaged) {
            idleTicks = 0;
            setOpen(true);
        } else if (++idleTicks > 80) {
            setOpen(false);
        }
        if (unfold > 0) {
            unfold--;
            this.getNavigation().stop();
            return;
        }
        if (boltCooldown > 0) boltCooldown--;
        if (engaged && isOpen() && boltCooldown <= 0) {
            double d = this.distanceTo(target);
            if (d > 4.5 && this.hasLineOfSight(target)) {
                shootBolt(target);
                boltCooldown = 35 + this.random.nextInt(20);
            }
        }
    }

    private void shootBolt(LivingEntity target) {
        Arrow bolt = new Arrow(this.level(), this, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
        double dx = target.getX() - this.getX();
        double dy = target.getY(0.4) - bolt.getY();
        double dz = target.getZ() - this.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        bolt.shoot(dx, dy + flat * 0.12, dz, 2.0f, 4.0f);
        bolt.setBaseDamage(3.0);
        bolt.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
        this.level().addFreshEntity(bolt);
        this.playSound(SoundEvents.CROSSBOW_SHOOT, 1.0f, 0.8f);
    }

    @Override
    public boolean doHurtTarget(net.minecraft.server.level.ServerLevel serverLevel, Entity target) {
        if (!isOpen() || unfold > 0) return false;
        return super.doHurtTarget(serverLevel, target);
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output263) {
        super.addAdditionalSaveData(output263);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        
        tag.putBoolean("Open", isOpen());
    
        output263.store(tag);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input263) {
        super.readAdditionalSaveData(input263);
        net.minecraft.nbt.CompoundTag tag = com.nirncraft.platform.ValueTags.read(input263 , "Open");
        
        this.entityData.set(DATA_OPEN, tag.getBooleanOr("Open", false));
    }
}
