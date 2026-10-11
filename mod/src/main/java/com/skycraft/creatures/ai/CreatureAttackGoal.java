package com.skycraft.creatures.ai;

import com.skycraft.creatures.entity.anim.CreatureAnims;
import com.skycraft.creatures.spec.Attack;
import com.skycraft.creatures.spec.Attacks;
import com.skycraft.creatures.spec.CreatureSpec;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * The bestiary's combat brain: closes in (or keeps its distance, for casters), picks a ready move whose range fits
 * (weighted random), plays its animation and lets the move land on its hit frame. Cheap: no entity scans; cooldowns
 * are per creature.
 */
public class CreatureAttackGoal extends Goal {
    private final PathfinderMob mob;
    private final CreatureSpec spec;
    private final List<Attack> attacks;
    private final int[] cooldowns;
    private Attack current;
    private int currentIndex;
    private int t;
    private int repath;
    private int globalCooldown;

    public CreatureAttackGoal(PathfinderMob mob, CreatureSpec spec) {
        this.mob = mob;
        this.spec = spec;
        this.attacks = spec.attacks;
        this.cooldowns = new int[attacks.size()];
        // stagger first use a little so packs don't strike in unison
        for (int i = 0; i < cooldowns.length; i++) cooldowns[i] = attacks.get(i).cooldown / 4;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** The move being performed, or null. */
    public Attack current() {
        return current;
    }

    private boolean valid(LivingEntity target) {
        if (target == null || !target.isAlive()) return false;
        if (target instanceof Player p && (p.isCreative() || p.isSpectator())) return false;
        return target.level() == mob.level();
    }

    @Override
    public boolean canUse() {
        if (mob instanceof com.skycraft.creatures.entity.SkyCreature sc && sc.isDormant()) return false;
        return valid(mob.getTarget());
    }

    @Override
    public boolean canContinueToUse() {
        return current != null || valid(mob.getTarget());
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        mob.setAggressive(true);
        repath = 0;
    }

    @Override
    public void stop() {
        current = null;
        mob.setAggressive(false);
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        for (int i = 0; i < cooldowns.length; i++) {
            if (cooldowns[i] > 0) cooldowns[i]--;
        }
        if (globalCooldown > 0) globalCooldown--;
        LivingEntity target = mob.getTarget();
        if (!valid(target)) {
            current = null;
            return;
        }
        mob.getLookControl().setLookAt(target, 30f, 30f);
        double gap = Attacks.gap(mob, target);

        if (current != null) {
            t++;
            if (current.roots) {
                mob.getNavigation().stop();
                float yaw = (float) (Mth.atan2(target.getZ() - mob.getZ(), target.getX() - mob.getX()) * Mth.RAD_TO_DEG) - 90f;
                mob.setYRot(Mth.approachDegrees(mob.getYRot(), yaw, t < current.windup ? 15f : 4f));
                mob.yBodyRot = mob.getYRot();
            }
            current.tick(mob, target, t);
            if (t >= current.duration) {
                cooldowns[currentIndex] = current.cooldown;
                current = null;
                globalCooldown = 4;
            }
            return;
        }

        boolean able = !(mob instanceof com.skycraft.creatures.entity.SkyCreature sc) || sc.canAttackNow();
        if (able && globalCooldown <= 0 && (mob.tickCount + mob.getId()) % 2 == 0 && pick(target, gap)) return;
        move(target, gap);
    }

    private boolean pick(LivingEntity target, double gap) {
        float total = 0;
        boolean sight = false;
        boolean sightChecked = false;
        float[] w = new float[attacks.size()];
        for (int i = 0; i < attacks.size(); i++) {
            Attack a = attacks.get(i);
            if (cooldowns[i] > 0 || !a.canUse(mob, target, gap)) continue;
            if (a.needsSight) {
                if (!sightChecked) {
                    sight = mob.getSensing().hasLineOfSight(target);
                    sightChecked = true;
                }
                if (!sight) continue;
            }
            if (a.chance < 1f && mob.getRandom().nextFloat() > a.chance) {
                cooldowns[i] = 20; // rolled no: think again in a second
                continue;
            }
            w[i] = a.weight;
            total += a.weight;
        }
        if (total <= 0) return false;
        float r = mob.getRandom().nextFloat() * total;
        for (int i = 0; i < w.length; i++) {
            if (w[i] <= 0) continue;
            r -= w[i];
            if (r <= 0) {
                current = attacks.get(i);
                currentIndex = i;
                t = 0;
                if (current.roots) mob.getNavigation().stop();
                CreatureAnims.play(mob, current.anim);
                current.start(mob, target);
                return true;
            }
        }
        return false;
    }

    private void move(LivingEntity target, double gap) {
        if (spec.keepDistance > 0) {
            if (gap < spec.keepDistance * 0.5) {
                if (--repath <= 0) {
                    Vec3 away = DefaultRandomPos.getPosAway(mob, 10, 5, target.position());
                    if (away != null) mob.getNavigation().moveTo(away.x, away.y, away.z, spec.chaseSpeed);
                    repath = 20;
                }
                return;
            }
            if (gap <= spec.keepDistance && mob.getSensing().hasLineOfSight(target)) {
                mob.getNavigation().stop();
                return;
            }
        }
        if (--repath <= 0) {
            repath = 6 + mob.getRandom().nextInt(6);
            if (spec.move == CreatureSpec.Move.FLY || spec.move == CreatureSpec.Move.HOVER) {
                double up = spec.move == CreatureSpec.Move.FLY ? 1.0 + mob.getRandom().nextDouble() * 1.5 : 0.8;
                mob.getNavigation().moveTo(target.getX(), target.getY() + up, target.getZ(), spec.chaseSpeed);
            } else {
                mob.getNavigation().moveTo(target, spec.chaseSpeed);
            }
        }
    }
}
