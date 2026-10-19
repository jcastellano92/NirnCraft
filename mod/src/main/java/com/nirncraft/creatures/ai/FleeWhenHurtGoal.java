package com.nirncraft.creatures.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** Badly hurt creatures break off and run (Skyrim's fleeing wildlife), then come back once they recovered a little. */
public class FleeWhenHurtGoal extends Goal {
    private final PathfinderMob mob;
    private final float below;
    private final double speed;
    private int ticks;

    public FleeWhenHurtGoal(PathfinderMob mob, float below, double speed) {
        this.mob = mob;
        this.below = below;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        LivingEntity threat = mob.getTarget() != null ? mob.getTarget() : mob.getLastHurtByMob();
        return threat != null && mob.getHealth() < mob.getMaxHealth() * below;
    }

    @Override
    public boolean canContinueToUse() {
        return ticks > 0 && mob.getHealth() < mob.getMaxHealth() * (below + 0.15f);
    }

    @Override
    public void start() {
        ticks = 120;
        flee();
    }

    @Override
    public void tick() {
        ticks--;
        if (mob.getNavigation().isDone()) flee();
    }

    private void flee() {
        LivingEntity threat = mob.getTarget() != null ? mob.getTarget() : mob.getLastHurtByMob();
        if (threat == null) return;
        Vec3 away = DefaultRandomPos.getPosAway(mob, 16, 7, threat.position());
        if (away != null) mob.getNavigation().moveTo(away.x, away.y, away.z, speed);
    }
}
