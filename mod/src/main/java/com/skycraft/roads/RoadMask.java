package com.skycraft.roads;

import com.skycraft.world.PlacementMask;
import com.skycraft.worldplan.RoadPlan;

/**
 * The baked road plan as a {@link PlacementMask.Source}: every column's distance to the nearest planned road centre
 * line minus the class half width and two blocks of verge, so trees, ground cover, rocks and scatter keep off the real
 * roads (and not off the map's approximate corridors, which this replaces). Exact, allocation-free per column, no
 * neighbour chunk needed: {@link RoadPlan#at} reads the plan across chunk borders.
 */
final class RoadMask implements PlacementMask.Source {
    /** Verge kept clear beside the bed (blocks): the painter's worn edge plus the posts and signs beside the road. */
    static final float VERGE = 2.0f;

    @Override
    public void contribute(long worldSeed, int chunkX, int chunkZ, byte[] out) {
        RoadPlan plan = RoadPlan.get();
        int x0 = chunkX << 4, z0 = chunkZ << 4;
        RoadPlan.Hit hit = new RoadPlan.Hit();
        // the plan answers within 32 blocks of the query: none at the centre means none within 20 of the chunk
        if (!plan.at(x0 + 8, z0 + 8, hit)) return;
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                if (plan.at(x0 + dx + 0.5, z0 + dz + 0.5, hit)) {
                    PlacementMask.lower(out, dz << 4 | dx, hit.distance - (hit.halfWidth + VERGE));
                }
            }
        }
    }

    /** Registers the plan as a mask source (once, at startup). */
    static void install() {
        PlacementMask.register(new RoadMask());
        PlacementMask.useMapRoadChannel(false);
    }
}
