package com.skycraft.fauna.entity;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * Wild day animals (deer, elk, bears, mammoths, horkers, mudcrabs) bed down at night: they settle one by one as the
 * dark comes on, lie still (the rig's sleep animation) and stay down until dawn, unless something disturbs them: a
 * player coming close (closer if sneaking), a wound or a target. Night hunters (sabre cats) and fish are not affected
 * ({@link FaunaAnimal#sleepsAtNight()}).
 */
final class RestAtNightGoal extends Goal {
    private final FaunaAnimal animal;

    RestAtNightGoal(FaunaAnimal animal) {
        this.animal = animal;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    private boolean disturbed(double range) {
        Player p = animal.level().getNearestPlayer(animal, range);
        if (p == null || p.isSpectator() || p.isCreative()) return false;
        return animal.distanceToSqr(p) < (p.isCrouching() ? range * range * 0.4 : range * range);
    }

    private boolean hurtRecently(int ticks) {
        return animal.getLastHurtByMob() != null && animal.tickCount - animal.getLastHurtByMobTimestamp() < ticks;
    }

    @Override
    public boolean canUse() {
        if (!animal.sleepsAtNight() || !animal.level().isNight()) return false;
        if (animal.getTarget() != null || hurtRecently(600) || animal.isInWaterOrBubble() || animal.isLeashed() || animal.isVehicle()) return false;
        if (animal.getRandom().nextInt(80) != 0) return false; // they settle gradually, not all at once
        return !disturbed(14.0);
    }

    @Override
    public boolean canContinueToUse() {
        return animal.level().isNight() && animal.getTarget() == null && !hurtRecently(100) && !animal.isInWaterOrBubble() && !disturbed(9.0);
    }

    @Override
    public void start() {
        animal.getNavigation().stop();
        animal.setResting(true);
    }

    @Override
    public void tick() {
        animal.getNavigation().stop();
    }

    @Override
    public void stop() {
        animal.setResting(false);
    }
}
