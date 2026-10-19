package com.nirncraft.atmosphere.client;

import com.nirncraft.NirnCraft;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.GpuDevice;

/** Nonblocking whole-frame GPU timing. Rendering adapts without changing weather
 * state or cloud bounds; an overloaded integrated server also reserves headroom. */
final class AtmosphereBudget {
    private static final boolean[] pending = new boolean[8];
    private static GpuQueryPool timestamps;
    private static GpuDevice timerDevice;
    private static float timestampPeriod;
    private static boolean initialized, supported, measuring;
    private static int frame, slot, samples;
    private static float scale = 1f, targetScale = 1f, gpuMs = 12f, serverMs, lastMeasurement;
    private static long lastFrame;

    private AtmosphereBudget() {}

    static void beginFrame() {
        var device = RenderSystem.getDevice();
        if (!initialized || timerDevice != device) {
            close();
            initialized = true;
            timerDevice = device;
            timestampPeriod = device.getDeviceInfo().timestampPeriod();
            supported = Float.isFinite(timestampPeriod) && timestampPeriod > 0f;
            if (Boolean.getBoolean("nirncraft.autotest"))
                NirnCraft.LOGGER.info("Atmosphere GPU timer supported={}", supported);
            if (supported) timestamps = device.createTimestampQueryPool(pending.length * 2);
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
        serverMs = server == null ? 0f : server.getCurrentSmoothedTickTime();
        int currentFrame = frame++;
        if (currentFrame % 30 == 0) {
            if (serverMs > 42f) targetScale = Math.max(0.55f, targetScale - 0.025f);
            else if (!supported && serverMs < 35f) targetScale = Math.min(1f, targetScale + 0.008f);
        }
        if (!supported) return;
        slot = currentFrame & 7;
        if (pending[slot]) {
            var values = timestamps.getValues(slot * 2, 2);
            if (values[0].isEmpty() || values[1].isEmpty()) return;
            double elapsed = (values[1].getAsLong() - values[0].getAsLong()) * (double) timestampPeriod / 1_000_000.0;
            pending[slot] = false;
            lastMeasurement = (float) elapsed;
            if (elapsed > 0 && elapsed < 250) {
                gpuMs += ((float) elapsed - gpuMs) * 0.08f;
                if (++samples % 30 == 0) {
                    if (gpuMs > 17f || serverMs > 42f) targetScale = Math.max(0.55f, targetScale - 0.025f);
                    else if (gpuMs < 11f && serverMs < 35f) targetScale = Math.min(1f, targetScale + 0.008f);
                    if (samples % 300 == 0 && Boolean.getBoolean("nirncraft.autotest"))
                        NirnCraft.LOGGER.info("Atmosphere budget: scale={} gpu_ms={} integrated_server_ms={}", scale, gpuMs, serverMs);
                }
            }
        }
        // Frame Pre/Post run outside native render passes, so the shared encoder owns these writes.
        timerDevice.createCommandEncoder().writeTimestamp(timestamps, slot * 2);
        measuring = true;
    }

    static void endFrame() {
        if (!measuring) return;
        timerDevice.createCommandEncoder().writeTimestamp(timestamps, slot * 2 + 1);
        pending[slot] = true;
        measuring = false;
    }

    /** Release device-owned queries; quality history remains useful when switching worlds. */
    static void close() {
        measuring = false;
        if (timestamps != null) timestamps.close();
        timestamps = null;
        timerDevice = null;
        initialized = supported = false;
        java.util.Arrays.fill(pending, false);
    }

    static float scale() { return scale; }
    static long physicsBudget(long baseline) { return Math.max(250_000L, (long) (baseline * scale)); }
    static String describe() {
        return "budget=" + scale + " gpu_ms=" + gpuMs + " server_ms=" + serverMs
                + " samples=" + samples + " frames=" + frame + " timer=" + supported + " latest_ms=" + lastMeasurement;
    }
}
