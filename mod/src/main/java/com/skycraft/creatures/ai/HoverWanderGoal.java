package com.skycraft.creatures.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;

/**
 * Drifts between random points a few blocks above the ground (netch, wisps, dragon priests, wraiths), or higher for
 * true flyers (cliff racers circling). Uses the flying navigation; never looks further than its home area.
 */
public class HoverWanderGoal extends Goal {
    private final PathfinderMob mob;
    private final double speed;
    private final int minHeight;
    private final int maxHeight;
    private final int radius;

    public HoverWanderGoal(PathfinderMob mob, double speed, int minHeight, int maxHeight, int radius) {
        this.mob = mob;
        this.speed = speed;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.radius = radius;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return mob.getTarget() == null && mob.getNavigation().isDone() && mob.getRandom().nextInt(30) == 0;
    }

    @Override
    public boolean canContinueToUse() {
        return mob.getTarget() == null && mob.getNavigation().isInProgress();
    }

    @Override
    public void start() {
        for (int i = 0; i < 6; i++) {
            int x = Mth.floor(mob.getX()) + mob.getRandom().nextInt(radius * 2 + 1) - radius;
            int z = Mth.floor(mob.getZ()) + mob.getRandom().nextInt(radius * 2 + 1) - radius;
            if (!mob.level().isLoaded(new BlockPos(x, Mth.floor(mob.getY()), z))) continue;
            int ground = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            int y = ground + minHeight + mob.getRandom().nextInt(Math.max(1, maxHeight - minHeight + 1));
            if (!mob.level().getBlockState(new BlockPos(x, y, z)).isAir()) continue;
            mob.getNavigation().moveTo(x + 0.5, y, z + 0.5, speed);
            return;
        }
    }
}
