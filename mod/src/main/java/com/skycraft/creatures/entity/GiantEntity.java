package com.skycraft.creatures.entity;

import com.skycraft.creatures.entity.anim.AnimSlot;
import com.skycraft.creatures.entity.anim.AnimatedCreature;
import com.skycraft.creatures.entity.anim.CreatureAnims;
import com.skycraft.core.Notifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * {@code skycraft:giant}: peaceful herders unless provoked. Get too close and the giant stomps the ground in
 * warning; stay and it attacks. Its club sends you flying into the sky.
 */
public class GiantEntity extends Monster implements AnimatedCreature {
    private static final int SMASH_COOLDOWN = 36;
    @Nullable
    private BlockPos home;
    @Nullable
    private UUID warned;
    private int warnTicks;
    private int lastSmash = -1000;
    private boolean initialized;

    public GiantEntity(EntityType<? extends GiantEntity> type, Level level) {
        super(type, level);
        this.xpReward = 40;
        this.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT).setBaseValue(1.5f);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 120.0)
                .add(Attributes.ATTACK_DAMAGE, 12.0)
                .add(Attributes.MOVEMENT_SPEED, 0.26)
                .add(Attributes.FOLLOW_RANGE, 24.0)
                .add(Attributes.ARMOR, 4.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.1D, true) {
            @Override
            protected boolean canPerformAttack(LivingEntity target) {
                double reach = 3.6 + target.getBbWidth() * 0.5;
                return this.isTimeToAttack() && GiantEntity.this.distanceToSqr(target) <= reach * reach && GiantEntity.this.getSensing().hasLineOfSight(target);
            }
        });
        this.goalSelector.addGoal(5, new MoveTowardsRestrictionGoal(this, 0.8D));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.6D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers());
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason,
                                        @Nullable SpawnGroupData data) {
        data = super.finalizeSpawn(level, difficulty, reason, data);
        initialize();
        // Giants keep near where they appeared. Their camps (campfire and all) are the skycraft:giant_camp structure;
        // a roaming giant never builds one.
        setHome(this.blockPosition());
        return data;
    }

    private void initialize() {
        if (initialized) return;
        initialized = true;
        this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BONE));
        this.setDropChance(EquipmentSlot.MAINHAND, 0.0f);
    }

    public void setHome(BlockPos pos) {
        this.home = pos.immutable();
        this.setHomeTo(this.home, 20);
    }

    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel serverLevel263) {
        super.customServerAiStep(serverLevel263);
        if (!initialized) initialize();
        if (warnTicks > 0) warnTicks--;
        LivingEntity target = this.getTarget();
        if (target == null) {
            if (this.tickCount % 10 == 0) checkIntruders();
        } else if (target instanceof Player player && (player.isCreative() || player.isSpectator()
                || this.distanceToSqr(player) > 24 * 24 && this.getLastHurtByMob() != player)) {
            // Giants give up chasing people who ran away.
            this.setTarget(null);
        }
    }

    private void checkIntruders() {
        Player player = this.level().getNearestPlayer(this.getX(), this.getY(), this.getZ(), 7.0, EntitySelector.NO_CREATIVE_OR_SPECTATOR);
        if (player == null) return;
        if (warned == null || !warned.equals(player.getUUID()) || warnTicks <= 0) {
            warned = player.getUUID();
            warnTicks = 80;
            stomp(player);
        } else if (warnTicks < 50 && this.distanceToSqr(player) < 5.0 * 5.0) {
            this.setTarget(player);
        }
    }

    /** The warning stomp: dust, a heavy thud and a clear message. */
    private void stomp(Player player) {
        this.getLookControl().setLookAt(player, 30f, 30f);
        CreatureAnims.play(this, AnimSlot.ATTACK3);
        this.playSound(com.skycraft.audio.AudioSounds.creature("giant", "stomp"), 2.5f, 1.0f);
        this.playSound(com.skycraft.audio.AudioSounds.creature("giant", "ambient"), 1.5f, 0.9f);
        if (this.level() instanceof ServerLevel server) {
            BlockState ground = this.level().getBlockState(this.blockPosition().below());
            if (!ground.isAir()) {
                server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), this.getX(), this.getY() + 0.1, this.getZ(),
                        40, 1.2, 0.1, 1.2, 0.15);
            }
        }
        if (player instanceof ServerPlayer sp) Notifier.message(sp, Component.translatable("creatures.skycraft.giant.warning"));
    }

    /** Club hits launch the target into the sky. */
    @Override
    public boolean doHurtTarget(net.minecraft.server.level.ServerLevel serverLevel263, Entity target) {
        if (this.tickCount - lastSmash < SMASH_COOLDOWN) return false;
        lastSmash = this.tickCount;
        boolean hit = super.doHurtTarget(serverLevel263, target);
        if (hit && target instanceof LivingEntity living) {
            double dx = target.getX() - this.getX();
            double dz = target.getZ() - this.getZ();
            double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
            living.setDeltaMovement(dx / len * 0.6, 1.45, dz / len * 0.6);
            living.syncVelocity = true;
            this.playSound(SoundEvents.IRON_GOLEM_ATTACK, 1.5f, 0.6f);
        }
        return hit;
    }

    @Override
    public boolean isPreventingPlayerRest(net.minecraft.server.level.ServerLevel serverLevel263, Player player) {
        return this.getTarget() == player;
    }


    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 0.55f;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return com.skycraft.audio.AudioSounds.creature("giant", "ambient");
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return com.skycraft.audio.AudioSounds.creature("giant", "hurt");
    }

    @Override
    protected SoundEvent getDeathSound() {
        return com.skycraft.audio.AudioSounds.creature("giant", "death");
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(com.skycraft.audio.AudioSounds.creature("giant", "stomp"), 0.3f, 1.25f);
    }

    @Override
    protected float getSoundVolume() {
        return 1.5f;
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output263) {
        super.addAdditionalSaveData(output263);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        
        if (home != null) tag.put("Home", com.skycraft.platform.NbtPositions.write(home));
        tag.putBoolean("SkycraftInit", initialized);
    
        output263.store(tag);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input263) {
        super.readAdditionalSaveData(input263);
        net.minecraft.nbt.CompoundTag tag = com.skycraft.platform.ValueTags.read(input263 , "Home", "SkycraftInit");
        
        if (tag.contains("Home")) setHome(com.skycraft.platform.NbtPositions.read(tag.getCompoundOrEmpty("Home")));
        initialized = tag.getBooleanOr("SkycraftInit",false);
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
