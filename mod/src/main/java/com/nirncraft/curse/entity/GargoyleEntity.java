package com.nirncraft.curse.entity;

import com.nirncraft.curse.CurseAnims;
import com.nirncraft.curse.Curses;
import com.nirncraft.society.entity.NpcEntity;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

/**
 * {@code nirncraft:gargoyle}: a winged stone guardian of the vampires. Those placed in a lair stand as statues until
 * a mortal comes close (or strikes them), then crack free. Hard to hurt, slow, and its claws drain life.
 */
public class GargoyleEntity extends CurseMonster {
    private static final EntityDataAccessor<Boolean> DORMANT = SynchedEntityData.defineId(GargoyleEntity.class, EntityDataSerializers.BOOLEAN);

    public GargoyleEntity(EntityType<? extends GargoyleEntity> type, Level level) {
        super(type, level);
        this.xpReward = 35;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 70.0)
                .add(Attributes.ATTACK_DAMAGE, 8.0)
                .add(Attributes.MOVEMENT_SPEED, 0.24)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.ARMOR, 12.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.9);
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DORMANT, false);
    }

    public boolean isDormant() {
        return this.entityData.get(DORMANT);
    }

    public void setDormant(boolean dormant) {
        this.entityData.set(DORMANT, dormant);
        this.setNoAi(dormant);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.2, true));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.7));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 10f));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, NpcEntity.class, 10, true, false,
                (e, targetLevel263) -> e instanceof NpcEntity npc && npc.role() != com.nirncraft.society.NpcRole.VAMPIRE));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, true));
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason,
                                        @Nullable SpawnGroupData data) {
        data = super.finalizeSpawn(level, difficulty, reason, data);
        // the guardians of a lair wait as statues
        if (reason == EntitySpawnReason.STRUCTURE || reason == EntitySpawnReason.CHUNK_GENERATION) {
            setDormant(true);
            this.setPersistenceRequired();
        }
        return data;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide() || !isDormant() || this.tickCount % 10 != 0) return;
        Player near = this.level().getNearestPlayer(this, 7);
        if (near != null && !near.isCreative() && !near.isSpectator() && !Curses.isVampire(near)) awaken(near);
    }

    private void awaken(@Nullable Entity cause) {
        setDormant(false);
        playAnim(CurseAnims.Anim.AWAKEN);
        this.playSound(SoundEvents.STONE_BREAK, 1.5f, 0.5f);
        this.playSound(com.nirncraft.audio.AudioSounds.creature("gargoyle", "roar"), 0.8f, 1.0f);
        if (this.level() instanceof ServerLevel sl) {
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()), getX(), getY() + 1.2, getZ(),
                    40, 0.5, 0.9, 0.5, 0.1);
        }
        if (cause instanceof Player p) this.setTarget(p);
    }

    @Override
    public boolean hurtServer(net.minecraft.server.level.ServerLevel serverLevel263, DamageSource source, float amount) {
        if (isDormant() && !this.level().isClientSide() && source.getEntity() != null) awaken(source.getEntity());
        // stone: arrows barely scratch it
        if (source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow) amount *= 0.5f;
        return super.hurtServer(serverLevel263, source, amount);
    }

    @Override
    public boolean doHurtTarget(net.minecraft.server.level.ServerLevel serverLevel263, Entity target) {
        playAnim(CurseAnims.Anim.ATTACK);
        boolean hit = super.doHurtTarget(serverLevel263, target);
        if (hit) this.heal(2f);
        return hit;
    }

    @Override
    public boolean isInvertedHealAndHarm() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return !isDormant() && super.isPushable();
    }

    @Override
    public boolean causeFallDamage(double distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return isDormant() ? null : com.nirncraft.audio.AudioSounds.creature("gargoyle", "ambient");
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return com.nirncraft.audio.AudioSounds.creature("gargoyle", "hurt");
    }

    @Override
    protected SoundEvent getDeathSound() {
        return com.nirncraft.audio.AudioSounds.creature("gargoyle", "death");
    }

    @Override
    public float getVoicePitch() {
        return 1.3f;
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output263) {
        super.addAdditionalSaveData(output263);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        
        tag.putBoolean("Dormant", isDormant());
    
        output263.store(tag);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input263) {
        super.readAdditionalSaveData(input263);
        net.minecraft.nbt.CompoundTag tag = com.nirncraft.platform.ValueTags.read(input263 , "Dormant");
        
        setDormant(tag.getBooleanOr("Dormant",false));
    }
}
