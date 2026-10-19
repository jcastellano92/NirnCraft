package com.nirncraft.creatures.entity;

import com.nirncraft.creatures.entity.anim.AnimSlot;
import com.nirncraft.creatures.entity.anim.AnimatedCreature;
import com.nirncraft.creatures.entity.anim.CreatureAnims;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.level.Level;

/** {@code nirncraft:frostbite_spider}: large arachnids infesting Skyrim's caves and frozen passes. */
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
                .add(Attributes.FOLLOW_RANGE, 24.0)
                .add(Attributes.ARMOR, 2.0);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.targetSelector.addGoal(3, new com.nirncraft.creatures.ai.SmartPartyTargetGoal(this));
    }

    @Override
    public boolean doHurtTarget(net.minecraft.server.level.ServerLevel serverLevel263, Entity target) {
        boolean hit = super.doHurtTarget(serverLevel263, target);
        if (hit && target instanceof LivingEntity living) {
            // Frostbite bite slows and poisons
            living.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.SLOWNESS), 100, 1), this);
            living.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.POISON), 80, 0), this);
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
        // playtest 3: the constant chittering/hissing was dropped; the spider is silent until hurt or killed
        return null;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getHurtSound(net.minecraft.world.damagesource.DamageSource source) {
        return com.nirncraft.audio.AudioSounds.creature("frostbite_spider", "hurt");
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getDeathSound() {
        return com.nirncraft.audio.AudioSounds.creature("frostbite_spider", "death");
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
    public boolean swing(net.minecraft.world.InteractionHand hand, net.minecraft.world.item.component.SwingAnimation animation263, boolean updateSelf263) {
        boolean swung263 = super.swing(hand, animation263, updateSelf263);
        if (!this.level().isClientSide()) CreatureAnims.play(this, AnimSlot.ATTACK);
        return swung263;
    }

    @Override
    public int animVariant() {
        return isGiant() ? 1 : 0;
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(GIANT, false);
    }

    /** Giant frostbite spiders: twice the size, lurking in the deepest caves and nests. */
    public boolean isGiant() {
        return this.entityData.get(GIANT);
    }

    public void setGiant(boolean giant) {
        this.entityData.set(GIANT, giant);
        this.refreshDimensions();
        if (giant && !this.level().isClientSide()) {
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
                                                                   net.minecraft.world.entity.EntitySpawnReason reason,
                                                                   @org.jetbrains.annotations.Nullable net.minecraft.world.entity.SpawnGroupData data) {
        data = super.finalizeSpawn(level, difficulty, reason, data);
        // vanilla spiders may roll Invisibility from their effect group on hard: a NirnCraft spider is always seen
        this.removeEffect(com.nirncraft.platform.Effects.holder(net.minecraft.world.effect.MobEffects.INVISIBILITY));
        if (this.random.nextInt(12) == 0) setGiant(true);
        return data;
    }

    @Override
    protected net.minecraft.world.entity.EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
        net.minecraft.world.entity.EntityDimensions d = super.getDefaultDimensions(pose);
        return isGiant() ? d.scale(2.0f) : d;
    }

    @Override
    public void onSyncedDataUpdated(net.minecraft.network.syncher.EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (GIANT.equals(key)) this.refreshDimensions();
    }

    @Override
    protected net.minecraft.network.chat.Component getTypeName() {
        return isGiant() ? net.minecraft.network.chat.Component.translatable("entity.nirncraft.frostbite_spider.giant") : super.getTypeName();
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output263) {
        super.addAdditionalSaveData(output263);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        
        tag.putBoolean("Giant", isGiant());
    
        output263.store(tag);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input263) {
        super.readAdditionalSaveData(input263);
        net.minecraft.nbt.CompoundTag tag = com.nirncraft.platform.ValueTags.read(input263 , "Giant");
        
        this.entityData.set(GIANT, tag.getBooleanOr("Giant",false));
        this.refreshDimensions();
    }
}
