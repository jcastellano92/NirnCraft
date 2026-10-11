package com.skycraft.creatures.entity;

import com.skycraft.creatures.entity.anim.AnimSlot;
import com.skycraft.creatures.entity.anim.AnimatedCreature;
import com.skycraft.creatures.entity.anim.CreatureAnims;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.level.Level;

/** {@code skycraft:frostbite_spider}: large arachnids infesting Skyrim's caves and frozen passes. */
public class FrostbiteSpiderEntity extends Spider implements AnimatedCreature {
    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> GIANT =
            net.minecraft.network.syncher.SynchedEntityData.defineId(FrostbiteSpiderEntity.class, net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);

    public FrostbiteSpiderEntity(EntityType<? extends FrostbiteSpiderEntity> type, Level level) {
        super(type, level);
        this.xpReward = 12;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 30.0)
                .add(Attributes.ATTACK_DAMAGE, 4.0)
                .add(Attributes.MOVEMENT_SPEED, 0.30)
                .add(Attributes.ARMOR, 2.0);
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit && target instanceof LivingEntity living) {
            // Frostbite bite slows and poisons
            living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 1), this);
            living.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 0), this);
            if (living.canFreeze()) {
                living.setTicksFrozen(Math.min(living.getTicksRequiredToFreeze() + 80, living.getTicksFrozen() + 30));
            }
        }
        return hit;
    }

    /** Giant frostbite spiders hiss deeper. */
    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * (isGiant() ? 0.72f : 1.0f);
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getAmbientSound() {
        return com.skycraft.audio.AudioSounds.creature("frostbite_spider", "ambient");
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getHurtSound(net.minecraft.world.damagesource.DamageSource source) {
        return com.skycraft.audio.AudioSounds.creature("frostbite_spider", "hurt");
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getDeathSound() {
        return com.skycraft.audio.AudioSounds.creature("frostbite_spider", "death");
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
        if (!this.level().isClientSide) CreatureAnims.play(this, AnimSlot.ATTACK);
    }

    @Override
    public int animVariant() {
        return isGiant() ? 1 : 0;
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(GIANT, false);
    }

    /** Giant frostbite spiders: twice the size, lurking in the deepest caves and nests. */
    public boolean isGiant() {
        return this.entityData.get(GIANT);
    }

    public void setGiant(boolean giant) {
        this.entityData.set(GIANT, giant);
        this.refreshDimensions();
        if (giant && !this.level().isClientSide) {
            var hp = this.getAttribute(Attributes.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(90.0);
            var dmg = this.getAttribute(Attributes.ATTACK_DAMAGE);
            if (dmg != null) dmg.setBaseValue(7.0);
            this.setHealth(this.getMaxHealth());
            this.xpReward = 30;
        }
    }

    @Override
    @org.jetbrains.annotations.Nullable
    public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(net.minecraft.world.level.ServerLevelAccessor level,
                                                                   net.minecraft.world.DifficultyInstance difficulty,
                                                                   net.minecraft.world.entity.MobSpawnType reason,
                                                                   @org.jetbrains.annotations.Nullable net.minecraft.world.entity.SpawnGroupData data,
                                                                   @org.jetbrains.annotations.Nullable net.minecraft.nbt.CompoundTag tag) {
        data = super.finalizeSpawn(level, difficulty, reason, data, tag);
        if (this.random.nextInt(12) == 0) setGiant(true);
        return data;
    }

    @Override
    public net.minecraft.world.entity.EntityDimensions getDimensions(net.minecraft.world.entity.Pose pose) {
        net.minecraft.world.entity.EntityDimensions d = super.getDimensions(pose);
        return isGiant() ? d.scale(2.0f) : d;
    }

    @Override
    public void onSyncedDataUpdated(net.minecraft.network.syncher.EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (GIANT.equals(key)) this.refreshDimensions();
    }

    @Override
    protected net.minecraft.network.chat.Component getTypeName() {
        return isGiant() ? net.minecraft.network.chat.Component.translatable("entity.skycraft.frostbite_spider.giant") : super.getTypeName();
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Giant", isGiant());
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.entityData.set(GIANT, tag.getBoolean("Giant"));
        this.refreshDimensions();
    }
}
