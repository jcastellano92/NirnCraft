package com.skycraft.creatures.entity;

import com.skycraft.creatures.ModEntities;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** {@code skycraft:necromancer}: dark sorcerers practicing soul trapping and reanimation. */
public class NecromancerEntity extends SkyHumanoid implements Ranked {
    public static final int SKINS = 2;
    private int spellCooldown = 60;
    private boolean summonedUndead = false;

    public NecromancerEntity(EntityType<? extends NecromancerEntity> type, Level level) {
        super(type, level);
        this.xpReward = 18;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 30.0)
                .add(Attributes.ATTACK_DAMAGE, 2.5)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.FOLLOW_RANGE, 32.0);
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
        Gear.equip(this, EquipmentSlot.MAINHAND, new ItemStack(Gear.modItem("iron_dagger", Items.IRON_SWORD)), 0.08f);
    }

    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel serverLevel263) {
        super.customServerAiStep(serverLevel263);
        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive()) return;

        double distSqr = this.distanceToSqr(target);

        // Raise dead minion once per encounter when damaged
        if (!summonedUndead && this.getHealth() < this.getMaxHealth() * 0.75f && this.level() instanceof ServerLevel sl) {
            summonedUndead = true;
            this.playSound(SoundEvents.EVOKER_PREPARE_SUMMON, 1.0f, 0.8f);
            DraugrEntity minion = ModEntities.DRAUGR.get().create(sl, net.minecraft.world.entity.EntitySpawnReason.EVENT);
            if (minion != null) {
                minion.snapTo(this.getX() + (this.random.nextDouble() - 0.5) * 3, this.getY(), this.getZ() + (this.random.nextDouble() - 0.5) * 3, this.getYRot(), 0);
                minion.finalizeSpawn(sl, sl.getCurrentDifficultyAt(minion.blockPosition()), EntitySpawnReason.MOB_SUMMONED, null);
                minion.setTarget(target);
                sl.addFreshEntity(minion);
                sl.sendParticles(ParticleTypes.SOUL, minion.getX(), minion.getY() + 0.5, minion.getZ(), 15, 0.3, 0.5, 0.3, 0.02);
            }
        }

        // Cast dark magic projectile
        if (--spellCooldown <= 0 && distSqr < 256.0 && this.hasLineOfSight(target)) {
            spellCooldown = 70 + this.random.nextInt(30);
            double dx = target.getX() - this.getX();
            double dy = target.getY(0.5) - this.getY(0.5);
            double dz = target.getZ() - this.getZ();
            WitherSkull skull = new WitherSkull(this.level(), this, new net.minecraft.world.phys.Vec3(dx, dy, dz));
            skull.setPosRaw(this.getX(), this.getEyeY() - 0.1, this.getZ());
            this.level().addFreshEntity(skull);
            this.playSound(SoundEvents.WITHER_SHOOT, 1.0f, 1.2f);
        }
    }

    @Override
    @Nullable
    public Component rankName(int level) {
        String rank = level >= 28 ? "master_necromancer" : level >= 20 ? "necromancer_adept" : level >= 12 ? "novice_necromancer" : null;
        return rank == null ? null : Component.translatable("entity.skycraft.necromancer." + rank);
    }
}
