package com.nirncraft.creatures.entity;

import com.nirncraft.creatures.Bestiary;
import com.nirncraft.creatures.entity.anim.CreatureAnims;
import com.nirncraft.creatures.spec.CreatureSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * A hostile bestiary creature (an {@link net.minecraft.world.entity.monster.Enemy}: leveled, Monster Hunter perk):
 * chaurus, draugr-kin, atronachs, spriggans, cliff racers... Behaviour, stats and sounds come from its
 * {@link CreatureSpec}; subclasses add unique mechanics.
 */
public class SkyMonster extends Monster implements SkyCreature {
    private static final EntityDataAccessor<Integer> VARIANT = SynchedEntityData.defineId(SkyMonster.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DORMANT = SynchedEntityData.defineId(SkyMonster.class, EntityDataSerializers.BOOLEAN);

    private final CreatureAnims anims = new CreatureAnims();
    private CreatureSpec spec;
    private int lastFireHurt = -1000;
    private boolean variantChosen;
    private int emergeTicks;
    private boolean pendingEmerge;

    public SkyMonster(EntityType<? extends SkyMonster> type, Level level) {
        super(type, level);
        CreatureSpec s = spec();
        this.xpReward = s.xp;
        this.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT).setBaseValue(s.stepHeight);
        MoveControl move = CreatureCore.moveControl(this, s);
        if (move != null) this.moveControl = move;
        LookControl look = CreatureCore.lookControl(this, s);
        if (look != null) this.lookControl = look;
        if (s.move == CreatureSpec.Move.FLY || s.move == CreatureSpec.Move.HOVER) this.setNoGravity(true);
    }

    @Override
    public CreatureSpec spec() {
        if (spec == null) spec = Bestiary.spec(this.getType());
        return spec;
    }

    @Override
    public CreatureAnims anims() {
        return anims;
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(VARIANT, 0);
        builder.define(DORMANT, false);
    }

    @Override
    public boolean isDormant() {
        return this.entityData.get(DORMANT);
    }

    /** Puts the creature to sleep (statue pose) until a player comes close, or wakes it with its emerge animation. */
    public void setDormant(boolean dormant) {
        boolean was = isDormant();
        this.entityData.set(DORMANT, dormant);
        if (was && !dormant && !this.level().isClientSide()) {
            CreatureAnims.play(this, com.nirncraft.creatures.entity.anim.AnimSlot.EMERGE);
            emergeTicks = 30;
            playEmergeSound();
        }
    }

    /** A statue grinding awake, ash spawn rising from the ash: the creature's wake or emerge sound, if it has one. */
    private void playEmergeSound() {
        SoundEvent s = com.nirncraft.audio.AudioSounds.creatureOrNull(spec().id, "wake");
        if (s == null) s = com.nirncraft.audio.AudioSounds.creatureOrNull(spec().id, "emerge");
        if (s != null) this.playSound(s, spec().soundVolume * 1.2f, this.getVoicePitch());
    }

    @Override
    public boolean animSleeping() {
        return isDormant();
    }

    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel serverLevel263) {
        super.customServerAiStep(serverLevel263);
        if (this.tickCount == 2 && pendingEmerge) {
            pendingEmerge = false;
            CreatureAnims.play(this, com.nirncraft.creatures.entity.anim.AnimSlot.EMERGE);
            emergeTicks = 30;
            playEmergeSound();
        }
        if (isDormant()) {
            this.setTarget(null);
            this.getNavigation().stop();
            this.getMoveControl().setWantedPosition(this.getX(), this.getY(), this.getZ(), 0);
            if (this.tickCount % 10 == 0) {
                net.minecraft.world.entity.player.Player p = this.level().getNearestPlayer(this, spec().ambush);
                if (p != null && !p.isCreative() && !p.isSpectator()) {
                    setDormant(false);
                    this.setTarget(p);
                }
            }
        } else if (emergeTicks > 0) {
            emergeTicks--;
            this.getNavigation().stop();
            this.getMoveControl().setWantedPosition(this.getX(), this.getY(), this.getZ(), 0);
        }
    }

    @Override
    protected void registerGoals() {
        CreatureCore.registerGoals(this, spec(), this.goalSelector, this.targetSelector);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        PathNavigation nav = CreatureCore.navigation(this, level, spec());
        return nav != null ? nav : super.createNavigation(level);
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (!anims.onEvent(this, id)) super.handleEntityEvent(id);
    }

    // ------------------------------------------------------------------ variant & names

    @Override
    public int getVariant() {
        return this.entityData.get(VARIANT);
    }

    @Override
    public void setVariant(int variant) {
        this.entityData.set(VARIANT, spec().tinted ? variant : Mth.clamp(variant, 0, Math.max(0, spec().variants - 1)));
        this.variantChosen = true;
    }

    @Override
    public int animVariant() {
        return getVariant();
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason,
                                        @Nullable SpawnGroupData data) {
        data = super.finalizeSpawn(level, difficulty, reason, data);
        if (!variantChosen && spec().variantPicker != null) setVariant(spec().variantPicker.pick(level, this.blockPosition(), this.getRandom()));
        CreatureCore.applyVariantPower(this, spec(), getVariant());
        if (spec().ambush > 0 && reason == EntitySpawnReason.NATURAL && this.getRandom().nextFloat() < spec().dormantChance) {
            this.entityData.set(DORMANT, true);
        } else if (spec().emergeOnSpawn && (reason == EntitySpawnReason.NATURAL || reason == EntitySpawnReason.MOB_SUMMONED)) {
            pendingEmerge = true;
        }
        return data;
    }

    @Override
    protected Component getTypeName() {
        Component v = CreatureCore.variantName(spec(), getVariant());
        return v != null ? v : super.getTypeName();
    }

    @Override
    @Nullable
    public Component rankName(int level) {
        return CreatureCore.rankName(spec(), level);
    }

    // ------------------------------------------------------------------ combat & body

    @Override
    public boolean hurtServer(net.minecraft.server.level.ServerLevel serverLevel263, DamageSource source, float amount) {
        if (source.is(DamageTypeTags.IS_FIRE)) lastFireHurt = this.tickCount;
        if (isDormant() && !this.level().isClientSide()) setDormant(false);
        float mult = CreatureCore.damageMultiplier(spec(), source);
        if (mult <= 0) return false;
        return super.hurtServer(serverLevel263, source, amount * mult);
    }

    @Override
    public boolean fireImmune() {
        return spec().fireImmune || super.fireImmune();
    }

    @Override
    public boolean canFreeze() {
        return spec().frostMult > 0.5f && super.canFreeze();
    }

    @Override
    public boolean canBreatheUnderwater() {
        return spec().waterBreathing || spec().undead || spec().move == CreatureSpec.Move.SWIM;
    }

    @Override
    public boolean isInvertedHealAndHarm() {
        return spec().undead || super.isInvertedHealAndHarm();
    }

    @Override
    public boolean causeFallDamage(double distance, float multiplier, DamageSource source) {
        if (spec().move != CreatureSpec.Move.WALK) return false;
        return super.causeFallDamage(distance, multiplier, source);
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
        if (spec().move == CreatureSpec.Move.WALK) super.checkFallDamage(y, onGround, state, pos);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide()) {
            if (spec().clientTick != null) spec().clientTick.accept(this);
        } else {
            CreatureCore.serverTick(this, spec(), lastFireHurt);
        }
    }

    @Override
    public boolean animFlying() {
        CreatureSpec.Move m = spec().move;
        return m == CreatureSpec.Move.HOVER || m == CreatureSpec.Move.FLY && !this.onGround();
    }

    @Override
    public boolean animSwimming() {
        return spec().move == CreatureSpec.Move.SWIM || this.isInWater() && !this.onGround();
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return !spec().persistent && super.removeWhenFarAway(distance);
    }

    @Override
    public boolean isPushable() {
        return spec().width < 2.5f && super.isPushable();
    }

    // ------------------------------------------------------------------ sounds (placeholders until the audio module's own)

    @Override
    @Nullable
    protected SoundEvent getAmbientSound() {
        return spec().ambientSound != null ? spec().ambientSound.get() : super.getAmbientSound();
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return spec().hurtSound != null ? spec().hurtSound.get() : super.getHurtSound(source);
    }

    @Override
    protected SoundEvent getDeathSound() {
        return spec().deathSound != null ? spec().deathSound.get() : super.getDeathSound();
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        if (spec().stepSound != null) this.playSound(spec().stepSound.get(), 0.4f, 1.0f);
        else if (spec().move == CreatureSpec.Move.WALK) super.playStepSound(pos, state);
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * spec().voicePitch;
    }

    @Override
    protected float getSoundVolume() {
        return spec().soundVolume;
    }

    @Override
    public int getAmbientSoundInterval() {
        return spec().ambientInterval;
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!this.level().isClientSide() && spec().onDeath != null) spec().onDeath.accept(this);
    }

    // ------------------------------------------------------------------ save

    @Override
    public void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output263) {
        super.addAdditionalSaveData(output263);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        
        tag.putInt("Variant", getVariant());
        if (isDormant()) tag.putBoolean("Dormant", true);
    
        output263.store(tag);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input263) {
        super.readAdditionalSaveData(input263);
        net.minecraft.nbt.CompoundTag tag = com.nirncraft.platform.ValueTags.read(input263 , "Variant", "Dormant");
        
        if (tag.contains("Variant")) setVariant(tag.getIntOr("Variant",0));
        this.entityData.set(DORMANT, tag.getBooleanOr("Dormant",false));
    }
}
