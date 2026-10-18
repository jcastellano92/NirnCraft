package com.skycraft.creatures.entity;

import com.skycraft.core.Notifier;
import com.skycraft.creatures.GuardProfiles;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import org.jetbrains.annotations.Nullable;

/**
 * {@code skycraft:guard}: the watch of a settlement. A posted guard stands at a gate ({@link #setPost}), a patrolling one
 * walks a route through its settlement ({@link #setPatrol}); both fight monsters, say the famous lines and answer a
 * shout for the guard ({@link #summon}). Their look (skin, name, weapon) is the {@link GuardProfiles.Profile} of the
 * settlement they serve. Neutral to players: the crime module decides when a player becomes their target
 * ({@link #setTarget}); the melee goal pursues whatever target is set and the monster-target goal never replaces a
 * player target.
 */
public class GuardEntity extends PathfinderMob {
    /** Index into {@link GuardProfiles#all()}: which guard this is (skin on the client, name and gear on the server). */
    private static final EntityDataAccessor<Integer> PROFILE = SynchedEntityData.defineId(GuardEntity.class, EntityDataSerializers.INT);
    public static final int LINES = 20;
    /** The lines that are not about Skyrim's dragons, draugr and mead: what a guard outside Skyrim says. */
    private static final int[] GENERIC_LINES = {1, 3, 4, 5, 9, 10, 11, 13, 14, 16, 17, 18, 19};
    /** Ticks a guard keeps answering a shout (it is renewed while the player stays wanted nearby). */
    public static final int SUMMON_TICKS = 400;

    @Nullable
    private BlockPos home;
    /** A fixed post (a settlement gate's flank): the guard stands here facing {@link #postYaw} and returns after a fight. */
    @Nullable
    private BlockPos post;
    private float postYaw;
    /** The waypoints of a patrolling guard (market, inn, forge, gate...): it walks them in turn, stopping at each. */
    private long[] route = new long[0];
    private long patrolIdleUntil;
    private int routeIndex = -1;
    /** Where a shout for the guard sent it, and until when it keeps going there instead of walking back to its post. */
    @Nullable
    private BlockPos summonPos;
    private long summonUntil;
    private long nextLine;
    private boolean initialized;
    private boolean profileSet;

    public GuardEntity(EntityType<? extends GuardEntity> type, Level level) {
        super(type, level);
        this.xpReward = 5;
        if (this.getNavigation() instanceof GroundPathNavigation nav) nav.setCanOpenDoors(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 34.0)
                .add(Attributes.ATTACK_DAMAGE, 5.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .add(Attributes.ARMOR, 12.0)
                .add(Attributes.ARMOR_TOUGHNESS, 2.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.3);
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(PROFILE, 0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new SummonedGoal());
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.15D, true));
        this.goalSelector.addGoal(4, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(3, new HoldPostGoal());
        this.goalSelector.addGoal(5, new MoveTowardsRestrictionGoal(this, 0.8D) {
            @Override
            public boolean canUse() {
                return !GuardEntity.this.isSummoned() && super.canUse();
            }
        });
        this.goalSelector.addGoal(5, new PatrolGoal());
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.6D) {
            @Override
            public boolean canUse() {
                return GuardEntity.this.post == null && GuardEntity.this.route.length == 0 && !GuardEntity.this.isSummoned() && super.canUse();
            }
        });
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, GuardEntity.class).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Mob.class, 5, false, false,
                (e, targetLevel263) -> e instanceof Enemy && !(e instanceof Creeper) && !(e instanceof Enderman) && !(e instanceof GiantEntity)) {
            @Override
            public boolean canUse() {
                // never replace a player target set by the crime module
                return !(GuardEntity.this.getTarget() instanceof Player) && super.canUse();
            }
        });
    }

    // ------------------------------------------------------------------ profile & home

    /** Which guard this is: skin, name, gear and lines come from it. */
    public GuardProfiles.Profile getProfile() {
        return GuardProfiles.byIndex(this.entityData.get(PROFILE));
    }

    /** Index into {@link GuardProfiles#all()} (what the client renderer picks the skin by). */
    public int getProfileIndex() {
        return this.entityData.get(PROFILE);
    }

    /** Makes this guard that profile (before {@link #finalizeSpawn}: the name and gear follow it). */
    public void setProfile(GuardProfiles.Profile profile) {
        this.entityData.set(PROFILE, profile.index());
        this.profileSet = true;
    }

    @Nullable
    public BlockPos getHome() {
        return home;
    }

    /** Guards patrol within 32 blocks of their home (the village meeting point). */
    public void setHome(BlockPos pos) {
        this.home = pos.immutable();
        this.setHomeTo(this.home, 40);
    }

    /** Pins this guard to a post: it stands there facing {@code yaw}, never strolls, and walks back after a fight. */
    public void setPost(BlockPos pos, float yaw) {
        this.post = pos.immutable();
        this.postYaw = yaw;
        this.home = this.post;
        this.setHomeTo(this.post, 8);
    }

    @Nullable
    public BlockPos getPost() {
        return post;
    }

    /**
     * Makes this guard patrol: it walks the route's points in turn (stopping a while at each) and never strays more
     * than {@code radius} blocks from {@code center}.
     */
    public void setPatrol(BlockPos center, int radius, java.util.List<BlockPos> waypoints) {
        this.post = null;
        this.home = center.immutable();
        this.setHomeTo(this.home, Math.max(16, radius));
        this.route = new long[waypoints.size()];
        for (int i = 0; i < route.length; i++) route[i] = waypoints.get(i).asLong();
        this.routeIndex = -1;
    }

    /** Whether this guard walks a patrol route (as opposed to standing at a post). */
    public boolean isPatrolling() {
        return route.length > 0;
    }

    /**
     * A shout for the guard: this one goes to {@code at} and stays about there for {@code ticks} (renewed while the
     * trouble goes on), instead of standing at its post or walking its route. A fight in hand wins over it.
     */
    public void summon(BlockPos at, int ticks) {
        this.summonPos = at.immutable();
        this.summonUntil = this.level().getGameTime() + ticks;
    }

    /** Whether a shout is still being answered. */
    public boolean isSummoned() {
        return summonPos != null && this.level().getGameTime() < summonUntil;
    }

    /** Walks to the place a shout came from. */
    private final class SummonedGoal extends net.minecraft.world.entity.ai.goal.Goal {
        SummonedGoal() {
            this.setFlags(java.util.EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return isSummoned() && getTarget() == null && summonPos.distSqr(blockPosition()) > 4;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse() && !getNavigation().isDone();
        }

        @Override
        public void start() {
            getNavigation().moveTo(summonPos.getX() + 0.5, summonPos.getY(), summonPos.getZ() + 0.5, 1.1D);
        }

        @Override
        public void tick() {
            if (summonPos != null && tickCount % 10 == 0) {
                getNavigation().moveTo(summonPos.getX() + 0.5, summonPos.getY(), summonPos.getZ() + 0.5, 1.1D);
            }
        }
    }

    /** Walks the patrol route: next waypoint, a stop of a few seconds there, the next one. */
    private final class PatrolGoal extends net.minecraft.world.entity.ai.goal.Goal {
        PatrolGoal() {
            this.setFlags(java.util.EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return route.length > 0 && post == null && getTarget() == null && !isSummoned() && !isConversing()
                    && level().getGameTime() >= patrolIdleUntil;
        }

        @Override
        public boolean canContinueToUse() {
            return route.length > 0 && getTarget() == null && !isSummoned() && !isConversing() && !getNavigation().isDone();
        }

        @Override
        public void start() {
            int next = route.length == 1 ? 0 : (routeIndex + 1 + getRandom().nextInt(2)) % route.length;
            routeIndex = next;
            BlockPos at = BlockPos.of(route[next]);
            if (!getNavigation().moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.7D)) {
                patrolIdleUntil = level().getGameTime() + 60;
            }
        }

        @Override
        public void stop() {
            patrolIdleUntil = level().getGameTime() + 100 + getRandom().nextInt(260);
        }
    }

    /** Walks back to the post whenever nothing is being fought. */
    private final class HoldPostGoal extends net.minecraft.world.entity.ai.goal.Goal {
        HoldPostGoal() {
            this.setFlags(java.util.EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return post != null && getTarget() == null && !isSummoned() && !isConversing()
                    && distanceToSqr(post.getX() + 0.5, post.getY(), post.getZ() + 0.5) > 1.5 * 1.5;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse() && !getNavigation().isDone();
        }

        @Override
        public void start() {
            getNavigation().moveTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5, 0.75D);
        }
    }

    private int conversingPlayerId = -1;
    private long conversingUntil = 0L;

    public void engageConversation(@Nullable Player player, int ticks) {
        if (player == null) {
            this.conversingPlayerId = -1;
            this.conversingUntil = 0L;
            return;
        }
        this.conversingPlayerId = player.getId();
        this.conversingUntil = this.level().getGameTime() + ticks;
        this.getNavigation().stop();
        this.getLookControl().setLookAt(player, 30.0F, 30.0F);
    }

    @Nullable
    private Player activeConversationPartner() {
        if (this.getTarget() != null || this.conversingPlayerId < 0) return null;
        if (this.level().getGameTime() >= this.conversingUntil) {
            this.conversingPlayerId = -1;
            return null;
        }
        Entity e = this.level().getEntity(this.conversingPlayerId);
        if (!(e instanceof Player p) || !p.isAlive() || this.distanceToSqr(p) > 64.0
                || !com.skycraft.dialogue.Dialogue.isTalking(p.getUUID())) {
            this.conversingPlayerId = -1;
            return null;
        }
        return p;
    }

    public boolean isConversing() {
        return activeConversationPartner() != null;
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason,
                                        @Nullable SpawnGroupData data) {
        data = super.finalizeSpawn(level, difficulty, reason, data);
        initialize(level.getLevel());
        return data;
    }

    private void initialize(Level level) {
        if (initialized) return;
        initialized = true;
        // the watch of the settlement they stand in (or the region): its livery, name and gear
        if (!profileSet) setProfile(GuardProfiles.at(level, this.blockPosition()));
        GuardProfiles.Profile profile = getProfile();
        if (!this.hasCustomName()) {
            // "Whiterun Guard", "Redoran Guard", "Temple Ordinator"... named after the watch they belong to
            this.setCustomName(Component.translatable(profile.nameKey()));
        }
        if (home == null) setHome(this.blockPosition());
        this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(profile.weaponItem()));
        this.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(profile.shieldItem()));
        for (EquipmentSlot slot : EquipmentSlot.values()) this.setDropChance(slot, 0.0f);
    }

    // ------------------------------------------------------------------ behaviour

    @Override
    public float getWalkTargetValue(BlockPos pos, net.minecraft.world.level.LevelReader level) {
        if (!this.level().isClientSide()) {
            if (com.skycraft.society.NpcPlacement.isFireHazard(level, pos)
                    || com.skycraft.society.NpcPlacement.isSlabHazard(level, pos)
                    || com.skycraft.society.NpcPlacement.isFurnitureHazard(level, pos)) {
                return -1.0e9F;
            }
            if (this.level().canSeeSky(pos) && com.skycraft.society.NpcPlacement.hasRoomBelow(this.level(), pos)) {
                return -1.0e9F;
            }
        }
        return super.getWalkTargetValue(pos, level);
    }

    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel serverLevel263) {
        super.customServerAiStep(serverLevel263);
        if (!initialized) initialize(this.level());
        if ((this.tickCount + this.getId()) % 20 == 0 && this.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            BlockPos here = this.blockPosition();
            boolean onRoof = this.onGround() && sl.canSeeSky(here) && com.skycraft.society.NpcPlacement.hasRoomBelow(sl, here);
            boolean onHazard = this.isOnFire() || com.skycraft.society.NpcPlacement.isDirectFire(sl, here)
                    || com.skycraft.society.NpcPlacement.isFurnitureHazard(sl, here);
            if (onRoof || onHazard) {
                this.clearFire();
                BlockPos safe = com.skycraft.society.NpcPlacement.safeSurfaceAt(sl, here.getX(), here.getZ());
                if (!safe.equals(here) && !com.skycraft.society.NpcPlacement.hasRoomBelow(sl, safe)) {
                    if (post != null && sl.isLoaded(post) && com.skycraft.society.NpcPlacement.hasRoomBelow(sl, post)) {
                        setPost(safe, postYaw);
                    } else if (home != null && sl.isLoaded(home) && com.skycraft.society.NpcPlacement.hasRoomBelow(sl, home)) {
                        setHome(safe);
                    }
                    this.getNavigation().stop();
                    this.snapTo(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5, this.getYRot(), 0.0F);
                }
            }
        }
        LivingEntity target = this.getTarget();
        if (target != null && (!target.isAlive() || target instanceof Player p && (p.isCreative() || p.isSpectator()))) {
            this.setTarget(null);
            target = null;
        }
        Player partner = activeConversationPartner();
        if (target == null && partner != null) {
            this.getNavigation().stop();
            this.zza = 0.0F;
            this.xxa = 0.0F;
            this.setSpeed(0.0F);
            this.getLookControl().setLookAt(partner, 30.0F, 30.0F);
            double dx = partner.getX() - this.getX();
            double dz = partner.getZ() - this.getZ();
            float targetYaw = (float) (net.minecraft.util.Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
            this.setYRot(net.minecraft.util.Mth.approachDegrees(this.getYRot(), targetYaw, 25.0F));
            this.yBodyRot = net.minecraft.util.Mth.approachDegrees(this.yBodyRot, targetYaw, 25.0F);
            this.setYHeadRot(net.minecraft.util.Mth.approachDegrees(this.getYHeadRot(), targetYaw, 30.0F));
            return;
        }
        if (target == null && post != null && this.tickCount % 10 == 0
                && this.distanceToSqr(post.getX() + 0.5, post.getY(), post.getZ() + 0.5) <= 2.25) {
            // on the post: square up facing outward (the head is left to look at passers-by)
            this.setYRot(postYaw);
            this.yBodyRot = postYaw;
            this.getNavigation().stop();
        }
        if (target == null) {
            if (this.tickCount % 40 == 0 && this.getHealth() < this.getMaxHealth()) this.heal(1.0f);
            if (this.tickCount % 20 == 0) maybeSayLine();
        }
    }

    /** "I used to be an adventurer like you..." */
    private void maybeSayLine() {
        long now = this.level().getGameTime();
        if (now < nextLine) return;
        Player player = this.level().getNearestPlayer(this.getX(), this.getY(), this.getZ(), 4.5, EntitySelector.NO_SPECTATORS);
        if (!(player instanceof ServerPlayer sp) || !this.hasLineOfSight(player)) return;
        nextLine = now + 1200 + this.getRandom().nextInt(1800);
        this.getLookControl().setLookAt(player, 30f, 30f);
        int line = getProfile().skyrimLines() ? this.getRandom().nextInt(LINES)
                : GENERIC_LINES[this.getRandom().nextInt(GENERIC_LINES.length)];
        Notifier.message(sp, Component.translatable("creatures.skycraft.guard.says", this.getDisplayName(),
                Component.translatable("creatures.skycraft.guard.line." + line)));
    }

    @Override
    public boolean hurtServer(net.minecraft.server.level.ServerLevel serverLevel263, DamageSource source, float amount) {
        // no friendly fire between guards
        if (source.getEntity() instanceof GuardEntity) return false;
        return super.hurtServer(serverLevel263, source, amount);
    }

    @Override
    public boolean doHurtTarget(net.minecraft.server.level.ServerLevel serverLevel263, Entity target) {
        boolean hit = super.doHurtTarget(serverLevel263, target);
        if (hit) this.playSound(SoundEvents.PLAYER_ATTACK_STRONG, 0.8f, 1.0f);
        return hit;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }

    // ------------------------------------------------------------------ save

    @Override
    public void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output263) {
        super.addAdditionalSaveData(output263);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        
        tag.putString("Profile", getProfile().id());
        tag.putBoolean("SkycraftInit", initialized);
        if (home != null) tag.put("Home", com.skycraft.platform.NbtPositions.write(home));
        if (route.length > 0) tag.put("Route", new LongArrayTag(route));
        if (post != null) {
            tag.put("Post", com.skycraft.platform.NbtPositions.write(post));
            tag.putFloat("PostYaw", postYaw);
        }
    
        output263.store(tag);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input263) {
        super.readAdditionalSaveData(input263);
        net.minecraft.nbt.CompoundTag tag = com.skycraft.platform.ValueTags.read(input263 , "Profile", "Hold", "SkycraftInit", "Home", "Route", "Post", "PostYaw");
        
        if (tag.contains("Profile")) {
            GuardProfiles.Profile p = GuardProfiles.byId(tag.getStringOr("Profile",""));
            if (p != null) setProfile(p);
        } else if (tag.contains("Hold")) {
            setProfile(GuardProfiles.ofHoldIndex(tag.getIntOr("Hold",0))); // a guard saved before the profiles
        }
        initialized = tag.getBooleanOr("SkycraftInit",false);
        if (tag.contains("Home")) setHome(com.skycraft.platform.NbtPositions.read(tag.getCompoundOrEmpty("Home")));
        if (tag.contains("Route")) route = tag.getLongArray("Route").orElseGet(() -> new long[0]);
        if (tag.contains("Post")) setPost(com.skycraft.platform.NbtPositions.read(tag.getCompoundOrEmpty("Post")), tag.getFloatOr("PostYaw",0F));
    }
}
