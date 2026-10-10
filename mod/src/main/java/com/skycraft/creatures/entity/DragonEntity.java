package com.skycraft.creatures.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import com.skycraft.core.SkyData;
import com.skycraft.magic.MagicDamage;

/**
 * {@code skycraft:dragon}: flies, circles its prey high above, swoops in breathing fire (frost for Frost Dragons),
 * lands to bite and tail-sweep, then takes off again. Dragonrend (magic module) forces it to the ground:
 * {@code getPersistentData().getLong("skycraft_dragonrend_until") > gameTime}.
 *
 * <p>Variants by level: Dragon, Blood Dragon, Frost Dragon, Elder Dragon, Ancient Dragon. On death it burns and
 * falls for a few seconds; the magic module absorbs its soul (LivingDeathEvent, {@code #skycraft:dragons}) and the
 * creatures module leaves its body to loot.</p>
 */
public class DragonEntity extends Monster {
    public static final String DRAGONREND_KEY = "skycraft_dragonrend_until";
    public static final String[] VARIANTS = {"dragon", "blood", "frost", "elder", "ancient"};
    private static final float[] TIER_HEALTH = {1.0f, 1.35f, 1.7f, 2.2f, 2.8f};
    private static final float[] TIER_DAMAGE = {1.0f, 1.25f, 1.5f, 1.8f, 2.1f};
    public static final double BASE_HEALTH = 300.0;
    public static final double BASE_DAMAGE = 12.0;
    /** Game ticks without a player within 256 blocks before a wild dragon leaves. */
    private static final long ABSENCE_LIMIT = 1200;
    /** Flying: keep at least this far above the terrain ahead. */
    private static final double TERRAIN_MARGIN = 3.0;
    private static final double[] LOOKAHEAD = {0.0, 3.0, 6.0, 10.0, 15.0};

    private static final EntityDataAccessor<Integer> VARIANT = SynchedEntityData.defineId(DragonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> FLYING = SynchedEntityData.defineId(DragonEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> BREATH_TARGET = SynchedEntityData.defineId(DragonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> BREATHING = SynchedEntityData.defineId(DragonEntity.class, EntityDataSerializers.BOOLEAN);

    private enum Phase { CIRCLE, SWOOP, LAND, GROUND, TAKEOFF }

    private final ServerBossEvent bossEvent = new ServerBossEvent(Component.translatable("entity.skycraft.dragon"),
            BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);

    private Phase phase = Phase.CIRCLE;
    private int phaseTicks;
    private int swoops;
    private int swoopsBeforeLanding = 2;
    private double circleAngle;
    private int circleDir = 1;
    private boolean breathedThisSwoop;
    @Nullable
    private Vec3 anchor;
    @Nullable
    private Vec3 landSpot;
    private int breathTicks;
    private int biteCooldown;
    private int tailCooldown;
    private int groundBreathCooldown = 80;
    /** Game time a player was last within 256 blocks (game time also passes while the dragon's chunk is unloaded). */
    private long lastSeenTime = -1;
    /** Just loaded from disk: check how long nobody was around before doing anything else. */
    private boolean checkAbsence;
    private int stuckTicks;
    private Vec3 lastGroundPos = Vec3.ZERO;
    private boolean initialized;
    private int deathLandedAt = -1;
    /** The lootable body prepared by the corpse handler; spawned when the death animation ends. */
    @Nullable
    private Entity pendingCorpse;
    /** Client-side only: this instance is a corpse dummy (wings folded, lying still). */
    private boolean corpsePose;

    /** Hittable head, neck and wings (the body box is only the torso). */
    private final DragonPart head;
    private final DragonPart neck;
    private final DragonPart leftWing;
    private final DragonPart rightWing;
    private final DragonPart[] parts;

    public DragonEntity(EntityType<? extends DragonEntity> type, Level level) {
        super(type, level);
        this.xpReward = 500;
        this.lookControl = new DragonLookControl(this);
        this.setMaxUpStep(1.5f);
        this.noCulling = true;
        this.head = new DragonPart(this, "head", 2.0f, 2.0f, 1.25f);
        this.neck = new DragonPart(this, "neck", 1.6f, 1.6f, 1.0f);
        this.leftWing = new DragonPart(this, "left_wing", 3.5f, 1.0f, 0.6f);
        this.rightWing = new DragonPart(this, "right_wing", 3.5f, 1.0f, 0.6f);
        this.parts = new DragonPart[]{head, neck, leftWing, rightWing};
        // parts take the ids right after the dragon's (like the ender dragon's), the same on server and client
        this.setId(ENTITY_COUNTER.getAndAdd(parts.length + 1) + 1);
    }

    @Override
    public void setId(int id) {
        super.setId(id);
        if (parts == null) return; // Entity's constructor sets the first id before the parts exist
        for (int i = 0; i < parts.length; i++) parts[i].setId(id + i + 1);
    }

    @Override
    public boolean isMultipartEntity() {
        return true;
    }

    @Override
    public net.minecraftforge.entity.PartEntity<?>[] getParts() {
        return parts;
    }

    /** Damage taken by one of the parts (already scaled by where it hit). */
    public boolean hurtFromPart(DragonPart part, DamageSource source, float amount) {
        if (this.isRemoved() || this.isDeadOrDying()) return false;
        return this.hurt(source, amount);
    }

    /** Places the parts around the body: head and neck ahead (higher when standing), wings spread while flying. */
    private void updateParts() {
        Vec3 fwd = Vec3.directionFromRotation(0, this.yBodyRot);
        Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
        boolean flying = isFlying();
        Vec3 base = this.position();
        placePart(head, mouthPos().subtract(fwd.scale(0.8)));
        placePart(neck, base.add(fwd.scale(3.2)).add(0, flying ? 1.3 : 2.4, 0));
        double span = flying ? 4.6 : 2.2;
        double wingY = flying ? 1.4 : 1.6;
        placePart(leftWing, base.add(right.scale(-span)).add(0, wingY, 0));
        placePart(rightWing, base.add(right.scale(span)).add(0, wingY, 0));
    }

    /** Moves a part so that its box is centered on {@code center}. */
    private static void placePart(DragonPart part, Vec3 center) {
        part.setOldPosAndRot();
        part.xOld = part.getX();
        part.yOld = part.getY();
        part.zOld = part.getZ();
        part.setPos(center.x, center.y - part.getBbHeight() / 2.0, center.z);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, BASE_HEALTH)
                .add(Attributes.ATTACK_DAMAGE, BASE_DAMAGE)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 128.0)
                .add(Attributes.ARMOR, 15.0)
                .add(Attributes.ARMOR_TOUGHNESS, 6.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(VARIANT, 0);
        this.entityData.define(FLYING, false);
        this.entityData.define(BREATH_TARGET, -1);
        this.entityData.define(BREATHING, false);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new DragonTargetGoal<>(this, Player.class, 10));
        this.targetSelector.addGoal(3, new DragonTargetGoal<>(this, GuardEntity.class, 20));
        this.targetSelector.addGoal(4, new DragonTargetGoal<>(this, AbstractVillager.class, 40));
    }

    /**
     * The dragon circles 20-30 blocks above its prey: looks for targets in a tall box (64 blocks around, 48 up and
     * down) instead of vanilla's flat one, and only ones it can see.
     */
    private static class DragonTargetGoal<T extends LivingEntity> extends NearestAttackableTargetGoal<T> {
        DragonTargetGoal(DragonEntity dragon, Class<T> type, int interval) {
            super(dragon, type, interval, true, false, null);
        }

        @Override
        protected AABB getTargetSearchArea(double distance) {
            return this.mob.getBoundingBox().inflate(64.0, 48.0, 64.0);
        }

        @Override
        protected void findTarget() {
            // vanilla checks players by distance only; use the box for them too
            this.target = this.mob.level().getNearestEntity(this.mob.level().getEntitiesOfClass(this.targetType,
                    this.getTargetSearchArea(0), e -> true), this.targetConditions, this.mob, this.mob.getX(), this.mob.getEyeY(), this.mob.getZ());
        }
    }

    // ------------------------------------------------------------------ state

    public int getVariant() {
        return Mth.clamp(this.entityData.get(VARIANT), 0, VARIANTS.length - 1);
    }

    public void setVariant(int variant) {
        this.entityData.set(VARIANT, Mth.clamp(variant, 0, VARIANTS.length - 1));
    }

    public boolean isFrost() {
        return getVariant() == 2;
    }

    public boolean isFlying() {
        return this.entityData.get(FLYING);
    }

    public void setFlying(boolean flying) {
        this.entityData.set(FLYING, flying);
        this.setNoGravity(flying);
        if (!flying) enableCollision();
    }

    public boolean isBreathing() {
        return this.entityData.get(BREATHING);
    }

    private void setBreathing(boolean breathing, int targetId) {
        this.entityData.set(BREATHING, breathing);
        this.entityData.set(BREATH_TARGET, targetId);
    }

    public boolean isCorpsePose() {
        return corpsePose;
    }

    public void setCorpsePose(boolean corpse) {
        this.corpsePose = corpse;
    }

    public void setPendingCorpse(@Nullable Entity corpse) {
        this.pendingCorpse = corpse;
    }

    /** Dragonrend (magic): the dragon can't fly until this game time. */
    public boolean isDragonrended() {
        return this.getPersistentData().getLong(DRAGONREND_KEY) > this.level().getGameTime();
    }

    @Override
    public boolean fireImmune() {
        return !isFrost() || super.fireImmune();
    }

    @Override
    public boolean canFreeze() {
        return false;
    }

    @Override
    public boolean canBreatheUnderwater() {
        return true;
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        return this.getBoundingBox().inflate(8.0, 4.0, 8.0);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Frost dragons are weak to fire, like in Skyrim.
        if (isFrost() && source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)) amount *= 1.25f;
        return super.hurt(source, amount);
    }

    // ------------------------------------------------------------------ spawning & tiers

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData data, @Nullable CompoundTag tag) {
        data = super.finalizeSpawn(level, difficulty, reason, data, tag);
        if (reason == MobSpawnType.SPAWN_EGG || reason == MobSpawnType.COMMAND || reason == MobSpawnType.EVENT) setFlying(true);
        return data;
    }

    /** Chooses the dragon type from the nearest player's level: Dragon, Blood, Frost, Elder, Ancient. */
    private void initDragon() {
        initialized = true;
        Player nearest = this.level().getNearestPlayer(this, 160);
        int level = nearest != null ? SkyData.get(nearest).getLevel() : 1;
        int tier = level >= 44 ? 4 : level >= 32 ? 3 : level >= 22 ? 2 : level >= 12 ? 1 : 0;
        BlockPos pos = this.blockPosition();
        if (tier < 2 && this.level().getBiome(pos).value().coldEnoughToSnow(pos) && this.getRandom().nextFloat() < 0.5f) tier = 2;
        applyVariant(tier);
        this.getPersistentData().putInt("skycraft_level", level);
    }

    public void applyVariant(int tier) {
        setVariant(tier);
        AttributeInstance health = this.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) health.setBaseValue(BASE_HEALTH * TIER_HEALTH[getVariant()]);
        AttributeInstance damage = this.getAttribute(Attributes.ATTACK_DAMAGE);
        if (damage != null) damage.setBaseValue(BASE_DAMAGE * TIER_DAMAGE[getVariant()]);
        this.setHealth(this.getMaxHealth());
        if (getVariant() > 0 && !this.hasCustomName()) {
            this.setCustomName(Component.translatable("entity.skycraft.dragon." + VARIANTS[getVariant()]));
        }
        this.bossEvent.setName(this.getDisplayName());
    }

    private float tierDamage() {
        return TIER_DAMAGE[getVariant()];
    }

    // ------------------------------------------------------------------ boss bar

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        if (player.distanceToSqr(this) <= 64.0 * 64.0) {
            this.bossEvent.addPlayer(player);
        }
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        this.bossEvent.removePlayer(player);
    }

    @Override
    public void setCustomName(@Nullable Component name) {
        super.setCustomName(name);
        this.bossEvent.setName(this.getDisplayName());
    }

    // ------------------------------------------------------------------ movement

    @Override
    public void travel(Vec3 input) {
        if (isFlying() && this.isAlive()) {
            if (!this.level().isClientSide) this.move(MoverType.SELF, this.getDeltaMovement());
            this.fallDistance = 0;
        } else {
            super.travel(input);
        }
    }

    private double groundY(double x, double z) {
        return this.level().getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
    }

    private void steerTowards(Vec3 dest, double speed, double accel) {
        Vec3 to = dest.subtract(this.position());
        double dist = to.length();
        Vec3 desired = dist < 1.0E-4 ? Vec3.ZERO : to.scale(Math.min(speed, dist * 0.2) / dist);
        Vec3 vel = this.getDeltaMovement();
        vel = vel.add(desired.subtract(vel).scale(accel));
        if (isFlying()) vel = avoidTerrain(vel);
        this.setDeltaMovement(vel);
        faceVelocity(vel);
    }

    /**
     * Flight passes through blocks (no physics, for smooth turns), so it must not fly into hills: samples the terrain
     * along the flight path and climbs (slowing down in front of a wall) to stay {@value #TERRAIN_MARGIN} blocks above it.
     */
    private Vec3 avoidTerrain(Vec3 vel) {
        Vec3 flat = new Vec3(vel.x, 0, vel.z);
        double h = flat.length();
        Vec3 dir = h < 1.0E-3 ? Vec3.directionFromRotation(0, this.getYRot()) : flat.scale(1.0 / h);
        double need = 0;
        double nearNeed = 0;
        for (double d : LOOKAHEAD) {
            double ground = groundY(this.getX() + dir.x * d, this.getZ() + dir.z * d);
            double climb = ground + TERRAIN_MARGIN - this.getY();
            if (climb <= 0) continue;
            need = Math.max(need, climb);
            if (d <= 6.0) nearNeed = Math.max(nearNeed, climb);
        }
        if (need <= 0) return vel;
        double vy = Math.max(vel.y, Math.min(0.9, 0.15 + need * 0.12));
        double slow = nearNeed > 4.0 ? 0.6 : 1.0; // a cliff right ahead: slow down while climbing over it
        return new Vec3(vel.x * slow, vy, vel.z * slow);
    }

    /** Flying with no physics when well above the ground, otherwise with collisions (once out of the terrain). */
    private void flightPhysics(double clearance) {
        if (this.getY() > groundY(this.getX(), this.getZ()) + clearance) this.noPhysics = true;
        else enableCollision();
    }

    /**
     * Turns collisions back on, but never while the body is inside blocks (it would be stuck there for good): until it
     * is out, it keeps passing through and rises.
     */
    private void enableCollision() {
        if (!this.noPhysics) return;
        if (this.level().noCollision(this, this.getBoundingBox())) {
            this.noPhysics = false;
            return;
        }
        Vec3 v = this.getDeltaMovement();
        this.setDeltaMovement(v.x * 0.5, Math.max(v.y, 0.4), v.z * 0.5);
    }

    private void faceVelocity(Vec3 vel) {
        double h = vel.horizontalDistance();
        if (h > 0.05) {
            float yaw = (float) (Mth.atan2(vel.z, vel.x) * Mth.RAD_TO_DEG) - 90.0f;
            this.setYRot(Mth.approachDegrees(this.getYRot(), yaw, 7.0f));
        }
        float pitch = (float) (-(Mth.atan2(vel.y, Math.max(h, 0.05)) * Mth.RAD_TO_DEG));
        this.setXRot(Mth.approach(this.getXRot(), Mth.clamp(pitch, -35.0f, 35.0f), 3.0f));
        this.yBodyRot = this.getYRot();
        this.yHeadRot = this.getYRot();
    }

    private void setPhase(Phase next) {
        this.phase = next;
        this.phaseTicks = 0;
        if (next == Phase.CIRCLE) {
            Vec3 center = circleCenter();
            circleAngle = Math.atan2(this.getZ() - center.z, this.getX() - center.x);
            if (this.getRandom().nextInt(3) == 0) circleDir = -circleDir;
        } else if (next == Phase.SWOOP) {
            breathedThisSwoop = false;
        } else if (next == Phase.LAND) {
            landSpot = null;
        } else if (next == Phase.GROUND) {
            swoops = 0;
            swoopsBeforeLanding = 2 + this.getRandom().nextInt(2);
            lastGroundPos = this.position();
            stuckTicks = 0;
        }
    }

    private Vec3 circleCenter() {
        LivingEntity target = this.getTarget();
        if (target != null) return target.position();
        if (anchor == null) anchor = this.position();
        return anchor;
    }

    // ------------------------------------------------------------------ AI

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!initialized) initDragon();
        if (anchor == null) anchor = this.position();
        this.bossEvent.setProgress(this.getHealth() / this.getMaxHealth());
        if (this.tickCount % 20 == 0 && this.level() instanceof ServerLevel sl) {
            double rangeSq = 64.0 * 64.0;
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
        if (tickDespawn()) return;

        LivingEntity target = this.getTarget();
        if (target != null && (!target.isAlive() || target.level() != this.level()
                || target instanceof Player p && (p.isCreative() || p.isSpectator()))) {
            this.setTarget(null);
            target = null;
        }
        phaseTicks++;
        if (biteCooldown > 0) biteCooldown--;
        if (tailCooldown > 0) tailCooldown--;
        if (groundBreathCooldown > 0) groundBreathCooldown--;

        boolean rended = isDragonrended();
        if (rended && isFlying() && phase != Phase.LAND) setPhase(Phase.LAND);
        if (!isFlying() && phase != Phase.GROUND && phase != Phase.TAKEOFF) setPhase(Phase.GROUND);
        if (isFlying() && phase == Phase.GROUND) setPhase(Phase.CIRCLE);

        switch (phase) {
            case CIRCLE -> tickCircle(target);
            case SWOOP -> tickSwoop(target);
            case LAND -> tickLand(target, rended);
            case GROUND -> tickGround(target, rended);
            case TAKEOFF -> tickTakeoff();
        }
        tickBreath(target);
    }

    /**
     * Wild dragons (random attacks, spawn eggs: anything not made persistent by a quest, a lair or a name tag) leave
     * when nobody has been within 256 blocks for a minute. Counted in game time, so a dragon that was outrun and sat in
     * an unloaded chunk is gone as soon as it loads again.
     */
    private boolean tickDespawn() {
        long now = this.level().getGameTime();
        if (this.isPersistenceRequired() || lastSeenTime < 0) {
            lastSeenTime = now;
            checkAbsence = false;
            return false;
        }
        if (checkAbsence) {
            checkAbsence = false;
            if (now - lastSeenTime > ABSENCE_LIMIT) {
                this.discard();
                return true;
            }
        }
        if (this.tickCount % 20 != 0) return false;
        if (this.level().getNearestPlayer(this, 256) != null) {
            lastSeenTime = now;
        } else if (now - lastSeenTime > ABSENCE_LIMIT) {
            this.discard();
            return true;
        }
        return false;
    }

    private void tickCircle(@Nullable LivingEntity target) {
        Vec3 center = circleCenter();
        double radius = target != null ? 26.0 : 40.0;
        double altitude = target != null ? 20.0 + 6.0 * Math.sin(this.tickCount * 0.02) : 28.0;
        circleAngle += (target != null ? 0.035 : 0.02) * circleDir;
        double gx = center.x + Math.cos(circleAngle) * radius;
        double gz = center.z + Math.sin(circleAngle) * radius;
        double gy = Math.max(center.y + altitude, groundY(gx, gz) + 14.0);
        steerTowards(new Vec3(gx, gy, gz), 0.95, 0.06);
        flightPhysics(5.0);

        if (target != null) {
            if (phaseTicks > 90 + this.getRandom().nextInt(80)) setPhase(swoops >= swoopsBeforeLanding ? Phase.LAND : Phase.SWOOP);
        } else if (phaseTicks > 900 && this.getRandom().nextInt(300) == 0) {
            setPhase(Phase.LAND); // perch for a while
        }
    }

    private void tickSwoop(@Nullable LivingEntity target) {
        if (target == null) {
            setPhase(Phase.CIRCLE);
            return;
        }
        Vec3 aim = target.position().add(0, 7.0, 0);
        double hd = Math.sqrt(Mth.square(target.getX() - this.getX()) + Mth.square(target.getZ() - this.getZ()));
        if (hd < 14) {
            // pass over the target, keep going straight
            Vec3 vel = this.getDeltaMovement();
            Vec3 flat = new Vec3(vel.x, 0, vel.z);
            if (flat.lengthSqr() > 0.01) aim = this.position().add(flat.normalize().scale(20)).add(0, 2, 0);
        }
        aim = new Vec3(aim.x, Math.max(aim.y, groundY(aim.x, aim.z) + 5.0), aim.z);
        steerTowards(aim, 1.15, 0.08);
        flightPhysics(4.0);

        double dist = this.distanceTo(target);
        Vec3 fwd = Vec3.directionFromRotation(0, this.getYRot());
        Vec3 toTarget = target.position().subtract(this.position()).normalize();
        if (!breathedThisSwoop && breathTicks <= 0 && dist < 30 && fwd.dot(toTarget) > 0.55) {
            startBreath(target, 36);
            breathedThisSwoop = true;
        }
        if (phaseTicks > 150 || (hd < 6 && phaseTicks > 30)) {
            swoops++;
            setPhase(Phase.CIRCLE);
        }
    }

    private void chooseLandSpot(@Nullable LivingEntity target, boolean forced) {
        RandomSource r = this.getRandom();
        Vec3 center = forced || target == null ? this.position() : target.position();
        for (int i = 0; i < 10; i++) {
            double dist = forced ? r.nextDouble() * 6 : 9 + r.nextDouble() * 5;
            double angle = r.nextDouble() * Math.PI * 2;
            double x = center.x + Math.cos(angle) * dist;
            double z = center.z + Math.sin(angle) * dist;
            int y = this.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
            BlockPos below = BlockPos.containing(x, y - 1, z);
            if (!this.level().getFluidState(below).isEmpty() && i < 9) continue;
            landSpot = new Vec3(x, y, z);
            return;
        }
        landSpot = new Vec3(center.x, groundY(center.x, center.z), center.z);
    }

    private void tickLand(@Nullable LivingEntity target, boolean forced) {
        if (landSpot == null) chooseLandSpot(target, forced);
        Vec3 spot = landSpot;
        double hd = Math.sqrt(Mth.square(spot.x - this.getX()) + Mth.square(spot.z - this.getZ()));
        if (hd > 4.0 && !(forced && phaseTicks > 20)) {
            double gy = Math.max(spot.y + 10.0, groundY(this.getX(), this.getZ()) + 6.0);
            steerTowards(new Vec3(spot.x, gy, spot.z), 0.85, 0.07);
            flightPhysics(6.0);
        } else {
            // descend (once out of any terrain it flew through: until then enableCollision lifts it)
            enableCollision();
            Vec3 vel = this.getDeltaMovement();
            if (!this.noPhysics) this.setDeltaMovement(vel.x * 0.85, forced ? -0.7 : -0.35, vel.z * 0.85);
            if (target != null) this.setYRot(Mth.approachDegrees(this.getYRot(), yawTo(target), 5.0f));
            this.setXRot(Mth.approach(this.getXRot(), 0, 3.0f));
            this.yBodyRot = this.getYRot();
            this.yHeadRot = this.getYRot();
            if (this.onGround() || this.verticalCollision) {
                setFlying(false);
                this.setDeltaMovement(Vec3.ZERO);
                anchor = this.position();
                this.playSound(com.skycraft.audio.AudioSounds.creature("dragon", "land"), 3.0f, 0.9f + this.getRandom().nextFloat() * 0.2f);
                if (this.level() instanceof ServerLevel server) {
                    server.sendParticles(ParticleTypes.CLOUD, this.getX(), this.getY() + 0.2, this.getZ(), 40, 2.0, 0.2, 2.0, 0.05);
                }
                if (target != null) roar();
                setPhase(Phase.GROUND);
                return;
            }
        }
        if (phaseTicks > 400) {
            // couldn't reach the spot: try straight down
            landSpot = new Vec3(this.getX(), groundY(this.getX(), this.getZ()), this.getZ());
            phaseTicks = 200;
        }
    }

    private float yawTo(Entity e) {
        return (float) (Mth.atan2(e.getZ() - this.getZ(), e.getX() - this.getX()) * Mth.RAD_TO_DEG) - 90.0f;
    }

    private boolean facing(Entity e, double threshold) {
        Vec3 fwd = Vec3.directionFromRotation(0, this.yBodyRot);
        Vec3 to = new Vec3(e.getX() - this.getX(), 0, e.getZ() - this.getZ());
        if (to.lengthSqr() < 1.0E-4) return true;
        return fwd.dot(to.normalize()) > threshold;
    }

    private void tickGround(@Nullable LivingEntity target, boolean rended) {
        enableCollision();
        if (this.isNoGravity()) this.setNoGravity(false);
        if (target == null) {
            if (phaseTicks % 120 == 0 && this.getRandom().nextBoolean()) {
                Vec3 base = anchor != null ? anchor : this.position();
                this.getMoveControl().setWantedPosition(base.x + this.getRandom().nextInt(17) - 8, base.y,
                        base.z + this.getRandom().nextInt(17) - 8, 0.6);
            }
            if (!rended && phaseTicks > 500 + this.getRandom().nextInt(400)) setPhase(Phase.TAKEOFF);
            return;
        }
        this.getLookControl().setLookAt(target, 20.0f, 30.0f);
        double dist = this.distanceTo(target);
        double reach = 3.6 + target.getBbWidth();

        if (breathTicks > 0) {
            this.getNavigation().stop();
            this.setYRot(Mth.approachDegrees(this.getYRot(), yawTo(target), 6.0f));
            this.yBodyRot = this.getYRot();
        } else if (dist < reach) {
            this.getNavigation().stop();
            this.setYRot(Mth.approachDegrees(this.getYRot(), yawTo(target), 10.0f));
            this.yBodyRot = this.getYRot();
            if (biteCooldown <= 0 && facing(target, 0.5)) {
                biteCooldown = 25;
                this.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                this.doHurtTarget(target);
                this.playSound(com.skycraft.audio.AudioSounds.creature("dragon", "growl"), 1.5f, 1.0f + this.getRandom().nextFloat() * 0.15f);
            }
        } else {
            this.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), 1.0);
        }

        // Tail sweep against anything at our sides or behind.
        if (tailCooldown <= 0 && dist < 7.0 && !facing(target, 0.4)) {
            tailCooldown = 60;
            tailSweep();
        }
        // Breath on the ground.
        if (breathTicks <= 0 && groundBreathCooldown <= 0 && dist > 5 && dist < 18 && facing(target, 0.7) && this.hasLineOfSight(target)) {
            startBreath(target, 45);
            groundBreathCooldown = 140 + this.getRandom().nextInt(80);
        }

        // Stuck detection: if we can't make progress towards a far target, fly.
        if (phaseTicks % 40 == 0) {
            if (dist > reach + 1 && this.position().distanceToSqr(lastGroundPos) < 1.0) stuckTicks += 40;
            else stuckTicks = 0;
            lastGroundPos = this.position();
        }
        if (!rended && (phaseTicks > 320 + this.getRandom().nextInt(120) || dist > 40 || stuckTicks >= 120)) {
            setPhase(Phase.TAKEOFF);
        }
    }

    private void tailSweep() {
        Vec3 fwd = Vec3.directionFromRotation(0, this.yBodyRot);
        this.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 2.0f, 0.5f);
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(5.0, 1.0, 5.0),
                e -> e != this && e.isAlive() && !(e instanceof DragonEntity))) {
            if (e instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
            Vec3 to = new Vec3(e.getX() - this.getX(), 0, e.getZ() - this.getZ());
            if (to.lengthSqr() < 1.0E-4) continue;
            if (fwd.dot(to.normalize()) > 0.4) continue; // in front: bites handle those
            e.hurt(this.damageSources().mobAttack(this), 6.0f * tierDamage());
            Vec3 push = to.normalize().scale(1.6);
            e.setDeltaMovement(e.getDeltaMovement().add(push.x, 0.5, push.z));
            e.hurtMarked = true;
        }
    }

    private void tickTakeoff() {
        if (phaseTicks == 1) {
            setFlying(true);
            roar();
        }
        Vec3 fwd = Vec3.directionFromRotation(0, this.getYRot());
        Vec3 vel = this.getDeltaMovement();
        this.setDeltaMovement(vel.x * 0.9 + fwd.x * 0.04, Math.min(0.6, vel.y + 0.08), vel.z * 0.9 + fwd.z * 0.04);
        this.setXRot(Mth.approach(this.getXRot(), -25.0f, 3.0f));
        this.yBodyRot = this.getYRot();
        this.yHeadRot = this.getYRot();
        if (phaseTicks > 30 || this.getY() > groundY(this.getX(), this.getZ()) + 10.0) {
            this.noPhysics = true;
            setPhase(Phase.CIRCLE);
        }
    }

    public void roar() {
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(), com.skycraft.audio.AudioSounds.creature("dragon", "roar"), SoundSource.HOSTILE, 6.0f,
                0.9f + this.getRandom().nextFloat() * 0.15f);
    }

    // ------------------------------------------------------------------ breath

    private void startBreath(LivingEntity target, int ticks) {
        breathTicks = ticks;
        setBreathing(true, target.getId());
        this.playSound(isFrost() ? com.skycraft.audio.AudioSounds.creature("dragon", "breath_frost") : com.skycraft.audio.AudioSounds.creature("dragon", "breath_fire"), 3.0f, 1.0f);
        // the dragon shouts its breath (Yol Toor Shul / Fo Krah Diin) in a deeper voice
        this.playSound(com.skycraft.audio.AudioSounds.shout(isFrost() ? com.skycraft.magic.shout.Shout.FROST_BREATH : com.skycraft.magic.shout.Shout.FIRE_BREATH, 3),
                4.0f, 0.78f);
    }

    /** Where fire comes out: the head sits ~5 blocks ahead, higher when standing. */
    public Vec3 mouthPos() {
        Vec3 fwd = Vec3.directionFromRotation(0, this.yBodyRot);
        return isFlying() ? this.position().add(fwd.scale(6.0)).add(0, 1.3, 0) : this.position().add(fwd.scale(5.0)).add(0, 3.0, 0);
    }

    /** Breath direction: towards the breath target when it's in front, otherwise forward and down. */
    public Vec3 breathDir(@Nullable Entity target) {
        Vec3 fwd = Vec3.directionFromRotation(0, this.yBodyRot);
        Vec3 mouth = mouthPos();
        if (target != null) {
            Vec3 to = target.getBoundingBox().getCenter().subtract(mouth);
            if (to.lengthSqr() > 1.0E-4) {
                Vec3 dir = to.normalize();
                if (new Vec3(dir.x, 0, dir.z).dot(fwd) > 0.1 || Math.abs(dir.y) > 0.8) return dir;
            }
        }
        return fwd.add(0, isFlying() ? -0.6 : -0.3, 0).normalize();
    }

    private void tickBreath(@Nullable LivingEntity target) {
        if (breathTicks <= 0) {
            if (isBreathing()) setBreathing(false, -1);
            return;
        }
        breathTicks--;
        Entity breathTarget = this.level().getEntity(this.entityData.get(BREATH_TARGET));
        if (breathTarget == null) breathTarget = target;
        Vec3 mouth = mouthPos();
        Vec3 dir = breathDir(breathTarget);
        if (breathTicks % 5 == 0) breathDamage(mouth, dir);
        if (breathTicks % 10 == 0) {
            this.playSound(isFrost() ? SoundEvents.POWDER_SNOW_BREAK : SoundEvents.BLAZE_SHOOT, 2.0f, 0.5f);
        }
        if (breathTicks <= 0) setBreathing(false, -1);
    }

    private void breathDamage(Vec3 mouth, Vec3 dir) {
        double range = 22.0;
        AABB box = new AABB(mouth, mouth.add(dir.scale(range))).inflate(5.0);
        float damage = 2.5f * tierDamage();
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, box, e -> e != this && e.isAlive() && !(e instanceof DragonEntity))) {
            if (e instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
            Vec3 to = e.getBoundingBox().getCenter().subtract(mouth);
            double along = to.dot(dir);
            if (along < 0 || along > range) continue;
            double perp = to.subtract(dir.scale(along)).length();
            if (perp > 1.2 + along * 0.28) continue;
            // Breath is elemental, like a spell: fire/frost spell damage types (is_fire / is_freezing, ignore armor),
            // so Fire Resistance, wards, magic resistance and Spellbreaker all work against it.
            if (isFrost()) {
                e.hurt(MagicDamage.source(this.level(), MagicDamage.FROST, null, this), damage);
                e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 1), this);
                if (e.canFreeze()) e.setTicksFrozen(Math.min(e.getTicksRequiredToFreeze() + 80, e.getTicksFrozen() + 25));
            } else {
                if (e.hurt(MagicDamage.source(this.level(), MagicDamage.FIRE, null, this), damage)) e.setSecondsOnFire(5);
            }
        }
    }

    // ------------------------------------------------------------------ client effects

    @Override
    public void aiStep() {
        super.aiStep();
        if (!corpsePose) updateParts();
        if (!this.level().isClientSide || corpsePose) return;
        if (isBreathing()) spawnBreathParticles();
        if (isFlying() && this.isAlive()) {
            float cycle = Mth.cos(this.tickCount * 0.3f);
            float prev = Mth.cos((this.tickCount - 1) * 0.3f);
            if (cycle < -0.95f && prev >= -0.95f) {
                this.level().playLocalSound(this.getX(), this.getY(), this.getZ(), com.skycraft.audio.AudioSounds.creature("dragon", "wings"), SoundSource.HOSTILE,
                        2.5f, 0.9f + this.getRandom().nextFloat() * 0.2f, false);
            }
        }
    }

    private void spawnBreathParticles() {
        Entity target = this.level().getEntity(this.entityData.get(BREATH_TARGET));
        Vec3 mouth = mouthPos();
        Vec3 dir = breathDir(target);
        RandomSource r = this.getRandom();
        for (int i = 0; i < 8; i++) {
            double speed = 0.8 + r.nextDouble() * 0.6;
            double vx = dir.x * speed + (r.nextDouble() - 0.5) * 0.25;
            double vy = dir.y * speed + (r.nextDouble() - 0.5) * 0.25;
            double vz = dir.z * speed + (r.nextDouble() - 0.5) * 0.25;
            if (isFrost()) {
                this.level().addParticle(i % 3 == 0 ? ParticleTypes.CLOUD : ParticleTypes.SNOWFLAKE, mouth.x, mouth.y, mouth.z, vx, vy, vz);
            } else {
                this.level().addParticle(i % 4 == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.FLAME, mouth.x, mouth.y, mouth.z, vx, vy, vz);
            }
        }
    }

    // ------------------------------------------------------------------ death

    /** Falls out of the sky burning, lies smoking for two seconds, then leaves its body. */
    @Override
    protected void tickDeath() {
        this.deathTime++;
        if (isFlying()) setFlying(false);
        if (!this.level().isClientSide) enableCollision();
        if (this.level().isClientSide) {
            RandomSource r = this.getRandom();
            for (int i = 0; i < 6; i++) {
                double x = this.getX() + (r.nextDouble() - 0.5) * 5.0;
                double y = this.getY() + r.nextDouble() * 2.5;
                double z = this.getZ() + (r.nextDouble() - 0.5) * 5.0;
                this.level().addParticle(i % 2 == 0 ? ParticleTypes.FLAME : ParticleTypes.LARGE_SMOKE, x, y, z, 0, 0.08, 0);
            }
            if (this.deathTime % 10 == 0) this.level().addParticle(ParticleTypes.LAVA, this.getX(), this.getY() + 1.5, this.getZ(), 0, 0, 0);
            return;
        }
        if (deathLandedAt < 0 && (this.onGround() || this.isInWater() || this.deathTime > 200)) deathLandedAt = this.deathTime;
        if (deathLandedAt >= 0 && this.deathTime - deathLandedAt >= 40) {
            if (pendingCorpse != null && this.level() instanceof ServerLevel server) {
                pendingCorpse.moveTo(this.getX(), this.getY(), this.getZ(), this.yBodyRot, 0);
                server.addFreshEntity(pendingCorpse);
                pendingCorpse = null;
            }
            if (this.level() instanceof ServerLevel server) {
                server.sendParticles(ParticleTypes.LARGE_SMOKE, this.getX(), this.getY() + 1, this.getZ(), 60, 2.5, 1.0, 2.5, 0.02);
            }
            this.remove(RemovalReason.KILLED);
        }
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return com.skycraft.audio.AudioSounds.creature("dragon", "growl");
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return com.skycraft.audio.AudioSounds.creature("dragon", "hurt");
    }

    @Override
    protected SoundEvent getDeathSound() {
        return com.skycraft.audio.AudioSounds.creature("dragon", "death");
    }

    @Override
    protected float getSoundVolume() {
        return 4.0f;
    }

    @Override
    public int getAmbientSoundInterval() {
        return 200;
    }

    // ------------------------------------------------------------------ save

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Variant", getVariant());
        tag.putBoolean("Flying", isFlying());
        tag.putBoolean("SkycraftInit", initialized);
        if (lastSeenTime >= 0) tag.putLong("LastSeen", lastSeenTime);
        if (anchor != null) {
            tag.putDouble("AnchorX", anchor.x);
            tag.putDouble("AnchorY", anchor.y);
            tag.putDouble("AnchorZ", anchor.z);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setVariant(tag.getInt("Variant"));
        setFlying(tag.getBoolean("Flying"));
        initialized = tag.getBoolean("SkycraftInit");
        if (tag.contains("LastSeen")) {
            lastSeenTime = tag.getLong("LastSeen");
            checkAbsence = true;
        }
        if (tag.contains("AnchorX")) anchor = new Vec3(tag.getDouble("AnchorX"), tag.getDouble("AnchorY"), tag.getDouble("AnchorZ"));
        this.bossEvent.setName(this.getDisplayName());
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        this.bossEvent.removeAllPlayers();
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        this.bossEvent.removeAllPlayers();
    }

    /** Leaves pitch alone while flying (the flight code steers the head); normal look control on the ground. */
    private static class DragonLookControl extends LookControl {
        private final DragonEntity dragon;

        DragonLookControl(DragonEntity dragon) {
            super(dragon);
            this.dragon = dragon;
        }

        @Override
        public void tick() {
            if (dragon.isFlying()) return;
            super.tick();
        }
    }
}
