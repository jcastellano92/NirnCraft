package com.skycraft.mounts;

import com.skycraft.creatures.ai.CreatureAttackGoal;
import com.skycraft.creatures.entity.SkyBeast;
import com.skycraft.creatures.spec.CreatureSpec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A ridden beast ({@link MountEntities}): senche-tiger, giant wolf, indrik, woolly mammoth, Akaviri tiger, realm
 * grazer. Steered like a horse with a charged jump (the vanilla jump bar), galloped with the sprint key
 * ({@link MountStamina}); variant 0 bare, 1 saddled, 2 barded ({@link MountItems#BEAST_BARDING}). Only its owner (and
 * friends behind them) may ride; sneak-use opens its saddlebags. Unridden it waits where it was left, wanders a little
 * and defends itself and its owner; ridden, a fighting beast strikes at whatever its rider attacks
 * ({@link MountedCombat}).
 */
public class MountBeast extends SkyBeast implements PlayerRideableJumping {
    private static final net.minecraft.network.syncher.EntityDataAccessor<Float> JUMP_MULT =
            net.minecraft.network.syncher.SynchedEntityData.defineId(MountBeast.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
    private float jumpPending;
    private int assistCooldown;

    public MountBeast(EntityType<? extends MountBeast> type, Level level) {
        super(type, level);
    }

    @Override
    public CreatureSpec spec() {
        return MountEntities.spec(this.getType());
    }

    public MountKind kind() {
        MountKind k = MountEntities.kind(this.getType());
        return k != null ? k : MountKind.SENCHE;
    }

    @Override
    protected void registerGoals() {
        CreatureSpec s = spec();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        if (!s.attacks.isEmpty()) this.goalSelector.addGoal(3, new CreatureAttackGoal(this, s));
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.6, 0.0005f) {
            @Override
            public boolean canUse() {
                return !MountBeast.this.isVehicle() && super.canUse();
            }
        });
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8f));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        if (!s.attacks.isEmpty()) {
            this.targetSelector.addGoal(1, new HurtByTargetGoal(this) {
                @Override
                protected boolean canAttack(@Nullable LivingEntity target, net.minecraft.world.entity.ai.targeting.TargetingConditions conditions) {
                    return target != null && !Mounts.isFriend(MountBeast.this, target) && super.canAttack(target, conditions);
                }
            });
        }
    }

    // ------------------------------------------------------------------ riding

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (this.level().isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        return Mounts.interact(sp, this, player.getItemInHand(hand)) ? InteractionResult.CONSUME : InteractionResult.PASS;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return this.getPassengers().size() < kind().seats;
    }

    @Override
    @Nullable
    public LivingEntity getControllingPassenger() {
        if (getVariant() >= 1 && this.getFirstPassenger() instanceof Player p && Mounts.mayControl(p, this)) return p;
        return null;
    }

    @Override
    protected void tickRidden(Player player, Vec3 input) {
        super.tickRidden(player, input);
        this.setRot(player.getYRot(), player.getXRot() * 0.5f);
        this.yRotO = this.yBodyRot = this.yHeadRot = this.getYRot();
        this.setTarget(null);
        if (this.isLocalInstanceAuthoritative() && this.onGround()) {
            if (jumpPending > 0) {
                double v = jump() * jumpPending;
                Vec3 m = this.getDeltaMovement();
                this.setDeltaMovement(m.x, v, m.z);
                this.needsSync = true;
                if (input.z > 0) {
                    float sin = Mth.sin(this.getYRot() * Mth.DEG_TO_RAD), cos = Mth.cos(this.getYRot() * Mth.DEG_TO_RAD);
                    this.setDeltaMovement(this.getDeltaMovement().add(-0.4 * sin * jumpPending, 0, 0.4 * cos * jumpPending));
                }
                this.playSound(SoundEvents.HORSE_JUMP, 0.4f, 1.0f);
            }
            jumpPending = 0;
        }
    }

    /** Upward speed of a full jump (horse-like for the light beasts, a hop for the mammoth). */
    double jump() {
        // the kind's average, times this animal's Jump roll and growth (synced: the rider's client does the jumping)
        return MountProfile.baseJump(kind()) * this.entityData.get(JUMP_MULT);
    }

    /** Server side: the individual's jump multiplier ({@link MountGrowth#applyAttributes}). */
    void setJumpMult(float mult) {
        if (this.entityData.get(JUMP_MULT) != mult) this.entityData.set(JUMP_MULT, mult);
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(JUMP_MULT, 1f);
    }

    @Override
    protected Vec3 getRiddenInput(Player player, Vec3 input) {
        float strafe = player.xxa * 0.5f;
        float forward = player.zza;
        if (forward <= 0f) forward *= 0.25f;
        return new Vec3(strafe, 0.0, forward);
    }

    @Override
    protected float getRiddenSpeed(Player player) {
        return (float) this.getAttributeValue(Attributes.MOVEMENT_SPEED);
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, net.minecraft.world.entity.EntityDimensions dimensions, float scale) {
        return new Vec3(0, passengerRidingOffset263(), 0);
    }

    private double passengerRidingOffset263() {
        return this.getBbHeight() * switch (kind()) {
            case MAMMOTH -> 0.92;
            case INDRIK -> 0.68;
            case GIANT_WOLF -> 0.8;
            default -> 0.78;
        };
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction move) {
        super.positionRider(passenger, move);
        int i = this.getPassengers().indexOf(passenger);
        if (i > 0) {
            // the second seat sits behind the first
            float back = kind() == MountKind.MAMMOTH ? 1.1f : 0.6f;
            float sin = Mth.sin(this.yBodyRot * Mth.DEG_TO_RAD), cos = Mth.cos(this.yBodyRot * Mth.DEG_TO_RAD);
            move.accept(passenger, passenger.getX() + sin * back, passenger.getY(), passenger.getZ() - cos * back);
        }
        if (passenger instanceof LivingEntity living) living.yBodyRot = this.yBodyRot;
    }

    @Override
    public void onPlayerJump(int power) {
        if (power <= 0) return;
        jumpPending = power >= 90 ? 1.0f : 0.4f + 0.4f * power / 90f;
    }

    @Override
    public boolean canJump() {
        return getVariant() >= 1;
    }

    @Override
    public void handleStartJump(int power) {
    }

    @Override
    public void handleStopJump() {
    }

    @Override
    public boolean causeFallDamage(double distance, float multiplier, DamageSource source) {
        // beasts land like horses: the first few blocks are free, for them and the rider
        double d = distance - 3f;
        if (d <= 0) return false;
        boolean hurt = super.causeFallDamage(d, multiplier * 0.5f, source);
        for (Entity e : this.getIndirectPassengers()) e.causeFallDamage(d, multiplier * 0.5f, source);
        return hurt;
    }

    // ------------------------------------------------------------------ fighting

    @Override
    public void setTarget(@Nullable LivingEntity target) {
        if (target != null && (Mounts.isFriend(this, target) || this.isVehicle() && this.hasPassenger(target))) return;
        super.setTarget(target);
    }

    @Override
    public boolean hurtServer(net.minecraft.server.level.ServerLevel serverLevel263, DamageSource source, float amount) {
        Entity by = source.getEntity();
        if (by != null && (this.hasPassenger(by) || by instanceof Player p && Mounts.isOwner(p, this))) return false;
        return super.hurtServer(serverLevel263, source, amount);
    }

    /** Ready to strike beside its rider; consumes the cooldown. */
    boolean readyToAssist() {
        if (assistCooldown > 0 || spec().attacks.isEmpty()) return false;
        assistCooldown = kind() == MountKind.MAMMOTH ? 40 : 24;
        return true;
    }

    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel serverLevel263) {
        super.customServerAiStep(serverLevel263);
        if (assistCooldown > 0) assistCooldown--;
    }

    @Override
    public boolean isFood(ItemStack stack) {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean canBeLeashed() { return !this.isVehicle(); }

    @Override
    public boolean canHaveALeashAttachedTo(Entity holder) {
        return super.canHaveALeashAttachedTo(holder) && (Mounts.ownerOf(this) == null || holder instanceof Player player && Mounts.isOwner(player, this));
    }

    @Nullable
    public UUID owner() {
        return Mounts.ownerOf(this);
    }
}
