package com.nirncraft.atmosphere;

import java.util.Arrays;

/** Bounded transient curtain packets and their low-order diffuse emission.
 * Geometry consumes the same packets as lighting; this is an artistic auroral
 * substorm approximation, not a magnetohydrodynamics solver. */
public final class AuroraMath {
    public static final int PACKETS = 6, VALUES = 60;
    private AuroraMath() {}

    public static void sample(double seconds, float latitude, float activity, float red, float[] out) {
        Arrays.fill(out, 0f);
        if (activity <= 0 || latitude <= 0) return;
        for (int i = 0; i < PACKETS; i++) {
            int band = i / 2;
            double cycle = seconds / (48 + band * 23) + (i % 2) * 0.5 + band * 0.317;
            long epoch = (long) Math.floor(cycle);
            double age = cycle - epoch;
            double seed = hash(epoch + i * 197L);
            float envelope = smooth(0, 0.19, age) * (1 - smooth(0.64, 1, age));
            // Some packets never develop into bright curtains; recovery can be quiet.
            float amplitude = activity * envelope * (float) (0.30 + 0.70 * hash(epoch * 7 + i * 103L));
            float azimuth = (float) ((seed - 0.5) * 2.5);
            float width = (float) (0.27 + 0.70 * hash(epoch * 11 + i * 31L));
            float hem = 0.035f + latitude * 0.61f + band * 0.095f;
            float extent = 0.14f + latitude * 0.37f;
            float energy = (float) hash(epoch * 19 + i * 79L);
            int p = i * 8;
            out[p] = azimuth; out[p+1] = width; out[p+2] = hem; out[p+3] = extent;
            out[p+4] = amplitude; out[p+5] = (float) seed; out[p+6] = energy;
            float elevation = Math.min(1.5f, hem + extent * 0.3f);
            float dx = (float) (Math.sin(azimuth) * Math.cos(elevation));
            float dy = (float) Math.sin(elevation);
            float dz = (float) (-Math.cos(azimuth) * Math.cos(elevation));
            float weight = amplitude * width * extent * 0.85f;
            float r = 0.10f + red * 0.32f + energy * 0.04f;
            float g = 0.78f - red * 0.30f;
            float b = 0.37f + latitude * 0.20f + energy * 0.13f;
            for (int channel = 0; channel < 3; channel++) {
                float value = (channel == 0 ? r : channel == 1 ? g : b) * weight;
                out[48+channel] += value * 0.5f;
                out[51+channel] += value * dx * 0.5f;
                out[54+channel] += value * dy * 0.5f;
                out[57+channel] += value * dz * 0.5f;
            }
        }
    }

    private static double hash(long value) {
        long x = value + 0x9e3779b97f4a7c15L;
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        return ((x ^ (x >>> 31)) >>> 11) * 0x1.0p-53;
    }
    private static float smooth(double low, double high, double value) {
        double t = Math.max(0, Math.min(1, (value - low) / (high - low)));
        return (float) (t * t * (3 - 2 * t));
    }
}
