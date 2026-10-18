package com.skycraft.creatures.ai;

import com.skycraft.combat.Stealth;
import com.skycraft.creatures.entity.GuardEntity;
import com.skycraft.magic.Targeting;
import com.skycraft.magic.spell.Summons;
import com.skycraft.society.Followers;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tactical group & party target evaluation for hostile creatures and dungeon packs.
 *
 * <ul>
 *   <li>Prevents entire enemy packs from blindly dogpiling a single target while ignoring other party members,
 *       hired followers, or conjured companions.</li>
 *   <li>Allows a mob to turn and intercept a closer party member or companion rushing up to flank it, or switch
 *       to a party member who just struck it.</li>
 *   <li>Never applies to town guards ({@link GuardEntity}) or player-allied summons ({@link Summons#isCompanion}).</li>
 * </ul>
 */
public class SmartPartyTargetGoal extends Goal {
    private static final double PARTY_SCAN_RANGE = 18.0;
    private static final double ALLY_PACK_RANGE = 16.0;

    private final Mob mob;
    private int nextEvalTick;

    public SmartPartyTargetGoal(Mob mob) {
        this.mob = mob;
    }

    @Override
    public boolean canUse() {
        if (this.mob instanceof GuardEntity || Summons.isCompanion(this.mob)) return false;
        if (this.mob.tickCount < this.nextEvalTick) return false;
        this.nextEvalTick = this.mob.tickCount + 18 + this.mob.getRandom().nextInt(10);

        LivingEntity current = this.mob.getTarget();
        if (current == null || !current.isAlive()) {
            // If idle and hostile, check if an active player companion/follower is right in front of us
            if (this.mob instanceof Enemy) {
                LivingEntity companion = findNearbyPartyCompanion();
                if (companion != null) {
                    this.mob.setTarget(companion);
                    return true;
                }
            }
            return false;
        }

        Player anchor = resolvePartyAnchor(current);
        if (anchor == null) return false;

        List<LivingEntity> party = gatherPartyTargets(anchor, current);
        if (party.size() < 2) return false;

        List<Mob> packAllies = this.mob.level().getEntitiesOfClass(Mob.class,
                this.mob.getBoundingBox().inflate(ALLY_PACK_RANGE),
                m -> m != this.mob && m.isAlive() && m.getTarget() != null && isPackAlly(m));

        LivingEntity best = current;
        double currentScore = scoreCandidate(current, current, packAllies) + 3.5; // hysteresis to prevent erratic ping-pong
        double bestScore = currentScore;

        for (LivingEntity candidate : party) {
            if (candidate == current) continue;
            double s = scoreCandidate(candidate, current, packAllies);
            if (s > bestScore) {
                bestScore = s;
                best = candidate;
            }
        }

        if (best != current) {
            this.mob.setTarget(best);
            return true;
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    private double scoreCandidate(LivingEntity candidate, LivingEntity current, List<Mob> packAllies) {
        double dist = this.mob.distanceTo(candidate);
        double currentDist = this.mob.distanceTo(current);
        double score = -dist * 1.4;

        // Retaliation bonus if this party member recently damaged us
        if (this.mob.getLastHurtByMob() == candidate && this.mob.tickCount - this.mob.getLastHurtByMobTimestamp() < 90) {
            score += 8.0;
        }

        // Flank/interception bonus: a party member or companion closed into melee range while the old target is farther out
        if (dist < 4.5 && currentDist > 6.5 && this.mob.hasLineOfSight(candidate)) {
            score += 9.5;
        } else if (dist + 3.5 < currentDist && this.mob.hasLineOfSight(candidate)) {
            score += 4.5;
        }

        // Anti-dogpile penalty: split pack attention across the player's party & companions
        int otherAttackers = 0;
        for (Mob ally : packAllies) {
            if (ally.getTarget() == candidate) {
                otherAttackers++;
            }
        }
        score -= otherAttackers * 6.5;

        return score;
    }

    private boolean isPackAlly(Mob other) {
        if (other instanceof GuardEntity || Summons.isCompanion(other)) return false;
        if (other.getType() == this.mob.getType()) return true;
        return (this.mob instanceof Enemy) && (other instanceof Enemy);
    }

    @Nullable
    private Player resolvePartyAnchor(LivingEntity target) {
        if (target instanceof Player p) return p;
        UUID ownerId = Summons.ownerOf(target);
        if (ownerId == null) ownerId = Followers.getOwnerId(target);
        if (ownerId == null && target instanceof NpcEntity npc) ownerId = npc.getLeader();
        if (ownerId == null && target instanceof TamableAnimal tame) ownerId = com.skycraft.platform.EntityData.ownerId(tame);
        if (ownerId != null) {
            return this.mob.level().getPlayerByUUID(ownerId);
        }
        return null;
    }

    private List<LivingEntity> gatherPartyTargets(Player anchor, LivingEntity current) {
        AABB box = this.mob.getBoundingBox().inflate(PARTY_SCAN_RANGE);
        List<LivingEntity> list = new ArrayList<>();
        for (LivingEntity e : this.mob.level().getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
            if (e == this.mob || e.isSpectator()) continue;
            if (e instanceof Player p) {
                if (p.isCreative()) continue;
                if (e != current && Stealth.hiddenFrom(this.mob, p)) continue;
                if (p == anchor || Targeting.isFriendly(anchor, p)) {
                    list.add(p);
                }
            } else if (Targeting.isCompanionOrParty(anchor, e)) {
                if (e == current || this.mob.hasLineOfSight(e) || this.mob.distanceToSqr(e) <= 36.0) {
                    list.add(e);
                }
            }
        }
        return list;
    }

    @Nullable
    private LivingEntity findNearbyPartyCompanion() {
        double range = 14.0;
        AABB box = this.mob.getBoundingBox().inflate(range);
        LivingEntity closest = null;
        double closestDistSqr = range * range;
        for (LivingEntity e : this.mob.level().getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
            if (e == this.mob || e instanceof Player || e.isSpectator()) continue;
            Player owner = resolvePartyAnchor(e);
            if (owner == null || owner.isCreative() || owner.isSpectator()) continue;
            double d2 = this.mob.distanceToSqr(e);
            if (d2 < closestDistSqr && this.mob.hasLineOfSight(e)) {
                closestDistSqr = d2;
                closest = e;
            }
        }
        return closest;
    }
}

