package com.skycraft.roads;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Read-only answer to "is there a road here?" for other systems (props, yards, spawners, structures placed after the
 * network is planned). A column is a road cell when it lies within the paved band of a road: the half width of its
 * class plus the edge stones of a highway or the rails of a bridge. Ground inside a planned town's gates is not paved,
 * so a road polyline that ends at a gate never reports the town's own streets.
 *
 * <p>Backed by the baked road plan ({@code com.skycraft.worldplan.RoadPlan}): the band is the plan's class half width
 * plus the margin, answered from any thread. Roads of a save that predates the plan (the legacy network, server thread
 * only) are consulted when the plan has none there. Overworld only; every other level answers "no road".</p>
 */
public final class RoadCells {
    /** Outer half width (blocks) of the paved band per road class (highway, road, lane, trail); bridges are wider. */
    private static final double[] BAND = {2.5, 1.6, 1.15, 0.8};
    private static final double BRIDGE_BAND = 2.0;
    /** The planned points are two blocks apart: half a spacing of slack so the band has no holes. */
    private static final double SLACK = 1.0;

    private RoadCells() {}

    /** True if (x, z) lies on a road's paved band. */
    public static boolean isRoad(Level level, int x, int z) {
        return isRoad(level, x, z, 0);
    }

    /** True if a road's paved band comes within {@code margin} blocks of (x, z). */
    public static boolean isRoad(Level level, int x, int z, int margin) {
        return kind(level, x, z, margin) >= 0;
    }

    /** The class of the nearest road within {@code margin} blocks of the paved band ({@link Road#HIGHWAY}..), or -1. */
    public static int kind(Level level, int x, int z, int margin) {
        if (level.dimension() != Level.OVERWORLD) return -1;
        // the baked road plan answers (immutable, any thread): the roads that worldgen painted
        int k = PlanRoute.kindAt(x + 0.5, z + 0.5, margin, HIT.get());
        if (k >= 0) return k;
        // roads of a pre-plan save (planned by the legacy planner) live in the level's data: server thread only
        if (level instanceof ServerLevel sl && sl.getServer().isSameThread()) return near(RoadsData.get(sl), x, z, margin, true);
        return -1;
    }

    private static final ThreadLocal<com.skycraft.worldplan.RoadPlan.Hit> HIT =
            ThreadLocal.withInitial(com.skycraft.worldplan.RoadPlan.Hit::new);

    /** Road class of the nearest road whose band is within {@code margin} of (x, z), or -1 (any polyline point counts). */
    static int near(RoadsData data, int x, int z, int margin) {
        return near(data, x, z, margin, false);
    }

    private static int near(RoadsData data, int x, int z, int margin, boolean pavedOnly) {
        int reach = margin + 6;
        int bestKind = -1;
        double best = Double.MAX_VALUE;
        for (int cx = (x - reach) >> 4; cx <= (x + reach) >> 4; cx++) {
            for (int cz = (z - reach) >> 4; cz <= (z + reach) >> 4; cz++) {
                LongArrayList entries = data.chunkIndex.get(ChunkPos.asLong(cx, cz));
                if (entries == null) continue;
                for (int k = 0; k < entries.size(); k++) {
                    long e = entries.getLong(k);
                    Road r = data.road(RoadsData.entryRoad(e));
                    int i = RoadsData.entryIndex(e);
                    if (r == null || i < 0 || i >= r.size()) continue;
                    int kind = Math.max(0, Math.min(3, r.kind));
                    double band = r.water(i) ? Math.max(BAND[kind], BRIDGE_BAND) : BAND[kind];
                    double dx = r.xs[i] - x, dz = r.zs[i] - z;
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d > band + SLACK + margin || d >= best) continue;
                    if (pavedOnly && data.noRoadZoneAt(r.xs[i], r.zs[i]) != null) continue;
                    best = d;
                    bestKind = kind;
                }
            }
        }
        return bestKind;
    }
}
