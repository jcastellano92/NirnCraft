package com.nirncraft.roads;

import com.nirncraft.worldplan.RoadPlan;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The runtime road network follows the baked road plan (docs/WORLDGEN-PIPELINE.md P3): instead of running its own
 * A* on noise heights, a link of the network takes the polyline of the plan's link between the same places (or, for a
 * gate lane or a trail, the plan's link from that gate or destination), resampled to a point every 2 blocks. So the
 * travelers, carriages, signposts, lanterns, milestones, wayshrines and roadside dressing stand on the roads that are
 * really painted into the world, and {@link RoadNetwork} needs no planner threads for them.
 *
 * <p>Pieces of a link that the plan cut away where it shares another road are joined by a straight chord (the other
 * road is within a few blocks there by construction). A link without a plan equivalent (a generic structure-found
 * settlement) returns null: the caller decides whether the legacy planner may serve it.</p>
 */
final class PlanRoute {
    /** Marker in {@link RoadPlanner.Result#expansions()}: the polyline comes from the plan, not from a search. */
    static final int FROM_PLAN = -1;
    private static final double STEP = RoadPlanner.SPACING;
    private static final double END_TOLERANCE = 8.0;

    private record Index(RoadPlan plan, Map<String, Integer> links, IntArrayList[] segs) {
    }

    private static volatile Index index;

    private PlanRoute() {}

    private static Index index() {
        RoadPlan plan = RoadPlan.get();
        Index i = index;
        if (i != null && i.plan == plan) return i;
        Map<String, Integer> links = new HashMap<>();
        for (int k = 0; k < plan.links(); k++) {
            if (plan.linkStatus[k] != RoadPlan.LINK_OK) continue;
            links.put(plan.linkA[k] + "|" + plan.linkB[k], k);
            if (!plan.linkB[k].startsWith("@")) links.put(plan.linkB[k] + "|" + plan.linkA[k], k);
        }
        IntArrayList[] segs = new IntArrayList[plan.links()];
        for (int s = 0; s < plan.segments(); s++) {
            int l = plan.segLink[s];
            if (segs[l] == null) segs[l] = new IntArrayList(2);
            segs[l].add(s);
        }
        index = i = new Index(plan, links, segs);
        return i;
    }

    /**
     * The plan's polyline for a link as a planner result, or null when the plan has none.
     *
     * @param placeA   place id of the road's first end (the destination of a trail, the town of a gate lane)
     * @param placeB   place id of the other end, or null for a trail or a gate lane
     * @param gate     index of the gate a gate lane leaves from, else -1
     * @param trail    the link is a footpath from {@code placeA} to a road point
     * @param ea, eb   the road ends the network chose (gate cells, centres or the point a trail joins)
     */
    @Nullable
    static RoadPlanner.Result route(int generation, int a, int b, @Nullable String placeA, @Nullable String placeB,
                                    int gate, boolean trail, int[] ea, int[] eb) {
        if (placeA == null || placeA.isEmpty()) return null;
        Index ix = index();
        Integer link = null;
        if (gate >= 0) link = ix.links.get(placeA + "|@gate" + gate);
        else if (trail) link = ix.links.get(placeA + "|@road");
        else if (placeB != null && !placeB.isEmpty()) link = ix.links.get(placeA + "|" + placeB);
        boolean named = link != null && ix.segs[link] != null;
        if (!named) {
            // the plan's rules and the network's can differ in a corner case: take the plan link that starts at this end
            link = byEnd(ix, ea, eb, trail || gate >= 0);
            if (link == null) return null;
        }
        return build(ix, link, generation, a, b, ea, eb, named);
    }

    /** A link of the plan with a vertex at {@code ea} (and, for a graph link, one at {@code eb}). */
    @Nullable
    private static Integer byEnd(Index ix, int[] ea, int[] eb, boolean oneEnd) {
        RoadPlan plan = ix.plan;
        RoadPlan.Hit hit = new RoadPlan.Hit();
        if (!plan.at(ea[0] + 0.5, ea[1] + 0.5, hit) || hit.distance > 4f) return null;
        int link = hit.link;
        if (link < 0 || ix.segs[link] == null || plan.linkStatus[link] != RoadPlan.LINK_OK) return null;
        if (oneEnd) return link;
        // a graph link must also reach the other end
        for (int s : ix.segs[link]) {
            for (int v = plan.segStart[s]; v < plan.segEnd[s]; v++) {
                if (Math.hypot(plan.vx[v] - eb[0] - 0.5, plan.vz[v] - eb[1] - 0.5) <= 6.0) return link;
            }
        }
        return null;
    }

    private static RoadPlanner.Result build(Index ix, int link, int generation, int a, int b, int[] ea, int[] eb, boolean named) {
        RoadPlan plan = ix.plan;
        IntArrayList segs = ix.segs[link];
        // chain the pieces from the end at ea
        int n = segs.size();
        boolean[] used = new boolean[n];
        FloatArrayList px = new FloatArrayList(), pz = new FloatArrayList();
        IntArrayList pv = new IntArrayList();
        double cx = ea[0] + 0.5, cz = ea[1] + 0.5;
        for (int round = 0; round < n; round++) {
            int best = -1;
            boolean reverse = false;
            double bd = Double.MAX_VALUE;
            for (int k = 0; k < n; k++) {
                if (used[k]) continue;
                int s = segs.getInt(k);
                int v0 = plan.segStart[s], v1 = plan.segEnd[s] - 1;
                double d0 = Math.hypot(plan.vx[v0] - cx, plan.vz[v0] - cz), d1 = Math.hypot(plan.vx[v1] - cx, plan.vz[v1] - cz);
                if (d0 < bd) {
                    bd = d0;
                    best = k;
                    reverse = false;
                }
                if (d1 < bd) {
                    bd = d1;
                    best = k;
                    reverse = true;
                }
            }
            if (best < 0) break;
            used[best] = true;
            int s = segs.getInt(best);
            int v0 = plan.segStart[s], v1 = plan.segEnd[s] - 1;
            if (!reverse) {
                for (int v = v0; v <= v1; v++) add(plan, px, pz, pv, v);
            } else {
                for (int v = v1; v >= v0; v--) add(plan, px, pz, pv, v);
            }
            cx = px.getFloat(px.size() - 1);
            cz = pz.getFloat(pz.size() - 1);
        }
        if (px.size() < 2) return null;
        // the chain must run from ea; if its far end is at ea instead (a link planned the other way) it is reversed
        int last = px.size() - 1;
        double dStart = Math.hypot(px.getFloat(0) - ea[0] - 0.5, pz.getFloat(0) - ea[1] - 0.5);
        double dEnd = Math.hypot(px.getFloat(last) - ea[0] - 0.5, pz.getFloat(last) - ea[1] - 0.5);
        boolean flip = dEnd < dStart;
        double off = Math.min(dStart, dEnd);
        // a link found by its places is the plan's own even where the network chose another gate (its neighbours are
        // the towns it has found so far, not the whole graph): the road starts at the plan's gate
        if (off > END_TOLERANCE * 8 && !named) return null;
        if (off > END_TOLERANCE && off <= END_TOLERANCE * 8) {
            // the plan merged this link's first stretch into another road leaving the same gate (P5: roads leave a
            // settlement radially, so two roads from one gate share their start): a chord from the gate along that road
            if (flip) {
                px.add(ea[0] + 0.5f);
                pz.add(ea[1] + 0.5f);
                pv.add(-1);
            } else {
                px.add(0, ea[0] + 0.5f);
                pz.add(0, ea[1] + 0.5f);
                pv.add(0, -1);
            }
        }

        // densify: one point every STEP blocks (chords between pieces included), integer columns
        IntArrayList xs = new IntArrayList(), zs = new IntArrayList();
        java.io.ByteArrayOutputStream flags = new java.io.ByteArrayOutputStream();
        int count = px.size();
        int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        for (int q = 0; q < count; q++) {
            int i = flip ? count - 1 - q : q;
            int j = flip ? i - 1 : i + 1;
            double x0 = px.getFloat(i), z0 = pz.getFloat(i);
            boolean water = water(plan, pv.getInt(i));
            int ix0 = (int) Math.floor(x0), iz0 = (int) Math.floor(z0);
            if (ix0 != lastX || iz0 != lastZ) {
                xs.add(ix0);
                zs.add(iz0);
                flags.write(water ? Road.WATER : 0);
                lastX = ix0;
                lastZ = iz0;
            }
            if (q + 1 >= count) break;
            double x1 = px.getFloat(j), z1 = pz.getFloat(j);
            double len = Math.hypot(x1 - x0, z1 - z0);
            int steps = (int) Math.floor(len / STEP);
            boolean water2 = water || water(plan, pv.getInt(j));
            for (int s = 1; s <= steps; s++) {
                double t = s * STEP / len;
                if (t >= 1.0 - 1e-6) break;
                int qx = (int) Math.floor(x0 + (x1 - x0) * t), qz = (int) Math.floor(z0 + (z1 - z0) * t);
                if (qx == lastX && qz == lastZ) continue;
                xs.add(qx);
                zs.add(qz);
                flags.write(water && water2 ? Road.WATER : 0);
                lastX = qx;
                lastZ = qz;
            }
        }
        if (xs.size() < 2) return null;
        return new RoadPlanner.Result(generation, a, b, xs.toIntArray(), zs.toIntArray(), flags.toByteArray(), false, FROM_PLAN, 0);
    }

    private static void add(RoadPlan plan, FloatArrayList px, FloatArrayList pz, IntArrayList pv, int v) {
        px.add(plan.vx[v]);
        pz.add(plan.vz[v]);
        pv.add(v);
    }

    /** A vertex the road crosses water at on a bridge deck. */
    private static boolean water(RoadPlan plan, int v) {
        return v >= 0 && plan.vspan[v] == RoadPlan.BRIDGE && !Float.isNaN(plan.vw[v]);
    }

    /** The class (0 highway .. 3 trail) of the plan road nearest to the column within {@code margin} of its band, or -1. */
    static int kindAt(double x, double z, double margin, RoadPlan.Hit hit) {
        RoadPlan plan = RoadPlan.get();
        if (!plan.at(x, z, hit)) return -1;
        return hit.distance <= hit.halfWidth + margin ? hit.kind : -1;
    }
}
