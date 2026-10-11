package com.skycraft.creatures.spec;

import com.skycraft.creatures.Bestiary;
import com.skycraft.creatures.entity.CreatureBolt;
import com.skycraft.creatures.entity.anim.AnimSlot;
import com.skycraft.magic.MagicDamage;
import com.skycraft.magic.spell.Summons;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Supplier;

/** The bestiary's moves: bites and claws, pounces, charges, ground slams, spells and spit, breath, summons, blinks, roars. */
public final class Attacks {
    private Attacks() {}

    // ------------------------------------------------------------------ helpers

    /** The gap between two creatures' bodies (horizontal distance minus half widths, plus any height difference). */
    public static double gap(LivingEntity a, LivingEntity b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double h = Math.sqrt(dx * dx + dz * dz) - (a.getBbWidth() + b.getBbWidth()) * 0.5;
        double dy = 0;
        if (b.getY() > a.getY() + a.getBbHeight()) dy = b.getY() - (a.getY() + a.getBbHeight());
        else if (a.getY() > b.getY() + b.getBbHeight()) dy = a.getY() - (b.getY() + b.getBbHeight());
        return Math.max(0, Math.max(h, dy));
    }

    /** True if {@code e} may be hurt by {@code mob}'s area attacks: not itself, its kin, or creative players. */
    public static boolean validVictim(Mob mob, LivingEntity e) {
        if (e == mob || !e.isAlive() || e.getType() == mob.getType()) return false;
        if (e instanceof Player p && (p.isCreative() || p.isSpectator())) return false;
        if (Summons.isCompanion(mob)) {
            java.util.UUID owner = Summons.ownerOf(mob);
            return owner == null || (!owner.equals(e.getUUID()) && !owner.equals(Summons.ownerOf(e)));
        }
        return e == mob.getTarget() || !(e instanceof net.minecraft.world.entity.monster.Enemy) || e instanceof Player;
    }

    /** A melee blow scaled by {@code mult} of the creature's attack damage, with knockback and effects. */
    public static boolean strike(Mob mob, LivingEntity target, float mult, HitEffect... effects) {
        float dmg = (float) mob.getAttributeValue(Attributes.ATTACK_DAMAGE) * mult;
        boolean hit = target.hurt(mob.damageSources().mobAttack(mob), dmg);
        if (hit) {
            double kb = mob.getAttributeValue(Attributes.ATTACK_KNOCKBACK);
            if (kb > 0) {
                target.knockback(kb * 0.5, Mth.sin(mob.getYRot() * Mth.DEG_TO_RAD), -Mth.cos(mob.getYRot() * Mth.DEG_TO_RAD));
            }
            mob.setLastHurtMob(target);
            for (HitEffect e : effects) e.apply(mob, target);
        }
        return hit;
    }

    private static boolean facing(Mob mob, LivingEntity target, double threshold) {
        Vec3 fwd = Vec3.directionFromRotation(0, mob.getYRot());
        Vec3 to = new Vec3(target.getX() - mob.getX(), 0, target.getZ() - mob.getZ());
        if (to.lengthSqr() < 1.0E-4) return true;
        return fwd.dot(to.normalize()) > threshold;
    }

    static DamageSource element(Mob mob, ResourceKey<DamageType> type) {
        return MagicDamage.source(mob.level(), type, null, mob);
    }

    // ------------------------------------------------------------------ melee

    /** A bite, claw or weapon blow: hits on the windup frame if the target is still in reach and in front. */
    public static Attack melee(float mult, HitEffect... effects) {
        return new Attack() {
            {
                cry = "attack";
            }

            @Override
            protected void perform(Mob mob, LivingEntity target) {
                if (gap(mob, target) <= maxRange + 0.9 && facing(mob, target, 0.2)) strike(mob, target, mult, effects);
            }
        };
    }

    /** A sweeping blow that hits everything in a wide arc in front (giant clubs, troll swipes, tail sweeps). */
    public static Attack sweep(float mult, float radius, float arcDot, HitEffect... effects) {
        return new Attack() {
            {
                cry = "attack";
            }

            @Override
            protected void perform(Mob mob, LivingEntity target) {
                Vec3 fwd = Vec3.directionFromRotation(0, mob.getYRot());
                double r = radius + mob.getBbWidth() * 0.5;
                for (LivingEntity e : mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(r, 1.5, r),
                        e -> validVictim(mob, e))) {
                    Vec3 to = new Vec3(e.getX() - mob.getX(), 0, e.getZ() - mob.getZ());
                    if (to.length() > r + e.getBbWidth() * 0.5) continue;
                    if (to.lengthSqr() > 0.01 && fwd.dot(to.normalize()) < arcDot) continue;
                    strike(mob, e, mult, effects);
                }
                mob.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.2f, 0.6f);
            }
        };
    }

    /** Leaps at the target; the first contact while airborne deals the blow. */
    public static Attack pounce(float mult, float power, HitEffect... effects) {
        Attack a = new Attack() {
            {
                cry = "attack";
            }

            @Override
            public void tick(Mob mob, LivingEntity target, int t) {
                if (t == windup) {
                    Vec3 d = target.position().subtract(mob.position());
                    Vec3 h = new Vec3(d.x, 0, d.z);
                    double len = h.length();
                    if (len > 1.0E-4) h = h.scale(Math.min(power, len * 0.2) / len);
                    mob.setDeltaMovement(h.x, 0.42 + power * 0.08, h.z);
                    mob.getPersistentData().putBoolean("skycraft_pounce", true);
                } else if (t > windup && mob.getPersistentData().getBoolean("skycraft_pounce")) {
                    if (mob.getBoundingBox().inflate(0.5).intersects(target.getBoundingBox())) {
                        mob.getPersistentData().remove("skycraft_pounce");
                        strike(mob, target, mult, effects);
                    } else if (mob.onGround() && t > windup + 3) {
                        mob.getPersistentData().remove("skycraft_pounce");
                    }
                }
            }

            @Override
            protected void perform(Mob mob, LivingEntity target) {}
        };
        a.range(2.0f, 7f).timing(6, 22, 70);
        return a;
    }

    /** Lowers its head and charges in a straight line; trampling contact deals a heavy blow and throws the victim. */
    public static Attack charge(float mult, double speed, HitEffect... effects) {
        Attack a = new Attack() {
            {
                cry = "roar";
            }

            @Override
            public void tick(Mob mob, LivingEntity target, int t) {
                var pd = mob.getPersistentData();
                if (t == windup) {
                    Vec3 d = new Vec3(target.getX() - mob.getX(), 0, target.getZ() - mob.getZ());
                    if (d.lengthSqr() < 1.0E-4) d = Vec3.directionFromRotation(0, mob.getYRot());
                    d = d.normalize();
                    pd.putDouble("skycraft_cx", d.x);
                    pd.putDouble("skycraft_cz", d.z);
                    pd.putBoolean("skycraft_charging", true);
                    // creatures with a roar of their own bellow when the charge starts (the goal plays their cry)
                    if (!(mob instanceof com.skycraft.creatures.entity.SkyCreature sc && sc.spec().cries.containsKey("roar"))) {
                        mob.playSound(SoundEvents.RAVAGER_ROAR, 1.0f, 1.2f);
                    }
                }
                if (t > windup && pd.getBoolean("skycraft_charging")) {
                    double cx = pd.getDouble("skycraft_cx");
                    double cz = pd.getDouble("skycraft_cz");
                    Vec3 v = mob.getDeltaMovement();
                    mob.setDeltaMovement(cx * speed, v.y, cz * speed);
                    mob.setYRot((float) (Mth.atan2(cz, cx) * Mth.RAD_TO_DEG) - 90f);
                    mob.yBodyRot = mob.getYRot();
                    if (mob.level() instanceof ServerLevel sl && t % 3 == 0) {
                        BlockState below = mob.level().getBlockState(mob.blockPosition().below());
                        if (!below.isAir()) {
                            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, below), mob.getX(), mob.getY() + 0.1, mob.getZ(),
                                    4, mob.getBbWidth() * 0.4, 0.05, mob.getBbWidth() * 0.4, 0.1);
                        }
                    }
                    for (LivingEntity e : mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(0.4),
                            e -> validVictim(mob, e))) {
                        strike(mob, e, mult, effects);
                        pd.remove("skycraft_charging");
                    }
                    if (mob.horizontalCollision || t >= duration - 1) pd.remove("skycraft_charging");
                }
            }

            @Override
            protected void perform(Mob mob, LivingEntity target) {}
        };
        a.range(3f, 14f).timing(10, 40, 140);
        a.roots = false;
        return a;
    }

    /** Pounds the ground: everything around takes damage and is thrown up (giants, trolls, centurions). */
    public static Attack slam(float mult, float radius, double up, HitEffect... effects) {
        return new Attack() {
            {
                cry = "attack";
            }

            @Override
            protected void perform(Mob mob, LivingEntity target) {
                double r = radius + mob.getBbWidth() * 0.5;
                for (LivingEntity e : mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(r, 2, r),
                        e -> validVictim(mob, e))) {
                    if (e.distanceToSqr(mob) > (r + 1) * (r + 1)) continue;
                    if (strike(mob, e, mult, effects)) {
                        e.setDeltaMovement(e.getDeltaMovement().add(0, up, 0));
                        e.hurtMarked = true;
                    }
                }
                if (mob.level() instanceof ServerLevel sl) {
                    BlockState below = mob.level().getBlockState(mob.blockPosition().below());
                    ParticleOptions p = below.isAir() ? ParticleTypes.POOF : new BlockParticleOption(ParticleTypes.BLOCK, below);
                    for (int i = 0; i < 24; i++) {
                        double a = i / 24.0 * Math.PI * 2;
                        sl.sendParticles(p, mob.getX() + Math.cos(a) * r * 0.8, mob.getY() + 0.1, mob.getZ() + Math.sin(a) * r * 0.8,
                                2, 0.2, 0.05, 0.2, 0.15);
                    }
                }
                mob.playSound(com.skycraft.audio.AudioSounds.creature("giant", "stomp"), 1.5f, 0.9f + mob.getRandom().nextFloat() * 0.2f);
            }
        };
    }

    // ------------------------------------------------------------------ ranged

    /** Throws or casts {@code count} bolts of an element at the target. */
    public static Attack bolt(CreatureBolt.Kind kind, float damage, float speed, int count, float spread) {
        Attack a = new Attack() {
            {
                cry = "cast";
            }

            @Override
            protected void perform(Mob mob, LivingEntity target) {
                for (int i = 0; i < count; i++) shoot(mob, target, kind, damage, speed, spread * (count > 1 ? 1 : 0.3f));
                SoundEvent s = switch (kind) {
                    case FIRE -> com.skycraft.audio.AudioSounds.spell("fire");
                    case FROST -> com.skycraft.audio.AudioSounds.spell("frost");
                    case SHOCK -> com.skycraft.audio.AudioSounds.spell("shock");
                    case ROCK -> SoundEvents.SNOWBALL_THROW;
                    case BOLT -> SoundEvents.CROSSBOW_SHOOT;
                    default -> SoundEvents.LLAMA_SPIT;
                };
                mob.playSound(s, 1.0f, 0.95f + mob.getRandom().nextFloat() * 0.1f);
            }
        };
        a.anim(AnimSlot.CAST).range(3f, 22f).timing(10, 20, 50);
        return a;
    }

    public static void shoot(Mob mob, LivingEntity target, CreatureBolt.Kind kind, float damage, float speed, float spread) {
        CreatureBolt bolt = new CreatureBolt(Bestiary.BOLT.get(), mob, mob.level(), kind, damage);
        Vec3 eye = mob.getEyePosition();
        Vec3 fwd = Vec3.directionFromRotation(0, mob.getYRot());
        bolt.setPos(eye.x + fwd.x * mob.getBbWidth() * 0.6, eye.y - 0.2, eye.z + fwd.z * mob.getBbWidth() * 0.6);
        double dx = target.getX() - bolt.getX();
        double dy = target.getY(0.5) - bolt.getY();
        double dz = target.getZ() - bolt.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        float grav = kind == CreatureBolt.Kind.ROCK ? 0.15f : kind == CreatureBolt.Kind.POISON || kind == CreatureBolt.Kind.ASH ? 0.08f : 0f;
        bolt.shoot(dx, dy + h * grav, dz, speed, spread * 10f);
        mob.level().addFreshEntity(bolt);
    }

    /** A continuous cone of fire, frost or shock (breath weapons) for the move's whole duration. */
    public static Attack breath(CreatureBolt.Kind kind, float range, float damagePerTick) {
        Attack a = new Attack() {
            {
                cry = "breath";
            }

            @Override
            public void tick(Mob mob, LivingEntity target, int t) {
                if (t < windup) return;
                if ((t - windup) % 14 == 0) {
                    SoundEvent s = switch (kind) {
                        case FROST -> com.skycraft.audio.AudioSounds.spell("frost_stream");
                        case SHOCK -> com.skycraft.audio.AudioSounds.spell("shock_stream");
                        case POISON -> com.skycraft.audio.AudioSounds.creature("chaurus", "attack");
                        default -> com.skycraft.audio.AudioSounds.spell("fire_stream");
                    };
                    mob.playSound(s, 1.2f, kind == CreatureBolt.Kind.POISON ? 0.6f : 0.85f);
                }
                mob.getLookControl().setLookAt(target, 20f, 20f);
                Vec3 mouth = mob.getEyePosition().add(Vec3.directionFromRotation(0, mob.getYRot()).scale(mob.getBbWidth() * 0.6));
                Vec3 dir = target.getEyePosition().subtract(mouth).normalize();
                if (mob.level() instanceof ServerLevel sl) {
                    ParticleOptions p = switch (kind) {
                        case FROST -> ParticleTypes.SNOWFLAKE;
                        case SHOCK -> ParticleTypes.ELECTRIC_SPARK;
                        case POISON -> ParticleTypes.SNEEZE;
                        default -> ParticleTypes.FLAME;
                    };
                    for (int i = 0; i < 4; i++) {
                        double s = 0.6 + mob.getRandom().nextDouble() * 0.5;
                        sl.sendParticles(p, mouth.x, mouth.y, mouth.z, 0, dir.x * s + (mob.getRandom().nextDouble() - 0.5) * 0.2,
                                dir.y * s + (mob.getRandom().nextDouble() - 0.5) * 0.2, dir.z * s + (mob.getRandom().nextDouble() - 0.5) * 0.2, 1.0);
                    }
                }
                if (t % 4 != 0) return;
                for (LivingEntity e : mob.level().getEntitiesOfClass(LivingEntity.class, new AABB(mouth, mouth.add(dir.scale(range))).inflate(2.5),
                        e -> validVictim(mob, e))) {
                    Vec3 to = e.getBoundingBox().getCenter().subtract(mouth);
                    double along = to.dot(dir);
                    if (along < 0 || along > range) continue;
                    double perp = to.subtract(dir.scale(along)).length();
                    if (perp > 0.8 + along * 0.3) continue;
                    switch (kind) {
                        case FROST -> {
                            e.hurt(element(mob, MagicDamage.FROST), damagePerTick * 4);
                            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1), mob);
                        }
                        case SHOCK -> e.hurt(element(mob, MagicDamage.SHOCK), damagePerTick * 4);
                        case POISON -> {
                            e.hurt(mob.damageSources().mobAttack(mob), damagePerTick * 2);
                            e.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 1), mob);
                        }
                        default -> {
                            if (e.hurt(element(mob, MagicDamage.FIRE), damagePerTick * 4)) e.setSecondsOnFire(4);
                        }
                    }
                }
            }

            @Override
            protected void perform(Mob mob, LivingEntity target) {}
        };
        a.anim(AnimSlot.SPECIAL).range(1f, range * 0.8f).timing(8, 40, 120);
        return a;
    }

    // ------------------------------------------------------------------ magic & tactics

    /** Calls allies: spawns {@code count} creatures around itself, at most {@code max} of that kind nearby. */
    public static Attack summon(Supplier<? extends EntityType<? extends Mob>> type, int count, int max) {
        Attack a = new Attack() {
            {
                cry = "cast";
            }

            @Override
            public boolean canUse(Mob mob, LivingEntity target, double gap) {
                return super.canUse(mob, target, gap)
                        && mob.level().getEntities(type.get(), mob.getBoundingBox().inflate(24), e -> e.isAlive()).size() < max;
            }

            @Override
            protected void perform(Mob mob, LivingEntity target) {
                if (!(mob.level() instanceof ServerLevel sl)) return;
                for (int i = 0; i < count; i++) {
                    Mob m = type.get().create(sl);
                    if (m == null) return;
                    double ang = mob.getRandom().nextDouble() * Math.PI * 2;
                    double x = mob.getX() + Math.cos(ang) * 2.5;
                    double z = mob.getZ() + Math.sin(ang) * 2.5;
                    int y = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
                    if (Math.abs(y - mob.getY()) > 4) y = Mth.floor(mob.getY());
                    m.moveTo(x, y, z, mob.getYRot(), 0);
                    m.finalizeSpawn(sl, sl.getCurrentDifficultyAt(BlockPos.containing(x, y, z)), MobSpawnType.MOB_SUMMONED, null, null);
                    m.setTarget(target);
                    m.addTag("skycraft.summoned_by_creature");
                    sl.addFreshEntity(m);
                    sl.sendParticles(ParticleTypes.SOUL, x, y + 1, z, 12, 0.3, 0.6, 0.3, 0.02);
                }
                mob.playSound(com.skycraft.audio.AudioSounds.spell("conjure"), 1.2f, 0.9f);
            }
        };
        a.anim(AnimSlot.SUMMON).range(0f, 30f).timing(14, 30, 400);
        a.needsSight = false;
        return a;
    }

    /** Teleports to a spot near the target (or away, when {@code away}). */
    public static Attack blink(boolean away) {
        Attack a = new Attack() {
            @Override
            protected void perform(Mob mob, LivingEntity target) {
                if (!(mob.level() instanceof ServerLevel sl)) return;
                for (int i = 0; i < 10; i++) {
                    double ang = mob.getRandom().nextDouble() * Math.PI * 2;
                    double dist = away ? 8 + mob.getRandom().nextDouble() * 6 : 2.5 + mob.getRandom().nextDouble() * 2;
                    double x = target.getX() + Math.cos(ang) * dist;
                    double z = target.getZ() + Math.sin(ang) * dist;
                    BlockPos col = BlockPos.containing(x, target.getY(), z);
                    if (!sl.isLoaded(col)) continue;
                    int y = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col.getX(), col.getZ());
                    if (Math.abs(y - target.getY()) > 5) continue;
                    if (!sl.noCollision(mob, mob.getType().getAABB(x, y, z))) continue;
                    sl.sendParticles(ParticleTypes.PORTAL, mob.getX(), mob.getY() + 1, mob.getZ(), 30, 0.4, 0.8, 0.4, 0.3);
                    mob.teleportTo(x, y, z);
                    sl.sendParticles(ParticleTypes.REVERSE_PORTAL, x, y + 1, z, 30, 0.4, 0.8, 0.4, 0.1);
                    sl.playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.8f, 0.8f);
                    return;
                }
            }
        };
        a.anim(AnimSlot.CAST).range(away ? 0f : 6f, away ? 3f : 24f).timing(8, 14, 160);
        a.needsSight = false;
        return a;
    }

    /** A roar that rattles nearby players (slowness, weakness) and rallies kin. */
    public static Attack roar(float radius, int ticks) {
        Attack a = new Attack() {
            {
                cry = "roar";
            }

            @Override
            protected void perform(Mob mob, LivingEntity target) {
                List<LivingEntity> near = mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(radius));
                for (LivingEntity e : near) {
                    if (e.getType() == mob.getType() && e instanceof Mob kin && kin.getTarget() == null) {
                        kin.setTarget(target);
                    } else if (e instanceof Player p && validVictim(mob, p)) {
                        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 0), mob);
                        p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 0), mob);
                    }
                }
            }
        };
        a.anim(AnimSlot.ROAR).range(0f, 16f).timing(10, 32, 300).chance(0.3f);
        a.needsSight = false;
        return a;
    }

    /** Unrelenting Force: a cone that hurls everything in front away (draugr, dragons). */
    public static Attack shout(float damage, double force, float range) {
        Attack a = new Attack() {
            @Override
            protected void perform(Mob mob, LivingEntity target) {
                Vec3 dir = Vec3.directionFromRotation(0, mob.getYRot());
                Vec3 eye = mob.getEyePosition();
                if (mob.level() instanceof ServerLevel sl) {
                    for (int i = 1; i <= 8; i++) {
                        Vec3 p = eye.add(dir.scale(i * range / 8));
                        sl.sendParticles(ParticleTypes.SWEEP_ATTACK, p.x, p.y - 0.3, p.z, 1, 0.3 * i / 4, 0.3, 0.3 * i / 4, 0);
                    }
                }
                mob.playSound(com.skycraft.audio.AudioSounds.shout(com.skycraft.magic.shout.Shout.UNRELENTING_FORCE, 3), 2.5f, 0.9f);
                for (LivingEntity e : mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(range),
                        e -> validVictim(mob, e))) {
                    Vec3 to = e.position().subtract(mob.position());
                    double len = to.length();
                    if (len > range || len < 1.0E-3 || to.normalize().dot(dir) < 0.6) continue;
                    e.hurt(element(mob, MagicDamage.SPELL), damage);
                    double kb = force * (1 - e.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE)) * (1.2 - len / range * 0.5);
                    Vec3 push = to.normalize().scale(kb);
                    e.setDeltaMovement(e.getDeltaMovement().add(push.x, 0.4 + kb * 0.15, push.z));
                    e.hurtMarked = true;
                }
            }
        };
        a.anim(AnimSlot.SHOUT).range(0f, range * 0.8f).timing(12, 26, 260).chance(0.4f);
        return a;
    }
}
