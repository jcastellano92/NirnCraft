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
    public static float handoffBand = 24f;

    /** Device losses tolerated per session (each followed by a cool-down and a fresh context). */
    static final int MAX_LOSSES = 2;
    static final long LOSS_COOLDOWN_NS = 60_000_000_000L;

    private static AtlasNative lib;
    private static AtlasNative.Context ctx;
    private static AtlasTargets targets;
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
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) ->
                AtlasGuard.run("atlas client tick", () -> {
                    AtlasTargets.flushClear();
                    AtlasFarField.restMs = com.nirncraft.render.quality.QualityClient.workerRestMs();
                }));
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

    public static boolean active() {
        return ctx != null && !failed;
    }

    private static void afterOpaque(RenderLevelStageEvent.AfterOpaqueBlocks event) {
        if (failed) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
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
            LevelRenderState state = event.getLevelRenderState();
            CameraRenderState cam = state.cameraRenderState;
            Vec3 p = cam.pos;
            farField.follow(p.x, p.y, p.z);
            if (!farField.updated()) {
                targets.clear();  // the worker has not staged the first window yet: nothing far this frame
                com.nirncraft.render.quality.QualityClient.atlasFrame(false, 0);
                return;
            }
            feedWeather();
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
            Matrix4f invVP = new Matrix4f(cam.projectionMatrix).mul(cam.viewRotationMatrix).invert();
            invVP.get(frame.invViewProj);
            frame.width = w;
            frame.height = h;
            // the hand-off follows the loaded terrain's radius (what Iris hands the pack as `far`), not the
            // projection's far plane: 26.3's far plane lies well beyond the chunks, which left an empty band out to it
            float handEnd = mc.options.getEffectiveRenderDistance() * 16f - 16f;
            frame.nearFieldEnd = Math.max(32f, handEnd - handoffBand);
            frame.maxDistance = quality.maxDistance();
            frame.planetRadius = planetRadius;
            float[] sun = sunDirection(state.skyRenderState.sunAngle);
            frame.sunX = sun[0];
            frame.sunY = sun[1];
            frame.sunZ = sun[2];
            frame.timeSec = (state.gameTime + state.worldPartialTicks) / 20f;
            frame.lodBias = quality.lodBias();  // > 1: coarser levels sooner with distance (far detail goes first)
            // the pack draws the sky; atlas adds far terrain, clouds and far actors over it (no pack: no clouds,
            // NirnCraft's own cloud deck draws them)
            frame.flags = quality.flags(AtlasNative.FRAME_SHADOWS | AtlasNative.FRAME_ATMOS | AtlasNative.FRAME_CLOUDS
                    | AtlasNative.FRAME_ACTORS | AtlasNative.FRAME_LOD_DITHER);

            io.nearDepthTex = 0xFFFFFFFF;  // ATLAS_GL_BOUND_DEPTH: the bound gbuffer target's depth
            new Matrix4f(cam.projectionMatrix).invert().get(io.nearInvProjection);
            io.depthZeroToOne = AtlasTargets.depthZeroToOne();
            io.reversedZ = reversedZ(pack);  // the depth convention the near field's depth was written with
            io.dstColor = targets.colorId();
            io.dstNormal = 0;
            io.dstDepth = targets.depthId();
            io.dstSunMap = targets.sunId();
            if (firstFrame && guard != null) guard.firstFrames();  // a driver crash from here on is the guard's case
            ctx.glRender(frame, io);
            if (!pack && !AtlasFallback.fogHidesFarField(cam.fogData)) {
                AtlasTargets t = targets;
                AtlasGuard.run(AtlasFallback.FEATURE, () -> AtlasFallback.draw(event.getRenderPass(), t));
            }
            if (guard != null) guard.frame(now);
            if (firstFrame) {
                firstFrame = false;
                status(AtlasStatusLine.on(ctx.info(), !pack, com.nirncraft.render.quality.QualityClient.qualityName()),
                        null);
            }
            // atlas's own GPU time (its timestamps, a couple of frames late) for the quality controller's cost model
            com.nirncraft.render.quality.QualityClient.atlasFrame(true, ctx.stats().frameMs());
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
            ctx = lib.create(AtlasNative.BACKEND_VULKAN);
            firstFrame = true;
            armTestFault(ctx);
            // memory: what this machine can spare sizes the targets, the sun map, the clouds and the clipmap
            AtlasMemory.Plan plan = shrunk != null ? shrunk : memoryPlan(mc, ctx);
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
            if (source instanceof AtlasRefiningSource r) r.materials(mats);
            farField = new AtlasFarField(ctx, source, () -> AtlasSettlements.create(ground, mats), lods);
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
    static boolean irisPackInUse() {
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
        try {
            if (farField != null) farField.close();
        } catch (Throwable ignored) {
            // best effort: the session continues without atlas
        }
        try {
            if (targets != null) targets.close();
        } catch (Throwable ignored) {
            // best effort
        }
        try {
            if (ctx != null) {
                ctx.glDetach();
                ctx.close();
            }
        } catch (Throwable ignored) {
            // best effort
        }
        farField = null;
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
