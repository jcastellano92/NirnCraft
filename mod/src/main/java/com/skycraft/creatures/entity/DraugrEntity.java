package com.skycraft.creatures.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code skycraft:draugr}: undead Nordic warriors guarding ancient tombs. They never burn in daylight, have no
 * babies and never turn into villagers. Some carry ancient bows. Their blows carry the chill of the grave.
 */
public class DraugrEntity extends SkyHumanoid implements Ranked {
    public static final int SKINS = 2;
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> RANK =
            net.minecraft.network.syncher.SynchedEntityData.defineId(DraugrEntity.class, net.minecraft.network.syncher.EntityDataSerializers.INT);
    /** Unrelenting Force, shouted by scourges and overlords. */
    private static final com.skycraft.creatures.spec.Attack FUS = com.skycraft.creatures.spec.Attacks.shout(3f, 1.8, 9f);
    private int shoutCooldown = 100;

    public DraugrEntity(EntityType<? extends DraugrEntity> type, Level level) {
        super(type, level);
        this.xpReward = 8;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 24.0)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.MOVEMENT_SPEED, 0.24)
                .add(Attributes.FOLLOW_RANGE, 28.0)
                .add(Attributes.ARMOR, 4.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.2);
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(RANK, 1);
    }

    /** 0 restless, 1 draugr, 2 wight, 3 scourge, 4 overlord (helmets and armour follow it). */
    public int getRank() {
        return this.entityData.get(RANK);
    }

    @Override
    public void onLevelApplied(int level) {
        this.entityData.set(RANK, level >= 28 ? 4 : level >= 20 ? 3 : level >= 12 ? 2 : level >= 5 ? 1 : 0);
    }

    /** Scourges and overlords know the Thu'um: Unrelenting Force. */
    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel serverLevel263) {
        super.customServerAiStep(serverLevel263);
        if (shoutCooldown > 0) {
            shoutCooldown--;
            return;
        }
        LivingEntity target = this.getTarget();
        if (getRank() < 3 || target == null || this.distanceToSqr(target) > 7.5 * 7.5 || !this.hasLineOfSight(target)) return;
        shoutCooldown = 240 + this.getRandom().nextInt(200);
        this.getLookControl().setLookAt(target, 60f, 60f);
        this.setYRot((float) (net.minecraft.util.Mth.atan2(target.getZ() - this.getZ(), target.getX() - this.getX()) * net.minecraft.util.Mth.RAD_TO_DEG) - 90f);
        if (this.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            Component words = Component.translatable("shout.skycraft.dragon.force");
            for (net.minecraft.server.level.ServerPlayer p : sl.players()) {
                if (p.distanceToSqr(this) < 24 * 24) com.skycraft.core.Notifier.message(p, words);
            }
        }
        FUS.tick(this, target, FUS.windup);
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output263) {
        super.addAdditionalSaveData(output263);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        
        tag.putInt("Rank", getRank());
    
        output263.store(tag);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input263) {
        super.readAdditionalSaveData(input263);
        net.minecraft.nbt.CompoundTag tag = com.skycraft.platform.ValueTags.read(input263 , "Rank");
        
        if (tag.contains("Rank")) this.entityData.set(RANK, tag.getIntOr("Rank",0));
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, false));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, GuardEntity.class, true));
        this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
    }

    @Override
    public boolean isInvertedHealAndHarm() {
        return true;
    }

    @Override
    public boolean canFreeze() {
        return false;
    }

    @Override
    protected int skinCount() {
        return SKINS;
    }

    @Override
    protected void equip(RandomSource random, DifficultyInstance difficulty) {
        // economy workstream: Ancient Nord weapons and armour pieces
        if (!this.level().isClientSide()) {
            com.skycraft.arsenal.LeveledGear.outfit(this, com.skycraft.arsenal.LeveledGear.Outfit.DRAUGR, random, 0.05f);
            return;
        }
        if (random.nextFloat() < 0.25f) {
            Gear.equip(this, EquipmentSlot.MAINHAND, new ItemStack(Items.BOW), 0.05f);
        } else {
            Item[] weapons = {Items.STONE_SWORD, Items.IRON_SWORD, Items.STONE_AXE, Items.IRON_AXE,
                    Gear.modItem("iron_war_axe", Items.IRON_AXE), Gear.modItem("iron_greatsword", Items.IRON_SWORD)};
            Gear.equip(this, EquipmentSlot.MAINHAND, Gear.pick(random, weapons), 0.05f);
            if (random.nextFloat() < 0.15f) Gear.equip(this, EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD), 0.03f);
        }
        // Ancient Nord mail: a few chainmail pieces over the burial wrappings.
        for (EquipmentSlot slot : Gear.ARMOR_SLOTS) {
            if (slot != EquipmentSlot.HEAD && random.nextFloat() < 0.3f) {
                Gear.equip(this, slot, new ItemStack(Gear.chainmail(slot)), 0.05f);
            }
        }
    }

    /** The cold touch of the draugr slows its victims. */
    @Override
    public boolean doHurtTarget(net.minecraft.server.level.ServerLevel serverLevel263, Entity target) {
        boolean hit = super.doHurtTarget(serverLevel263, target);
        if (hit && target instanceof LivingEntity living && this.getRandom().nextFloat() < 0.25f) {
            living.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.SLOWNESS), 40, 0), this);
        }
        return hit;
    }

    @Override
    @Nullable
    public Component rankName(int level) {
        String rank = level >= 28 ? "overlord" : level >= 20 ? "scourge" : level >= 12 ? "wight" : level >= 5 ? null : "restless";
        return rank == null ? null : Component.translatable("entity.skycraft.draugr." + rank);
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 0.6f;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return com.skycraft.audio.AudioSounds.creature("draugr", "ambient");
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return com.skycraft.audio.AudioSounds.creature("draugr", "hurt");
    }

    @Override
    protected SoundEvent getDeathSound() {
        return com.skycraft.audio.AudioSounds.creature("draugr", "death");
    }
}
