package com.skycraft.curse.entity;

import com.skycraft.curse.CurseAnims;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LeapAtTargetGoal;
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

/**
 * {@code skycraft:death_hound}: the vampires' gaunt, red-eyed undead hounds. Fast, they hunt in the dark beside their
 * masters, and their bite can carry Sanguinare Vampiris.
 */
public class DeathHoundEntity extends CurseMonster {
    public DeathHoundEntity(EntityType<? extends DeathHoundEntity> type, Level level) {
        super(type, level);
        this.xpReward = 12;
        this.setMaxUpStep(1.0f);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 22.0)
                .add(Attributes.ATTACK_DAMAGE, 4.5)
                .add(Attributes.MOVEMENT_SPEED, 0.36)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.ARMOR, 2.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new LeapAtTargetGoal(this, 0.4f));
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.25, true));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.9));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 10f));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, NpcEntity.class, 10, true, false,
                e -> e instanceof NpcEntity npc && npc.role() != com.skycraft.society.NpcRole.VAMPIRE));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, true));
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        playAnim(CurseAnims.Anim.ATTACK);
        return super.doHurtTarget(target);
    }

    @Override
    public MobType getMobType() {
        return MobType.UNDEAD;
    }

    @Override
    protected float fireWeakness() {
        return 0.5f;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return com.skycraft.audio.AudioSounds.creature("death_hound", "ambient");
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return com.skycraft.audio.AudioSounds.creature("death_hound", "hurt");
    }

    @Override
    protected SoundEvent getDeathSound() {
        return com.skycraft.audio.AudioSounds.creature("death_hound", "death");
    }

    @Override
    public float getVoicePitch() {
        return 0.45f + this.random.nextFloat() * 0.1f;
    }
}
