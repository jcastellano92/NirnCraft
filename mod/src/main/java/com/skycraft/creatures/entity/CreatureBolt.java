package com.skycraft.creatures.entity;

import com.skycraft.magic.MagicDamage;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * {@code skycraft:creature_bolt}: what creatures throw and cast — fire bolts (atronachs, scamps, dragon priests),
 * ice spikes, lightning, poison spit (chaurus, frostbite spiders), ash (ash spawn), rocks (giants, gargoyles) and
 * ballista bolts. Drawn as a particle trail; damage by element, so wards and resistances apply to the spells.
 */
public class CreatureBolt extends ThrowableProjectile {
    public enum Kind { FIRE, FROST, SHOCK, POISON, ASH, ROCK, BOLT, SHADOW, WATER }

    private static final EntityDataAccessor<Byte> KIND = SynchedEntityData.defineId(CreatureBolt.class, EntityDataSerializers.BYTE);
    private static final DustParticleOptions POISON_DUST = new DustParticleOptions(new Vector3f(0.45f, 0.75f, 0.15f), 1.2f);
    private static final DustParticleOptions ASH_DUST = new DustParticleOptions(new Vector3f(0.3f, 0.28f, 0.26f), 1.5f);
    private static final DustParticleOptions ROCK_DUST = new DustParticleOptions(new Vector3f(0.5f, 0.48f, 0.44f), 1.6f);
    private static final DustParticleOptions SHADOW_DUST = new DustParticleOptions(new Vector3f(0.35f, 0.1f, 0.45f), 1.3f);

    private float damage = 4f;
    private int life;

    public CreatureBolt(EntityType<? extends CreatureBolt> type, Level level) {
        super(type, level);
    }

    public CreatureBolt(EntityType<? extends CreatureBolt> type, LivingEntity owner, Level level, Kind kind, float damage) {
        super(type, owner, level);
        setKind(kind);
        this.damage = damage;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(KIND, (byte) 0);
    }

    public Kind kind() {
        return Kind.values()[Math.floorMod(this.entityData.get(KIND), Kind.values().length)];
    }

    public void setKind(Kind kind) {
        this.entityData.set(KIND, (byte) kind.ordinal());
    }

    @Override
    protected float getGravity() {
        return switch (kind()) {
            case POISON, ASH, WATER -> 0.02f;
            case ROCK -> 0.04f;
            case BOLT -> 0.01f;
            default -> 0f;
        };
    }

    @Override
    public void tick() {
        super.tick();
        if (++life > 120) {
            this.discard();
            return;
        }
        if (this.level().isClientSide) {
            ParticleOptions p = trail();
            for (int i = 0; i < 3; i++) {
                double f = i / 3.0;
                Vec3 v = this.getDeltaMovement();
                this.level().addParticle(p, this.getX() - v.x * f, this.getY() + 0.1 - v.y * f, this.getZ() - v.z * f,
                        (this.random.nextDouble() - 0.5) * 0.02, (this.random.nextDouble() - 0.5) * 0.02, (this.random.nextDouble() - 0.5) * 0.02);
            }
            if (kind() == Kind.FIRE && this.random.nextInt(3) == 0) {
                this.level().addParticle(ParticleTypes.SMOKE, this.getX(), this.getY() + 0.1, this.getZ(), 0, 0.01, 0);
            }
        }
    }

    private ParticleOptions trail() {
        return switch (kind()) {
            case FIRE -> ParticleTypes.FLAME;
            case FROST -> ParticleTypes.SNOWFLAKE;
            case SHOCK -> ParticleTypes.ELECTRIC_SPARK;
            case POISON -> POISON_DUST;
            case ASH -> ASH_DUST;
            case ROCK -> ROCK_DUST;
            case BOLT -> ParticleTypes.CRIT;
            case SHADOW -> SHADOW_DUST;
            case WATER -> ParticleTypes.SPLASH;
        };
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        super.onHitEntity(hit);
        if (this.level().isClientSide) return;
        Entity target = hit.getEntity();
        Entity owner = this.getOwner();
        if (target == owner || owner != null && target.getType() == owner.getType()) return;
        DamageSource src = switch (kind()) {
            case FIRE -> MagicDamage.source(this.level(), MagicDamage.FIRE, this, owner);
            case FROST -> MagicDamage.source(this.level(), MagicDamage.FROST, this, owner);
            case SHOCK -> MagicDamage.source(this.level(), MagicDamage.SHOCK, this, owner);
            case SHADOW -> MagicDamage.source(this.level(), MagicDamage.SPELL, this, owner);
            default -> this.damageSources().thrown(this, owner);
        };
        target.hurt(src, damage);
        if (target instanceof LivingEntity living) {
            switch (kind()) {
                case FIRE -> living.setSecondsOnFire(4);
                case FROST -> {
                    living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 1), owner);
                    if (living.canFreeze()) living.setTicksFrozen(Math.min(living.getTicksRequiredToFreeze() + 60, living.getTicksFrozen() + 50));
                }
                case POISON -> living.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 1), owner);
                case ASH -> {
                    living.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0), owner);
                    living.setSecondsOnFire(2);
                }
                case ROCK, BOLT -> {
                    Vec3 push = this.getDeltaMovement().multiply(1, 0, 1).normalize().scale(0.6);
                    living.push(push.x, 0.2, push.z);
                }
                case SHADOW -> living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0), owner);
                default -> {}
            }
        }
        burst();
        this.discard();
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (!this.level().isClientSide && result.getType() == HitResult.Type.BLOCK) {
            burst();
            this.discard();
        }
    }

    private void burst() {
        if (!(this.level() instanceof ServerLevel server)) return;
        server.sendParticles(trail(), this.getX(), this.getY(), this.getZ(), 12, 0.25, 0.25, 0.25, 0.05);
        switch (kind()) {
            case FIRE -> this.playSound(com.skycraft.audio.AudioSounds.spell("impact_fire"), 0.8f, 1.1f);
            case FROST -> this.playSound(com.skycraft.audio.AudioSounds.spell("impact_frost"), 0.7f, 1.1f);
            case SHOCK -> this.playSound(com.skycraft.audio.AudioSounds.spell("impact_shock"), 0.6f, 1.2f);
            case ROCK -> this.playSound(SoundEvents.STONE_BREAK, 1.0f, 0.7f);
            default -> this.playSound(SoundEvents.SLIME_SQUISH_SMALL, 0.8f, 1.0f);
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte("Kind", (byte) kind().ordinal());
        tag.putFloat("Damage", damage);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setKind(Kind.values()[Math.floorMod(tag.getByte("Kind"), Kind.values().length)]);
        damage = tag.getFloat("Damage");
    }
}
