package com.skycraft.atmosphere;

import java.util.Random;

/** Bounded, deterministic discharge geometry and timing; no world or renderer access. */
public final class LightningMath {
    public static final int POINTS = 33, FORKS = 10, FORK_POINTS = 17;
    private LightningMath() {}

    public static float leaderTicks(long seed) { return 1.4f + ((seed >>> 8) & 255) / 255f * 3.4f; }
    public static float lifetime(long seed) { return leaderTicks(seed) + 7f + ((seed >>> 19) & 15); }
    public static float progress(long seed, float age) { return clamp(age / leaderTicks(seed)); }
    public static float pulse(long seed, float age, float life) {
        if (age < 0 || age >= life) return 0;
        float t = age - leaderTicks(seed);
        if (t < 0) return 0.055f + 0.12f * progress(seed, age);
        // Smooth sub-tick strokes reuse the connected channel, with dark gaps between them.
        float first = (float) Math.exp(-t * 2.8f);
        float repeat = 0;
        int strokes = 2 + (int) ((seed >>> 27) & 3);
        for (int i = 1; i <= strokes; i++) {
            float at = i * (2.0f + ((seed >>> (i * 5)) & 7) * 0.16f);
            if (t >= at) repeat = Math.max(repeat, (float) Math.exp(-(t - at) * 3.4f) * (0.90f - i * 0.08f));
        }
        float continuing = (seed & 3) == 0 ? 0.16f * (1f - clamp(t / (life - leaderTicks(seed)))) : 0.018f;
        return Math.max(first, Math.max(repeat, continuing)) * (1f - clamp((age - life + 2f) / 2f));
    }

    /** Exposure/short-path heuristic, not an electric-field simulation or a tallest-object guarantee. */
    public static float targetScore(float height, float baseline, float horizontalDistance, float cloudHeight) {
        return (height - baseline) * 2.5f - horizontalDistance * 0.22f
                - Math.max(0, height - cloudHeight + 12) * 12f;
    }

    public static final class Channel {
        public final float[][] x = new float[FORKS + 1][POINTS];
        public final float[][] y = new float[FORKS + 1][POINTS];
        public final float[][] z = new float[FORKS + 1][POINTS];
        public final float[] starts = new float[FORKS + 1];
        public final float[] ends = new float[FORKS + 1];

        public void build(long seed, float x0, float y0, float z0, float x1, float y1, float z1,
                          boolean ground, float floor, float ceiling) {
            Random r = new Random(seed);
            // Neighboring storm towers can have different condensation heights.
            // Preserve both attachments instead of clipping the bridge to the first tower's layer.
            if (!ground) {
                floor = Math.min(floor, Math.min(y0, y1));
                ceiling = Math.max(ceiling, Math.max(y0, y1));
            }
            float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
            float span = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float jx = 0, jy = 0, jz = 0;
            starts[0] = 0; ends[0] = 1;
            for (int i = 0; i < POINTS; i++) {
                float t = i / (float) (POINTS - 1), envelope = 4 * t * (1 - t);
                x[0][i] = x0 + dx * t + jx * envelope;
                y[0][i] = y0 + dy * t + (ground ? 0 : Math.min(80, span * 0.14f) + jy) * envelope;
                z[0][i] = z0 + dz * t + jz * envelope;
                if (!ground) y[0][i] = Math.max(floor, Math.min(ceiling, y[0][i]));
                jx += (r.nextFloat() - 0.5f) * span * 0.055f;
                jy += (r.nextFloat() - 0.5f) * span * 0.025f;
                jz += (r.nextFloat() - 0.5f) * span * 0.055f;
            }
            for (int b = 1; b <= FORKS; b++) {
                int parent = b > 6 ? b - 6 : 0;
                int at = parent == 0 ? 3 + r.nextInt(22) : 4 + r.nextInt(7);
                int parentCount = parent == 0 ? POINTS : FORK_POINTS;
                starts[b] = starts[parent] + (ends[parent] - starts[parent]) * at / (parentCount - 1f);
                ends[b] = Math.min(1, starts[b] + (parent == 0 ? 0.30f : 0.15f));
                float fx = x[parent][at], fy = y[parent][at], fz = z[parent][at];
                float angle = r.nextFloat() * (float) (Math.PI * 2);
                float length = span * (parent == 0 ? 0.30f + r.nextFloat() * 0.45f : 0.13f);
                float sx = (float) Math.cos(angle) * length / (FORK_POINTS - 1);
                float sz = (float) Math.sin(angle) * length / (FORK_POINTS - 1);
                for (int i = 0; i < FORK_POINTS; i++) {
                    x[b][i] = fx; y[b][i] = fy; z[b][i] = fz;
                    fx += sx + (r.nextFloat() - 0.5f) * length * 0.12f;
                    fz += sz + (r.nextFloat() - 0.5f) * length * 0.12f;
                    fy += (ground ? -length * 0.025f : 0) + (r.nextFloat() - 0.5f) * length * 0.065f;
                    fy = Math.max(floor + 3, Math.min(ceiling, fy));
                }
            }
        }
    }
    private static float clamp(float t) { return Math.max(0, Math.min(1, t)); }
}
