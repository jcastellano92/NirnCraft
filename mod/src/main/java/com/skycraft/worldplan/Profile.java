package com.skycraft.worldplan;

import com.skycraft.roads.PlanLinks;

/**
 * The vertical profile of one road piece and its spans. A 1D optimisation along the polyline: the road follows the
 * ground as closely as the class's grade cap allows (the mean of the largest grade-capped curve below the target and
 * the smallest one above it: cuts through crests and fills across dips balance), never lower than water surface plus
 * clearance on a bridge or culvert, and meets its pinned ends (a site pad, or the road it joins) within the cap.
 *
 * <p>Spans, per vertex: water crossings are a bridge (wider than {@link PlanRouter#CULVERT_WIDTH}, or any lake/sea),
 * a culvert (a narrow stream passes under the road) or a ford (a footpath through shallow water); on dry ground the
 * road is in a cut or on a fill where it is more than {@link RoadPlanBuilder#CUT_FILL} blocks off the ground, on a
 * bridge over a gap where a short fill would be over {@link RoadPlanBuilder#GAP} high, in a tunnel where a long cut is
 * over {@link RoadPlanBuilder#TUNNEL} deep; footpaths steeper than {@link RoadPlanBuilder#STAIRS_GRADE} are stairs and
 * the zigzags the builder inserted are switchbacks.</p>
 */
final class Profile {
    private static final float NONE = -1e9f;

    private Profile() {}

    static void run(RoadPlanBuilder.Poly p, byte kind, float pinA, float pinB) {
        int n = p.n;
        float c = (float) RoadPlanBuilder.gradeCap(kind);
        float clearance = RoadPlanBuilder.clearance(kind);
        boolean trail = kind == PlanLinks.TRAIL;
        byte[] span = p.span;
        float[] t = new float[n], lb = new float[n];
        java.util.Arrays.fill(lb, NONE);
        java.util.Arrays.fill(span, RoadPlan.NORMAL);
        // 1. water crossings: one type per run of wet vertices
        int i = 0;
        while (i < n) {
            if (Float.isNaN(p.w[i])) {
                i++;
                continue;
            }
            int j = i;
            float maxWidth = 0, maxDepth = 0;
            boolean lakeSea = false;
            while (j < n && !Float.isNaN(p.w[j])) {
                maxWidth = Math.max(maxWidth, p.width[j]);
                maxDepth = Math.max(maxDepth, p.w[j] - p.g[j]);
                lakeSea |= p.lakeSea[j];
                j++;
            }
            double run = p.s[j - 1] - p.s[i] + RoadPlanBuilder.SPACING;
            byte type;
            if (trail && !lakeSea && maxDepth <= PlanRouter.FORD_DEPTH && run <= PlanRouter.FORD_WIDTH) type = RoadPlan.FORD;
            else if (!lakeSea && maxWidth < PlanRouter.CULVERT_WIDTH && run <= 10) type = RoadPlan.CULVERT;
            else type = RoadPlan.BRIDGE;
            for (int k = i; k < j; k++) {
                span[k] = type;
                if (type == RoadPlan.FORD) {
                    t[k] = Math.max(p.g[k], p.w[k] - 1f);
                } else {
                    lb[k] = p.w[k] + (type == RoadPlan.CULVERT ? 2f : clearance);
                    t[k] = lb[k];
                }
            }
            i = j;
        }
        // 2. dry ground: the target is the ground, lightly smoothed; never cut below nearby water
        for (i = 0; i < n; i++) {
            if (!Float.isNaN(p.w[i])) continue;
            float sum = 0, wsum = 0;
            for (int k = Math.max(0, i - 2); k <= Math.min(n - 1, i + 2); k++) {
                if (!Float.isNaN(p.w[k])) continue;
                float wk = k == i ? 2f : 1f;
                sum += p.g[k] * wk;
                wsum += wk;
            }
            t[i] = sum / wsum;
            if (!Float.isNaN(p.near[i]) && p.near[i] + 1f <= p.g[i]) lb[i] = p.near[i] + 1f;
        }
        // 2b. a bridge's abutments: the dry vertex either side carries the deck onto the bank
        for (i = 0; i < n; i++) {
            if (span[i] != RoadPlan.BRIDGE || Float.isNaN(p.w[i])) continue;
            float deck = p.w[i] + clearance;
            for (int k = i - 1; k <= i + 1; k += 2) {
                if (k < 0 || k >= n || !Float.isNaN(p.w[k])) continue;
                span[k] = RoadPlan.BRIDGE;
                lb[k] = Math.max(lb[k], deck);
                t[k] = Math.max(t[k], deck);
            }
        }
        // 3. grade-capped fit: mean of the lower and upper envelopes
        float[] lo = new float[n], hi = new float[n];
        envelope(p.s, t, c, lo, true);
        envelope(p.s, t, c, hi, false);
        float[] h = p.h;
        for (i = 0; i < n; i++) h[i] = (lo[i] + hi[i]) * 0.5f;
        // 4. pinned ends
        double len = p.s[n - 1];
        if (!Float.isNaN(pinA)) {
            for (i = 0; i < n; i++) h[i] = clamp(h[i], (float) (pinA - c * p.s[i]), (float) (pinA + c * p.s[i]));
        }
        if (!Float.isNaN(pinB)) {
            for (i = 0; i < n; i++) {
                double d = len - p.s[i];
                h[i] = clamp(h[i], (float) (pinB - c * d), (float) (pinB + c * d));
            }
        }
        // 5. hard floors (bridge decks, culverts) win
        float[] floor = new float[n];
        envelope(p.s, lb, c, floor, false);
        for (i = 0; i < n; i++) if (floor[i] > h[i]) h[i] = floor[i];
        // 6. dry spans
        for (i = 0; i < n; i++) {
            if (span[i] != RoadPlan.NORMAL) continue;
            float d = h[i] - p.g[i];
            if (d > RoadPlanBuilder.CUT_FILL) span[i] = RoadPlan.FILL;
            else if (d < -RoadPlanBuilder.CUT_FILL) span[i] = RoadPlan.CUT;
        }
        i = 0;
        while (i < n) {
            byte type = span[i];
            int j = i;
            while (j + 1 < n && span[j + 1] == type) j++;
            double run = p.s[j] - p.s[i] + RoadPlanBuilder.SPACING;
            if (type == RoadPlan.FILL) {
                float top = 0;
                for (int k = i; k <= j; k++) top = Math.max(top, h[k] - p.g[k]);
                if (top > RoadPlanBuilder.GAP && run <= RoadPlanBuilder.GAP_BRIDGE_MAX) for (int k = i; k <= j; k++) span[k] = RoadPlan.BRIDGE;
            } else if (type == RoadPlan.CUT) {
                float deep = 0;
                for (int k = i; k <= j; k++) deep = Math.max(deep, p.g[k] - h[k]);
                if (deep > RoadPlanBuilder.TUNNEL && run >= RoadPlanBuilder.TUNNEL_MIN) {
                    for (int k = i; k <= j; k++) if (p.g[k] - h[k] > RoadPlanBuilder.TUNNEL * 0.5f) span[k] = RoadPlan.TUNNEL;
                }
            }
            i = j + 1;
        }
        if (trail) {
            for (i = 0; i + 1 < n; i++) {
                double ds = Math.max(1e-6, p.s[i + 1] - p.s[i]);
                if (Math.abs(h[i + 1] - h[i]) / ds <= RoadPlanBuilder.STAIRS_GRADE) continue;
                if (dryKind(span[i])) span[i] = RoadPlan.STAIRS;
                if (dryKind(span[i + 1])) span[i + 1] = RoadPlan.STAIRS;
            }
        }
        for (i = 0; i < n; i++) if (p.sb[i] && dryKind(span[i])) span[i] = RoadPlan.SWITCHBACK;
    }

    private static boolean dryKind(byte t) {
        return t == RoadPlan.NORMAL || t == RoadPlan.CUT || t == RoadPlan.FILL;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /**
     * The grade-capped envelope of {@code t} along arc lengths {@code s}: with {@code below}, the largest function
     * under {@code t} whose slope never exceeds {@code c}; otherwise the smallest one above it. Two linear passes.
     */
    static void envelope(double[] s, float[] t, float c, float[] out, boolean below) {
        int n = t.length;
        System.arraycopy(t, 0, out, 0, n);
        for (int i = 1; i < n; i++) {
            float lim = (float) (c * (s[i] - s[i - 1]));
            out[i] = below ? Math.min(out[i], out[i - 1] + lim) : Math.max(out[i], out[i - 1] - lim);
        }
        for (int i = n - 2; i >= 0; i--) {
            float lim = (float) (c * (s[i + 1] - s[i]));
            out[i] = below ? Math.min(out[i], out[i + 1] + lim) : Math.max(out[i], out[i + 1] - lim);
        }
    }
}
