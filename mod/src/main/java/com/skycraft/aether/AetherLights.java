package com.skycraft.aether;

import java.util.List;

/**
 * Far lights (aether_set_light_group): what makes a town, a fort or the Red Mountain read at night from kilometres away.
 * Kinds and colours follow the near field's light blocks: lanterns and lit windows a warm 2700 K, braziers and camp
 * fires orange and flickering, lava red-orange and pulsing, waterfall spray white (lit by the sun, not glowing).
 */
final class AetherLights {
    private AetherLights() {}

    static final int NIGHT = 0, FIRE = 1, LAVA = 2, MIST = 3;
    /** sRGB, r in the low byte. */
    static final int WARM = 0xFF << 0 | 0xB8 << 8 | 0x6A << 16, WINDOW = 0xFF | 0xA8 << 8 | 0x58 << 16,
            ORANGE = 0xFF | 0x86 << 8 | 0x2E << 16, LAVA_RGB = 0xFF | 0x5A << 8 | 0x14 << 16, SPRAY = 0xF4 | 0xF8 << 8 | 0xFF << 16;

    /** One light: world position, size (blocks), relative radiance (1 = a lantern), sRGB colour, kind. */
    record Light(double x, double y, double z, float radius, float intensity, int rgb, int kind) {}

    static Light lamp(double x, double y, double z, float intensity) {
        return new Light(x, y, z, 1f, intensity, WARM, NIGHT);
    }

    static Light window(double x, double y, double z, float intensity) {
        return new Light(x, y, z, 1.5f, intensity, WINDOW, NIGHT);
    }

    static Light fire(double x, double y, double z, float intensity) {
        return new Light(x, y, z, 1.5f, intensity, ORANGE, FIRE);
    }

    static Light lava(double x, double y, double z, float radius) {
        return new Light(x, y, z, radius, 1.5f + radius * 0.25f, LAVA_RGB, LAVA);
    }

    static Light mist(double x, double y, double z, float radius, float intensity) {
        return new Light(x, y, z, radius, intensity, SPRAY, MIST);
    }

    /** Replaces light group `group` (empty or null removes it). */
    static void upload(AetherNative.Context ctx, int group, List<Light> lights) {
        int n = lights == null ? 0 : lights.size();
        double[] xyz = new double[n * 3];
        float[] ri = new float[n * 2];
        int[] rk = new int[n * 2];
        for (int i = 0; i < n; i++) {
            Light l = lights.get(i);
            xyz[i * 3] = l.x();
            xyz[i * 3 + 1] = l.y();
            xyz[i * 3 + 2] = l.z();
            ri[i * 2] = l.radius();
            ri[i * 2 + 1] = l.intensity();
            rk[i * 2] = l.rgb();
            rk[i * 2 + 1] = l.kind();
        }
        ctx.setLightGroup(group, xyz, ri, rk, n);
    }
}
