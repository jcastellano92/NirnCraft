package com.nirncraft.magic;

import com.nirncraft.magic.spell.SpellCasting;
import com.nirncraft.quest.party.Parties;
import com.nirncraft.magic.spell.Summons;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** Aiming, friend-or-foe checks and area queries shared by spells and shouts. */
public final class Targeting {
    private Targeting() {}

    /** Result of a beam/aim ray: where it ends, and the creature hit (if any). */
    public record Ray(Vec3 start, Vec3 end, @Nullable LivingEntity entity, boolean hitBlock) {}

    /** Approximate position of the hand casting the current spell (see {@link SpellCasting#castingSide()}). */
    public static Vec3 handPos(Player player) {
        return handPos(player, SpellCasting.castingSide());
    }

    /** Approximate position of a casting hand: side 1 = right, -1 = left, 0 = both (in front of the chest). */
    public static Vec3 handPos(Player player, int side) {
        float yaw = player.getYRot() * ((float) Math.PI / 180f);
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3 look = player.getViewVector(1f);
        return player.getEyePosition().add(look.scale(0.55)).add(right.scale(0.32 * side)).add(0, -0.28, 0);
    }

    /** Casts a ray from the eyes; the first living entity matching {@code filter} (with a little aim assist) is hit. */
    public static Ray ray(Player player, double range, Predicate<LivingEntity> filter) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1f);
        Vec3 end = eye.add(look.scale(range));
        BlockHitResult block = player.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        boolean hitBlock = block.getType() != HitResult.Type.MISS;
        Vec3 limit = hitBlock ? block.getLocation() : end;

        LivingEntity best = null;
        Vec3 bestPos = null;
        double bestDist = Double.MAX_VALUE;
        AABB area = new AABB(eye, limit).inflate(1.5);
        for (LivingEntity e : player.level().getEntitiesOfClass(LivingEntity.class, area,
                e -> e != player && e.isAlive() && !e.isSpectator() && !(e instanceof ArmorStand) && filter.test(e))) {
            AABB bb = e.getBoundingBox().inflate(0.3);
            if (bb.contains(eye)) {
                best = e;
                bestPos = eye;
                bestDist = 0;
                continue;
            }
            Optional<Vec3> clip = bb.clip(eye, limit);
            if (clip.isPresent()) {
                double d = eye.distanceToSqr(clip.get());
                if (d < bestDist) {
                    bestDist = d;
                    best = e;
                    bestPos = clip.get();
                }
            }
        }
        if (best != null) return new Ray(eye, bestPos, best, false);
        return new Ray(eye, limit, null, hitBlock);
    }

    /**
     * The caster itself, its summons and allies, its hired/recruited followers and companions, its tamed animals,
     * team mates, members of its party (quest module) and their summons/followers.
     */
    public static boolean isFriendly(Player caster, @Nullable Entity e) {
        if (e == null) return false;
        if (e == caster) return true;
        UUID owner = Summons.ownerOf(e);
        if (owner == null) owner = com.nirncraft.society.Followers.getOwnerId(e);
        if (owner == null && e instanceof com.nirncraft.society.entity.NpcEntity npc) owner = npc.getLeader();
        if (owner != null && owner.equals(caster.getUUID())) return true;
        if (e instanceof TamableAnimal tame && tame.getOwnerReference() != null && caster.getUUID().equals(tame.getOwnerReference().getUUID())) return true;
        if (caster.isAlliedTo(e)) return true;
        if (caster instanceof ServerPlayer sp) {
            if (e instanceof ServerPlayer other && sameParty(sp, other)) return true;
            if (owner != null) {
                ServerPlayer ownerPlayer = sp.level().getServer().getPlayerList().getPlayer(owner);
                if (ownerPlayer != null && sameParty(sp, ownerPlayer)) return true;
            }
            if (e instanceof TamableAnimal tame && tame.getOwnerReference() != null) {
                ServerPlayer ownerPlayer = sp.level().getServer().getPlayerList().getPlayer(tame.getOwnerReference().getUUID());
                if (ownerPlayer != null && sameParty(sp, ownerPlayer)) return true;
            }
        }
        return false;
    }

    /** True if {@code e} is a bound summon, hired/recruited follower, companion, tamed pet, or party member of {@code caster} (excluding {@code caster} itself). */
    public static boolean isCompanionOrParty(Player caster, @Nullable Entity e) {
        return e != null && e != caster && isFriendly(caster, e);
    }

    /**
     * Finds a companion (bound summon, hired follower, companion, tamed pet, or party member) standing directly in
     * front of {@code caster} within {@code range} blocks (default 2.0 blocks) that {@code caster} is looking right at.
     * Used by single-target/self healing spells and healing staves so they heal a looked-at companion in front of the
     * caster instead of the caster (while AoE/rune/ward spells like Grand Healing, Guardian Circle, and Wards work on all).
     */
    public static Ray companionRayInFront(Player caster, double range) {
        Vec3 eye = caster.getEyePosition();
        Vec3 look = caster.getViewVector(1f);
        Vec3 end = eye.add(look.scale(range));
        BlockHitResult block = caster.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
        boolean hitBlock = block.getType() != HitResult.Type.MISS;
        Vec3 limit = hitBlock ? block.getLocation() : end;

        LivingEntity best = null;
        Vec3 bestPos = null;
        double bestDist = Double.MAX_VALUE;
        AABB area = caster.getBoundingBox().inflate(range + 0.5);
        for (LivingEntity e : caster.level().getEntitiesOfClass(LivingEntity.class, area,
                e -> e != caster && e.isAlive() && !e.isSpectator() && !(e instanceof ArmorStand) && isCompanionOrParty(caster, e))) {
            if (caster.distanceTo(e) > range) continue;
            Vec3 center = e.position().add(0, e.getBbHeight() * 0.5, 0);
            Vec3 to = center.subtract(eye);
            double dist = to.length();
            if (dist < 1e-4 || dist > range + 0.5) continue;
            if (to.scale(1.0 / dist).dot(look) < 0.85 || !caster.hasLineOfSight(e)) continue;
            AABB bb = e.getBoundingBox().inflate(0.1);
            if (bb.contains(eye)) {
                best = e;
                bestPos = center;
                bestDist = 0;
                continue;
            }
            Optional<Vec3> clip = bb.clip(eye, limit);
            if (clip.isPresent()) {
                double d = eye.distanceToSqr(clip.get());
                if (d < bestDist) {
                    bestDist = d;
                    best = e;
                    bestPos = clip.get();
                }
            }
        }
        if (best != null) return new Ray(eye, bestPos, best, false);
        return new Ray(eye, limit, null, hitBlock);
    }

    /** Party check through the quest module; never throws (no party data means no party). */
    public static boolean sameParty(ServerPlayer a, ServerPlayer b) {
        if (a == b) return true;
        try {
            return Parties.sameParty(a.level().getServer(), a, b);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Whether an offensive spell or shout from {@code caster} may affect {@code e} (PvP rules, allies, invulnerability). */
    public static boolean canHarm(ServerPlayer caster, @Nullable Entity e) {
        if (!(e instanceof LivingEntity living) || !living.isAlive() || living.isSpectator() || living instanceof ArmorStand) return false;
        if (isFriendly(caster, living) || living.isInvulnerable()) return false;
        if (living instanceof Player other) {
            // PvP: only when the server allows it (and teams permit); party members are friendly (above).
            if (other.isCreative() || !caster.level().getGameRules().get(net.minecraft.world.level.gamerules.GameRules.PVP) || !caster.canHarmPlayer(other)) return false;
        }
        return true;
    }

    /** Whether a helpful spell (Heal Other, Courage) should treat {@code e} as an ally. */
    public static boolean isHelpable(Player caster, LivingEntity e) {
        if (isFriendly(caster, e)) return true;
        if (e instanceof Player) return true;
        if (e instanceof Enemy) return false;
        return !(e instanceof Mob mob) || mob.getTarget() != caster;
    }

    public static List<LivingEntity> around(Entity center, Vec3 pos, double radius, Predicate<LivingEntity> filter) {
        return around(center.level(), pos, radius, filter);
    }

    /** Living entities whose center is within {@code radius} of {@code pos}. */
    public static List<LivingEntity> around(Level level, Vec3 pos, double radius, Predicate<LivingEntity> filter) {
        List<LivingEntity> out = new ArrayList<>();
        double r2 = radius * radius;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos, pos).inflate(radius),
                e -> e.isAlive() && filter.test(e))) {
            if (e.position().add(0, e.getBbHeight() / 2, 0).distanceToSqr(pos) <= r2) out.add(e);
        }
        return out;
    }

    /** Creatures in a cone in front of the player that it can see. */
    public static List<LivingEntity> cone(Player player, double range, double minDot, Predicate<LivingEntity> filter) {
        List<LivingEntity> out = new ArrayList<>();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1f);
        for (LivingEntity e : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(range),
                e -> e != player && e.isAlive() && filter.test(e))) {
            Vec3 to = e.position().add(0, e.getBbHeight() * 0.5, 0).subtract(eye);
            double dist = to.length();
            if (dist > range || dist < 1e-4) continue;
            if (to.scale(1 / dist).dot(look) < minDot && dist > 1.5) continue;
            if (!player.hasLineOfSight(e)) continue;
            out.add(e);
        }
        return out;
    }

    /** Hurts without vanilla knockback (beams and auras shouldn't shove things around 4 times a second). */
    public static boolean hurtNoKnockback(LivingEntity target, DamageSource source, float amount) {
        Vec3 motion = target.getDeltaMovement();
        target.setInvulnerableTime(0);
        boolean hurt = com.nirncraft.platform.Effects.hurt(target, source, amount);
        target.setDeltaMovement(motion);
        return hurt;
    }
}
