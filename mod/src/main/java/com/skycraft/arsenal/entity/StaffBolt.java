package com.skycraft.arsenal.entity;

import com.skycraft.arsenal.ArsenalEntities;
import com.skycraft.arsenal.StaffEffects;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
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

import java.util.HashSet;
import java.util.Set;

/**
 * Projectile of the aimed staves (fireball, ice storm, calm, fury, paralysis, Wabbajack) and of Bloodskal's energy
 * blade. Weightless, invisible (NoopRenderer); draws its own particle trail on the client. Ice storms and energy
 * blades pass through creatures, hurting each one once.
 */
public class StaffBolt extends ThrowableProjectile {
    public enum Kind {
        FIREBALL(60, false), ICE_STORM(50, true), CALM(60, false), FURY(60, false), PARALYSIS(60, false),
        WABBAJACK(80, false), BLADE(24, true);

        public final int life;
        public final boolean piercing;

        Kind(int life, boolean piercing) {
            this.life = life;
            this.piercing = piercing;
        }

        static Kind byId(int id) {
            Kind[] v = values();
            return v[Math.max(0, Math.min(v.length - 1, id))];
        }
    }

    private static final EntityDataAccessor<Byte> KIND = SynchedEntityData.defineId(StaffBolt.class, EntityDataSerializers.BYTE);

    private float power = 1f;
    private int life;
    private final Set<Integer> pierced = new HashSet<>();

    public StaffBolt(EntityType<? extends StaffBolt> type, Level level) {
        super(type, level);
    }

    public StaffBolt(Level level, LivingEntity owner, Kind kind, float power) {
        super(ArsenalEntities.STAFF_BOLT.get(), owner.getX(), owner.getEyeY() - 0.1, owner.getZ(), level);
        setOwner(owner);
        this.entityData.set(KIND, (byte) kind.ordinal());
        this.power = power;
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        builder.define(KIND, (byte) 0);
    }

    public Kind kind() {
        return Kind.byId(this.entityData.get(KIND));
    }

    public float power() {
        return power;
    }

    @Nullable
    public ServerPlayer caster() {
        return getOwner() instanceof ServerPlayer p ? p : null;
    }

    @Override
    protected double getDefaultGravity() {
        return 0f;
    }

    @Override
    public void tick() {
        super.tick();
        Kind kind = kind();
        if (level().isClientSide()) {
            trail(kind);
            return;
        }
        life++;
        ServerPlayer caster = caster();
        if (caster == null || life > kind.life) {
            if (caster != null && kind == Kind.FIREBALL) StaffEffects.impact(this, caster, kind, null, position());
            discard();
            return;
        }
        if (kind.piercing) {
            double r = kind == Kind.ICE_STORM ? 2.5 : 0.8;
            for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(r),
                    e -> e != caster && e.isAlive() && !e.isSpectator() && !pierced.contains(e.getId()))) {
                pierced.add(e.getId());
                StaffEffects.impact(this, caster, kind, e, e.position());
            }
            if (kind == Kind.ICE_STORM && life % 10 == 0) pierced.clear(); // the storm keeps biting
        }
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        if (kind().piercing) return false;
        if (target == getOwner()) return false;
        return super.canHitEntity(target);
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (level().isClientSide()) return;
        ServerPlayer caster = caster();
        if (caster != null) {
            LivingEntity target = result.getEntity() instanceof LivingEntity l ? l : null;
            StaffEffects.impact(this, caster, kind(), target, result.getLocation());
        }
        discard();
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        if (level().isClientSide()) return;
        ServerPlayer caster = caster();
        if (caster != null && !kind().piercing) {
            Vec3 pos = result.getLocation().add(Vec3.atLowerCornerOf(result.getDirection().getUnitVec3i()).scale(0.25));
            StaffEffects.impact(this, caster, kind(), null, pos);
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
        
        tag.putByte("kind", this.entityData.get(KIND));
        tag.putFloat("power", power);
        tag.putInt("life", life);
    
        output263.store(tag);
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input263) {
        super.readAdditionalSaveData(input263);
        net.minecraft.nbt.CompoundTag tag = com.skycraft.platform.ValueTags.read(input263 , "kind", "power", "life");
        
        this.entityData.set(KIND, tag.getByteOr("kind",(byte)0));
        this.power = tag.contains("power") ? tag.getFloatOr("power",0F) : 1f;
        this.life = tag.getIntOr("life",0);
    }

    // ------------------------------------------------------------------ client trail

    private static DustParticleOptions dust(float r, float g, float b, float size) {
        return new DustParticleOptions(net.minecraft.util.ARGB.colorFromFloat(1f, r, g, b), size);
    }

    private static double j(RandomSource r, double s) {
        return (r.nextDouble() - 0.5) * 2 * s;
    }

    private void trail(Kind kind) {
        Level level = level();
        RandomSource r = random;
        Vec3 m = getDeltaMovement();
        double x = getX(), y = getY() + getBbHeight() / 2, z = getZ();
        int steps = Math.max(1, (int) Math.ceil(m.length() / 0.4));
        for (int i = 0; i < steps; i++) {
            double f = i / (double) steps;
            double px = x - m.x * f, py = y - m.y * f, pz = z - m.z * f;
            switch (kind) {
                case FIREBALL -> {
                    level.addParticle(ParticleTypes.FLAME, px + j(r, 0.15), py + j(r, 0.15), pz + j(r, 0.15), 0, 0.01, 0);
                    if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.LARGE_SMOKE, px, py, pz, 0, 0.01, 0);
                }
                case ICE_STORM -> {
                    for (int k = 0; k < 3; k++) {
                        double a = r.nextDouble() * Math.PI * 2, rad = r.nextDouble() * 2.2;
                        level.addParticle(ParticleTypes.SNOWFLAKE, px + Math.cos(a) * rad, py + j(r, 1.0), pz + Math.sin(a) * rad,
                                -Math.sin(a) * 0.25, 0, Math.cos(a) * 0.25);
                    }
                    if (r.nextInt(2) == 0) level.addParticle(ParticleTypes.CLOUD, px + j(r, 1.2), py + j(r, 0.8), pz + j(r, 1.2), 0, 0, 0);
                }
                case CALM -> level.addParticle(dust(0.55f, 0.75f, 1f, 1.1f), px + j(r, 0.1), py + j(r, 0.1), pz + j(r, 0.1), 0, 0, 0);
                case FURY -> {
                    level.addParticle(dust(0.9f, 0.15f, 0.1f, 1.1f), px + j(r, 0.1), py + j(r, 0.1), pz + j(r, 0.1), 0, 0, 0);
                    if (r.nextInt(6) == 0) level.addParticle(ParticleTypes.ANGRY_VILLAGER, px, py, pz, 0, 0, 0);
                }
                case PARALYSIS -> {
                    level.addParticle(dust(0.75f, 0.9f, 0.3f, 1.1f), px + j(r, 0.1), py + j(r, 0.1), pz + j(r, 0.1), 0, 0, 0);
                    if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.ENCHANTED_HIT, px, py, pz, 0, 0, 0);
                }
                case WABBAJACK -> {
                    level.addParticle(dust(r.nextFloat(), r.nextFloat(), r.nextFloat(), 1.3f), px + j(r, 0.15), py + j(r, 0.15), pz + j(r, 0.15), 0, 0, 0);
                    if (r.nextInt(2) == 0) level.addParticle(ParticleTypes.WITCH, px, py, pz, 0, 0, 0);
                }
                case BLADE -> {
                    for (int k = -3; k <= 3; k++) {
                        level.addParticle(dust(0.85f, 0.05f, 0.12f, 1.4f), px + j(r, 0.05), py + k * 0.18, pz + j(r, 0.05), 0, 0, 0);
                    }
                }
            }
        }
    }
}
