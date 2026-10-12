package com.skycraft.creatures.entity;

import com.skycraft.core.Holds;
import com.skycraft.core.Notifier;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.entity.MobSpawnType;
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
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import org.jetbrains.annotations.Nullable;

/**
 * {@code skycraft:guard}: hold guards. Patrol around their village, fight monsters, and say the famous lines.
 * Neutral to players: the crime module decides when a player becomes their target ({@link #setTarget}); the
 * melee goal pursues whatever target is set and the monster-target goal never replaces a player target.
 */
public class GuardEntity extends PathfinderMob {
    private static final EntityDataAccessor<Integer> HOLD = SynchedEntityData.defineId(GuardEntity.class, EntityDataSerializers.INT);
    public static final int LINES = 20;

    @Nullable
    private BlockPos home;
    private long nextLine;
    private boolean initialized;

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
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(HOLD, 0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.15D, true));
        this.goalSelector.addGoal(4, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(5, new MoveTowardsRestrictionGoal(this, 0.8D));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.6D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, GuardEntity.class).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Mob.class, 5, false, false,
                e -> e instanceof Enemy && !(e instanceof Creeper) && !(e instanceof EnderMan) && !(e instanceof GiantEntity)) {
            @Override
            public boolean canUse() {
                // never replace a player target set by the crime module
                return !(GuardEntity.this.getTarget() instanceof Player) && super.canUse();
            }
        });
    }

    // ------------------------------------------------------------------ hold & home

    /** Index into {@link Holds#NAMES}: the tunic color of the hold this guard serves. */
    public int getHold() {
        return Math.floorMod(this.entityData.get(HOLD), Holds.NAMES.length);
    }

    public void setHold(int hold) {
        this.entityData.set(HOLD, Math.floorMod(hold, Holds.NAMES.length));
    }

    public static int holdIndex(String holdId) {
        // guards outside Skyrim's holds wear Whiterun's colours until their province gets its own guards
        return Math.max(0, Holds.index(holdId));
    }

    @Nullable
    public BlockPos getHome() {
        return home;
    }

    /** Guards patrol within 32 blocks of their home (the village meeting point). */
    public void setHome(BlockPos pos) {
        this.home = pos.immutable();
        this.restrictTo(this.home, 40);
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData data, @Nullable CompoundTag tag) {
        data = super.finalizeSpawn(level, difficulty, reason, data, tag);
        initialize(level.getLevel());
        return data;
    }

    private void initialize(Level level) {
        if (initialized) return;
        initialized = true;
        setHold(holdIndex(Holds.holdAt(level, this.blockPosition())));
        if (!this.hasCustomName()) {
            // "Whiterun Guard", "Riften Guard"... named after the hold they serve
            this.setCustomName(Component.translatable("entity.skycraft.guard." + Holds.NAMES[getHold()]));
        }
        if (home == null) setHome(this.blockPosition());
        this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        this.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        for (EquipmentSlot slot : EquipmentSlot.values()) this.setDropChance(slot, 0.0f);
    }

    // ------------------------------------------------------------------ behaviour

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!initialized) initialize(this.level());
        LivingEntity target = this.getTarget();
        if (target != null && (!target.isAlive() || target instanceof Player p && (p.isCreative() || p.isSpectator()))) {
            this.setTarget(null);
            target = null;
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
        int line = this.getRandom().nextInt(LINES);
        Notifier.message(sp, Component.translatable("creatures.skycraft.guard.says", this.getDisplayName(),
                Component.translatable("creatures.skycraft.guard.line." + line)));
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // no friendly fire between guards
        if (source.getEntity() instanceof GuardEntity) return false;
        return super.hurt(source, amount);
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
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
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Hold", getHold());
        tag.putBoolean("SkycraftInit", initialized);
        if (home != null) tag.put("Home", NbtUtils.writeBlockPos(home));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setHold(tag.getInt("Hold"));
        initialized = tag.getBoolean("SkycraftInit");
        if (tag.contains("Home")) setHome(NbtUtils.readBlockPos(tag.getCompound("Home")));
    }
}
