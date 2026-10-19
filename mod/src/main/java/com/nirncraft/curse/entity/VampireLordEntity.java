package com.nirncraft.curse.entity;

import com.nirncraft.curse.CurseAnims;
import com.nirncraft.curse.CurseRegistry;
import com.nirncraft.magic.MagicDamage;
import com.nirncraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.EnumSet;

/**
 * {@code nirncraft:vampire_lord}: the master of a vampire lair (and Harkon himself). Rises into the air to cast Drain
 * Life, raises the dead and calls a death hound to its side, scatters into bats when hard pressed, and fights with
 * claws up close. Burns badly.
 */
public class VampireLordEntity extends CurseMonster {
    private static final EntityDataAccessor<Boolean> LEVITATING = SynchedEntityData.defineId(VampireLordEntity.class, EntityDataSerializers.BOOLEAN);
    private long nextRaise;
    private long nextBats;
    private float damageSinceBats;

    public VampireLordEntity(EntityType<? extends VampireLordEntity> type, Level level) {
        super(type, level);
        this.xpReward = 60;
        this.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT).setBaseValue(1.1f);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 90.0)
                .add(Attributes.ATTACK_DAMAGE, 8.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.FOLLOW_RANGE, 40.0)
                .add(Attributes.ARMOR, 6.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.6);
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(LEVITATING, false);
    }

    public boolean isLevitating() {
        return this.entityData.get(LEVITATING);
    }

    private void setLevitating(boolean on) {
        this.entityData.set(LEVITATING, on);
        this.setNoGravity(on);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new CastGoal());
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.15, true));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.8));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12f));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, NpcEntity.class, 10, true, false,
                (e, targetLevel263) -> e instanceof NpcEntity npc && npc.role() != com.nirncraft.society.NpcRole.VAMPIRE));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, true));
    }

    @Override
    public boolean doHurtTarget(net.minecraft.server.level.ServerLevel serverLevel263, Entity target) {
        playAnim(CurseAnims.Anim.ATTACK);
        boolean hit = super.doHurtTarget(serverLevel263, target);
        if (hit) this.heal(2f);
        return hit;
    }

    @Override
    protected float fireWeakness() {
        return 0.5f;
    }

    @Override
    public boolean hurtServer(net.minecraft.server.level.ServerLevel serverLevel263, DamageSource source, float amount) {
        boolean hurt = super.hurtServer(serverLevel263, source, amount);
        if (hurt && !this.level().isClientSide()) {
            damageSinceBats += amount;
            if (damageSinceBats > this.getMaxHealth() * 0.2f && this.level().getGameTime() >= nextBats && this.getTarget() != null) bats();
        }
        return hurt;
    }

    /** Scatters into a cloud of bats and comes together again behind its foe. */
    private void bats() {
        LivingEntity target = this.getTarget();
        if (target == null || !(this.level() instanceof ServerLevel sl)) return;
        nextBats = sl.getGameTime() + 20 * 12;
        damageSinceBats = 0;
        Vec3 behind = target.position().subtract(target.getViewVector(1f).multiply(1, 0, 1).normalize().scale(3));
        int y = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(behind.x), Mth.floor(behind.z));
        if (Math.abs(y - target.getY()) > 4) y = Mth.floor(target.getY());
        sl.sendParticles(ParticleTypes.SQUID_INK, getX(), getY() + 1.3, getZ(), 30, 0.5, 0.9, 0.5, 0.05);
        this.playSound(SoundEvents.BAT_TAKEOFF, 1.4f, 0.6f);
        this.teleportTo(behind.x, y, behind.z);
        sl.sendParticles(ParticleTypes.SQUID_INK, getX(), getY() + 1.3, getZ(), 30, 0.5, 0.9, 0.5, 0.05);
        playAnim(CurseAnims.Anim.CAST);
    }

    /** Raises a thrall: a death hound or the dead of the lair (skeletons). */
    private void raiseDead(ServerLevel level) {
        nextRaise = level.getGameTime() + 20 * 25;
        playAnim(CurseAnims.Anim.HOWL);
        this.playSound(SoundEvents.EVOKER_PREPARE_SUMMON, 1.2f, 0.6f);
        int existing = level.getEntitiesOfClass(Mob.class, getBoundingBox().inflate(24),
                m -> m.entityTags().contains("nirncraft_lord_thrall") && m.isAlive()).size();
        if (existing >= 3) return;
        for (int i = 0; i < 2; i++) {
            Mob thrall = i == 0 ? CurseRegistry.DEATH_HOUND.get().create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT) : net.minecraft.world.entity.EntityTypes.SKELETON.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
            if (thrall == null) continue;
            BlockPos at = this.blockPosition().offset(this.random.nextInt(5) - 2, 0, this.random.nextInt(5) - 2);
            thrall.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, this.random.nextFloat() * 360, 0);
            if (!level.noCollision(thrall)) thrall.snapTo(getX(), getY(), getZ());
            thrall.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.MOB_SUMMONED, null);
            thrall.addTag("nirncraft_lord_thrall");
            if (i == 1) thrall.setCustomName(Component.translatable("entity.nirncraft.vampire_lord.thrall"));
            thrall.setTarget(this.getTarget());
            level.addFreshEntity(thrall);
            level.sendParticles(ParticleTypes.SOUL, thrall.getX(), thrall.getY() + 0.5, thrall.getZ(), 12, 0.3, 0.5, 0.3, 0.02);
        }
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide() && isLevitating() && this.random.nextInt(3) == 0) {
            this.level().addParticle(new DustParticleOptions(net.minecraft.util.ARGB.colorFromFloat(1f, 0.35f, 0.02f, 0.05f), 0.8f),
                    getRandomX(0.6), getY() + 0.2, getRandomZ(0.6), 0, -0.02, 0);
        }
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.PHANTOM_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PHANTOM_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PHANTOM_DEATH;
    }

    @Override
    public float getVoicePitch() {
        return 0.5f;
    }

    @Override
    public boolean causeFallDamage(double distance, float multiplier, DamageSource source) {
        return false;
    }

    /** Hovers up and pours Drain Life into its target; now and then raises the dead. */
    private class CastGoal extends Goal {
        private int channel;
        private int cooldown;

        CastGoal() {
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = getTarget();
            if (t == null || !t.isAlive() || --cooldown > 0) return false;
            double d = distanceToSqr(t);
            return d > 9 && d < 20 * 20 && hasLineOfSight(t);
        }

        @Override
        public boolean canContinueToUse() {
            LivingEntity t = getTarget();
            return channel > 0 && t != null && t.isAlive() && hasLineOfSight(t);
        }

        @Override
        public void start() {
            channel = 50;
            setLevitating(true);
            getNavigation().stop();
            setDeltaMovement(getDeltaMovement().add(0, 0.35, 0));
        }

        @Override
        public void stop() {
            channel = 0;
            cooldown = 40 + random.nextInt(40);
            setLevitating(false);
        }

        @Override
        public void tick() {
            LivingEntity t = getTarget();
            if (t == null) return;
            channel--;
            getLookControl().setLookAt(t, 30f, 30f);
            // hover about two blocks up
            int ground = level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, getBlockX(), getBlockZ());
            double want = Math.max(ground, t.getY()) + 1.8;
            Vec3 v = getDeltaMovement();
            setDeltaMovement(v.x * 0.6, Mth.clamp((want - getY()) * 0.15, -0.1, 0.12), v.z * 0.6);
            if (!(level() instanceof ServerLevel sl)) return;
            if (sl.getGameTime() >= nextRaise && channel == 40) raiseDead(sl);
            if (channel % 10 == 0) {
                playAnim(CurseAnims.Anim.CAST);
                Vec3 from = position().add(0, getBbHeight() * 0.7, 0).add(getViewVector(1f).scale(0.8));
                Vec3 to = t.position().add(0, t.getBbHeight() * 0.6, 0);
                Vec3 d = to.subtract(from);
                int steps = (int) (d.length() * 3);
                for (int i = 0; i <= steps; i++) {
                    Vec3 p = from.add(d.scale(i / (double) Math.max(1, steps)));
                    sl.sendParticles(new DustParticleOptions(net.minecraft.util.ARGB.colorFromFloat(1f, 0.7f, 0.04f, 0.08f), 1.1f), p.x, p.y, p.z, 1, 0.03, 0.03, 0.03, 0);
                }
                if (com.nirncraft.platform.Effects.hurt(t, MagicDamage.source(sl, MagicDamage.SPELL, null, VampireLordEntity.this), 3f)) heal(3f);
                if (channel % 20 == 0) playSound(SoundEvents.HONEY_DRINK.value(), 0.8f, 0.5f);
            }
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }
    }
}
