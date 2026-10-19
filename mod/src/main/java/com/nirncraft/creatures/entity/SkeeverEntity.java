package com.nirncraft.creatures.entity;

import com.nirncraft.creatures.entity.anim.AnimSlot;
import com.nirncraft.creatures.entity.anim.AnimatedCreature;
import com.nirncraft.creatures.entity.anim.CreatureAnims;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
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
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/** {@code nirncraft:skeever}: giant rats that come in packs. Their bites may carry Rattles (weakness/hunger). */
public class SkeeverEntity extends Monster implements AnimatedCreature, Ranked {
    public SkeeverEntity(EntityType<? extends SkeeverEntity> type, Level level) {
        super(type, level);
        this.xpReward = 3;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 7.0)
                .add(Attributes.ATTACK_DAMAGE, 2.0)
                .add(Attributes.MOVEMENT_SPEED, 0.34)
                .add(Attributes.FOLLOW_RANGE, 20.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new LeapAtTargetGoal(this, 0.35f));
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.15D, true));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 6.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new com.nirncraft.creatures.ai.SmartPartyTargetGoal(this));
        this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, Chicken.class, true));
    }

    @Override
    public boolean doHurtTarget(net.minecraft.server.level.ServerLevel serverLevel263, Entity target) {
        boolean hit = super.doHurtTarget(serverLevel263, target);
        if (hit && target instanceof Player living && this.getRandom().nextFloat() < 0.08f) {
            // Rattles: the classic skeever disease
            living.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.WEAKNESS), 1200, 0), this);
            living.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.HUNGER), 300, 0), this);
        }
        return hit;
    }

    @Override
    @Nullable
    public Component rankName(int level) {
        return level >= 15 ? Component.translatable("entity.nirncraft.skeever.giant") : null;
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 0.7f;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return com.nirncraft.audio.AudioSounds.creature("skeever", "ambient");
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return com.nirncraft.audio.AudioSounds.creature("skeever", "hurt");
    }

    @Override
    protected SoundEvent getDeathSound() {
        return com.nirncraft.audio.AudioSounds.creature("skeever", "death");
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.SILVERFISH_STEP, 0.15f, 0.8f);
    }

    @Override
    public boolean isPreventingPlayerRest(net.minecraft.server.level.ServerLevel serverLevel263, Player player) {
        return this.getTarget() == player;
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
        if (!this.level().isClientSide()) CreatureAnims.play(this, this.getRandom().nextInt(3) == 0 ? AnimSlot.ATTACK2 : AnimSlot.ATTACK);
        return swung263;
    }
}
