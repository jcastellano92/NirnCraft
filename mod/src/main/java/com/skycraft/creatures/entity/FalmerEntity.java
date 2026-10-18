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
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** {@code skycraft:falmer}: feral, blind subterranean dwellers residing in deep barrows and Dwemer ruins. */
public class FalmerEntity extends SkyHumanoid implements Ranked {
    public FalmerEntity(EntityType<? extends FalmerEntity> type, Level level) {
        super(type, level);
        this.xpReward = 16;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 32.0)
                .add(Attributes.ATTACK_DAMAGE, 4.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.ARMOR, 3.0);
    }

    @Override
    protected int skinCount() {
        return 1;
    }

    @Override
    protected void equip(RandomSource random, DifficultyInstance difficulty) {
        if (random.nextFloat() < 0.35f) {
            Gear.equip(this, EquipmentSlot.MAINHAND, new ItemStack(Items.BOW), 0.08f);
        } else {
            Gear.equip(this, EquipmentSlot.MAINHAND, new ItemStack(Gear.modItem("falmer_sword", Items.IRON_SWORD)), 0.08f);
            if (random.nextFloat() < 0.35f) {
                Gear.equip(this, EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD), 0.06f);
            }
        }
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new net.minecraft.world.entity.ai.goal.FloatGoal(this));
        this.goalSelector.addGoal(6, new net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(7, new net.minecraft.world.entity.ai.goal.LookAtPlayerGoal(this, net.minecraft.world.entity.player.Player.class, 8.0F));
        this.goalSelector.addGoal(8, new net.minecraft.world.entity.ai.goal.RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal(this).setAlertOthers());
        this.targetSelector.addGoal(2, new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(this, net.minecraft.world.entity.player.Player.class, true) {
            @Override
            public boolean canUse() {
                if (!super.canUse() || this.target == null) return false;
                return canHearOrTouch(target);
            }
        });
    }

    /**
     * Blind Falmer perception:
     * - Within 4 blocks: detects by touch / immediate proximity.
     * - Beyond 4 blocks up to 24 blocks: detects sound/vibrations only if NOT sneaking (crouching).
     */
    public boolean canHearOrTouch(LivingEntity target) {
        if (!(target instanceof net.minecraft.world.entity.player.Player player)) return true;
        if (player.isCreative() || player.isSpectator()) return false;
        double distSq = this.distanceToSqr(player);
        if (distSq <= 4.0 * 4.0) return true; // Direct touch / breath
        if (player.isCrouching()) {
            // Player is sneaking: silent to blind Falmer
            return false;
        }
        // Player is walking, running, or moving
        return distSq <= 24.0 * 24.0;
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        if (!super.canAttack(target)) return false;
        return canHearOrTouch(target);
    }

    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel serverLevel263) {
        super.customServerAiStep(serverLevel263);
        if (this.tickCount % 25 == 0 && this.getTarget() == null) {
            // Listen for noisy intruders within 24 blocks
            for (net.minecraft.world.entity.player.Player p : this.level().getEntitiesOfClass(
                    net.minecraft.world.entity.player.Player.class, this.getBoundingBox().inflate(24),
                    e -> !e.isCreative() && !e.isSpectator() && !e.isCrouching())) {
                this.setTarget(p);
                this.playSound(SoundEvents.WARDEN_SNIFF, 1.2f, 1.4f);
                alertPack(p);
                break;
            }
        }
    }

    /** Alerts all nearby subterranean Falmer to swarm the detected intruder. */
    public void alertPack(LivingEntity intruder) {
        for (FalmerEntity falmer : this.level().getEntitiesOfClass(FalmerEntity.class, this.getBoundingBox().inflate(24),
                f -> f != this && f.isAlive())) {
            if (falmer.getTarget() == null) {
                falmer.setTarget(intruder);
            }
        }
    }

    @Override
    public boolean doHurtTarget(net.minecraft.server.level.ServerLevel serverLevel263, Entity target) {
        boolean hit = super.doHurtTarget(serverLevel263, target);
        if (hit && target instanceof LivingEntity living) {
            // Falmer coat their weapons in Chaurus venom
            living.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.POISON), 80, 0), this);
            alertPack(living);
        }
        return hit;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return com.skycraft.audio.AudioSounds.creature("falmer", "ambient");
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return com.skycraft.audio.AudioSounds.creature("falmer", "hurt");
    }

    @Override
    protected SoundEvent getDeathSound() {
        return com.skycraft.audio.AudioSounds.creature("falmer", "death");
    }

    @Override
    @Nullable
    public Component rankName(int level) {
        String rank = level >= 28 ? "shadowmaster" : level >= 20 ? "nightprowler" : level >= 12 ? "skulker" : null;
        return rank == null ? null : Component.translatable("entity.skycraft.falmer." + rank);
    }
}

