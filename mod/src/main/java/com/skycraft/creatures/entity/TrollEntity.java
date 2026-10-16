package com.skycraft.creatures.entity;

import com.skycraft.creatures.entity.anim.AnimSlot;
import com.skycraft.creatures.entity.anim.AnimatedCreature;
import com.skycraft.creatures.entity.anim.CreatureAnims;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
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
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * {@code skycraft:troll}: three-eyed ape-like brutes. They regenerate quickly unless burning (Skyrim: use fire!).
 * Frost trolls live in snowy biomes.
 */
public class TrollEntity extends Monster implements AnimatedCreature, Ranked {
    private static final EntityDataAccessor<Boolean> FROST = SynchedEntityData.defineId(TrollEntity.class, EntityDataSerializers.BOOLEAN);
    private boolean initialized;
    private int lastFireHurt = -1000;

    public TrollEntity(EntityType<? extends TrollEntity> type, Level level) {
        super(type, level);
        this.xpReward = 20;
        this.setMaxUpStep(1.0f);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 60.0)
                .add(Attributes.ATTACK_DAMAGE, 7.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.ARMOR, 4.0)
                .add(Attributes.ATTACK_KNOCKBACK, 1.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.6);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(FROST, false);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.2D, true));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.7D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new com.skycraft.creatures.ai.SmartPartyTargetGoal(this));
        this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, true));
        this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, GuardEntity.class, true));
    }

    public boolean isFrost() {
        return this.entityData.get(FROST);
    }

    public void setFrost(boolean frost) {
        this.entityData.set(FROST, frost);
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData data, @Nullable CompoundTag tag) {
        data = super.finalizeSpawn(level, difficulty, reason, data, tag);
        initialize(level.getBiome(this.blockPosition()).value().coldEnoughToSnow(this.blockPosition()));
        return data;
    }

    private void initialize(boolean frost) {
        if (initialized) return;
        initialized = true;
        setFrost(frost);
        if (frost) {
            AttributeInstance health = this.getAttribute(Attributes.MAX_HEALTH);
            if (health != null) health.setBaseValue(80.0);
            AttributeInstance damage = this.getAttribute(Attributes.ATTACK_DAMAGE);
            if (damage != null) damage.setBaseValue(8.0);
            this.setHealth(this.getMaxHealth());
        }
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide) return;
        if (!initialized) {
            BlockPos pos = this.blockPosition();
            initialize(this.level().getBiome(pos).value().coldEnoughToSnow(pos));
        }
        // Troll regeneration: fast, but fire stops it.
        if (this.tickCount % 20 == 0 && this.isAlive() && this.getHealth() < this.getMaxHealth()
                && !this.isOnFire() && this.tickCount - lastFireHurt > 100) {
            this.heal(isFrost() ? 2.5f : 2.0f);
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypeTags.IS_FIRE)) {
            lastFireHurt = this.tickCount;
            amount *= 1.5f; // trolls fear fire
        }
        return super.hurt(source, amount);
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit && target instanceof LivingEntity living) {
            living.setDeltaMovement(living.getDeltaMovement().add(0, 0.25, 0));
            living.hurtMarked = true;
        }
        return hit;
    }

    @Override
    public boolean canFreeze() {
        return !isFrost() && super.canFreeze();
    }

    @Override
    @Nullable
    public Component rankName(int level) {
        if (level >= 25) return Component.translatable(isFrost() ? "entity.skycraft.troll.armored_frost" : "entity.skycraft.troll.armored");
        return isFrost() ? Component.translatable("entity.skycraft.troll.frost") : null;
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 0.7f;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return com.skycraft.audio.AudioSounds.creature("troll", "ambient");
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return com.skycraft.audio.AudioSounds.creature("troll", "hurt");
    }

    @Override
    protected SoundEvent getDeathSound() {
        return com.skycraft.audio.AudioSounds.creature("troll", "death");
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.POLAR_BEAR_STEP, 0.4f, 0.8f);
    }

    @Override
    public void setTarget(@Nullable LivingEntity target) {
        if (target != null && this.getTarget() == null && !this.level().isClientSide) {
            this.playSound(com.skycraft.audio.AudioSounds.creature("troll", "roar"), 1.5f, 1.0f);
            CreatureAnims.play(this, AnimSlot.ROAR);
        }
        super.setTarget(target);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Frost", isFrost());
        tag.putBoolean("SkycraftInit", initialized);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setFrost(tag.getBoolean("Frost"));
        initialized = tag.getBoolean("SkycraftInit");
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

    @Override
    public int animVariant() {
        return isFrost() ? 1 : 0;
    }
}
