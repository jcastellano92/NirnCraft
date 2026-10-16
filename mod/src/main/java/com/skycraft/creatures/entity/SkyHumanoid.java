package com.skycraft.creatures.entity;

import com.skycraft.creatures.entity.anim.AnimSlot;
import com.skycraft.creatures.entity.anim.AnimatedCreature;
import com.skycraft.creatures.entity.anim.CreatureAnims;
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
public abstract class SkyHumanoid extends Monster implements AnimatedCreature, RangedAttackMob {
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
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, false, false,
                p -> !(p instanceof Player pl) || detects(pl)));
        this.targetSelector.addGoal(3, new com.skycraft.creatures.ai.SmartPartyTargetGoal(this));
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

    @Nullable
    private BlockPos lastKnownTargetPos;
    private int unseenTicks;

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
                this.unseenTicks = 0;
                return;
            }
            if (detects(player)) {
                this.lastKnownTargetPos = player.blockPosition();
                this.unseenTicks = 0;
            } else {
                this.unseenTicks++;
                int maxUnseen = player.isCrouching() ? 50 : 140;
                if (this.unseenTicks >= maxUnseen) {
                    // Sustained loss of sight/hearing: transition to searching last known position
                    BlockPos where = this.lastKnownTargetPos != null ? this.lastKnownTargetPos : player.blockPosition();
                    this.lastKnownTargetPos = null;
                    this.unseenTicks = 0;
                    com.skycraft.combat.Stealth.beginSearch(this, Vec3.atBottomCenterOf(where), player.getUUID());
                }
            }
        } else {
            this.unseenTicks = 0;
        }
    }

    /** Whether this fighter can currently see or hear the player. */
    public boolean detects(Player player) {
        if (com.skycraft.combat.Stealth.provoked(this, player)) return true;
        double dist = this.distanceTo(player);
        boolean los = this.hasLineOfSight(player);
        if (!los) {
            // Hearing detection: sprinting or non-sneaking footsteps within close range
            if (!player.isCrouching()) {
                return dist <= (player.isSprinting() ? 13.0 : 8.0);
            }
            return dist <= 2.5;
        }
        if (!player.isCrouching()) return dist <= 28.0;
        int light = player.level().getMaxLocalRawBrightness(player.blockPosition());
        int sneakSkill = (player instanceof ServerPlayer sp) ? SkyData.get(sp).getSkill(Skill.SNEAK) : 15;
        Vec3 look = this.getViewVector(1.0F);
        Vec3 toPlayer = player.position().subtract(this.position()).normalize();
        if (look.dot(toPlayer) < 0.15 && dist > 4.0) return false; // behind or beside
        if (light <= 7 && dist > 10.0) return false; // hidden in shadows
        return !(sneakSkill >= 45 && dist > 14.0); // master of shadows
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

    // ------------------------------------------------------------------ animation (rig models)

    private final CreatureAnims anims = new CreatureAnims();

    @Override
    public CreatureAnims anims() {
        return anims;
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (!anims.onEvent(this, id)) super.handleEntityEvent(id);
    }

    /** Every melee swing plays an attack animation (alternating blows). */
    @Override
    public void swing(net.minecraft.world.InteractionHand hand) {
        super.swing(hand);
        if (!this.level().isClientSide) CreatureAnims.play(this, this.getRandom().nextInt(3) == 0 ? AnimSlot.ATTACK2 : AnimSlot.ATTACK);
    }
}
