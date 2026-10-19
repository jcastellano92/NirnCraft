package com.nirncraft.creatures.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Drifts between random points a few blocks above the ground (netch, wisps, dragon priests, wraiths), or higher for
 * true flyers (hawks, cliff racers circling at 12..28 blocks above terrain, climbing away airborne when a player
 * approaches within 10 blocks, and occasionally perching on high treetops or crags).
 */
public class HoverWanderGoal extends Goal {
    private final PathfinderMob mob;
    private final double speed;
    private final int minHeight;
    private final int maxHeight;
    private final int radius;
    private int perchTicks;
    private int perchCooldown;

    public HoverWanderGoal(PathfinderMob mob, double speed, int minHeight, int maxHeight, int radius) {
        this.mob = mob;
        this.speed = speed;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.radius = radius;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    private boolean isHighFlyer() {
        return minHeight >= 8;
    }

    @Nullable
    private Player nearbyThreatPlayer() {
        if (!isHighFlyer()) return null;
        Player p = mob.level().getNearestPlayer(mob, 10.0);
        if (p != null && !p.isCreative() && !p.isSpectator() && !p.isCrouching()) {
            return p;
        }
        return null;
    }

    @Override
    public boolean canUse() {
        if (mob.getTarget() != null) return false;
        if (perchCooldown > 0) perchCooldown--;
        if (nearbyThreatPlayer() != null) return true;
        int ground = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(mob.getX()), Mth.floor(mob.getZ()));
        if (isHighFlyer() && mob.getY() < ground + Math.max(6, minHeight - 4) && perchTicks <= 0) {
            return true;
        }
        return mob.getNavigation().isDone() && mob.getRandom().nextInt(isHighFlyer() ? 14 : 30) == 0;
    }

    @Override
    public boolean canContinueToUse() {
        if (mob.getTarget() != null) return false;
        if (perchTicks > 0) {
            return nearbyThreatPlayer() == null;
        }
        return mob.getNavigation().isInProgress();
    }

    @Override
    public void tick() {
        if (!isHighFlyer()) return;
        Player threat = nearbyThreatPlayer();
        if (perchTicks > 0) {
            perchTicks--;
            if (threat != null || perchTicks == 0) {
                perchTicks = 0;
                perchCooldown = 160 + mob.getRandom().nextInt(160);
                pickAirborneTarget(threat);
            } else {
                mob.setDeltaMovement(mob.getDeltaMovement().scale(0.25));
            }
            return;
        }
        // Ensure high flyers never skim the dirt or clip into hillsides
        int bx = Mth.floor(mob.getX()), bz = Mth.floor(mob.getZ());
        if (mob.level().isLoaded(new BlockPos(bx, Mth.floor(mob.getY()), bz))) {
            int ground = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
            double minSafeY = ground + Math.max(8, minHeight - 2);
            if (mob.getY() < minSafeY) {
                Vec3 v = mob.getDeltaMovement();
                mob.setDeltaMovement(v.x, Math.max(v.y + 0.05, 0.14), v.z);
            }
        }
    }

    @Override
    public void start() {
        Player threat = nearbyThreatPlayer();
        if (threat != null) {
            perchTicks = 0;
            perchCooldown = 120;
            pickAirborneTarget(threat);
            return;
        }
        // High flyers occasionally alight on a tall treetop canopy or high crag
        if (isHighFlyer() && perchCooldown == 0 && mob.getRandom().nextInt(5) == 0) {
            for (int i = 0; i < 6; i++) {
                int x = Mth.floor(mob.getX()) + mob.getRandom().nextInt(radius * 2 + 1) - radius;
                int z = Mth.floor(mob.getZ()) + mob.getRandom().nextInt(radius * 2 + 1) - radius;
                if (!mob.level().isLoaded(new BlockPos(x, Mth.floor(mob.getY()), z))) continue;
                int top = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                BlockPos support = new BlockPos(x, top, z);
                BlockState st = mob.level().getBlockState(support);
                if ((st.is(BlockTags.LEAVES) || top > mob.level().getSeaLevel() + 28) && mob.level().getBlockState(support.above()).isAir()) {
                    perchTicks = 100 + mob.getRandom().nextInt(140);
                    mob.getNavigation().moveTo(x + 0.5, top + 1.0, z + 0.5, speed);
                    return;
                }
            }
        }
        pickAirborneTarget(null);
    }

    private void pickAirborneTarget(@Nullable Player awayFrom) {
        for (int i = 0; i < 8; i++) {
            int x, z;
            if (awayFrom != null) {
                double dx = mob.getX() - awayFrom.getX();
                double dz = mob.getZ() - awayFrom.getZ();
                double baseAng = Math.atan2(dz, dx) + (mob.getRandom().nextDouble() - 0.5) * 1.1;
                double dist = 12.0 + mob.getRandom().nextDouble() * 12.0;
                x = Mth.floor(mob.getX() + Math.cos(baseAng) * dist);
                z = Mth.floor(mob.getZ() + Math.sin(baseAng) * dist);
            } else {
                x = Mth.floor(mob.getX()) + mob.getRandom().nextInt(radius * 2 + 1) - radius;
                z = Mth.floor(mob.getZ()) + mob.getRandom().nextInt(radius * 2 + 1) - radius;
            }
            if (!mob.level().isLoaded(new BlockPos(x, Mth.floor(mob.getY()), z))) continue;
            int ground = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            int low = awayFrom != null ? Math.max(10, minHeight) : minHeight;
            int high = awayFrom != null ? Math.max(22, maxHeight) : maxHeight;
            int y = ground + low + mob.getRandom().nextInt(Math.max(1, high - low + 1));
            if (!mob.level().getBlockState(new BlockPos(x, y, z)).isAir()) continue;
            mob.getNavigation().moveTo(x + 0.5, y, z + 0.5, awayFrom != null ? speed * 1.35 : speed);
            if (isHighFlyer() && mob.getY() < ground + 8) {
                Vec3 v = mob.getDeltaMovement();
                mob.setDeltaMovement(v.x, Math.max(v.y, 0.18), v.z);
            }
            return;
        }
    }
}
