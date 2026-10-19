package com.nirncraft.roads;

import com.nirncraft.tamriel.settlement.Culture;
import com.nirncraft.worldplan.RoadPlan;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Roads for the far field (Infinite ATLAS): the surface block a road gives a far column, from the same road plan,
 * widths and culture styles RoadPaint builds the near world with, so a road does not end where the near world does.
 * A far column covers `span` blocks: a road narrower than the column still marks it, so roads stay visible from far.
 */
public final class FarRoads {
    private FarRoads() {}

    private static final ThreadLocal<RoadPlan.Hit> HIT = ThreadLocal.withInitial(RoadPlan.Hit::new);

    /** The road surface for the far column centred at (x, z), `span` blocks wide; null where there is no road. */
    public static BlockState surface(int x, int z, int span, boolean wet) {
        RoadPlan plan;
        try {
            plan = RoadPlan.get();
        } catch (RuntimeException e) {
            return null;  // no baked plan in this world (tests, other dimensions)
        }
        if (plan == null) return null;
        RoadPlan.Hit hit = HIT.get();
        if (!plan.at(x + 0.5, z + 0.5, hit)) return null;
        if (hit.distance > Math.max(hit.halfWidth, span * 0.5f)) return null;
        if (hit.span == RoadPlan.TUNNEL) return null;  // under the hill: the far field shows the hill
        Culture c = RoadStyle.cultureAt(x, z);
        if (hit.span == RoadPlan.BRIDGE) return RoadStyle.deck(c);
        if (wet) return null;  // fords and culverts: the water reads better than a road under it
        return RoadStyle.surface(c, hit.kind, Paver.roll(x, z));
    }
}
