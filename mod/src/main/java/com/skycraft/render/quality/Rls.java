package com.skycraft.render.quality;

import java.util.Arrays;

/**
 * Recursive least squares with a prior, exponential forgetting and non-negative coefficients: the per-machine cost
 * of each knob, refined from every measured frame. Starting from the prior (theta0, with variance p0 per term), an
 * observation y = x . theta + noise moves only the terms its features excite, so a knob that never moves keeps its
 * prior instead of drifting. Outliers (a hitch, a GC pause, a burst of chunk uploads) are rejected against the running
 * residual spread. Covariance is clamped to 4x its prior so it cannot wind up while nothing varies, and every 50
 * samples each term takes a weak pseudo-observation of its prior (variance 4x the prior's): terms the data cannot tell
 * apart (a knob that never moves looks just like the base) stay near their priors instead of trading cost between
 * them, while terms the data does excite follow it.
 */
final class Rls {
    final int n;
    final double[] theta;
    private final double[] theta0;
    private final double[][] p;
    private final double[] p0;
    private final double lambda;
    private double residualVar = -1;
    private long samples;
    private int rejectedRun;

    Rls(double[] theta0, double[] variance0, double lambda) {
        this.n = theta0.length;
        this.theta0 = theta0.clone();
        this.theta = theta0.clone();
        this.p0 = variance0.clone();
        this.p = new double[n][n];
        for (int i = 0; i < n; i++) p[i][i] = variance0[i];
        this.lambda = lambda;
    }

    double predict(double[] x) {
        double s = 0;
        for (int i = 0; i < n; i++) s += theta[i] * x[i];
        return s;
    }

    long samples() {
        return samples;
    }

    /** The residual's running standard deviation (ms), or -1 before any sample. */
    double residualSd() {
        return residualVar < 0 ? -1 : Math.sqrt(residualVar);
    }

    /** One observation. False when it was rejected as an outlier. */
    boolean update(double[] x, double y) {
        if (!Double.isFinite(y)) return false;
        double r = y - predict(x);
        if (residualVar >= 0 && samples > 30 && r * r > 16 * Math.max(residualVar, 0.01) && rejectedRun < 8) {
            rejectedRun++;  // a hitch; but eight in a row are a new regime (another scene): those go in
            return false;
        }
        if (rejectedRun >= 8) residualVar = r * r;
        rejectedRun = 0;
        residualVar = residualVar < 0 ? r * r : residualVar * 0.98 + r * r * 0.02;
        double noise = Math.max(0.04, 0.02 * y * y);  // measurement noise: timestamps jitter a few percent
        double[] px = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int j = 0; j < n; j++) s += p[i][j] * x[j];
            px[i] = s;
        }
        double denom = lambda * noise;
        for (int i = 0; i < n; i++) denom += x[i] * px[i];
        if (!(denom > 1e-12)) return false;
        for (int i = 0; i < n; i++) theta[i] += px[i] / denom * r;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) p[i][j] = (p[i][j] - px[i] * px[j] / denom) / lambda;
        for (int i = 0; i < n; i++) {
            if (theta[i] < 0) theta[i] = 0;  // costs are never negative
            double cap = 4 * p0[i];
            if (p[i][i] > cap) {
                double f = Math.sqrt(cap / p[i][i]);
                for (int j = 0; j < n; j++) {
                    p[i][j] *= f;
                    p[j][i] *= f;
                }
            }
        }
        samples++;
        if (samples % 50 == 0) anchor();
        return true;
    }

    /** One weak pseudo-observation of the prior per term (no forgetting). */
    private void anchor() {
        double[] k = new double[n];
        for (int i = 0; i < n; i++) {
            double denom = 4 * p0[i] + p[i][i];
            if (!(denom > 1e-12)) continue;
            double r = theta0[i] - theta[i];
            for (int j = 0; j < n; j++) k[j] = p[j][i] / denom;
            for (int j = 0; j < n; j++) theta[j] += k[j] * r;
            double[] row = p[i].clone();
            for (int a = 0; a < n; a++)
                for (int b = 0; b < n; b++) p[a][b] -= k[a] * row[b];
        }
        for (int i = 0; i < n; i++) if (theta[i] < 0) theta[i] = 0;
    }

    /** Variance of each term (diagonal), for the saved profile. */
    double[] variances() {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = p[i][i];
        return v;
    }

    /** Restores a saved state: coefficients and their variances (covariances start at zero). */
    void restore(double[] t, double[] v, long count) {
        if (t == null || t.length != n || v == null || v.length != n) return;
        for (int i = 0; i < n; i++) {
            theta[i] = Math.max(0, Double.isFinite(t[i]) ? t[i] : theta0[i]);
            Arrays.fill(p[i], 0);
            p[i][i] = Double.isFinite(v[i]) && v[i] > 0 ? Math.min(v[i], 4 * p0[i]) : p0[i];
        }
        samples = Math.max(0, count);
    }
}
