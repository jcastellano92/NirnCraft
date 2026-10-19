package com.nirncraft.roads;

import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.TamrielMap;
import com.nirncraft.tamriel.settlement.SettlementAnchors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where roads meet a named settlement: its gates, as predicted by the settlement plan from the map and the place alone
 * ({@link SettlementAnchors#gates}); the gate each link uses is the one facing the neighbour it leads to, and when a
 * settlement has as many neighbours as gates every gate gets a road (a gate with no neighbour in its direction stays
 * a secondary gate: streets and a signpost, no road). Pure map work, safe on any thread.
 */
final class GateEnds {
    private static final Map<String, List<int[]>> RAW = new ConcurrentHashMap<>();
    private static final Map<String, List<int[]>> DRY = new ConcurrentHashMap<>();

    private GateEnds() {}

    /** The predicted gate points of a place (world x, z), main gate first; empty if the plan cannot say. */
    static List<int[]> raw(Place p) {
        return RAW.computeIfAbsent(p.id(), k -> {
            try {
                List<int[]> g = SettlementAnchors.gates(p);
                return g == null ? List.of() : List.copyOf(g);
            } catch (RuntimeException e) {
                return List.of();
            }
        });
    }

    /** The gates a road can actually reach: moved outwards off water; ones that stay wet are dropped. */
    static List<int[]> usable(Place p) {
        return DRY.computeIfAbsent(p.id(), k -> {
            List<int[]> out = new ArrayList<>();
            for (int[] g : raw(p)) {
                int[] d = dryOutward(p.x(), p.z(), g[0], g[1]);
                if (d != null) out.add(d);
            }
            return List.copyOf(out);
        });
    }

    /**
     * Radius around the centre inside which the town is built (its nearest gate): the road paver leaves everything
     * inside alone and paves from the gates outwards. 0 if unknown.
     */
    static int coreRadius(Place p) {
        double best = Double.MAX_VALUE;
        for (int[] g : raw(p)) best = Math.min(best, Math.hypot(g[0] - p.x(), g[1] - p.z()));
        // a road leaves a gate sideways along the wall: a core as wide as the gate distance would leave that stretch
        // unpaved (the road runs inside the circle for a long way), so it is kept clearly inside the wall
        return best == Double.MAX_VALUE || best < 12 ? 0 : Math.max(6, (int) Math.floor(best) - 14);
    }

    /**
     * The road end at {@code p} for the link to {@code otherId} (at ox, oz). {@code neighbours} are the linked
     * settlements as {id, x, z}, sorted by id (the assignment depends only on them, so it is the same for every road
     * of the settlement). Null if the place has no usable gate.
     */
    static int[] pick(Place p, int otherId, int ox, int oz, int[][] neighbours) {
        List<int[]> gates = usable(p);
        int m = gates.size();
        if (m == 0) return null;
        if (m == 1) return gates.get(0);
        int n = neighbours.length, target = -1;
        for (int k = 0; k < n; k++) if (neighbours[k][0] == otherId) target = k;
        if (target < 0) return nearest(gates, ox, oz);

        double[][] cost = new double[m][n];
        double[] min = new double[n];
        Arrays.fill(min, Double.MAX_VALUE);
        for (int g = 0; g < m; g++) {
            for (int k = 0; k < n; k++) {
                cost[g][k] = Math.hypot(gates.get(g)[0] - neighbours[k][1], gates.get(g)[1] - neighbours[k][2]);
                min[k] = Math.min(min[k], cost[g][k]);
            }
        }
        int[] gateOf = new int[n];
        Arrays.fill(gateOf, -1);
        boolean[] used = new boolean[m];
        // 1. each gate takes the neighbour it faces best, if that neighbour is nearly as close to it as to its own best gate
        List<double[]> cand = new ArrayList<>();
        for (int g = 0; g < m; g++) {
            for (int k = 0; k < n; k++) {
                if (cost[g][k] <= 1.35 * min[k] + 40) cand.add(new double[]{cost[g][k] / Math.max(1, min[k]), g, k});
            }
        }
        cand.sort(Comparator.<double[]>comparingDouble(c -> c[0]).thenComparingDouble(c -> c[1]).thenComparingDouble(c -> c[2]));
        for (double[] c : cand) {
            int g = (int) c[1], k = (int) c[2];
            if (used[g] || gateOf[k] >= 0) continue;
            used[g] = true;
            gateOf[k] = g;
        }
        // 2. neighbours nobody claimed go to their nearest gate
        for (int k = 0; k < n; k++) {
            if (gateOf[k] >= 0) continue;
            int bg = 0;
            for (int g = 1; g < m; g++) if (cost[g][k] < cost[bg][k]) bg = g;
            gateOf[k] = bg;
        }
        // 3. a gate still without a road takes a neighbour from a gate that has two (a road may go round the town)
        for (int g = 0; g < m; g++) {
            if (used[g]) continue;
            int bestK = -1;
            double bestRatio = 2.0;
            for (int k = 0; k < n; k++) {
                int owner = gateOf[k], shared = 0;
                for (int q = 0; q < n; q++) if (gateOf[q] == owner) shared++;
                if (shared < 2) continue;
                double ratio = cost[g][k] / Math.max(1, min[k]);
                if (ratio < bestRatio) {
                    bestRatio = ratio;
                    bestK = k;
                }
            }
            if (bestK >= 0) {
                gateOf[bestK] = g;
                used[g] = true;
            }
        }
        return gates.get(gateOf[target]);
    }

    private static int[] nearest(List<int[]> gates, int x, int z) {
        int[] best = gates.get(0);
        double bd = Double.MAX_VALUE;
        for (int[] g : gates) {
            double d = Math.hypot(g[0] - x, g[1] - z);
            if (d < bd) {
                bd = d;
                best = g;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ dry land

    /** True if a road can stand here: land, off the coast and out of lakes and rivers. */
    static boolean dry(int x, int z) {
        MapSample s = new MapSample();
        TamrielMap.sample(x, z, s);
        return s.region != 0 && !s.inWater() && s.coastDistance > 3 && s.lakeDistance < -3;
    }

    /** The point itself if dry, else the first dry point outwards from the centre (up to 48 blocks), else null. */
    private static int[] dryOutward(int cx, int cz, int x, int z) {
        if (dry(x, z)) return new int[]{x, z};
        double dx = x - cx, dz = z - cz, len = Math.hypot(dx, dz);
        if (len < 1) return null;
        dx /= len;
        dz /= len;
        for (int step = 6; step <= 48; step += 6) {
            int nx = (int) Math.round(x + dx * step), nz = (int) Math.round(z + dz * step);
            if (dry(nx, nz)) return new int[]{nx, nz};
        }
        return null;
    }

    /** The nearest dry point to (x, z) within {@code max} blocks (ring search, 8-block steps), or the point itself. */
    static int[] dryNear(int x, int z, int max) {
        if (dry(x, z)) return new int[]{x, z};
        for (int r = 8; r <= max; r += 8) {
            int steps = Math.max(8, r / 3);
            for (int i = 0; i < steps; i++) {
                double a = Math.PI * 2 * i / steps;
                int nx = (int) Math.round(x + Math.cos(a) * r), nz = (int) Math.round(z + Math.sin(a) * r);
                if (dry(nx, nz)) return new int[]{nx, nz};
            }
        }
        return new int[]{x, z};
    }
}
