package com.skycraft.atmosphere.client;

import net.minecraft.Util;

import java.lang.reflect.Method;

/**
 * Detects an active Oculus/Iris shader pack through the public Iris API
 * ({@code net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse()}) by reflection, so there is no
 * compile or runtime dependency. Any failure means "no shaders". The answer is cached for a second.
 */
final class ShaderCompat {
    private static boolean unavailable;
    private static Object api;
    private static Method inUse;
    private static long lastCheck;
    /** Whether {@link #lastCheck} holds a real time. (It used to start at Long.MIN_VALUE, and now - MIN_VALUE overflows
     * to a negative number, so the "checked less than a second ago" test always passed and shaders were never seen.) */
    private static boolean checked;
    private static boolean cached;

    private ShaderCompat() {}

    static boolean shadersActive() {
        if (unavailable) return false;
        long now = Util.getMillis();
        if (checked && now - lastCheck < 1000L) return cached;
        checked = true;
        lastCheck = now;
        cached = query();
        return cached;
    }

    private static boolean query() {
        try {
            if (api == null) {
                Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = c.getMethod("getInstance").invoke(null);
                inUse = c.getMethod("isShaderPackInUse");
                if (api == null) {
                    unavailable = true;
                    return false;
                }
            }
            return Boolean.TRUE.equals(inUse.invoke(api));
        } catch (Throwable t) {
            unavailable = true;
            return false;
        }
    }
}
