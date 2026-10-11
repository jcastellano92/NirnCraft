package com.skycraft.creatures.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** {@code skycraft:vampire}: predatory undead suffering from Sanguinare Vampiris. Drains life on strike. */
public class VampireEntity extends SkyHumanoid implements Ranked {
    public static final int SKINS = 2;

    public VampireEntity(EntityType<? extends VampireEntity> type, Level level) {
        super(type, level);
        this.xpReward = 20;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 35.0)
                .add(Attributes.ATTACK_DAMAGE, 4.5)
                .add(Attributes.MOVEMENT_SPEED, 0.31)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.ARMOR, 3.0);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, false));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, GuardEntity.class, true));
        this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
    }

    @Override
    protected int skinCount() {
        return SKINS;
    }

    @Override
    protected void equip(RandomSource random, DifficultyInstance difficulty) {
        Gear.equip(this, EquipmentSlot.MAINHAND, new ItemStack(Gear.modItem("steel_dagger", Items.IRON_SWORD)), 0.08f);
        Gear.equip(this, EquipmentSlot.CHEST, Gear.dyedLeather(Gear.leather(EquipmentSlot.CHEST), 0x221111), 0.08f);
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit && target instanceof LivingEntity living) {
            // Vampiric drain: heal vampire and apply brief weakness
            this.heal(2.5f);
            living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0), this);
            this.playSound(SoundEvents.PLAYER_BURP, 0.6f, 1.8f);
            if (this.level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.DAMAGE_INDICATOR, living.getX(), living.getY() + 1.0, living.getZ(), 4, 0.2, 0.3, 0.2, 0.01);
            }
        }
        return hit;
    }

    /** A wandering vampire sometimes hunts with a death hound at its heel (curse module). */
    @Override
    @Nullable
    public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(net.minecraft.world.level.ServerLevelAccessor level, DifficultyInstance difficulty,
                                                                   net.minecraft.world.entity.MobSpawnType reason,
                                                                   @Nullable net.minecraft.world.entity.SpawnGroupData data,
                                                                   @Nullable net.minecraft.nbt.CompoundTag tag) {
        data = super.finalizeSpawn(level, difficulty, reason, data, tag);
        if (reason == net.minecraft.world.entity.MobSpawnType.NATURAL && this.random.nextInt(4) == 0) {
            var hound = com.skycraft.curse.CurseRegistry.DEATH_HOUND.get().create(level.getLevel());
            if (hound != null) {
                hound.moveTo(getX() + 1, getY(), getZ(), getYRot(), 0);
                if (level.noCollision(hound)) {
                    hound.finalizeSpawn(level, difficulty, net.minecraft.world.entity.MobSpawnType.REINFORCEMENT, null, null);
                    level.addFreshEntity(hound);
                }
            }
        }
        return data;
    }

    @Override
    @Nullable
    public Component rankName(int level) {
        String rank = level >= 28 ? "master_vampire" : level >= 20 ? "vampire_mistress" : level >= 12 ? "vampire_fledgling" : null;
        return rank == null ? null : Component.translatable("entity.skycraft.vampire." + rank);
    }
}

