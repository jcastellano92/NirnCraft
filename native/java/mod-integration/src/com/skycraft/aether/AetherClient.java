package com.skycraft.aether;

import com.mojang.renderpearl.api.GpuFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
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
    private static ClientLevel startedFor;  // the level the far field's tiles belong to
    private static final WeatherFeed WEATHER = new AetherWeatherFeed();
    private static final AetherQuality quality = new AetherQuality();
    private static Supplier<WeatherFeed> weather = () -> WEATHER;
    private static boolean failed;
    private static final AetherNative.Frame frame = new AetherNative.Frame();
    private static final AetherNative.GlFrameIO io = new AetherNative.GlFrameIO();
    private static final int[][] fields = {new int[128 * 128], new int[128 * 128], new int[128 * 128], new int[128 * 128]};
    /** Set by the server's Hello: it streams exact far tiles for this world (0 = it does not). */
    private static volatile long serverWorldId;
    private static volatile AetherNet.FarActors farActors;
    private static volatile long farActorsAt;
    private static double[] actorXyz = new double[0];

    private AetherClient() {}

    public static void init(IEventBus modBus) {
        // the pack's aether textures must exist before Iris builds a world pipeline (it holds the texture objects from
        // then on). Client setup runs on a loader thread, so hop to the render thread, where GPU objects may be made.
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent e) ->
                e.enqueueWork(() -> net.minecraft.client.Minecraft.getInstance().execute(AetherTargets::registerPlaceholders)));
        NeoForge.EVENT_BUS.addListener(AetherClient::afterOpaque);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) -> AetherTargets.flushClear());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            serverWorldId = 0;
            farActors = null;
        });
        AetherNet.onHello = AetherClient::onHello;
        AetherNet.onTile = m -> {
            if (farField != null && farField.source() instanceof AetherServerSource s) s.accept(m);
        };
        AetherNet.onFarActors = m -> {
            farActors = m;
            farActorsAt = System.nanoTime();
        };
    }

    /** The server streams exact tiles: switch a multiplayer client from the stand-in (restarts next frame). */
    private static void onHello(AetherNet.Hello m) {
        if (m.codec() != AetherTileCodec.VERSION) return;  // another build: keep the stand-in
        serverWorldId = m.worldId();
        if (ctx != null && !Minecraft.getInstance().hasSingleplayerServer()
                && !(farField != null && farField.source() instanceof AetherServerSource)) stop();
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
            // a new world (or server): new tiles, maybe another column source
            if (ctx != null && startedFor != mc.level && mc.level.dimension() == Level.OVERWORLD) stop();
            if (ctx == null && !start(mc)) return;
            // aether's own resolution (dynamic: AetherQuality); the pack upsamples it to the window
            int w = quality.width(mc.getWindow().getWidth()), h = quality.height(mc.getWindow().getHeight());
            int sunMap = quality.sunMapSize();
            if (targets == null || targets.width() != w || targets.height() != h || targets.sunSize() != sunMap) {
                if (targets != null) targets.close();
                ctx.setSunMap(sunMap, AetherQuality.SUN_HALF_EXTENT);
                targets = AetherTargets.create(ctx, w, h, sunMap);
            }
            if (mc.level.dimension() != Level.OVERWORLD) {
                targets.clear();  // the far field is the overworld's; elsewhere the near field stands alone
                return;
            }
            LevelRenderState state = event.getLevelRenderState();
            CameraRenderState cam = state.cameraRenderState;
            Vec3 p = cam.pos;
            farField.follow(p.x, p.y, p.z);
            if (!farField.updated()) {
                targets.clear();  // the worker has not staged the first window yet: nothing far this frame
                return;
            }
            feedWeather();
            feedActors();

            frame.x = p.x;
            frame.y = p.y;
            frame.z = p.z;
            // camera-relative inverse view-projection: viewRotation has no translation
            Matrix4f invVP = new Matrix4f(cam.projectionMatrix).mul(cam.viewRotationMatrix).invert();
            invVP.get(frame.invViewProj);
            frame.width = w;
            frame.height = h;
            // the hand-off follows the loaded terrain's radius (what Iris hands the pack as `far`), not the
            // projection's far plane: 26.3's far plane lies well beyond the chunks, which left an empty band out to it
            float handEnd = mc.options.getEffectiveRenderDistance() * 16f - 16f;
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
            frame.flags = quality.flags(AetherNative.FRAME_SHADOWS | AetherNative.FRAME_ATMOS | AetherNative.FRAME_CLOUDS
                    | AetherNative.FRAME_ACTORS);

            io.nearDepthTex = 0xFFFFFFFF;  // AETHER_GL_BOUND_DEPTH: the bound gbuffer target's depth
            new Matrix4f(cam.projectionMatrix).invert().get(io.nearInvProjection);
            io.depthZeroToOne = AetherTargets.depthZeroToOne();
            io.reversedZ = reversedZ();  // the depth convention the shader pack's gbuffers see (Iris reports it)
            io.dstColor = targets.colorId();
            io.dstNormal = 0;
            io.dstDepth = targets.depthId();
            io.dstSunMap = targets.sunId();
            ctx.glRender(frame, io);
            if (quality.update((float) ctx.stats().frameMs(), mc.getFps(), System.nanoTime()))
                com.skycraft.Skycraft.LOGGER.debug("aether quality level {} (scale {})", quality.level(), quality.scale());
        } catch (Throwable t) {
            disable("aether frame failed", t);
        }
    }

    private static boolean start(Minecraft mc) {
        try {
            if (lib == null) {
                Path dll = AetherLibrary.extract(mc.gameDirectory.toPath());
                lib = AetherNative.load(dll);
            }
            ctx = lib.create(AetherNative.BACKEND_VULKAN);
            // single player: the server's own terrain height (exact); multiplayer: exact tiles from the server when
            // it streams them; otherwise (or until they arrive) the seed-free raster stand-in
            AetherFarField.ColumnSource source = null;
            if (mc.hasSingleplayerServer())
                source = AetherExactSource.forServer(mc.getSingleplayerServer(), AetherFarField::previewRaster);
            else if (serverWorldId != 0)
                source = new AetherServerSource(serverWorldId, mc.gameDirectory.toPath(), AetherFarField::previewRaster);
            if (source == null) source = AetherFarField.defaultSource();
            if (source.usesBlockPalette()) ctx.setPalette(AetherPalette.blocks());  // else aether's preview palette
            int[] noise = AetherPalette.cloudNoiseVolume();
            if (noise != null) ctx.setCloudNoise(noise);
            java.util.function.IntBinaryOperator ground = source instanceof AetherExactSource e ? e::groundAt : null;
            farField = new AetherFarField(ctx, source, () -> AetherSettlements.create(ground));
            startedFor = mc.level;
            com.skycraft.Skycraft.LOGGER.info("aether far field on ({} columns): {}", source instanceof AetherExactSource
                    ? "exact" : source instanceof AetherServerSource ? "server" : "raster", lib.buildInfo());
            return true;
        } catch (Throwable t) {
            disable("aether unavailable, far field off", t);
            return false;
        }
    }

    /** The server's far actors, moved on by their velocity since the packet (twice a second; at most a second). */
    private static void feedActors() {
        AetherNet.FarActors a = farActors;
        if (a == null) {
            ctx.setActors(actorXyz, new float[0], new int[0], 0);
            return;
        }
        double dt = Math.min((System.nanoTime() - farActorsAt) / 1e9, 1.0);
        if (actorXyz.length < a.count() * 3) actorXyz = new double[a.count() * 3];
        for (int i = 0; i < a.count() * 3; i++) actorXyz[i] = a.xyz()[i] + a.vel()[i] * dt;
        ctx.setActors(actorXyz, a.yawHeightRadius(), a.kindTint(), a.count());
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

    private static java.lang.reflect.Method irisReverseZ;
    private static Object irisApi;
    private static boolean irisChecked;

    /** Iris 1.11: whether depth is reversed while a pack renders (26.3 itself is reverse-Z); false without Iris. */
    private static boolean reversedZ() {
        try {
            if (!irisChecked) {
                irisChecked = true;
                Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = c.getMethod("getInstance").invoke(null);
                irisReverseZ = c.getMethod("isReverseZDuringShaders");
            }
            return irisApi != null && Boolean.TRUE.equals(irisReverseZ.invoke(irisApi));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static void disable(String why, Throwable t) {
        failed = true;
        com.skycraft.Skycraft.LOGGER.warn("{}: {}", why, t.toString());
        stop();
    }

    /** Tears aether down (start() brings it back with fresh tiles). The library stays loaded. */
    private static void stop() {
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
        farField = null;
        targets = null;
        ctx = null;
        startedFor = null;
    }

    static GpuFormat colorFormat() {
        return GpuFormat.RGBA16_FLOAT;
    }
}
