package com.nirncraft.atlas;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Client side of atlas: loads the native library, keeps the shared GL targets and the textures the NirnCraft shader
 * pack samples (nirncraft:atlas/color, nirncraft:atlas/depth), streams far-field tiles on a worker thread, and
 * renders one atlas frame per level frame after the opaque near field (so its depth can be read). Without a shader
 * pack the fallback compositor ({@link AtlasFallback}) draws the far field behind the vanilla near field.
 *
 * <p><b>Never crash for visuals.</b> Everything degrades to "no far field" with one WARN line and the game renders
 * as before: no library for this platform (macOS, ARM), Java refusing native access, no usable Vulkan device, not
 * enough video memory, the Vulkan renderer backend, or any native error. A lost device (a Windows TDR, an amdgpu GPU
 * reset, a hang) turns the far field off and retries after a cool-down (60 s, then 120 s; a third loss ends it for the
 * session); running out of memory steps atlas's memory plan down ({@link AtlasMemory#shrink}) and starts again.
 * A crash guard ({@link AtlasSafeMode}) starts the session with the far field off when the last one died while it
 * was starting. One status line says where things stand ({@link AtlasStatusLine}).</p>
 *
 * <p>Properties: {@code -Dnirncraft.atlas=false} (far field off), {@code -Dnirncraft.atlas.safeMode=false|true},
 * {@code -Dnirncraft.atlas.notice=false} (no in-game notice), {@code -Dnirncraft.atlas.fallback=false} (no fallback
 * compositor without a pack), {@code -Dnirncraft.atlas.nativesDir=DIR}, {@code -Dnirncraft.atlas.gpuHeight=false},
 * and for testing the failure paths {@code -Dnirncraft.atlas.fault=device_lost|timeout|oom[@frames]} (or the
 * environment variable ATLAS_FAULT, read by the native library itself).</p>
 */
public final class AtlasClient {
    /** The weather fields WeatherFieldTexture builds (RGBA8, 128 x 128, r in the low byte). */
    public interface WeatherFeed {
        /** Fills the four fields; false when nothing changed since the last call. */
        boolean fill(int[] nearField, int[] nearDyn, int[] farField, int[] farDyn);

        float rain();
    }

    // Must equal the shader pack's PLANET_RADIUS and sunPathRotation (shaderpack/.../lib/settings.glsl).
    public static float planetRadius = 6_371_000f;
    public static float sunPathRotationDeg = -25f;
    /** Blocks over which the rasterised near field hands over to atlas, ending 16 blocks inside render distance. */
    public static float handoffBand = 48f;  // published to the pack (data texture texel 17,1): both use this one value

    /** Device losses tolerated per session (each followed by a cool-down and a fresh context). */
    static final int MAX_LOSSES = 2;
    static final long LOSS_COOLDOWN_NS = 60_000_000_000L;

    private static AtlasNative lib;
    private static AtlasNative.Context ctx;
    private static boolean cpuBackend;  // ctx runs on the CPU backend (no usable Vulkan device, or forced)
    private static long cpuLogAt;       // nanoTime of the next CPU-path log line
    private static AtlasTargets targets;
    private static boolean farThisFrame;  // atlas drew this frame (its targets hold it): the near pass composites them
    private static boolean nearLitThisFrame;  // and lit the near pass's pixels (ATLAS_FRAME_NEAR_LIGHT)
    private static AtlasFarField farField;
    private static ClientLevel startedFor;  // the level the far field's tiles belong to
    private static final WeatherFeed WEATHER = new AtlasWeatherFeed();
    private static final AtlasQuality quality = new AtlasQuality();
    private static Supplier<WeatherFeed> weather = () -> WEATHER;
    /** Off for the session (the reason is in the status line). */
    private static boolean failed;
    private static boolean infoLogged;  // atlas_info logged for this context
    private static final AtlasNative.Frame frame = new AtlasNative.Frame();
    private static final AtlasNative.GlFrameIO io = new AtlasNative.GlFrameIO();
    private static final int[][] fields = {new int[128 * 128], new int[128 * 128], new int[128 * 128], new int[128 * 128]};
    /** Set by the server's Hello: it streams exact far tiles for this world (0 = it does not). */
    private static volatile long serverWorldId;
    private static volatile AtlasNet.FarActors farActors;
    /** The server's latest list of natural landmarks around this player (AtlasNatural), for the far field. */
    static volatile AtlasNet.FarSites farSites;
    private static volatile long farActorsAt;
    private static double[] actorXyz = new double[0];
    private static int[] actorFlags = new int[0];
    /** Per far actor (entity id): its impostor's opacity, fading out while the client also has the real entity. */
    private static final it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap actorOpacity = new it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap();
    private static int lightBudget = -1;
    /**
     * The content-shaped groups (docs/ATLAS-LOD.md; ATLAS_FRAME_GROUPS, atlas_core 0.14): only with
     * -Dnirncraft.atlas.groups=true until they are proven in game; the default stays the voxel path.
     */
    private static boolean groups;
    /** Seconds over which an impostor dissolves once the real entity is tracked (and region refinements dissolve). */
    static final float FADE_SECONDS = 0.6f;

    // robustness state
    private static AtlasSafeMode guard;
    private static boolean decided;           // the crash guard was consulted this session
    private static long retryAt;              // nanoTime: try again after a lost device (0: no cool-down)
    private static int losses;
    private static AtlasMemory.Plan shrunk;  // the plan after running out of memory (null: measure afresh)
    private static boolean firstFrame = true; // of the current context
    private static String lastStatus;
    private static boolean noticeShown, accessAdviceLogged;
    private static Boolean lastPack;          // shader pack in use last frame (null: not known yet)
    private static String fallbackBlocker;    // why the compositor cannot run (null: it can, or not checked)
    private static boolean fallbackChecked;

    private AtlasClient() {}

    public static void init(IEventBus modBus) {
        // the pack's atlas textures must exist before Iris builds a world pipeline (it holds the texture objects from
        // then on). Client setup runs on a loader thread, so hop to the render thread, where GPU objects may be made.
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent e) ->
                e.enqueueWork(() -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    try {
                        AtlasTargets.registerPlaceholders();
                    } catch (Throwable t) {
                        // without the targets the far field cannot reach the pack: off, the game goes on
                        offForSession("its shader-pack textures could not be created (" + t + ")",
                                AtlasStatusLine.Off.ERROR, t);
                    }
                    AtlasGuard.run("shader pack textures", com.nirncraft.render.client.PackTextures::preload);
                })));
        modBus.addListener((RegisterRenderPipelinesEvent e) -> AtlasGuard.run(AtlasFallback.FEATURE,
                () -> AtlasFallback.register(e)));
        NeoForge.EVENT_BUS.addListener(AtlasClient::afterOpaque);
        AtlasNearPass.init(modBus);
        modBus.addListener((RegisterRenderPipelinesEvent e) -> AtlasGuard.run(AtlasNearPass.FEATURE, () -> AtlasSky.register(e)));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent e) ->
                AtlasGuard.run(AtlasNearPass.FEATURE, () -> AtlasSky.extract(e)));
        // after atlas's frame (or its absence): our near terrain into the frame, atlas's far frame behind it
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOW,
                (RenderLevelStageEvent.AfterOpaqueBlocks e) -> AtlasGuard.run(AtlasNearPass.FEATURE,
                        () -> AtlasNearPass.composite(e.getRenderPass())));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) ->
                AtlasGuard.run("atlas client tick", () -> {
                    AtlasTargets.flushClear();
                    AtlasFarField.restMs = com.nirncraft.render.quality.QualityClient.workerRestMs();
                    if (farField != null) AtlasClientSections.tick(Minecraft.getInstance());  // the loaded chunks' blocks
                }));
        AtlasClientSections.init();
        NeoForge.EVENT_BUS.addListener((ClientStoppingEvent e) -> AtlasGuard.run("atlas shutdown", () -> {
            if (guard != null) guard.stopped();  // a clean exit is not a crash
            stop();
        }));
        // adaptive quality v2: measures the frame, tunes atlas and the pack to this machine (render/quality)
        AtlasGuard.run("adaptive quality", com.nirncraft.render.quality.QualityClient::init);
        com.nirncraft.render.quality.QualityClient.setColumnThreadSink(n -> {
            AtlasFarField f = farField;
            if (f != null && f.source() instanceof AtlasExactSource s) s.setThreads(n);
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            serverWorldId = 0;
            farActors = null;
            farSites = null;
            AtlasSites.clearFacings();
        });
        AtlasNet.onHello = m -> AtlasGuard.run("atlas server hello", () -> onHello(m));
        AtlasNet.onTile = m -> AtlasGuard.run("atlas server tiles", () -> {
            if (farField != null && farField.source() instanceof AtlasServerSource s) s.accept(m);
        });
        AtlasNet.onFarActors = m -> {
            farActors = m;
            farActorsAt = System.nanoTime();
        };
        AtlasNet.onFarSites = m -> farSites = m;
        AtlasNet.onFarFacings = AtlasSites::acceptFacings;
        AtlasNet.onFarEdits = AtlasEditsClient::accept;
        AtlasNet.onFarSections = AtlasEditsClient::acceptSections;
    }

    /** The server streams exact tiles: switch a multiplayer client from the stand-in (restarts next frame). */
    private static void onHello(AtlasNet.Hello m) {
        if (m.codec() != AtlasTileCodec.VERSION) return;  // another build: keep the stand-in
        serverWorldId = m.worldId();
        if (ctx != null && !Minecraft.getInstance().hasSingleplayerServer()
                && !(farField != null && farField.source() instanceof AtlasServerSource)) stop();
    }

    public static void setWeatherFeed(Supplier<WeatherFeed> feed) {
        weather = feed;
    }

    /** The far field has finished streaming around the camera (true when it is off: nothing to wait for). */
    public static boolean farFieldIdle() {
        AtlasFarField f = farField;
        return f == null || f.idle();
    }

    /** What keeps the far field streaming ("" when idle or absent): the harness prints it when a settle times out. */
    public static String farFieldWhy() {
        AtlasFarField f = farField;
        return f == null ? "" : f.streamingWhy();
    }

    /** The far field's loading timeline from now on (harness: far-timeline start); false when there is no far field. */
    public static boolean farTimelineMark() {
        AtlasFarField f = farField;
        if (f != null) f.timelineMark();
        return f != null;
    }

    /** The loading timeline since the last mark, a JSON array ("[]" without a far field). */
    public static String farTimelineJson() {
        AtlasFarField f = farField;
        return f == null ? "[]" : f.timelineJson();
    }

    /** Atlas's targets when it drew this frame (else null): the near pass composites them. */
    static AtlasTargets farTargetsThisFrame() {
        return farThisFrame ? targets : null;
    }

    /** Atlas lit the near pass's pixels this frame (ATLAS_FRAME_NEAR_LIGHT). */
    static boolean nearLitThisFrame() {
        return nearLitThisFrame;
    }

    /** atlas's targets show a frame a few frames behind the camera (the CPU backend renders on a worker). */
    static boolean farLags() {
        return ctx != null && !failed && cpuBackend;
    }

    /** The loaded core lights the near pass's pixels (ABI 0.15: ATLAS_FRAME_NEAR_LIGHT, AtlasGlIO near info / light). */
    static boolean nearLightSupported() {
        return ctx != null && !failed && ctx.hasNearLight();
    }

    public static boolean active() {
        return ctx != null && !failed;
    }

    /** No far field at all while set (the autotest verb atlas-suspend: pinned ground-truth captures, client/pinned). */
    public static volatile boolean suspended;

    private static void afterOpaque(RenderLevelStageEvent.AfterOpaqueBlocks event) {
        // the near pass owns the frame: atlas already drew after our G-buffer (AtlasNearPass, at AfterSky)
        if (AtlasNearPass.owns()) return;
        renderFar(event.getLevelRenderState(), event.getRenderPass());
    }

    /**
     * Atlas's frame. pass: the main pass atlas is drawn in (vanilla's AfterOpaqueBlocks: the fallback compositor draws
     * there), or null when the near pass calls it right after its G-buffer (it composites atlas itself).
     */
    static void renderFar(LevelRenderState state, com.mojang.renderpearl.api.commands.RenderPass pass) {
        farThisFrame = false;
        nearLitThisFrame = false;
        if (failed) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            if (suspended) {  // nothing far this frame (a pack composites the targets: they are cleared)
                if (targets != null) targets.clear();
                com.nirncraft.render.quality.QualityClient.atlasFrame(false, 0);
                return;
            }
            long now = System.nanoTime();
            if (ctx == null && retryAt != 0 && now < retryAt) return;  // cooling down after a lost device
            boolean pack = irisPackInUse();
            if (lastPack == null || lastPack != pack) {
                lastPack = pack;
                quality.setFallbackMode(!pack);
                if (targets != null) {  // the sun map and the clouds follow the mode: new targets next
                    targets.close();
                    targets = null;
                }
            }
            if (!pack && !fallbackUsable()) {
                // nobody would see the far field: do not draw it (it comes back when a pack is switched on)
                status(AtlasStatusLine.off("no shader pack is active and the fallback compositor "
                        + (fallbackBlocker != null ? "cannot run: " + fallbackBlocker : "is off")), null);
                com.nirncraft.render.quality.QualityClient.atlasFrame(false, 0);
                return;
            }
            // a new world (or server): new tiles, maybe another column source
            if (ctx != null && startedFor != mc.level && mc.level.dimension() == Level.OVERWORLD) stop();
            if (ctx == null && !start(mc)) return;
            if (farField != null && farField.workerError() != null) throw farField.workerError();
            quality.begin();
            if (!quality.enabled()) {
                // adaptive quality's last fallback: no far field (the targets shrink to 1 x 1: the pack's own sky
                // and cloud deck take over); the context and its tiles stay for when it comes back
                if (targets != null) {
                    targets.close();
                    targets = null;
                }
                com.nirncraft.render.quality.QualityClient.atlasFrame(false, 0);
                return;
            }
            // atlas's own resolution (dynamic: AtlasQuality); the pack upsamples it to the window
            int w = quality.width(mc.getWindow().getWidth()), h = quality.height(mc.getWindow().getHeight());
            int sunMap = quality.sunMapSize();
            if (targets == null || targets.width() != w || targets.height() != h || targets.sunSize() != sunMap) {
                if (targets != null) targets.close();
                targets = null;
                ctx.setSunMap(sunMap, AtlasQuality.SUN_HALF_EXTENT);
                targets = AtlasTargets.create(ctx, w, h, sunMap);
                // device, driver and interop mode (zero-copy, or host-copy and why) once per context: the first
                // thing to read in a bug report from an AMD / Deck / Linux machine
                if (!infoLogged) {
                    infoLogged = true;
                    com.nirncraft.NirnCraft.LOGGER.info("atlas: {}", ctx.info());
                }
            }
            if (mc.level.dimension() != Level.OVERWORLD) {
                targets.clear();  // the far field is the overworld's; elsewhere the near field stands alone
                com.nirncraft.render.quality.QualityClient.atlasFrame(false, 0);
                return;
            }
            CameraRenderState cam = state.cameraRenderState;
            Vec3 p = cam.pos;
            farField.follow(p.x, p.y, p.z);
            farField.near(mc.options.getEffectiveRenderDistance() * 16.0 - 16.0);
            if (mc.player != null)  // horizontal half-FOV with a margin: what the frame can show
                farField.look(mc.player.getYRot(), Math.min(85, mc.options.fov().get() * 0.75 + 20));
            if (!farField.updated()) {
                targets.clear();  // the worker has not staged the first window yet: nothing far this frame
                com.nirncraft.render.quality.QualityClient.atlasFrame(false, 0);
                return;
            }
            feedWeather();
            AtlasAir.update(mc, lib, ctx);  // the air at the eye (the sky, the sun through it, the haze)
            feedActors(mc.level);
            int budget = quality.lightBudget();
            if (budget != lightBudget) {
                lightBudget = budget;
                ctx.setLightBudget(budget, 6000f);
            }

            frame.x = p.x;
            frame.y = p.y;
            frame.z = p.z;
            // camera-relative inverse view-projection: viewRotation has no translation
            Matrix4f invVP = AtlasLook.levelProjection(cam).mul(cam.viewRotationMatrix).invert();  // vanilla's, bob included
            invVP.get(frame.invViewProj);
            frame.width = w;
            frame.height = h;
            // the hand-off follows the loaded terrain's radius (what Iris hands the pack as `far`), not the
            // projection's far plane: 26.3's far plane lies well beyond the chunks, which left an empty band out to it
            float handEnd = mc.options.getEffectiveRenderDistance() * 16f - 16f;
            // our near pass draws every loaded chunk: atlas takes over only past them (with vanilla's terrain the hand-off
            // band met its chunks' fade; here it let atlas's own predicted trees float beside the real ones in the last
            // ring of chunks, wherever the near pass showed sky behind a crown)
            frame.nearFieldEnd = AtlasNearPass.owns() ? Math.max(32f, handEnd + 16f) : Math.max(32f, handEnd - handoffBand);
            frame.maxDistance = quality.maxDistance();
            frame.planetRadius = planetRadius;
            float[] sun = sunDirection(state.skyRenderState.sunAngle);
            frame.sunX = sun[0];
            frame.sunY = sun[1];
            frame.sunZ = sun[2];
            frame.timeSec = (state.gameTime + state.worldPartialTicks) / 20f;
            // visual tests: the clouds (and their shadows) still, so the views of a pair taken seconds apart see one
            // sky (the day time stands still there: the clock of the scene's time of day)
            if (com.nirncraft.client.autotest.ClientAutotest.ENABLED && mc.level != null)
                frame.timeSec = Math.floorMod(mc.level.getOverworldClockTime(), 24000L) / 20f;
            if (sun[1] < 0f && mc.level != null) {  // night: the pack's moon, not a fixed one (both fields alike)
                float[] key = com.nirncraft.atmosphere.client.LunarKey.compute(mc.level, sun, sunPathRotationDeg,
                        state.worldPartialTicks);
                try {
                    ctx.setMoon(new float[] {key[0], key[1], key[2]}, new float[] {key[3], key[4], key[5]});
                    AtlasAir.moon(new float[] {key[0], key[1], key[2]}, new float[] {key[3], key[4], key[5]});
                } catch (RuntimeException e) {
                    com.nirncraft.NirnCraft.LOGGER.debug("atlas: moon not set: {}", e.toString());
                }
            }
            frame.lodBias = quality.lodBias();  // > 1: coarser levels sooner with distance (far detail goes first)
            if (AtlasNearPass.owns()) {
                // the near pass's temporal resolve: atlas samples the same sub-pixel point as our G-buffer (in its pixels)
                float sc = (float) farScaleX();
                jittering = ctx.setJitter(AtlasNearPass.jitterX() * sc, AtlasNearPass.jitterY() * sc);
            } else {
                jitter(ctx, pack && packTaa(mc));
            }
            // the pack draws the sky; atlas adds far terrain, clouds and far actors over it (no pack: no clouds,
            // NirnCraft's own cloud deck draws them)
            frame.flags = quality.flags(AtlasNative.FRAME_SHADOWS | AtlasNative.FRAME_ATMOS | AtlasNative.FRAME_CLOUDS
                    | AtlasNative.FRAME_ACTORS | AtlasNative.FRAME_LOD_DITHER);
            // weather off (the world matched near to far without it): no cloud pass at all, high wisps and cloud
            // shadows included (they do not follow the weather field's cover)
            if (com.nirncraft.atmosphere.client.WeatherFieldTexture.isOff())
                frame.flags &= ~(AtlasNative.FRAME_CLOUDS | AtlasNative.FRAME_CLOUDS_QUARTER);
            if (AtlasTruthView.noFog) frame.flags &= ~AtlasNative.FRAME_ATMOS;  // ground-truth views: no aerial haze
            // no shader pack: the near field is vanilla's, so the far field lights as vanilla does (face shade and the
            // lightmap's sky brightness), without the sun shadows and haze it drew before: a stark line where vanilla's
            // chunks met it (the owner, flying the build); with the pack both sides take the pack's light
            if (!pack) {
                frame.flags |= AtlasNative.FRAME_VANILLA_LIGHT;
                frame.flags &= ~(AtlasNative.FRAME_SHADOWS | AtlasNative.FRAME_ATMOS);
            }
            // chunks still loading show the far field instead of a hole: in the open, and underground once atlas holds
            // the real blocks around the eye (AtlasClientSections: the trace starts in the cave, not in the tiles'
            // stone, which would cover a cave mouth)
            if (mc.player != null && mc.level != null
                    && (mc.level.canSeeSky(net.minecraft.core.BlockPos.containing(mc.player.getEyePosition()))
                    || AtlasClientSections.coversEye(p.x, p.z)))
                frame.flags |= AtlasNative.FRAME_FILL_NEAR;
            if (groups) frame.flags |= AtlasNative.FRAME_GROUPS;
            if (quality.cpuLightLow()) frame.flags |= AtlasNative.FRAME_LIGHT_LOW;  // the CPU's first step down

            // the near field's depth: our near pass's G-buffer when terrain is ours (reverse-Z, vanilla's projection),
            // else the bound target's (ATLAS_GL_BOUND_DEPTH: vanilla's, or the pack's gbuffer)
            boolean nearOwned = AtlasNearPass.owns();
            io.nearDepthTex = nearOwned ? AtlasNearPass.depthGlId() : 0xFFFFFFFF;
            // our terrain, a core that lights it: one light near and far (the far field's realistic light: sun and
            // traced shadow, traced sky, haze), the near pass's resolve takes it (no vanilla light any more)
            boolean nearLit = nearOwned && ctx.hasNearLight();
            if (nearLit) {
                frame.flags |= AtlasNative.FRAME_NEAR_LIGHT | AtlasNative.FRAME_SHADOWS | AtlasNative.FRAME_ATMOS;
                // atlas's clouds, in its light and haze (NirnCraft's deck steps aside: OverworldClouds), unless weather is off
                if (!com.nirncraft.atmosphere.client.WeatherFieldTexture.isOff()) frame.flags |= AtlasNative.FRAME_CLOUDS;
                frame.flags &= ~AtlasNative.FRAME_VANILLA_LIGHT;
                if (AtlasTruthView.noFog) frame.flags &= ~AtlasNative.FRAME_ATMOS;
            }
            io.nearInfoTex = nearLit ? AtlasNearPass.infoGlId() : 0;
            io.dstNearLight = nearLit ? targets.nearLightId() : 0;
            // the projection the near field's depth was drawn with: our near pass's carries this frame's TAA jitter
            // (AtlasNearPass.drawGbuffer), and the linearised distance must be taken along the ray that depth belongs
            // to; the unjittered inverse put each near point up to a pixel's footprint off along its ray, behind a wall
            // seen at an angle (its shadow ray started inside it: sunlit faces black, flickering with the jitter)
            Matrix4f nearProj = AtlasLook.levelProjection(cam);
            if (nearOwned) {
                int gw = mc.getWindow().getWidth(), gh = mc.getWindow().getHeight();
                nearProj = new Matrix4f().translation(-2f * AtlasNearPass.jitterX() / gw, -2f * AtlasNearPass.jitterY() / gh, 0f)
                        .mul(nearProj);
            }
            nearProj.invert().get(io.nearInvProjection);
            io.depthZeroToOne = AtlasTargets.depthZeroToOne();
            io.reversedZ = nearOwned || reversedZ(pack);  // the depth convention the near field's depth was written with
            io.dstColor = targets.colorId();
            io.dstNormal = 0;
            io.dstDepth = targets.depthId();
            io.dstSunMap = targets.sunId();
            if (firstFrame && guard != null) guard.firstFrames();  // a driver crash from here on is the guard's case
            ctx.glRender(frame, io);
            farThisFrame = true;
            nearLitThisFrame = nearLit;
            if (pass != null && !pack && !nearOwned && !AtlasFallback.fogHidesFarField(cam.fogData)) {
                AtlasTargets t = targets;
                AtlasGuard.run(AtlasFallback.FEATURE, () -> AtlasFallback.draw(pass, t));
            }
            if (guard != null) guard.frame(now);
            if (firstFrame) {
                firstFrame = false;
                status(AtlasStatusLine.on(ctx.info(), !pack, com.nirncraft.render.quality.QualityClient.qualityName()),
                        null);
            }
            // atlas's own GPU time (its timestamps, a couple of frames late) for the quality controller's cost model; on
            // the CPU backend atlas costs the GPU nothing: its worker's time steers its own scale (AtlasQuality.observeCpu)
            double atlasMs = ctx.stats().frameMs();
            com.nirncraft.render.quality.QualityClient.atlasFrame(true, cpuBackend ? 0 : atlasMs);
            if (cpuBackend) {
                quality.observeCpu(atlasMs, now);
                if (now >= cpuLogAt) {  // the worker's times (cpuGl: render, convert, upload, latency) every 30 s
                    cpuLogAt = now + 30_000_000_000L;
                    com.nirncraft.NirnCraft.LOGGER.info("atlas (CPU, scale {}): {}", quality.scale(), ctx.info());
                }
            }
        } catch (Throwable t) {
            frameFailed(t);
        }
    }

    // ------------------------------------------------------------------ start

    private static boolean start(Minecraft mc) {
        try {
            if (!decided) {
                decided = true;
                if ("false".equalsIgnoreCase(System.getProperty("nirncraft.atlas"))) {
                    offForSession("-Dnirncraft.atlas=false", AtlasStatusLine.Off.SETTING, null);
                    return false;
                }
                String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
                if (backend != null && !backend.toLowerCase(Locale.ROOT).contains("gl")) {
                    offForSession("the game runs on the " + backend + " renderer backend; atlas shares its images with "
                            + "OpenGL only (Options > Video > Graphics API: OpenGL)", AtlasStatusLine.Off.SETTING, null);
                    return false;
                }
                AtlasLibrary.resource();  // UnsupportedPlatformException: macOS, ARM ...
                guard = new AtlasSafeMode(mc.gameDirectory.toPath().resolve("config"));
                AtlasSafeMode.Decision d = guard.decide(guardKey(), System.getProperty("nirncraft.atlas.safeMode"));
                if (!d.allowed()) {
                    offForSession(d.reason(), d.reason() != null && d.reason().startsWith("nirncraft.atlas.safeMode")
                            ? AtlasStatusLine.Off.SETTING : AtlasStatusLine.Off.SAFE_MODE, null);
                    return false;
                }
                if (d.reason() != null) com.nirncraft.NirnCraft.LOGGER.info("atlas: {}", d.reason());
            }
            if (!accessAdviceLogged) {
                accessAdviceLogged = true;
                String advice = AtlasNative.nativeAccessAdvice();
                if (advice != null) com.nirncraft.NirnCraft.LOGGER.info("atlas: {}", advice);
            }
            if (guard != null) guard.starting();
            if (lib == null) lib = loadLibrary(mc.gameDirectory.toPath());
            // the GPU when there is one; else (no Vulkan device, a driver that refuses) atlas on the CPU, slower and at
            // a lower resolution but the same picture: the near pass's light and the far field on every machine
            // (-Dnirncraft.atlas.backend=cpu forces it: what a machine without a GPU sees). On the CPU the frame is
            // rendered on atlas's worker from the near field's depth and info read back from GL, a few frames behind
            // the camera (atlas_core's CPU GL path; atlas_info's cpuGl block has its times)
            cpuBackend = "cpu".equalsIgnoreCase(System.getProperty("nirncraft.atlas.backend"));
            if (cpuBackend) {
                ctx = lib.create(AtlasNative.BACKEND_CPU);
            } else {
                try {
                    ctx = lib.create(AtlasNative.BACKEND_VULKAN);
                } catch (AtlasNative.AtlasException e) {
                    com.nirncraft.NirnCraft.LOGGER.warn("atlas: no GPU backend ({}): the far field runs on the CPU", e.getMessage());
                    ctx = lib.create(AtlasNative.BACKEND_CPU);
                    cpuBackend = true;
                }
            }
            firstFrame = true;
            armTestFault(ctx);
            // memory: what this machine can spare sizes the targets, the sun map, the clouds and the clipmap (on the
            // CPU also what it can render: a third of the window to start, quarter-resolution clouds, a 256 sun map)
            AtlasMemory.Plan plan = shrunk != null ? shrunk : memoryPlan(mc, ctx);
            if (cpuBackend) plan = AtlasMemory.cpu(plan);
            quality.setCpu(cpuBackend);
            if (plan.off()) {
                stop();
                offForSession(plan.reason(), AtlasStatusLine.Off.MEMORY, null);
                return false;
            }
            quality.setMemory(plan);
            com.nirncraft.render.quality.QualityClient.setAtlasLimits(plan.scaleKnobCeiling(), plan.shadowKnobCeiling(),
                    plan.quarterClouds(), plan.initialTier());
            int lods = plan.lods();
            if (lods < AtlasFarField.LODS && !ctx.setClipmap(lods)) lods = AtlasFarField.LODS;  // a 0.9 library
            com.nirncraft.NirnCraft.LOGGER.info("{}", plan.describe());
            ctx.setTransitions(FADE_SECONDS);  // refined tiles, new buildings and finer LODs dissolve in, never pop
            // single player: the server's own terrain height (exact); multiplayer: exact tiles from the server when
            // it streams them; otherwise (or until they arrive) the seed-free raster stand-in
            AtlasFarField.ColumnSource source = null;
            if (mc.hasSingleplayerServer())
                source = AtlasExactSource.forServer(mc.getSingleplayerServer(), AtlasFarField::previewRaster);
            else if (serverWorldId != 0)
                source = new AtlasServerSource(serverWorldId, mc.gameDirectory.toPath(), AtlasFarField::previewRaster);
            // exact heights on the GPU where this machine has a device that keeps them bit-exact and it is faster
            // (verified against the CPU path tile by tile; -Dnirncraft.atlas.gpuHeight=false keeps the CPU)
            if (source instanceof AtlasExactSource e && !"false".equals(System.getProperty("nirncraft.atlas.gpuHeight"))) {
                String why = e.enableGpu(lib);
                if (why != null) com.nirncraft.NirnCraft.LOGGER.info("atlas exact heights on the CPU: {}", why);
            }
            if (source == null) source = AtlasFarField.defaultSource();
            if (source.usesBlockPalette()) ctx.setPalette(AtlasPalette.blocks());  // else atlas's preview palette
            int[] noise = AtlasPalette.cloudNoiseVolume();
            if (noise != null) ctx.setCloudNoise(noise);
            java.util.function.IntBinaryOperator ground = source instanceof AtlasExactSource e ? e::groundAt : null;
            AtlasMaterials mats = new AtlasMaterials();  // one id per block across buildings, landmarks and surfaces
            groups = "true".equalsIgnoreCase(System.getProperty("nirncraft.atlas.groups")) && ctx.hasShapes();
            if (groups) mats.enableShapes();  // every material's real shape and face tiles; real blocks per state
            if (groups) com.nirncraft.NirnCraft.LOGGER.info("atlas: content-shaped groups on (nirncraft.atlas.groups)");
            if (source instanceof AtlasRefiningSource r) r.materials(mats);
            // single player: the world's own trees over the finest LODs (AtlasTrees), else the dressing's canopy
            // (abi 0.13: an older core ignores ATLAS_COLUMN_NO_CANOPY and would grow its canopy under them)
            AtlasTrees trees = source instanceof AtlasExactSource e && lib.abi() >= 0x000D00
                    ? AtlasTrees.forServer(mc.getSingleplayerServer(), e) : null;
            AtlasTrees.activeLod = trees != null ? AtlasTrees.APPROX_LOD : -1;  // before the first tile goes up
            farField = new AtlasFarField(ctx, source, () -> AtlasSettlements.create(ground, mats, trees), lods);
            AtlasClientSections.reset();  // a new context holds no real blocks: the loaded chunks go up again
            lightBudget = -1;
            startedFor = mc.level;
            retryAt = 0;
            com.nirncraft.NirnCraft.LOGGER.info("Infinite ATLAS far field on ({} columns): {}", source instanceof AtlasExactSource
                    ? "exact" : source instanceof AtlasServerSource ? "server" : "raster", lib.buildInfo());
            return true;
        } catch (Throwable t) {
            if (guard != null) guard.stopped();  // a handled failure, not a crash
            AtlasStatusLine.Off kind = AtlasStatusLine.classify(t);
            if (kind == AtlasStatusLine.Off.DEVICE_LOST) {
                lost(t);
                return false;
            }
            String reason = kind == AtlasStatusLine.Off.PLATFORM || kind == AtlasStatusLine.Off.NATIVE_ACCESS
                    ? message(t) : "atlas unavailable (" + t + ")";
            stop();
            offForSession(reason, kind, kind == AtlasStatusLine.Off.PLATFORM ? null : t);
            return false;
        }
    }

    /** Tries every extracted copy until one loads (a noexec or read-only first choice falls through to the next). */
    private static AtlasNative loadLibrary(Path gameDir) throws java.io.IOException {
        List<Path> copies = AtlasLibrary.candidates(gameDir);
        StringBuilder why = new StringBuilder();
        for (Path p : copies) {
            try {
                return AtlasNative.load(p);
            } catch (AtlasNative.NativeAccessDenied e) {
                throw e;  // the same for every copy
            } catch (UnsatisfiedLinkError | RuntimeException e) {
                if (e instanceof IllegalCallerException ice) throw AtlasNative.denied(ice);
                why.append(why.isEmpty() ? "" : "; ").append(p).append(": ").append(e.getMessage());
            }
        }
        throw new UnsatisfiedLinkError("atlas_core would not load (" + why + ")");
    }

    private static AtlasMemory.Plan memoryPlan(Minecraft mc, AtlasNative.Context c) {
        try {
            AtlasNative.MemoryInfo mi = c.memoryInfo();
            long free = -1, total = -1;
            if (java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean os) {
                free = os.getFreeMemorySize();
                total = os.getTotalMemorySize();
            }
            if (mi == null && free <= 0) return AtlasMemory.unlimited("no memory information (atlas_core 0.9)");
            return AtlasMemory.plan(mi, mc.getWindow().getWidth(), mc.getWindow().getHeight(), free, total);
        } catch (RuntimeException e) {
            return AtlasMemory.unlimited("memory query failed: " + e);
        }
    }

    /** -Dnirncraft.atlas.fault=device_lost|timeout|oom[@frames]: the native test hook, on every new context. */
    private static void armTestFault(AtlasNative.Context c) {
        String v = System.getProperty("nirncraft.atlas.fault");
        if (v == null || v.isBlank()) return;
        String[] parts = v.trim().split("@", 2);
        int kind = switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "device_lost", "lost" -> AtlasNative.FAULT_DEVICE_LOST;
            case "timeout", "hang" -> AtlasNative.FAULT_TIMEOUT;
            case "oom", "out_of_memory" -> AtlasNative.FAULT_OUT_OF_MEMORY;
            default -> AtlasNative.FAULT_NONE;
        };
        int frames = 300;
        try {
            if (parts.length > 1) frames = Math.max(0, Integer.parseInt(parts[1].trim()));
        } catch (NumberFormatException ignored) {
            // keep the default
        }
        if (kind != AtlasNative.FAULT_NONE && c.debugFault(kind, frames))
            com.nirncraft.NirnCraft.LOGGER.warn("atlas: test fault {} armed after {} frames (nirncraft.atlas.fault)", parts[0], frames);
    }

    /** Mod version, GPU and driver: the crash guard's record is about this combination. */
    private static String guardKey() {
        String version = AtlasClient.class.getPackage().getImplementationVersion();
        try {
            var info = RenderSystem.getDevice().getDeviceInfo();
            return (version != null ? version : "dev") + " | " + info.name() + " | " + info.driverInfo();
        } catch (RuntimeException e) {
            return version != null ? version : "dev";
        }
    }

    // ------------------------------------------------------------------ failures

    /** A frame threw: a lost device retries later, exhausted memory shrinks, anything else ends the far field. */
    private static void frameFailed(Throwable t) {
        if (guard != null) guard.stopped();
        AtlasStatusLine.Off kind = AtlasStatusLine.classify(t);
        if (kind == AtlasStatusLine.Off.DEVICE_LOST) {
            lost(t);
            return;
        }
        if (kind == AtlasStatusLine.Off.MEMORY) {
            AtlasMemory.Plan now = shrunk != null ? shrunk : quality.memory();
            AtlasMemory.Plan smaller = AtlasMemory.shrink(now);
            stop();
            if (smaller == null) {
                offForSession("ran out of video memory at atlas's smallest settings (" + message(t) + ")",
                        AtlasStatusLine.Off.MEMORY, null);
            } else {
                shrunk = smaller;
                com.nirncraft.NirnCraft.LOGGER.warn("atlas ran out of memory ({}); starting again smaller: {}", message(t),
                        smaller.describe());
            }
            return;
        }
        stop();
        offForSession(kind == AtlasStatusLine.Off.NATIVE_ACCESS ? message(t) : "atlas frame failed (" + t + ")", kind, t);
    }

    /** The GPU was reset or hung: far field off now, a fresh context after a cool-down (at most MAX_LOSSES times). */
    private static void lost(Throwable t) {
        stop();
        losses++;
        com.nirncraft.render.quality.QualityClient.atlasFrame(false, 0);
        if (losses > MAX_LOSSES) {
            offForSession("the GPU was reset or hung " + losses + " times (" + message(t) + ")",
                    AtlasStatusLine.Off.DEVICE_LOST, null);
            return;
        }
        long wait = LOSS_COOLDOWN_NS * losses;
        retryAt = System.nanoTime() + wait;
        status(AtlasStatusLine.off("the GPU was reset or hung (" + message(t) + "); retrying in " + wait / 1_000_000_000L
                + " s"), AtlasStatusLine.Off.DEVICE_LOST);
    }

    /** Off for the rest of the session: one status line (WARN), a notice where the player can act on it. */
    private static void offForSession(String reason, AtlasStatusLine.Off kind, Throwable t) {
        failed = true;
        com.nirncraft.render.quality.QualityClient.atlasFrame(false, 0);  // its knobs cost nothing from now on
        status(AtlasStatusLine.off(reason), kind);
        if (t != null && kind == AtlasStatusLine.Off.ERROR)
            com.nirncraft.NirnCraft.LOGGER.debug("atlas failure detail", t);
    }

    /** Logs a status line when it differs from the last one; off lines are WARN (on is INFO). */
    private static void status(String line, AtlasStatusLine.Off kind) {
        if (line.equals(lastStatus)) return;
        lastStatus = line;
        if (line.contains("far field on")) com.nirncraft.NirnCraft.LOGGER.info("{}", line);
        else com.nirncraft.NirnCraft.LOGGER.warn("{}", line);
        if (kind != null && !noticeShown && AtlasStatusLine.notice(kind)
                && !"false".equalsIgnoreCase(System.getProperty("nirncraft.atlas.notice"))) {
            noticeShown = true;
            AtlasGuard.run("atlas notice", () -> com.nirncraft.client.hud.HudNotifications.add(
                    new com.nirncraft.network.CorePackets.Notify(com.nirncraft.network.NotifyKind.MESSAGE,
                            net.minecraft.network.chat.Component.literal(AtlasStatusLine.noticeText(kind)),
                            net.minecraft.network.chat.Component.empty(), 0, 0f)));
        }
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.toString() : m;
    }

    /** The compositor can run (checked once; a failure later turns it off through AtlasGuard). */
    private static boolean fallbackUsable() {
        if ("false".equalsIgnoreCase(System.getProperty("nirncraft.atlas.fallback"))) {
            fallbackBlocker = "nirncraft.atlas.fallback=false";
            return false;
        }
        if (AtlasGuard.off(AtlasFallback.FEATURE)) {
            fallbackBlocker = "it failed earlier this session (see the log)";
            return false;
        }
        if (!fallbackChecked) {
            fallbackChecked = true;
            try {
                fallbackBlocker = AtlasFallback.blocker();
            } catch (Throwable e) {  // incl. a pipeline class that failed to initialise
                fallbackBlocker = e.toString();
            }
        }
        return fallbackBlocker == null;
    }

    // ------------------------------------------------------------------ per frame helpers

    /**
     * The server's far actors, moved on by their velocity since the packet (twice a second; at most a second). An
     * impostor is never drawn for an entity the client has: by its id, or (an id the client does not know, e.g. a
     * camera away from the player the server measures from) any client entity of about its size standing where it
     * stands. One the client stops having shows at once, and atlas itself draws impostors only beyond the near field,
     * dissolving them in across the first 16 blocks past it (traceActors), so nothing pops or doubles at the hand-off.
     * Dragons beat their wings while climbing or cruising, glide while descending, and fold them on the ground.
     */
    private static void feedActors(net.minecraft.client.multiplayer.ClientLevel level) {
        AtlasNet.FarActors a = farActors;
        long now = System.nanoTime();
        if (a == null) {
            actorOpacity.clear();
            ctx.setActors(actorXyz, new float[0], new int[0], null, 0);
            return;
        }
        double dt = Math.min((now - farActorsAt) / 1e9, 1.0);
        int n = a.count();
        if (actorXyz.length < n * 3) actorXyz = new double[n * 3];
        if (actorFlags.length < n) actorFlags = new int[n];
        for (int i = 0; i < n * 3; i++) actorXyz[i] = a.xyz()[i] + a.vel()[i] * dt;
        it.unimi.dsi.fastutil.ints.IntOpenHashSet live = new it.unimi.dsi.fastutil.ints.IntOpenHashSet(n);
        for (int i = 0; i < n; i++) {
            int id = a.idFlags()[i * 2];
            live.add(id);
            float o = clientHas(level, a, i, id) ? 0f : 1f;
            actorOpacity.put(id, o);
            int gait;
            if ((a.idFlags()[i * 2 + 1] & AtlasNet.FarActors.ON_GROUND) != 0) gait = AtlasNative.GAIT_STILL;
            else gait = a.vel()[i * 3 + 1] < -4f ? AtlasNative.GAIT_GLIDE : AtlasNative.GAIT_FLAP;
            int seed = (id * 0x9E3779B1) >>> 16;
            actorFlags[i] = Math.round((1f - o) * 255f) | gait << 8 | seed << 16;
        }
        actorOpacity.keySet().retainAll(live);
        ctx.setActors(actorXyz, a.yawHeightRadius(), a.kindTint(), actorFlags, n);
    }

    /** True when the client has actor i's entity: the same id, or an entity of about its size where it stands. */
    private static boolean clientHas(net.minecraft.client.multiplayer.ClientLevel level, AtlasNet.FarActors a, int i, int id) {
        if (level.getEntity(id) != null) return true;
        double x = a.xyz()[i * 3], y = a.xyz()[i * 3 + 1], z = a.xyz()[i * 3 + 2];
        float h = a.yawHeightRadius()[i * 3 + 1], r = a.yawHeightRadius()[i * 3 + 2];
        double pad = Math.max(1.5, r * 2);
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(x - pad, y - 1, z - pad, x + pad, y + h + 1, z + pad);
        return !level.getEntities((net.minecraft.world.entity.Entity) null, box,
                e -> e.getBbHeight() >= h * 0.5f && e.getBbHeight() <= h * 2f).isEmpty();
    }

    /** The loaded atlas_core library, or null (AtlasAir's context-free calls). */
    static AtlasNative library() {
        return lib;
    }

    private static void feedWeather() {
        WeatherFeed feed = weather.get();
        if (feed != null && feed.fill(fields[0], fields[1], fields[2], fields[3])) {
            ctx.setWeather(fields[0], fields[1], fields[2], fields[3], feed.rain());
        }
    }

    /**
     * World direction to the sun, as Iris places it: Ry(-90) Rz(sunPathRotation) Rx(sunAngle) applied to +Y, so
     * atlas's light and the pack's (sunPosition) agree.
     */
    /**
     * The eye's adapted log2 exposure (AtlasMeter, from the finished frame: a bright town, the moon, twilight close the
     * eye; a dark forest opens it), or NaN when atlas's look is not metering. What the stars' visibility follows.
     */
    /** Whether the sky's layers should go into the reflection cube this frame (atlas lights the near pass). */
    public static boolean wantsSkyCube() {
        return AtlasNearPass.litThisFrame() || AtlasNearPass.owns();
    }

    public static float eyeExposureLog2() {
        return AtlasNearPass.litThisFrame() ? AtlasLook.lastLog2() : Float.NaN;
    }

    static float[] sunDirection(float sunAngle) {
        double a = sunAngle, r = Math.toRadians(sunPathRotationDeg);
        double x = -Math.sin(a), y = Math.cos(r) * Math.cos(a), z = -Math.sin(r) * Math.cos(a);
        return new float[] {(float) x, (float) y, (float) z};
    }

    private static java.lang.reflect.Method irisReverseZ, irisInUse;
    private static Object irisApi;
    private static boolean irisChecked;

    private static void iris() {
        if (irisChecked) return;
        irisChecked = true;
        try {
            Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            irisApi = c.getMethod("getInstance").invoke(null);
            irisReverseZ = c.getMethod("isReverseZDuringShaders");
            irisInUse = c.getMethod("isShaderPackInUse");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            irisApi = null;  // no Iris (or another API): vanilla rendering
        }
    }

    /** True while an Iris shader pack renders the world (false without Iris). */
    private static int jitterIndex;
    private static boolean jittering;
    private static long taaCheckedAt;
    private static boolean taaOn;

    /**
     * The far field's sub-pixel jitter for the pack's TAA (8 Halton (2, 3) offsets, one per frame), or none: without a
     * temporal resolve a jittered far field would visibly shake by a pixel.
     */
    private static void jitter(AtlasNative.Context ctx, boolean on) {
        try {
            if (on) {
                // the offset the pack's vertices took this frame (the index published last frame), in atlas pixels
                // (the pack's are screen pixels: at a reduced atlas scale the far field moved too far)
                int i = (jitterIndex & 7) + 1;
                float scale = (float) farScaleX();
                jittering = ctx.setJitter((halton(i, 2) - 0.5f) * scale, (halton(i, 3) - 0.5f) * scale);
                jitterIndex++;
                com.nirncraft.atmosphere.client.SmokeSourceTexture.jitterIndex(jitterIndex & 7);  // next frame's
            } else if (jittering) {
                ctx.setJitter(0f, 0f);
                jittering = false;
                com.nirncraft.atmosphere.client.SmokeSourceTexture.jitterIndex(-1);
            }
        } catch (RuntimeException e) {
            com.nirncraft.NirnCraft.LOGGER.debug("atlas: jitter not set: {}", e.toString());
        }
    }

    /** atlas's render width over the window's (its dynamic scale). */
    private static double farScaleX() {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getWidth();
        return targets == null || w <= 0 ? 1.0 : targets.width() / (double) w;
    }

    private static float halton(int i, int base) {
        float f = 1f, r = 0f;
        for (; i > 0; i /= base) {
            f /= base;
            r += f * (i % base);
        }
        return r;
    }

    /**
     * Whether Infinite ATLAS resolves frames temporally: its TAA option, on unless switched off in Iris' option file
     * for the pack (shaderpacks/PACK.txt). Another pack has no resolve for atlas's jitter. Re-read every 2 s.
     */
    static boolean packTaa(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - taaCheckedAt < 2000) return taaOn;
        taaCheckedAt = now;
        taaOn = false;
        java.nio.file.Path game = mc.gameDirectory.toPath();
        try (var in = java.nio.file.Files.newBufferedReader(game.resolve("config/iris.properties"))) {
            java.util.Properties iris = new java.util.Properties();
            iris.load(in);
            String name = iris.getProperty("shaderPack", "");
            if (!name.contains("InfiniteATLAS")) return false;
            java.nio.file.Path opts = game.resolve("shaderpacks").resolve(name + ".txt");
            java.util.Properties o = new java.util.Properties();
            if (java.nio.file.Files.isRegularFile(opts))
                try (var r = java.nio.file.Files.newBufferedReader(opts)) {
                    o.load(r);
                }
            taaOn = !"false".equalsIgnoreCase(o.getProperty("TAA", "true").trim());
        } catch (java.io.IOException | RuntimeException e) {
            taaOn = false;
        }
        return taaOn;
    }

    public static boolean irisPackInUse() {
        iris();
        try {
            return irisApi != null && irisInUse != null && Boolean.TRUE.equals(irisInUse.invoke(irisApi));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether the near field's depth is reversed: under a pack, what Iris 1.11 reports; without one, vanilla 26.3's
     * own convention, which is always reverse-Z (GameRenderer swaps near and far and clears depth to 0).
     */
    private static boolean reversedZ(boolean pack) {
        if (!pack) return true;
        try {
            return irisApi != null && irisReverseZ != null && Boolean.TRUE.equals(irisReverseZ.invoke(irisApi));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Tears atlas down (start() brings it back with fresh tiles). The library stays loaded. */
    private static void stop() {
        AtlasNative.Context c = ctx;
        // the context goes only once the far-field worker is out of it (it may still be mid-pass after close()'s
        // join: destroyed under it, its next atlas_update crashed the game); the GL side goes now, on this thread
        Runnable destroy = () -> {
            try {
                if (c != null) c.close();
            } catch (Throwable ignored) {
                // best effort
            }
        };
        try {
            if (farField != null) farField.stopWorker();
        } catch (Throwable ignored) {
            // best effort: the session continues without atlas
        }
        try {
            if (targets != null) targets.close();
        } catch (Throwable ignored) {
            // best effort
        }
        try {
            if (c != null) c.glDetach();
        } catch (Throwable ignored) {
            // best effort
        }
        try {
            if (farField != null) farField.close(destroy);  // now, or when the worker leaves
            else destroy.run();
        } catch (Throwable ignored) {
            // best effort (the context is left to the worker, or leaked)
        }
        farField = null;
        AtlasEditsClient.clear();  // the next world's changes come from its own server
        targets = null;
        ctx = null;
        infoLogged = false;
        startedFor = null;
        firstFrame = true;
    }

    static GpuFormat colorFormat() {
        return GpuFormat.RGBA16_FLOAT;
    }
}
