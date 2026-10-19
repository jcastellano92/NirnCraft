package com.nirncraft.atmosphere.client;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * The night's key light exactly as the shader pack computes it (lib/celestial.glsl moons / lunarKeyDir /
 * lunarRadiance), for the far field (atlas_set_moon): both fields then light the night with the same moon. Inputs are
 * the pack's: the sky clock in days, the sun path tilt, NightSkyState's Masser / Secunda light, the Secunda option.
 */
public final class LunarKey {
    private LunarKey() {}

    /** {dirX, dirY, dirZ, radR, radG, radB} towards the brighter moon; sun is the unit vector towards the sun. */
    public static float[] compute(ClientLevel level, float[] sun, float sunPathRotationDeg, float partialTick) {
        double days = (level.getOverworldClockTime() + partialTick) / 24000.0;
        double tilt = Math.toRadians(sunPathRotationDeg);
        double[] axis = {0.0, Math.sin(tilt), Math.cos(tilt)};
        double[] anti = {-sun[0], -sun[1], -sun[2]};
        double[] masser = rotate(anti, axis, frac((days - 0.75) / 8.0) * Math.PI * 2);
        double[] secunda = rotate(rotate(anti, axis, frac((days - 0.75) / 9.0 + 0.375) * Math.PI * 2),
                new double[] {1.0, 0.0, 0.0}, -0.41887902);
        double wx = (0.5 - 0.5 * dot(sun, masser)) * smooth(-0.035, 0.10, masser[1]) * NightSkyState.masserLight;
        double wy = (0.5 - 0.5 * dot(sun, secunda)) * smooth(-0.035, 0.10, secunda[1]) * NightSkyState.secundaLight;
        if (!com.nirncraft.atmosphere.AtmosphereConfig.SECUNDA.get()) wy = 0;
        double second = smooth(0.8, 1.2, wy * 0.20 / Math.max(0.001, wx * 0.80));
        double[] dir = new double[3];
        for (int i = 0; i < 3; i++) dir[i] = masser[i] + (secunda[i] - masser[i]) * second;
        double len = Math.max(1e-6, Math.sqrt(dot(dir, dir)));
        return new float[] {(float) (dir[0] / len), (float) (dir[1] / len), (float) (dir[2] / len),
                (float) ((1.0 * wx * 0.80 + 0.78 * wy * 0.20) * 0.16),
                (float) ((0.72 * wx * 0.80 + 0.88 * wy * 0.20) * 0.16),
                (float) ((0.78 * wx * 0.80 + 1.0 * wy * 0.20) * 0.16)};
    }

    private static double[] rotate(double[] p, double[] k, double a) {
        double c = Math.cos(a), s = Math.sin(a), kd = dot(k, p);
        double[] kx = {k[1] * p[2] - k[2] * p[1], k[2] * p[0] - k[0] * p[2], k[0] * p[1] - k[1] * p[0]};
        return new double[] {p[0] * c + kx[0] * s + k[0] * kd * (1 - c), p[1] * c + kx[1] * s + k[1] * kd * (1 - c),
                p[2] * c + kx[2] * s + k[2] * kd * (1 - c)};
    }

    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    private static double dot(float[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    private static double frac(double v) { return v - Math.floor(v); }

    private static double smooth(double e0, double e1, double x) {
        double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }
}
