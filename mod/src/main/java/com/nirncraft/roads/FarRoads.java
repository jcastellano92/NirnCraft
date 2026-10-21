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

    /** Every block a far road shows (the styles' surfaces and bridge decks): the GPU generator's block table. */
    public static java.util.List<BlockState> blocks() {
        java.util.LinkedHashSet<BlockState> out = new java.util.LinkedHashSet<>();
        for (Culture c : Culture.VALUES) {
            out.add(RoadStyle.deck(c));
            for (int k = 0; k < 4; k++) java.util.Collections.addAll(out, RoadStyle.surfaces(c, k));
        }
        return java.util.List.copyOf(out);
    }

    /** Words per culture in {@link #gpuStyles}. */
    public static final int STYLE_WORDS = 1 + 4 * 9;

    /**
     * The styles for the GPU's far roads (atlas_page.slang farRoad): per culture ordinal STYLE_WORDS words, the bridge
     * deck's id, then per road class (highway, road, lane, trail) its surface count and up to 8 ids (surface()'s order).
     */
    public static int[] gpuStyles(java.util.function.ToIntFunction<BlockState> id) {
        int[] w = new int[Culture.VALUES.length * STYLE_WORDS];
        for (Culture c : Culture.VALUES) {
            int at = c.ordinal() * STYLE_WORDS;
            w[at] = id.applyAsInt(RoadStyle.deck(c));
            for (int k = 0; k < 4; k++) {
                BlockState[] list = RoadStyle.surfaces(c, k);
                if (list.length > 8) throw new IllegalStateException("a road style with more than 8 surfaces");
                w[at + 1 + k * 9] = list.length;
                for (int i = 0; i < list.length; i++) w[at + 2 + k * 9 + i] = id.applyAsInt(list[i]);
            }
        }
        return w;
    }

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
