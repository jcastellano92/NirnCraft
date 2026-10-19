package com.nirncraft.worldplan;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.HashMap;
import java.util.Map;

/**
 * Where the planned roads arrive at each site: for every segment end that joins a site pad ({@link RoadPlan#JOIN_SITE}),
 * a point on the road about {@link #BACK} blocks before the end. A structure that builds a named place (a dungeon's
 * mouth, a tower's door, a camp's gate) turns to face it, so the trail that leads there meets the entrance instead of
 * the back wall or the far side of the mountain. Built lazily from the road plan; immutable and thread-safe after.
 */
public final class TrailEnds {
    /** How far back along the road (blocks) the approach point is taken. */
    public static final float BACK = 16f;

    /** One arrival: the approach point and the class of the road (0 highway .. 3 trail). */
    public record Arrival(float x, float z, byte kind) {
    }

    private static volatile Map<String, Arrival> bySite;

    private TrailEnds() {}

    /** The arrival of the planned road at the site {@code placeId}, or null when no road ends there. */
    public static Arrival of(String placeId) {
        Map<String, Arrival> m = bySite;
        if (m == null) {
            synchronized (TrailEnds.class) {
                if (bySite == null) bySite = build(RoadPlan.get());
                m = bySite;
            }
        }
        return m.get(placeId);
    }

    private static long key(int bx, int bz) {
        return ((long) bx << 32) ^ (bz & 0xFFFFFFFFL);
    }

    static Map<String, Arrival> build(RoadPlan rp) {
        // sites by 64-block bucket
        Long2ObjectOpenHashMap<int[]> buckets = new Long2ObjectOpenHashMap<>();
        for (int i = 0; i < rp.sites(); i++) {
            long k = key(rp.siteX[i] >> 6, rp.siteZ[i] >> 6);
            int[] old = buckets.get(k);
            int[] grown = old == null ? new int[1] : java.util.Arrays.copyOf(old, old.length + 1);
            grown[grown.length - 1] = i;
            buckets.put(k, grown);
        }
        Map<String, Arrival> out = new HashMap<>();
        for (int s = 0; s < rp.segments(); s++) {
            for (int end = 0; end < 2; end++) {
                int join = end == 0 ? rp.segJoinA[s] : rp.segJoinB[s];
                if (join != RoadPlan.JOIN_SITE) continue;
                int a = rp.segStart[s], b = rp.segEnd[s] - 1;
                if (b <= a) continue;
                int v = end == 0 ? a : b;
                float ex = rp.vx[v], ez = rp.vz[v];
                int site = nearestSite(rp, buckets, ex, ez);
                if (site < 0) continue;
                // walk back along the segment to the approach point
                float target = end == 0 ? rp.varc[a] + BACK : rp.varc[b] - BACK;
                int u = v;
                if (end == 0) {
                    while (u < b && rp.varc[u] < target) u++;
                } else {
                    while (u > a && rp.varc[u] > target) u--;
                }
                Arrival arr = new Arrival(rp.vx[u], rp.vz[u], rp.segKind[s]);
                Arrival prev = out.get(rp.siteId[site]);
                // the biggest road wins (a town's highway over a hunter's trail to the same gate)
                if (prev == null || arr.kind() < prev.kind()) out.put(rp.siteId[site], arr);
            }
        }
        return out;
    }

    private static int nearestSite(RoadPlan rp, Long2ObjectOpenHashMap<int[]> buckets, float x, float z) {
        int bx = (int) Math.floor(x) >> 6, bz = (int) Math.floor(z) >> 6;
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int[] l = buckets.get(key(bx + dx, bz + dz));
                if (l == null) continue;
                for (int i : l) {
                    double ddx = rp.siteX[i] - x, ddz = rp.siteZ[i] - z, d = ddx * ddx + ddz * ddz;
                    double lim = rp.siteRadius[i] + 24.0;
                    if (d < bestD && d <= lim * lim) {
                        bestD = d;
                        best = i;
                    }
                }
            }
        }
        return best;
    }
}
