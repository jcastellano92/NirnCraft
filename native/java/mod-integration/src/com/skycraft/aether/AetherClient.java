package com.skycraft.aether;

import com.mojang.renderpearl.api.GpuFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Client side of aether: loads the native library, keeps the shared GL targets and the textures the Skycraft shader
 * pack samples (skycraft:aether/color, skycraft:aether/depth), streams far-field tiles on a worker thread, and
 * renders one aether frame per level frame after the opaque near field (so its depth can be read).
 *
 * <p>Everything degrades to "no far field": no DLL, no Vulkan, a GL context on another GPU, or any native error
 * disables aether for the session with one log line; the game renders as before.</p>
 */
public final class AetherClient {
    /** The weather fields WeatherFieldTexture builds (RGBA8, 128 x 128, r in the low byte). */
    public interface WeatherFeed {
        /** Fills the four fields; false when nothing changed since the last call. */
        boolean fill(int[] nearField, int[] nearDyn, int[] farField, int[] farDyn);

        float rain();
    }

    // Must equal the shader pack's PLANET_RADIUS and sunPathRotation (shaderpack/.../lib/settings.glsl).
    public static float planetRadius = 6_371_000f;
    public static float sunPathRotationDeg = -25f;
    /** Blocks over which the rasterised near field hands over to aether, ending 16 blocks inside render distance. */
    public static float handoffBand = 24f;

    private static AetherNative lib;
    private static AetherNative.Context ctx;
    private static AetherTargets targets;
    private static AetherFarField farField;
    private static Supplier<WeatherFeed> weather = () -> null;
    private static boolean failed;
    private static final AetherNative.Frame frame = new AetherNative.Frame();
    private static final AetherNative.GlFrameIO io = new AetherNative.GlFrameIO();
    private static final int[][] fields = {new int[128 * 128], new int[128 * 128], new int[128 * 128], new int[128 * 128]};

    private AetherClient() {}

    public static void init(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(AetherClient::afterOpaque);
    }

    public static void setWeatherFeed(Supplier<WeatherFeed> feed) {
        weather = feed;
    }

    public static boolean active() {
        return ctx != null && !failed;
    }

    private static void afterOpaque(RenderLevelStageEvent.AfterOpaqueBlocks event) {
        if (failed) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            if (ctx == null && !start(mc)) return;
            int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
            if (targets == null || targets.width() != w || targets.height() != h) {
                if (targets != null) targets.close();
                targets = AetherTargets.create(ctx, w, h);
            }
            LevelRenderState state = event.getLevelRenderState();
            CameraRenderState cam = state.cameraRenderState;
            Vec3 p = cam.pos;
            farField.follow(p.x, p.y, p.z);
            feedWeather();

            frame.x = p.x;
            frame.y = p.y;
            frame.z = p.z;
            // camera-relative inverse view-projection: viewRotation has no translation
            Matrix4f invVP = new Matrix4f(cam.projectionMatrix).mul(cam.viewRotationMatrix).invert();
            invVP.get(frame.invViewProj);
            frame.width = w;
            frame.height = h;
            float handEnd = cam.depthFar - 16f;
            frame.nearFieldEnd = Math.max(32f, handEnd - handoffBand);
            frame.maxDistance = 40_000f;
            frame.planetRadius = planetRadius;
            float[] sun = sunDirection(state.skyRenderState.sunAngle);
            frame.sunX = sun[0];
            frame.sunY = sun[1];
            frame.sunZ = sun[2];
            frame.timeSec = (state.gameTime + state.worldPartialTicks) / 20f;
            frame.lodBias = 1f;
            // the pack draws the sky; aether adds far terrain, clouds and far actors over it
            frame.flags = AetherNative.FRAME_SHADOWS | AetherNative.FRAME_ATMOS | AetherNative.FRAME_CLOUDS
                    | AetherNative.FRAME_ACTORS;

            io.nearDepthTex = 0xFFFFFFFF;  // AETHER_GL_BOUND_DEPTH: the bound gbuffer target's depth
            new Matrix4f(cam.projectionMatrix).invert().get(io.nearInvProjection);
            io.depthZeroToOne = AetherTargets.depthZeroToOne();
            io.reversedZ = false;
            io.dstColor = targets.colorId();
            io.dstNormal = 0;
            io.dstDepth = targets.depthId();
            ctx.glRender(frame, io);
        } catch (Throwable t) {
            disable("aether frame failed", t);
        }
    }

    private static boolean start(Minecraft mc) {
        try {
            Path dll = AetherLibrary.extract(mc.gameDirectory.toPath());
            lib = AetherNative.load(dll);
            ctx = lib.create(AetherNative.BACKEND_VULKAN);
            ctx.setPalette(AetherPalette.build());
            int[] noise = AetherPalette.cloudNoiseVolume();
            if (noise != null) ctx.setCloudNoise(noise);
            farField = new AetherFarField(ctx, AetherFarField.defaultSource());
            com.skycraft.Skycraft.LOGGER.info("aether far field on: {}", lib.buildInfo());
            return true;
        } catch (Throwable t) {
            disable("aether unavailable, far field off", t);
            return false;
        }
    }

    private static void feedWeather() {
        WeatherFeed feed = weather.get();
        if (feed != null && feed.fill(fields[0], fields[1], fields[2], fields[3])) {
            ctx.setWeather(fields[0], fields[1], fields[2], fields[3], feed.rain());
        }
    }

    /**
     * World direction to the sun, as Iris places it: Ry(-90) Rz(sunPathRotation) Rx(sunAngle) applied to +Y, so
     * aether's light and the pack's (sunPosition) agree.
     */
    static float[] sunDirection(float sunAngle) {
        double a = sunAngle, r = Math.toRadians(sunPathRotationDeg);
        double x = -Math.sin(a), y = Math.cos(r) * Math.cos(a), z = -Math.sin(r) * Math.cos(a);
        return new float[] {(float) x, (float) y, (float) z};
    }

    private static void disable(String why, Throwable t) {
        failed = true;
        com.skycraft.Skycraft.LOGGER.warn("{}: {}", why, t.toString());
        try {
            if (farField != null) farField.close();
            if (targets != null) targets.close();
            if (ctx != null) {
                ctx.glDetach();
                ctx.close();
            }
        } catch (Throwable ignored) {
            // best effort: the session continues without aether
        }
        ctx = null;
    }

    static GpuFormat colorFormat() {
        return GpuFormat.RGBA16_FLOAT;
    }
}
