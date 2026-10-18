package com.skycraft.roads;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.EnumSet;

/**
 * Walks a traveler along its road (or after its group leader). Re-targets about once a second; combat, trading,
 * panicking etc. (higher priority goals, or a target) take over and the walk resumes afterwards.
 */
final class TravelerGoal extends Goal {
    private final Mob mob;
    private int cooldown;

    TravelerGoal(Mob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        CompoundTag t = Travelers.tag(mob);
        if (t == null || t.getBooleanOr("arrived", false) || t.getBooleanOr("static", false)) return false;
        if (mob.getTarget() != null || mob.isPassenger() || mob.isLeashed()) return false;
        return !(mob instanceof AbstractVillager v && v.isTrading());
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        cooldown = 0;
    }

    @Override
    public void stop() {
        if (!(mob instanceof Villager)) mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        // goals without requiresUpdateEveryTick() tick every other game tick: 10 calls ~ 1 second
        if (--cooldown > 0) return;
        cooldown = 10;
        Travelers.steer(mob);
    }
}
