package com.nirncraft.render.quality;

import java.util.function.ToDoubleFunction;

/**
 * Greedy marginal allocation: start every knob at its floor and repeatedly raise the one whose next rung buys the
 * most visual value per predicted millisecond, while the predicted frame stays inside the budget. Rungs that cost
 * nothing measurable are always taken (a CPU-bound frame keeps its looks); a knob below its "off" threshold jumps to
 * it in one rung, so a feature is either worth its first useful level or stays off. With concave value curves and
 * near-linear costs this is the optimum of the separable knapsack; the cost model need not be separable (far detail
 * scales the distance-scaled knobs' work), since every rung is priced by the full model.
 */
final class Allocator {
    private Allocator() {}

    /**
     * @param floor   lowest q per knob
     * @param ceil    highest q per knob (1, or less while a fallback tier caps it)
     * @param budget  predicted frame ms to stay within
     * @param predict the whole frame's predicted ms for a knob vector
     */
    static double[] allocate(double[] floor, double[] ceil, double budget, ToDoubleFunction<double[]> predict) {
        int n = floor.length;
        double[] q = new double[n];
        for (int k = 0; k < n; k++) q[k] = Math.min(floor[k], ceil[k]);
        double cost = predict.applyAsDouble(q);
        double[] trial = q.clone();
        for (int guard = 0; guard < 64 * n; guard++) {
            int best = -1;
            double bestRatio = -1, bestNext = 0, bestCost = 0;
            for (int k = 0; k < n; k++) {
                if (q[k] >= ceil[k] - 1e-9) continue;
                Knob knob = Knob.ALL[k];
                double next = Math.min(ceil[k], knob.next(q[k]));
                if (next <= q[k] + 1e-9) continue;
                double dv = knob.value(next) - knob.value(q[k]);
                if (dv <= 1e-12) {
                    // no value of its own yet (below "off"): price the jump to the first useful rung
                    if (next < knob.offBelow) continue;
                }
                trial[k] = next;
                double c = predict.applyAsDouble(trial);
                trial[k] = q[k];
                double dc = c - cost;
                if (dc > 1e-6 && c > budget) continue;  // does not fit
                double ratio = dc <= 1e-6 ? Double.POSITIVE_INFINITY : dv / dc;
                if (dv <= 1e-12 && dc > 1e-6) continue;
                if (ratio > bestRatio) {
                    best = k;
                    bestRatio = ratio;
                    bestNext = next;
                    bestCost = c;
                }
            }
            if (best < 0) break;
            q[best] = bestNext;
            trial[best] = bestNext;
            cost = bestCost;
        }
        return q;
    }

    /** Total visual value of a knob vector. */
    static double value(double[] q) {
        double v = 0;
        for (int k = 0; k < q.length; k++) v += Knob.ALL[k].value(q[k]);
        return v;
    }
}
