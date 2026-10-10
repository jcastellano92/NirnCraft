package com.skycraft.creatures.entity;

import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import org.jetbrains.annotations.Nullable;

/**
 * Base for Skyrim's humanoid fighters (bandits, draugr): random skin, random gear chosen once, melee or archer
 * behaviour depending on the weapon in hand (like vanilla skeletons).
 */
public abstract class SkyHumanoid extends Monster implements RangedAttackMob {
    private static final EntityDataAccessor<Integer> SKIN = SynchedEntityData.defineId(SkyHumanoid.class, EntityDataSerializers.INT);

    private final RangedBowAttackGoal<SkyHumanoid> bowGoal = new RangedBowAttackGoal<>(this, 1.0D, 30, 16.0F);
    private final MeleeAttackGoal meleeGoal = new MeleeAttackGoal(this, 1.15D, false);
    private boolean initialized;

    protected SkyHumanoid(EntityType<? extends SkyHumanoid> type, Level level) {
        super(type, level);
        reassessWeaponGoal();
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(SKIN, 0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // ------------------------------------------------------------------ skins & gear

    /** Number of skin textures for this creature. */
    protected abstract int skinCount();

    /** Puts on this creature's random armor and weapons. */
    protected abstract void equip(RandomSource random, DifficultyInstance difficulty);

    public int getSkin() {
        return this.entityData.get(SKIN);
    }

    public void setSkin(int skin) {
        this.entityData.set(SKIN, Math.max(0, skin));
    }

    /** Chooses skin and gear once; also runs on the first tick for creatures spawned without finalizeSpawn. */
    protected void initialize(RandomSource random, DifficultyInstance difficulty) {
        if (initialized) return;
        initialized = true;
        setSkin(random.nextInt(Math.max(1, skinCount())));
        equip(random, difficulty);
        reassessWeaponGoal();
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData data, @Nullable CompoundTag tag) {
        data = super.finalizeSpawn(level, difficulty, reason, data, tag);
        initialize(level.getRandom(), difficulty);
        return data;
    }

    private int searchTicks = 0;
    @Nullable
    private BlockPos lastKnownTargetPos;

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && !initialized) {
            initialize(this.getRandom(), this.level().getCurrentDifficultyAt(this.blockPosition()));
        }
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        LivingEntity target = this.getTarget();
        if (target instanceof Player player) {
            if (player.isCreative() || player.isSpectator() || !player.isAlive()) {
                this.setTarget(null);
                this.searchTicks = 0;
                return;
            }

            boolean hasLos = this.hasLineOfSight(player);
            boolean sneaking = player.isCrouching();
            boolean detected = true;

            if (!hasLos) {
                detected = false;
            } else if (sneaking) {
                double dist = this.distanceTo(player);
                int light = player.level().getMaxLocalRawBrightness(player.blockPosition());
                int sneakSkill = (player instanceof ServerPlayer sp) ? SkyData.get(sp).getSkill(Skill.SNEAK) : 15;
                Vec3 look = this.getViewVector(1.0F);
                Vec3 toPlayer = player.position().subtract(this.position()).normalize();
                double dot = look.dot(toPlayer);

                if (dot < 0.2 && dist > 3.0) {
                    detected = false; // Behind or side of enemy
                } else if (light <= 7 && dist > 8.0) {
                    detected = false; // Hidden in shadows
                } else if (sneakSkill >= 45 && dist > 12.0) {
                    detected = false; // Master of shadows
                }
            }

            if (detected) {
                this.searchTicks = 0;
                this.lastKnownTargetPos = player.blockPosition();
            } else {
                if (this.searchTicks == 0) {
                    this.searchTicks = 140; // 7 seconds search state
                    if (this.lastKnownTargetPos != null) {
                        this.getNavigation().moveTo(lastKnownTargetPos.getX(), lastKnownTargetPos.getY(), lastKnownTargetPos.getZ(), 1.0D);
                    }
                } else {
                    this.searchTicks--;
                    if (this.searchTicks % 40 == 0 && player instanceof ServerPlayer sp) {
                        com.skycraft.skills.Progression.addSkillXp(sp, Skill.SNEAK, 0.6f / Skill.SNEAK.useMult);
                    }
                    if (this.searchTicks <= 0) {
                        // Lost the player: give up and return to normal
                        this.setTarget(null);
                        this.lastKnownTargetPos = null;
                        this.getNavigation().stop();
                    }
                }
            }
        }
    }

    public boolean isArcher() {
        return this.getMainHandItem().getItem() instanceof BowItem;
    }

    /** Swaps between the bow and the melee goal depending on the weapon in hand. */
    public void reassessWeaponGoal() {
        if (this.level() == null || this.level().isClientSide || bowGoal == null || meleeGoal == null) return;
        this.goalSelector.removeGoal(this.meleeGoal);
        this.goalSelector.removeGoal(this.bowGoal);
        if (isArcher()) {
            this.bowGoal.setMinAttackInterval(this.level().getDifficulty() == Difficulty.HARD ? 20 : 35);
            this.goalSelector.addGoal(4, this.bowGoal);
        } else {
            this.goalSelector.addGoal(4, this.meleeGoal);
        }
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        super.setItemSlot(slot, stack);
        if (slot == EquipmentSlot.MAINHAND && this.level() != null && !this.level().isClientSide) reassessWeaponGoal();
    }

    @Override
    public void performRangedAttack(LivingEntity target, float velocity) {
        ItemStack bow = this.getMainHandItem();
        ItemStack ammo = this.getProjectile(bow);
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, ammo, velocity);
        if (bow.getItem() instanceof BowItem bowItem) arrow = bowItem.customArrow(arrow);
        double dx = target.getX() - this.getX();
        double dy = target.getY(0.3333333333333333D) - arrow.getY();
        double dz = target.getZ() - this.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + horizontal * 0.2D, dz, 1.6F, (float) (14 - this.level().getDifficulty().getId() * 4));
        this.playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
        this.level().addFreshEntity(arrow);
    }

    // ------------------------------------------------------------------ save

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Skin", getSkin());
        tag.putBoolean("SkycraftInit", initialized);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setSkin(tag.getInt("Skin"));
        initialized = tag.getBoolean("SkycraftInit");
        reassessWeaponGoal();
    }
}
