package com.skycraft.atmosphere.client;

import com.skycraft.Skycraft;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/** Nonblocking whole-frame GPU timing. Rendering adapts without changing weather
 * state or cloud bounds; an overloaded integrated server also reserves headroom. */
final class AtmosphereBudget {
    private static final int[] begin = new int[8], end = new int[8];
    private static final boolean[] pending = new boolean[8];
    private static boolean initialized, supported, measuring;
    private static int frame, slot, samples;
    private static float scale = 1f, targetScale = 1f, gpuMs = 12f, serverMs, lastMeasurement;
    private static long lastFrame;

    private AtmosphereBudget() {}

    static void beginFrame() {
        if (!initialized) {
            initialized = true;
            var capabilities = GL.getCapabilities();
            supported = capabilities.OpenGL33 || capabilities.GL_ARB_timer_query;
            if (Boolean.getBoolean("skycraft.autotest"))
                Skycraft.LOGGER.info("Atmosphere GPU timer supported={}", supported);
            if (supported) for (int i = 0; i < 8; i++) {
                begin[i] = GL15.glGenQueries(); end[i] = GL15.glGenQueries();
            }
        }
        measuring = false;
        long now=System.nanoTime();
        float seconds=lastFrame==0?0f:Math.min(0.1f,(now-lastFrame)*1e-9f);
        lastFrame=now;
        // Hysteresis chooses a target; slew it continuously instead of dropping
        // several primary ray samples together every thirty measured frames.
        float delta=targetScale-scale;
        scale+=Math.copySign(Math.min(Math.abs(delta),seconds*0.012f),delta);
        var server = Minecraft.getInstance().getSingleplayerServer();
        serverMs = server == null ? 0f : server.getAverageTickTime();
        int currentFrame = frame++;
        if (currentFrame % 30 == 0) {
            if (serverMs > 42f) targetScale = Math.max(0.55f, targetScale - 0.025f);
            else if (!supported && serverMs < 35f) targetScale = Math.min(1f, targetScale + 0.008f);
        }
        if (!supported) return;
        slot = currentFrame & 7;
        if (pending[slot]) {
            if (GL15.glGetQueryObjecti(end[slot], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) return;
            double elapsed = (GL33.glGetQueryObjectui64(end[slot], GL15.GL_QUERY_RESULT)
                    - GL33.glGetQueryObjectui64(begin[slot], GL15.GL_QUERY_RESULT)) / 1_000_000.0;
            pending[slot] = false;
            lastMeasurement = (float) elapsed;
            if (elapsed > 0 && elapsed < 250) {
                gpuMs += ((float) elapsed - gpuMs) * 0.08f;
                if (++samples % 30 == 0) {
                    if (gpuMs > 17f || serverMs > 42f) targetScale = Math.max(0.55f, targetScale - 0.025f);
                    else if (gpuMs < 11f && serverMs < 35f) targetScale = Math.min(1f, targetScale + 0.008f);
                    if (samples % 300 == 0 && Boolean.getBoolean("skycraft.autotest"))
                        Skycraft.LOGGER.info("Atmosphere budget: scale={} gpu_ms={} integrated_server_ms={}", scale, gpuMs, serverMs);
                }
            }
        }
        GL33.glQueryCounter(begin[slot], GL33.GL_TIMESTAMP);
        measuring = true;
    }

    static void endFrame() {
        if (!measuring) return;
        GL33.glQueryCounter(end[slot], GL33.GL_TIMESTAMP);
        pending[slot] = true;
        measuring = false;
    }

    static float scale() { return scale; }
    static long physicsBudget(long baseline) { return Math.max(250_000L, (long) (baseline * scale)); }
    static String describe() {
        return "budget=" + scale + " gpu_ms=" + gpuMs + " server_ms=" + serverMs
                + " samples=" + samples + " frames=" + frame + " timer=" + supported + " latest_ms=" + lastMeasurement;
    }
}
