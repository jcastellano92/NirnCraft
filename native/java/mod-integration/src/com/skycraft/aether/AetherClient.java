package com.skycraft.aether;

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
 * Client side of aether: loads the native library, keeps the shared GL targets and the textures the Skycraft shader
 * pack samples (skycraft:aether/color, skycraft:aether/depth), streams far-field tiles on a worker thread, and
 * renders one aether frame per level frame after the opaque near field (so its depth can be read). Without a shader
 * pack the fallback compositor ({@link AetherFallback}) draws the far field behind the vanilla near field.
 *
 * <p><b>Never crash for visuals.</b> Everything degrades to "no far field" with one WARN line and the game renders
 * as before: no library for this platform (macOS, ARM), Java refusing native access, no usable Vulkan device, not
 * enough video memory, the Vulkan renderer backend, or any native error. A lost device (a Windows TDR, an amdgpu GPU
 * reset, a hang) turns the far field off and retries after a cool-down (60 s, then 120 s; a third loss ends it for the
 * session); running out of memory steps aether's memory plan down ({@link AetherMemory#shrink}) and starts again.
 * A crash guard ({@link AetherSafeMode}) starts the session with the far field off when the last one died while it
 * was starting. One status line says where things stand ({@link AetherStatusLine}).</p>
 *
 * <p>Properties: {@code -Dskycraft.aether=false} (far field off), {@code -Dskycraft.aether.safeMode=false|true},
 * {@code -Dskycraft.aether.notice=false} (no in-game notice), {@code -Dskycraft.aether.fallback=false} (no fallback
 * compositor without a pack), {@code -Dskycraft.aether.nativesDir=DIR}, {@code -Dskycraft.aether.gpuHeight=false},
 * and for testing the failure paths {@code -Dskycraft.aether.fault=device_lost|timeout|oom[@frames]} (or the
 * environment variable AETHER_FAULT, read by the native library itself).</p>
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

    /** Device losses tolerated per session (each followed by a cool-down and a fresh context). */
    static final int MAX_LOSSES = 2;
    static final long LOSS_COOLDOWN_NS = 60_000_000_000L;

    private static AetherNative lib;
    private static AetherNative.Context ctx;
    private static AetherTargets targets;
    private static AetherFarField farField;
    private static ClientLevel startedFor;  // the level the far field's tiles belong to
    private static final WeatherFeed WEATHER = new AetherWeatherFeed();
    private static final AetherQuality quality = new AetherQuality();
    private static Supplier<WeatherFeed> weather = () -> WEATHER;
    /** Off for the session (the reason is in the status line). */
    private static boolean failed;
    private static boolean infoLogged;  // aether_info logged for this context
    private static final AetherNative.Frame frame = new AetherNative.Frame();
    private static final AetherNative.GlFrameIO io = new AetherNative.GlFrameIO();
    private static final int[][] fields = {new int[128 * 128], new int[128 * 128], new int[128 * 128], new int[128 * 128]};
    /** Set by the server's Hello: it streams exact far tiles for this world (0 = it does not). */
    private static volatile long serverWorldId;
    private static volatile AetherNet.FarActors farActors;
    /** The server's latest list of natural landmarks around this player (AetherNatural), for the far field. */
    static volatile AetherNet.FarSites farSites;
    private static volatile long farActorsAt;
    private static double[] actorXyz = new double[0];
    private static int[] actorFlags = new int[0];
    /** Per far actor (entity id): its impostor's opacity, fading out while the client also has the real entity. */
    private static final it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap actorOpacity = new it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap();
    private static int lightBudget = -1;
    /** Seconds over which an impostor dissolves once the real entity is tracked (and region refinements dissolve). */
    static final float FADE_SECONDS = 0.6f;

    // robustness state
    private static AetherSafeMode guard;
    private static boolean decided;           // the crash guard was consulted this session
    private static long retryAt;              // nanoTime: try again after a lost device (0: no cool-down)
    private static int losses;
    private static AetherMemory.Plan shrunk;  // the plan after running out of memory (null: measure afresh)
    private static boolean firstFrame = true; // of the current context
    private static String lastStatus;
    private static boolean noticeShown, accessAdviceLogged;
    private static Boolean lastPack;          // shader pack in use last frame (null: not known yet)
    private static String fallbackBlocker;    // why the compositor cannot run (null: it can, or not checked)
    private static boolean fallbackChecked;

    private AetherClient() {}

    public static void init(IEventBus modBus) {
        // the pack's aether textures must exist before Iris builds a world pipeline (it holds the texture objects from
        // then on). Client setup runs on a loader thread, so hop to the render thread, where GPU objects may be made.
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent e) ->
                e.enqueueWork(() -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    try {
                        AetherTargets.registerPlaceholders();
                    } catch (Throwable t) {
                        // without the targets the far field cannot reach the pack: off, the game goes on
                        offForSession("its shader-pack textures could not be created (" + t + ")",
                                AetherStatusLine.Off.ERROR, t);
                    }
                    AetherGuard.run("shader pack textures", com.skycraft.render.client.PackTextures::preload);
                })));
        modBus.addListener((RegisterRenderPipelinesEvent e) -> AetherGuard.run(AetherFallback.FEATURE,
                () -> AetherFallback.register(e)));
        NeoForge.EVENT_BUS.addListener(AetherClient::afterOpaque);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) ->
                AetherGuard.run("aether client tick", () -> {
                    AetherTargets.flushClear();
                    AetherFarField.restMs = com.skycraft.render.quality.QualityClient.workerRestMs();
                }));
        NeoForge.EVENT_BUS.addListener((ClientStoppingEvent e) -> AetherGuard.run("aether shutdown", () -> {
            if (guard != null) guard.stopped();  // a clean exit is not a crash
            stop();
        }));
        // adaptive quality v2: measures the frame, tunes aether and the pack to this machine (render/quality)
        AetherGuard.run("adaptive quality", com.skycraft.render.quality.QualityClient::init);
        com.skycraft.render.quality.QualityClient.setColumnThreadSink(n -> {
            AetherFarField f = farField;
            if (f != null && f.source() instanceof AetherExactSource s) s.setThreads(n);
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            serverWorldId = 0;
            farActors = null;
            farSites = null;
        });
        AetherNet.onHello = m -> AetherGuard.run("aether server hello", () -> onHello(m));
        AetherNet.onTile = m -> AetherGuard.run("aether server tiles", () -> {
            if (farField != null && farField.source() instanceof AetherServerSource s) s.accept(m);
        });
        AetherNet.onFarActors = m -> {
            farActors = m;
            farActorsAt = System.nanoTime();
        };
        AetherNet.onFarSites = m -> farSites = m;
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
                status(AetherStatusLine.off("no shader pack is active and the fallback compositor "
                        + (fallbackBlocker != null ? "cannot run: " + fallbackBlocker : "is off")), null);
                com.skycraft.render.quality.QualityClient.aetherFrame(false, 0);
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
                com.skycraft.render.quality.QualityClient.aetherFrame(false, 0);
                return;
            }
            // aether's own resolution (dynamic: AetherQuality); the pack upsamples it to the window
            int w = quality.width(mc.getWindow().getWidth()), h = quality.height(mc.getWindow().getHeight());
            int sunMap = quality.sunMapSize();
            if (targets == null || targets.width() != w || targets.height() != h || targets.sunSize() != sunMap) {
                if (targets != null) targets.close();
                targets = null;
                ctx.setSunMap(sunMap, AetherQuality.SUN_HALF_EXTENT);
                targets = AetherTargets.create(ctx, w, h, sunMap);
                // device, driver and interop mode (zero-copy, or host-copy and why) once per context: the first
                // thing to read in a bug report from an AMD / Deck / Linux machine
                if (!infoLogged) {
                    infoLogged = true;
                    com.skycraft.Skycraft.LOGGER.info("aether: {}", ctx.info());
                }
            }
            if (mc.level.dimension() != Level.OVERWORLD) {
                targets.clear();  // the far field is the overworld's; elsewhere the near field stands alone
                com.skycraft.render.quality.QualityClient.aetherFrame(false, 0);
                return;
            }
            LevelRenderState state = event.getLevelRenderState();
            CameraRenderState cam = state.cameraRenderState;
            Vec3 p = cam.pos;
            farField.follow(p.x, p.y, p.z);
            if (!farField.updated()) {
                targets.clear();  // the worker has not staged the first window yet: nothing far this frame
                com.skycraft.render.quality.QualityClient.aetherFrame(false, 0);
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
            // the pack draws the sky; aether adds far terrain, clouds and far actors over it (no pack: no clouds,
            // Skycraft's own cloud deck draws them)
            frame.flags = quality.flags(AetherNative.FRAME_SHADOWS | AetherNative.FRAME_ATMOS | AetherNative.FRAME_CLOUDS
                    | AetherNative.FRAME_ACTORS | AetherNative.FRAME_LOD_DITHER);

            io.nearDepthTex = 0xFFFFFFFF;  // AETHER_GL_BOUND_DEPTH: the bound gbuffer target's depth
            new Matrix4f(cam.projectionMatrix).invert().get(io.nearInvProjection);
            io.depthZeroToOne = AetherTargets.depthZeroToOne();
            io.reversedZ = reversedZ(pack);  // the depth convention the near field's depth was written with
            io.dstColor = targets.colorId();
            io.dstNormal = 0;
            io.dstDepth = targets.depthId();
            io.dstSunMap = targets.sunId();
            if (firstFrame && guard != null) guard.firstFrames();  // a driver crash from here on is the guard's case
            ctx.glRender(frame, io);
            if (!pack && !AetherFallback.fogHidesFarField(cam.fogData)) {
                AetherTargets t = targets;
                AetherGuard.run(AetherFallback.FEATURE, () -> AetherFallback.draw(event.getRenderPass(), t));
            }
            if (guard != null) guard.frame(now);
            if (firstFrame) {
                firstFrame = false;
                status(AetherStatusLine.on(ctx.info(), !pack, com.skycraft.render.quality.QualityClient.qualityName()),
                        null);
            }
            // aether's own GPU time (its timestamps, a couple of frames late) for the quality controller's cost model
            com.skycraft.render.quality.QualityClient.aetherFrame(true, ctx.stats().frameMs());
        } catch (Throwable t) {
            frameFailed(t);
        }
    }

    // ------------------------------------------------------------------ start

    private static boolean start(Minecraft mc) {
        try {
            if (!decided) {
                decided = true;
                if ("false".equalsIgnoreCase(System.getProperty("skycraft.aether"))) {
                    offForSession("-Dskycraft.aether=false", AetherStatusLine.Off.SETTING, null);
                    return false;
                }
                String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
                if (backend != null && !backend.toLowerCase(Locale.ROOT).contains("gl")) {
                    offForSession("the game runs on the " + backend + " renderer backend; aether shares its images with "
                            + "OpenGL only (Options > Video > Graphics API: OpenGL)", AetherStatusLine.Off.SETTING, null);
                    return false;
                }
                AetherLibrary.resource();  // UnsupportedPlatformException: macOS, ARM ...
                guard = new AetherSafeMode(mc.gameDirectory.toPath().resolve("config"));
                AetherSafeMode.Decision d = guard.decide(guardKey(), System.getProperty("skycraft.aether.safeMode"));
                if (!d.allowed()) {
                    offForSession(d.reason(), d.reason() != null && d.reason().startsWith("skycraft.aether.safeMode")
                            ? AetherStatusLine.Off.SETTING : AetherStatusLine.Off.SAFE_MODE, null);
                    return false;
                }
                if (d.reason() != null) com.skycraft.Skycraft.LOGGER.info("aether: {}", d.reason());
            }
            if (!accessAdviceLogged) {
                accessAdviceLogged = true;
                String advice = AetherNative.nativeAccessAdvice();
                if (advice != null) com.skycraft.Skycraft.LOGGER.info("aether: {}", advice);
            }
            if (guard != null) guard.starting();
            if (lib == null) lib = loadLibrary(mc.gameDirectory.toPath());
            ctx = lib.create(AetherNative.BACKEND_VULKAN);
            firstFrame = true;
            armTestFault(ctx);
            // memory: what this machine can spare sizes the targets, the sun map, the clouds and the clipmap
            AetherMemory.Plan plan = shrunk != null ? shrunk : memoryPlan(mc, ctx);
            if (plan.off()) {
                stop();
                offForSession(plan.reason(), AetherStatusLine.Off.MEMORY, null);
                return false;
            }
            quality.setMemory(plan);
            com.skycraft.render.quality.QualityClient.setAetherLimits(plan.scaleKnobCeiling(), plan.shadowKnobCeiling(),
                    plan.quarterClouds(), plan.initialTier());
            int lods = plan.lods();
            if (lods < AetherFarField.LODS && !ctx.setClipmap(lods)) lods = AetherFarField.LODS;  // a 0.9 library
            com.skycraft.Skycraft.LOGGER.info("{}", plan.describe());
            ctx.setTransitions(FADE_SECONDS);  // refined tiles, new buildings and finer LODs dissolve in, never pop
            // single player: the server's own terrain height (exact); multiplayer: exact tiles from the server when
            // it streams them; otherwise (or until they arrive) the seed-free raster stand-in
            AetherFarField.ColumnSource source = null;
            if (mc.hasSingleplayerServer())
                source = AetherExactSource.forServer(mc.getSingleplayerServer(), AetherFarField::previewRaster);
            else if (serverWorldId != 0)
                source = new AetherServerSource(serverWorldId, mc.gameDirectory.toPath(), AetherFarField::previewRaster);
            // exact heights on the GPU where this machine has a device that keeps them bit-exact and it is faster
            // (verified against the CPU path tile by tile; -Dskycraft.aether.gpuHeight=false keeps the CPU)
            if (source instanceof AetherExactSource e && !"false".equals(System.getProperty("skycraft.aether.gpuHeight"))) {
                String why = e.enableGpu(lib);
                if (why != null) com.skycraft.Skycraft.LOGGER.info("aether exact heights on the CPU: {}", why);
            }
            if (source == null) source = AetherFarField.defaultSource();
            if (source.usesBlockPalette()) ctx.setPalette(AetherPalette.blocks());  // else aether's preview palette
            int[] noise = AetherPalette.cloudNoiseVolume();
            if (noise != null) ctx.setCloudNoise(noise);
            java.util.function.IntBinaryOperator ground = source instanceof AetherExactSource e ? e::groundAt : null;
            AetherMaterials mats = new AetherMaterials();  // one id per block across buildings, landmarks and surfaces
            if (source instanceof AetherRefiningSource r) r.materials(mats);
            farField = new AetherFarField(ctx, source, () -> AetherSettlements.create(ground, mats), lods);
            lightBudget = -1;
            startedFor = mc.level;
            retryAt = 0;
            com.skycraft.Skycraft.LOGGER.info("aether far field on ({} columns): {}", source instanceof AetherExactSource
                    ? "exact" : source instanceof AetherServerSource ? "server" : "raster", lib.buildInfo());
            return true;
        } catch (Throwable t) {
            if (guard != null) guard.stopped();  // a handled failure, not a crash
            AetherStatusLine.Off kind = AetherStatusLine.classify(t);
            if (kind == AetherStatusLine.Off.DEVICE_LOST) {
                lost(t);
                return false;
            }
            String reason = kind == AetherStatusLine.Off.PLATFORM || kind == AetherStatusLine.Off.NATIVE_ACCESS
                    ? message(t) : "aether unavailable (" + t + ")";
            stop();
            offForSession(reason, kind, kind == AetherStatusLine.Off.PLATFORM ? null : t);
            return false;
        }
    }

    /** Tries every extracted copy until one loads (a noexec or read-only first choice falls through to the next). */
    private static AetherNative loadLibrary(Path gameDir) throws java.io.IOException {
        List<Path> copies = AetherLibrary.candidates(gameDir);
        StringBuilder why = new StringBuilder();
        for (Path p : copies) {
            try {
                return AetherNative.load(p);
            } catch (AetherNative.NativeAccessDenied e) {
                throw e;  // the same for every copy
            } catch (UnsatisfiedLinkError | RuntimeException e) {
                if (e instanceof IllegalCallerException ice) throw AetherNative.denied(ice);
                why.append(why.isEmpty() ? "" : "; ").append(p).append(": ").append(e.getMessage());
            }
        }
        throw new UnsatisfiedLinkError("aether_core would not load (" + why + ")");
    }

    private static AetherMemory.Plan memoryPlan(Minecraft mc, AetherNative.Context c) {
        try {
            AetherNative.MemoryInfo mi = c.memoryInfo();
            long free = -1, total = -1;
            if (java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean os) {
                free = os.getFreeMemorySize();
                total = os.getTotalMemorySize();
            }
            if (mi == null && free <= 0) return AetherMemory.unlimited("no memory information (aether_core 0.9)");
            return AetherMemory.plan(mi, mc.getWindow().getWidth(), mc.getWindow().getHeight(), free, total);
        } catch (RuntimeException e) {
            return AetherMemory.unlimited("memory query failed: " + e);
        }
    }

    /** -Dskycraft.aether.fault=device_lost|timeout|oom[@frames]: the native test hook, on every new context. */
    private static void armTestFault(AetherNative.Context c) {
        String v = System.getProperty("skycraft.aether.fault");
        if (v == null || v.isBlank()) return;
        String[] parts = v.trim().split("@", 2);
        int kind = switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "device_lost", "lost" -> AetherNative.FAULT_DEVICE_LOST;
            case "timeout", "hang" -> AetherNative.FAULT_TIMEOUT;
            case "oom", "out_of_memory" -> AetherNative.FAULT_OUT_OF_MEMORY;
            default -> AetherNative.FAULT_NONE;
        };
        int frames = 300;
        try {
            if (parts.length > 1) frames = Math.max(0, Integer.parseInt(parts[1].trim()));
        } catch (NumberFormatException ignored) {
            // keep the default
        }
        if (kind != AetherNative.FAULT_NONE && c.debugFault(kind, frames))
            com.skycraft.Skycraft.LOGGER.warn("aether: test fault {} armed after {} frames (skycraft.aether.fault)", parts[0], frames);
    }

    /** Mod version, GPU and driver: the crash guard's record is about this combination. */
    private static String guardKey() {
        String version = AetherClient.class.getPackage().getImplementationVersion();
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
        AetherStatusLine.Off kind = AetherStatusLine.classify(t);
        if (kind == AetherStatusLine.Off.DEVICE_LOST) {
            lost(t);
            return;
        }
        if (kind == AetherStatusLine.Off.MEMORY) {
            AetherMemory.Plan now = shrunk != null ? shrunk : quality.memory();
            AetherMemory.Plan smaller = AetherMemory.shrink(now);
            stop();
            if (smaller == null) {
                offForSession("ran out of video memory at aether's smallest settings (" + message(t) + ")",
                        AetherStatusLine.Off.MEMORY, null);
            } else {
                shrunk = smaller;
                com.skycraft.Skycraft.LOGGER.warn("aether ran out of memory ({}); starting again smaller: {}", message(t),
                        smaller.describe());
            }
            return;
        }
        stop();
        offForSession(kind == AetherStatusLine.Off.NATIVE_ACCESS ? message(t) : "aether frame failed (" + t + ")", kind, t);
    }

    /** The GPU was reset or hung: far field off now, a fresh context after a cool-down (at most MAX_LOSSES times). */
    private static void lost(Throwable t) {
        stop();
        losses++;
        com.skycraft.render.quality.QualityClient.aetherFrame(false, 0);
        if (losses > MAX_LOSSES) {
            offForSession("the GPU was reset or hung " + losses + " times (" + message(t) + ")",
                    AetherStatusLine.Off.DEVICE_LOST, null);
            return;
        }
        long wait = LOSS_COOLDOWN_NS * losses;
        retryAt = System.nanoTime() + wait;
        status(AetherStatusLine.off("the GPU was reset or hung (" + message(t) + "); retrying in " + wait / 1_000_000_000L
                + " s"), AetherStatusLine.Off.DEVICE_LOST);
    }

    /** Off for the rest of the session: one status line (WARN), a notice where the player can act on it. */
    private static void offForSession(String reason, AetherStatusLine.Off kind, Throwable t) {
        failed = true;
        com.skycraft.render.quality.QualityClient.aetherFrame(false, 0);  // its knobs cost nothing from now on
        status(AetherStatusLine.off(reason), kind);
        if (t != null && kind == AetherStatusLine.Off.ERROR)
            com.skycraft.Skycraft.LOGGER.debug("aether failure detail", t);
    }

    /** Logs a status line when it differs from the last one; off lines are WARN (on is INFO). */
    private static void status(String line, AetherStatusLine.Off kind) {
        if (line.equals(lastStatus)) return;
        lastStatus = line;
        if (line.contains("far field on")) com.skycraft.Skycraft.LOGGER.info("{}", line);
        else com.skycraft.Skycraft.LOGGER.warn("{}", line);
        if (kind != null && !noticeShown && AetherStatusLine.notice(kind)
                && !"false".equalsIgnoreCase(System.getProperty("skycraft.aether.notice"))) {
            noticeShown = true;
            AetherGuard.run("aether notice", () -> com.skycraft.client.hud.HudNotifications.add(
                    new com.skycraft.network.CorePackets.Notify(com.skycraft.network.NotifyKind.MESSAGE,
                            net.minecraft.network.chat.Component.literal(AetherStatusLine.noticeText(kind)),
                            net.minecraft.network.chat.Component.empty(), 0, 0f)));
        }
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.toString() : m;
    }

    /** The compositor can run (checked once; a failure later turns it off through AetherGuard). */
    private static boolean fallbackUsable() {
        if ("false".equalsIgnoreCase(System.getProperty("skycraft.aether.fallback"))) {
            fallbackBlocker = "skycraft.aether.fallback=false";
            return false;
        }
        if (AetherGuard.off(AetherFallback.FEATURE)) {
            fallbackBlocker = "it failed earlier this session (see the log)";
            return false;
        }
        if (!fallbackChecked) {
            fallbackChecked = true;
            try {
                fallbackBlocker = AetherFallback.blocker();
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
     * stands. One the client stops having shows at once, and aether itself draws impostors only beyond the near field,
     * dissolving them in across the first 16 blocks past it (traceActors), so nothing pops or doubles at the hand-off.
     * Dragons beat their wings while climbing or cruising, glide while descending, and fold them on the ground.
     */
    private static void feedActors(net.minecraft.client.multiplayer.ClientLevel level) {
        AetherNet.FarActors a = farActors;
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
            if ((a.idFlags()[i * 2 + 1] & AetherNet.FarActors.ON_GROUND) != 0) gait = AetherNative.GAIT_STILL;
            else gait = a.vel()[i * 3 + 1] < -4f ? AetherNative.GAIT_GLIDE : AetherNative.GAIT_FLAP;
            int seed = (id * 0x9E3779B1) >>> 16;
            actorFlags[i] = Math.round((1f - o) * 255f) | gait << 8 | seed << 16;
        }
        actorOpacity.keySet().retainAll(live);
        ctx.setActors(actorXyz, a.yawHeightRadius(), a.kindTint(), actorFlags, n);
    }

    /** True when the client has actor i's entity: the same id, or an entity of about its size where it stands. */
    private static boolean clientHas(net.minecraft.client.multiplayer.ClientLevel level, AetherNet.FarActors a, int i, int id) {
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
     * aether's light and the pack's (sunPosition) agree.
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

    /** Tears aether down (start() brings it back with fresh tiles). The library stays loaded. */
    private static void stop() {
        try {
            if (farField != null) farField.close();
        } catch (Throwable ignored) {
            // best effort: the session continues without aether
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
