package com.nirncraft.magic.spell;

import com.nirncraft.magic.MagicRegistry;
import com.nirncraft.magic.Targeting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * The one projectile entity used by every aimed spell (Firebolt, Ice Spike, Fireball, Paralyze, Soul Trap, Calm...).
 * It has no gravity, carries its spell id in synced entity data, renders nothing and draws its own particle trail
 * on the client.
 */
public class SpellProjectile extends ThrowableProjectile {
    private static final EntityDataAccessor<String> SPELL = SynchedEntityData.defineId(SpellProjectile.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> DUAL = SynchedEntityData.defineId(SpellProjectile.class, EntityDataSerializers.BOOLEAN);
    private static final int MAX_LIFE = 100;

    private int life;

    public SpellProjectile(EntityType<? extends SpellProjectile> type, Level level) {
        super(type, level);
    }

    public SpellProjectile(Level level, LivingEntity owner, Spell spell) {
        super(MagicRegistry.SPELL_PROJECTILE.get(), owner.getX(), owner.getEyeY() - 0.1, owner.getZ(), level);
        setOwner(owner);
        this.entityData.set(SPELL, spell.id);
        this.entityData.set(DUAL, spell.dual);
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        builder.define(SPELL, "");
        builder.define(DUAL, false);
    }

    /** The spell this projectile carries (the dual-cast variant if it was dual cast). */
    @Nullable
    public Spell spell() {
        Spell spell = Spells.byId(this.entityData.get(SPELL));
        return spell != null && this.entityData.get(DUAL) ? spell.dualCast() : spell;
    }

    @Override
    protected double getDefaultGravity() {
        return 0f;
    }

    @Nullable
    public ServerPlayer caster() {
        return getOwner() instanceof ServerPlayer p ? p : null;
    }

    @Override
    public void tick() {
        super.tick();
        if (isRemoved()) return;
        Spell spell = spell();
        if (level().isClientSide()) {
            if (spell != null) trail(spell);
            return;
        }
        life++;
        ServerPlayer caster = caster();
        if (spell == null || caster == null || life > maxLife(spell)) {
            if (spell != null && caster != null && spell.piercing && spell.impact != null) {
                spell.impact.hit(this, caster, spell, null, position());
            }
            discard();
            return;
        }
        if (spell.element == Element.FIRE && isInWater()) {
            level().playSound(null, getX(), getY(), getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.6f, 1.4f);
            ((ServerLevel) level()).sendParticles(ParticleTypes.CLOUD, getX(), getY(), getZ(), 8, 0.2, 0.2, 0.2, 0.02);
            discard();
            return;
        }
        if (spell.flight != null) spell.flight.tick(this, caster, spell);
    }

    private static int maxLife(Spell spell) {
        return spell.piercing && spell.duration > 0 ? spell.duration : MAX_LIFE;
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        if (!super.canHitEntity(target)) return false;
        Entity owner = getOwner();
        if (target == owner) return false;
        Spell spell = spell();
        if (spell != null && spell.piercing) return false;
        return !(owner instanceof ServerPlayer p) || !Targeting.isFriendly(p, target) || spell != null && !spell.hostile;
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (level().isClientSide()) return;
        Spell spell = spell();
        ServerPlayer caster = caster();
        if (spell != null && caster != null && spell.impact != null) {
            LivingEntity target = result.getEntity() instanceof LivingEntity l ? l : null;
            spell.impact.hit(this, caster, spell, target, result.getLocation());
        }
        discard();
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        if (level().isClientSide()) return;
        Spell spell = spell();
        ServerPlayer caster = caster();
        if (spell != null && caster != null && spell.impact != null) {
            Vec3 pos = result.getLocation().add(result.getDirection().getUnitVec3().scale(0.2));
            spell.impact.hit(this, caster, spell, null, pos);
        }
        discard();
    }

    @Override
    public boolean isOnFire() {
        return false;
    }

    @Override
    protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output263) {
        super.addAdditionalSaveData(output263);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        
        tag.putString("spell", this.entityData.get(SPELL));
        tag.putBoolean("dual", this.entityData.get(DUAL));
        tag.putInt("life", life);
    
        output263.store(tag);
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input263) {
        super.readAdditionalSaveData(input263);
        net.minecraft.nbt.CompoundTag tag = com.nirncraft.platform.ValueTags.read(input263 , "spell", "dual", "life");
        
        this.entityData.set(SPELL, tag.getStringOr("spell", ""));
        this.entityData.set(DUAL, tag.getBooleanOr("dual", false));
        this.life = tag.getIntOr("life", 0);
    }

    // ------------------------------------------------------------------ client trail

    private void trail(Spell spell) {
        Level level = level();
        RandomSource r = random;
        Vec3 motion = getDeltaMovement();
        double x = getX(), y = getY() + getBbHeight() / 2, z = getZ();
        int steps = Math.max(1, (int) Math.ceil(motion.length() / (spell.dual ? 0.3 : 0.5)));
        for (int i = 0; i < steps; i++) {
            double f = i / (double) steps;
            double px = x - motion.x * f, py = y - motion.y * f, pz = z - motion.z * f;
            switch (spell.element) {
                case FIRE -> {
                    level.addParticle(ParticleTypes.FLAME, px + jitter(r, 0.12), py + jitter(r, 0.12), pz + jitter(r, 0.12), 0, 0.01, 0);
                    if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.SMOKE, px, py, pz, 0, 0.02, 0);
                    if (spell.tier.ordinal() >= Tier.ADEPT.ordinal()) level.addParticle(ParticleTypes.LAVA, px, py, pz, 0, 0, 0);
                }
                case FROST -> {
                    level.addParticle(ParticleTypes.SNOWFLAKE, px + jitter(r, 0.15), py + jitter(r, 0.15), pz + jitter(r, 0.15), 0, 0, 0);
                    level.addParticle(dust(0.75f, 0.9f, 1f, 1.2f), px, py, pz, 0, 0, 0);
                    if (spell.piercing) {
                        for (int k = 0; k < 4; k++) {
                            double a = r.nextDouble() * Math.PI * 2, rad = r.nextDouble() * spell.radius;
                            level.addParticle(ParticleTypes.SNOWFLAKE, px + Math.cos(a) * rad, py + jitter(r, 1.2), pz + Math.sin(a) * rad,
                                    -Math.sin(a) * 0.2, 0, Math.cos(a) * 0.2);
                        }
                        level.addParticle(ParticleTypes.CLOUD, px + jitter(r, 1.5), py + jitter(r, 1), pz + jitter(r, 1.5), 0, 0, 0);
                    }
                }
                case SHOCK -> {
                    level.addParticle(ParticleTypes.ELECTRIC_SPARK, px + jitter(r, 0.2), py + jitter(r, 0.2), pz + jitter(r, 0.2), 0, 0, 0);
                    level.addParticle(dust(0.7f, 0.8f, 1f, 1.0f), px, py, pz, 0, 0, 0);
                }
                case HOLY -> level.addParticle(dust(1f, 0.9f, 0.5f, 1.2f), px + jitter(r, 0.1), py + jitter(r, 0.1), pz + jitter(r, 0.1), 0, 0, 0);
                case SOUL -> {
                    level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, px + jitter(r, 0.1), py + jitter(r, 0.1), pz + jitter(r, 0.1), 0, 0.01, 0);
                    if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.SOUL, px, py, pz, 0, 0.02, 0);
                }
                case MIND -> {
                    level.addParticle(dust(0.6f, 0.4f, 1f, 1.1f), px + jitter(r, 0.1), py + jitter(r, 0.1), pz + jitter(r, 0.1), 0, 0, 0);
                    if (r.nextInt(2) == 0) level.addParticle(ParticleTypes.WITCH, px, py, pz, 0, 0, 0);
                }
                case ARCANE -> {
                    level.addParticle(ParticleTypes.END_ROD, px + jitter(r, 0.05), py + jitter(r, 0.05), pz + jitter(r, 0.05), 0, 0, 0);
                    level.addParticle(dust(0.5f, 1f, 0.7f, 1f), px, py, pz, 0, 0, 0);
                }
                default -> level.addParticle(ParticleTypes.ENCHANT, px, py, pz, 0, 0, 0);
            }
        }
    }

    private static double jitter(RandomSource r, double amount) {
        return (r.nextDouble() - 0.5) * 2 * amount;
    }

    private static ParticleOptions dust(float red, float green, float blue, float scale) {
        return new DustParticleOptions(net.minecraft.util.ARGB.colorFromFloat(1f, red, green, blue), scale);
    }
}
