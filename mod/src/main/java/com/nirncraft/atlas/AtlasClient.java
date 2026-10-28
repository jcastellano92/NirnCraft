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
    private static boolean clientStopping;
    /** A failed terminal close cannot be retried as a no-op and mistaken for safe device destruction. */
    static final class ShutdownResources {
        private final Runnable[] owners;
        private boolean attempted;
        private Throwable failure;

        ShutdownResources(Runnable... owners) { this.owners = owners; }

        void close() {
            if (!attempted) {
                attempted = true;
                for (Runnable owner : owners) {
                    try { owner.run(); }
                    catch (Throwable error) {
                        if (failure == null) failure = error;
                        else if (failure != error) failure.addSuppressed(error);
                    }
                }
            }
            if (failure != null) throw new IllegalStateException("atlas: terminal GPU cleanup failed; device retained", failure);
        }
    }
    private static volatile AtlasNative.Context ctx;  // (volatile: the info thread reads it, AtlasInfo)

    /**
     * NeoForge's early loading screen (earlydisplay) hands over a RenderPearlRenderBackend whose close() frees
     * its pipelines and host ByteBuffer but leaves SimpleBufferBuilder.gpuBuffer ("shared_error") and
     * ELSRenderBackend.screenSizeUbo ("ScreenSize UBO") open when VulkanDevice.close() destroys the device.
     */
    static void closeEarlyDisplayAtShutdown() {
        try {
            Class<?> cls = Class.forName("net.neoforged.fml.loading.EarlyLoadingScreenController");
            Object controller = cls.getMethod("current").invoke(null);
            closeEarlyDisplayBuffers(controller);
        } catch (ClassNotFoundException ignored) {
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("atlas: could not inspect earlydisplay controller at shutdown", e);
        }
    }

    static void closeEarlyDisplayBuffers(Object controller) {
        if (controller == null) return;
        java.lang.reflect.Field futureField = findDeclaredField(controller.getClass(), "rendererFuture");
        if (futureField == null) return;
        Throwable failure = null;
        try {
            futureField.setAccessible(true);
            if (!(futureField.get(controller) instanceof java.util.concurrent.Future<?> future)
                    || !future.isDone() || future.isCancelled()) return;
            Object screen = future.get();
            if (screen == null) return;
            java.lang.reflect.Field builderField = findDeclaredField(screen.getClass(), "bufferBuilder");
            if (builderField != null) {
                builderField.setAccessible(true);
                Object builder = builderField.get(screen);
                if (builder != null) {
                    java.lang.reflect.Field gpuField = findDeclaredField(builder.getClass(), "gpuBuffer");
                    if (gpuField != null) {
                        gpuField.setAccessible(true);
                        if (gpuField.get(builder) instanceof AutoCloseable gpuBuffer) {
                            try {
                                gpuBuffer.close();
                                gpuField.set(builder, null);
                            } catch (Throwable t) { failure = t; }
                        }
                    }
                }
            }
            java.lang.reflect.Field backendField = findDeclaredField(screen.getClass(), "backend");
            if (backendField != null) {
                backendField.setAccessible(true);
                Object backend = backendField.get(screen);
                if (backend != null) {
                    java.lang.reflect.Field uboField = findDeclaredField(backend.getClass(), "screenSizeUbo");
                    if (uboField != null) {
                        uboField.setAccessible(true);
                        if (uboField.get(backend) instanceof AutoCloseable ubo) {
                            try { ubo.close(); }
                            catch (Throwable t) {
                                if (failure == null) failure = t;
                                else if (failure != t) failure.addSuppressed(t);
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            if (failure == null) failure = t;
            else if (failure != t) failure.addSuppressed(t);
        }
        if (failure != null) throw new IllegalStateException("atlas: could not close earlydisplay GPU buffers", failure);
    }

    private static java.lang.reflect.Field findDeclaredField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
        }
        return null;
    }
    private record ContextReaders(AtlasNative.Context context, AtlasMaterials materials, AtlasReleaseGate gate) {}
    private static volatile ContextReaders infoReaders;
    record InfoLease(AtlasNative.Context context, AtlasMaterials materials, AtlasReleaseGate.Lease lease) implements AutoCloseable {
        @Override public void close() { lease.close(); }
    }
    private static volatile AtlasMaterials materials;  // (atlas-info: the exact ids' use)
    private static Path libPath;  // the loaded core (the client generator binds its own calls there)
    private static volatile AtlasClientGen clientGen;  // -Dnirncraft.atlas.clientGen=true: atlas generates the near field
    private static final Object CLIENT_GEN_LOCK = new Object();
    private static volatile Thread clientGenStart;  // (its start's thread: stop() releases the context after it)
    private static volatile java.util.concurrent.atomic.AtomicReference<Throwable> clientGenStartFailure;
    private static volatile java.util.concurrent.atomic.AtomicBoolean clientGenStartupFailed;
    private static volatile int clientGenEpoch;  // (stop: a generator still starting on its thread is closed when it arrives)
    private static final java.util.List<ReleaseTicket> releases = new java.util.ArrayList<>(); // render thread only
    private static boolean retirementRequested;
    private static ContextReaders retiringReaders;
    private static java.util.concurrent.CompletableFuture<Void> retirementCopy;
    private static java.util.concurrent.CompletableFuture<Void> previousRelease = java.util.concurrent.CompletableFuture.completedFuture(null);

    /** One context generation owns its workers and its interop resources until both sides have retired. */
    static final class ReleaseTicket {
        final AtlasNative.Context context;
        final AtlasVulkan.Path path;
        final Thread starter;
        final java.util.concurrent.atomic.AtomicReference<Throwable> startFailure;
        final AtlasClientGen generator;
        final AtlasFarField far;
        final java.util.concurrent.CompletableFuture<Void> farExited;
        final java.util.concurrent.CompletableFuture<Object[]> build;
        final AtlasReleaseGate readers;
        final java.util.function.Consumer<java.util.function.Consumer<Throwable>> closeFar;
        final Runnable drainGenerator;
        final Runnable renderDetach;
        final java.util.function.Supplier<AtlasNative.RetiredVulkan> detach;
        final Runnable closeContext;
        final java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        final java.util.concurrent.CompletableFuture<Void> renderDone = new java.util.concurrent.CompletableFuture<>();
        volatile AtlasNative.RetiredVulkan token;
        volatile boolean ready;
        volatile Throwable failure;

        ReleaseTicket(AtlasNative.Context context, Thread starter,
                      java.util.concurrent.atomic.AtomicReference<Throwable> startFailure, AtlasClientGen generator,
                      AtlasFarField far, java.util.concurrent.CompletableFuture<Void> farExited,
                      java.util.concurrent.CompletableFuture<Object[]> build, AtlasReleaseGate readers) {
            this(context, AtlasVulkan.Path.VK_SHARED, starter, startFailure, generator, far, farExited, build, readers);
        }

        ReleaseTicket(AtlasNative.Context context, AtlasVulkan.Path path, Thread starter,
                      java.util.concurrent.atomic.AtomicReference<Throwable> startFailure, AtlasClientGen generator,
                      AtlasFarField far, java.util.concurrent.CompletableFuture<Void> farExited,
                      java.util.concurrent.CompletableFuture<Object[]> build, AtlasReleaseGate readers) {
            this.context = context; this.path = path; this.starter = starter; this.startFailure = startFailure;
            this.generator = generator; this.far = far; this.farExited = farExited; this.build = build; this.readers = readers;
            this.closeFar = far == null ? callback -> callback.accept(null) : far::closeTracked;
            this.drainGenerator = generator == null ? () -> {} : generator::awaitStop;
            this.renderDetach = () -> {};
            this.detach = context != null ? () -> {
                context.vkPrepareClose();
                return AtlasVulkan.detachRetired(context);
            } : () -> null;
            this.closeContext = context == null ? () -> {} : context::close;
        }

        /** Injection seam for offline ordering and failure tests; production uses the captured owners above. */
        ReleaseTicket(Thread starter, java.util.concurrent.CompletableFuture<Void> farExited, AtlasReleaseGate readers,
                      java.util.function.Consumer<java.util.function.Consumer<Throwable>> closeFar,
                      Runnable drainGenerator, Runnable renderDetach,
                      Runnable closeContext) {
            this.context = null; this.path = AtlasVulkan.Path.VK_SHARED; this.starter = starter; this.startFailure = null;
            this.generator = null; this.far = null; this.farExited = farExited; this.build = null; this.readers = readers;
            this.closeFar = closeFar; this.drainGenerator = drainGenerator; this.renderDetach = renderDetach; this.closeContext = closeContext;
            this.detach = () -> null;
        }

        void pumpRender() {
            pumpRender(AtlasVulkan::enqueueRetired);
        }

        void pumpRender(java.util.function.Consumer<AtlasNative.RetiredVulkan> enqueue) {
            if (!ready || renderDone.isDone() || failure != null) return;
            try {
                renderDetach.run();
                if (context != null || token != null) enqueue.accept(token);
                renderDone.complete(null);
            } catch (Throwable t) {
                failure = t;
                done.completeExceptionally(t);
                com.nirncraft.NirnCraft.LOGGER.error("atlas: interop detach failed; context ownership retained", t);
            }
        }

        void launch() {
            Thread release = new Thread(() -> {
                boolean interrupted = false;
                try {
                    closeFar.accept(failure -> {
                        if (failure == null) farExited.complete(null);
                        else farExited.completeExceptionally(failure);
                    });
                    farExited.join();
                    if (starter != null) {
                        while (starter.isAlive()) {
                            try { starter.join(); }
                            catch (InterruptedException e) { interrupted = true; }
                        }
                    }
                    if (startFailure != null && startFailure.get() != null)
                        throw new IllegalStateException("generator startup did not release safely", startFailure.get());
                    if (readers != null) interrupted |= readers.awaitDrained();
                    drainGenerator.run();
                    if (build != null) {
                        Object[] made;
                        try { made = build.join(); }
                        catch (java.util.concurrent.CompletionException failedBuild) { made = null; }
                        if (made != null && made[0] != null) {
                            var pending = (AtlasNative.Context) made[0];
                            // A still-building adopted context has not yet selected AtlasVulkan.path or attached.
                            if (made.length > 2 && Boolean.TRUE.equals(made[2])) pending.vkPrepareClose();
                            pending.close();
                        }
                    }
                    token = detach.get();
                    ready = true;
                    renderDone.join();
                    closeContext.run();
                    done.complete(null);
                } catch (Throwable t) {
                    failure = t;
                    done.completeExceptionally(t);
                    com.nirncraft.NirnCraft.LOGGER.error("atlas: context release failed; device ownership retained", t);
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }, "atlas context release");
            release.setDaemon(true);
            release.start();
        }
    }

    /** Called on the owning render thread; a failed ticket remains visible to shutdown. */
    private static void pumpReleases() {
        pumpReleases(releases, AtlasVulkan::enqueueRetired);
    }

    static void pumpReleases(java.util.List<ReleaseTicket> tickets, java.util.function.Consumer<AtlasNative.RetiredVulkan> enqueue) {
        for (ReleaseTicket ticket : tickets) ticket.pumpRender(enqueue);
        tickets.removeIf(ticket -> ticket.done.isDone() && !ticket.done.isCompletedExceptionally());
    }

    static boolean releaseBarrierSatisfied(boolean stopped, java.util.List<ReleaseTicket> tickets) {
        return stopped && tickets.isEmpty();
    }

    private static boolean cpuBackend;  // ctx runs on the CPU backend (no usable Vulkan device, or forced)
    // (start) the context being made on its thread: [context, on the CPU]; when it began
    private static volatile java.util.concurrent.CompletableFuture<Object[]> ctxBuild;
    private static long ctxBuildStartNs;
    private static long cpuLogAt;       // nanoTime of the next CPU-path log line
    private static AtlasTargets targets;
    private static boolean farThisFrame;  // atlas drew this frame (its targets hold it): the near pass composites them
    private static long deliveredFrames, skippedFrames, lastDeliveredNs;
    private static volatile boolean outputReady;
    private static boolean nearLitThisFrame;  // and lit the near pass's pixels (ATLAS_FRAME_NEAR_LIGHT)
    private static AtlasFarField farField;
    private static java.util.concurrent.CompletableFuture<Void> farGenerationExited;
    private static ClientLevel startedFor;  // the level the far field's tiles belong to
    private static Object startedModels;  // aliases and ingest belong to this block-model set
    private static final java.util.concurrent.atomic.AtomicBoolean modelsReloaded = new java.util.concurrent.atomic.AtomicBoolean();
    private static final WeatherFeed WEATHER = new AtlasWeatherFeed();
    private static final AtlasQuality quality = new AtlasQuality();
    private static Supplier<WeatherFeed> weather = () -> WEATHER;
    /** Off for the session (the reason is in the status line). */
    private static boolean failed;
    private static AtlasStatusLine.Off offKind;
    /** No shader pack and no usable fallback compositor: atlas does not start (and nothing is wrong). */
    private static volatile boolean idleByConfig;
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
     * The content-shaped groups (docs/ATLAS-LOD.md; ATLAS_FRAME_GROUPS, atlas_core 0.14): on by default (the near
     * field's bricks and textures need them); -Dnirncraft.atlas.groups=false the voxel path.
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
        modBus.addListener((net.neoforged.neoforge.client.event.AddClientReloadListenersEvent e) -> {
            var id = net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft", "atlas_looks");
            e.addListener(id, (net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> modelsReloaded.set(true));
            e.addDependency(net.neoforged.neoforge.client.resources.VanillaClientListeners.LAST, id);
        });
        AtlasRecorder.init();  // the flight recorder: client tick zone, /nirncraft-atlas-trace
        AtlasVkProbe.init();  // (-Dnirncraft.atlas.vkProbe=true: the Vulkan-backend probe, docs/ATLAS-VULKAN-BACKEND.md)
        AtlasNearPass.init(modBus);
        modBus.addListener((RegisterRenderPipelinesEvent e) -> AtlasGuard.run(AtlasNearPass.FEATURE, () -> AtlasSky.register(e)));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent e) ->
                AtlasGuard.run(AtlasNearPass.FEATURE, () -> AtlasSky.extract(e)));
        // after atlas's frame (or its absence): our near terrain into the frame, atlas's far frame behind it
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOW,
                (RenderLevelStageEvent.AfterOpaqueBlocks e) -> AtlasGuard.run(AtlasNearPass.FEATURE, Z_COMPOSITE,
                        () -> AtlasNearPass.composite(e.getRenderPass())));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) ->
                AtlasGuard.run("atlas client tick", Z_TICK_HOOK, () -> {
                    pumpReleases();
                    if (!retirementRequested && clientGenStartupFailed != null && clientGenStartupFailed.get()) stop();
                    if (retirementRequested) stop();
                    AtlasTargets.flushClear();
                    AtlasFarField.restMs = com.nirncraft.render.quality.QualityClient.workerRestMs();
                    if (farField != null) AtlasClientSections.tick(Minecraft.getInstance());  // the loaded chunks' blocks
                    long pz = AtlasRecorder.begin();
                    profileTick();
                    AtlasRecorder.end(Z_PROFILE, pz, 100_000);
                }));
        AtlasClientSections.init();
        AtlasTextureClock.init(modBus);
        NeoForge.EVENT_BUS.addListener((ClientStoppingEvent e) -> {
            clientStopping = true;
            try { if (guard != null) guard.stopped(); } // a clean exit is not a crash
            catch (Throwable t) { com.nirncraft.NirnCraft.LOGGER.warn("atlas: could not record clean shutdown", t); }
            boolean interrupted = false, stopped = false;
            long nextLog = 0;
            while (!releaseBarrierSatisfied(stopped, releases)) {
                try {
                    if (!stopped) { stop(); stopped = !retirementRequested; }
                    pumpReleases();
                } catch (Throwable t) {
                    long now = System.nanoTime();
                    if (now >= nextLog) {
                        com.nirncraft.NirnCraft.LOGGER.error("atlas: waiting for safe shutdown", t);
                        nextLog = now + 5_000_000_000L;
                    }
                }
                if (!releaseBarrierSatisfied(stopped, releases)) {
                    long now = System.nanoTime();
                    if (now >= nextLog) {
                        com.nirncraft.NirnCraft.LOGGER.warn("atlas: waiting for {} context releases before device shutdown", releases.size());
                        nextLog = now + 5_000_000_000L;
                    }
                    try { Thread.sleep(100); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
            }
            var resources = new ShutdownResources(AtlasNearPass::closeAtShutdown, AtlasSky::closeAtShutdown,
                    AtlasMeter::closeAtShutdown, AtlasAir::closeAtShutdown, AtlasTargets::closeAtShutdown,
                    com.nirncraft.render.quality.QualityClient::closeAtShutdown, AtlasClient::closeEarlyDisplayAtShutdown);
            for (;;) {
                try { resources.close(); break; }
                catch (Throwable failure) {
                    long now = System.nanoTime();
                    if (now >= nextLog) {
                        com.nirncraft.NirnCraft.LOGGER.error("atlas: waiting for terminal GPU cleanup before device shutdown", failure);
                        nextLog = now + 5_000_000_000L;
                    }
                    try { Thread.sleep(100); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        });
        // adaptive quality v2: measures the frame, tunes atlas and the pack to this machine (render/quality)
        AtlasGuard.run("adaptive quality", com.nirncraft.render.quality.QualityClient::init);
        com.nirncraft.render.quality.QualityClient.setColumnThreadSink(n -> {
            AtlasFarField f = farField;
            if (f != null && f.source() instanceof AtlasExactSource s) s.setThreads(n);
        });
        NeoForge.EVENT_BUS.addListener(AtlasCacheWipe::onCommands);  // (/nirncraft-atlas-wipecache)
        // the loading phase (AtlasLoadingScreen): armed on joining, opened once vanilla's terrain screen closed
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> {
            AtlasLoadingScreen.arm();
            AtlasLoadStats.joined();  // (the loading gate's clock: join to AtlasReady, atlas-load.json)
        });
        // (a quit while the kernels compile: the player's own render distance back before the options are saved)
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.GameShuttingDownEvent e) -> reduceVanilla(Minecraft.getInstance(), false));
        // in the world while kernels still compile (the context's, or the split frames' far kernel in the background):
        // one small line, the real counts (atlas_pipeline_progress), never a timed bar
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderGuiEvent.Post e) ->
                AtlasGuard.run("atlas compile line", Z_LOADING, () -> compileLine(e.getGuiGraphics())));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) ->
                AtlasGuard.run("atlas loading phase", Z_LOADING, () -> {
                    AtlasLoadingScreen.tick(Minecraft.getInstance());
                    AtlasLoadStats.tick(Minecraft.getInstance());
                }));
        // (vanilla's distance back once no build runs, whatever path the frames took: a session turned off, no pack)
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) -> {
            if (reducedFrom >= 0 && !buildingContext()) reduceVanilla(Minecraft.getInstance(), false);
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            AtlasLoadingScreen.disarm();
            reduceVanilla(Minecraft.getInstance(), false);
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
        long rz = AtlasRecorder.begin();
        try {
            renderFarBody(state, pass);
        } finally {
            AtlasRecorder.end(Z_RENDER_FAR, rz);
        }
    }

    private static void renderFarBody(LevelRenderState state, com.mojang.renderpearl.api.commands.RenderPass pass) {
        farThisFrame = false;
        nearLitThisFrame = false;
        com.nirncraft.render.quality.QualityClient.atlasFrame(false, 0);
        if (retirementRequested) return;
        if (failed) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            if (ctx != null && !copyReady(() -> AtlasVulkan.finishPending(ctx))) return;
            if (modelsReloaded.getAndSet(false) || (ctx != null && startedModels != null && startedModels != mc.getModelManager().getBlockStateModelSet())) {
                AtlasClientSections.resetLooks();
                if (ctx != null || ctxBuild != null) stop(); // Preserve pending-build model reload cancellation.
                if (retirementRequested) return;
            }
            if (suspended) {  // nothing far this frame (a pack composites the targets: they are cleared)
                outputReady = false;
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
                    outputReady = false;
                    targets.close();
                    targets = null;
                }
            }
            // (Minecraft's Vulkan backend: the near pass composites atlas itself; the fallback compositor cannot run there)
            idleByConfig = !pack && !fallbackUsable() && !(AtlasVulkan.vulkan() && AtlasNearPass.capable(mc));
            if (idleByConfig) {
                // nobody would see the far field: do not draw it (it comes back when a pack is switched on)
                status(AtlasStatusLine.off("no shader pack is active and the fallback compositor "
                        + (fallbackBlocker != null ? "cannot run: " + fallbackBlocker : "is off")), null);
                com.nirncraft.render.quality.QualityClient.atlasFrame(false, 0);
                return;
            }
            // a new world (or server): new tiles, maybe another column source
            if (ctx != null && startedFor != mc.level && mc.level.dimension() == Level.OVERWORLD) stop();
            if (retirementRequested) return;
            if (ctx == null && !start(mc)) return;
            if (!copyReady(() -> AtlasVulkan.finishPending(ctx))) return;
            if (farField != null && farField.workerError() != null) throw farField.workerError();
            quality.begin();
            if (!quality.enabled(AtlasNearPass.unified())) {
                // adaptive quality's last fallback: no far field (the targets shrink to 1 x 1: the pack's own sky
                // and cloud deck take over); the context and its tiles stay for when it comes back. In unified mode
                // vanilla's terrain is suppressed, so atlas must keep drawing its minimum-quality world instead.
                if (targets != null) {
                    outputReady = false;
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
                outputReady = false;
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
                outputReady = false;
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
            // the client generator follows the camera this frame (not the far field's pass: seconds after a jump)
            if (clientGen != null) AtlasClientGen.follow(p.x, p.z, mc.player != null ? mc.player.getYRot() : 0f);
            if (!farField.updated()) {
                outputReady = false;
                targets.clear();  // the worker has not staged the first window yet: nothing far this frame
                com.nirncraft.render.quality.QualityClient.atlasFrame(false, 0);
                return;
            }
            long fz = AtlasRecorder.begin();
            feedWeather();
            AtlasRecorder.end(Z_WEATHER, fz, 50_000);
            fz = AtlasRecorder.begin();
            AtlasAir.update(mc, lib, ctx);  // the air at the eye (the sky, the sun through it, the haze)
            AtlasRecorder.end(Z_AIR, fz, 50_000);
            fz = AtlasRecorder.begin();
            feedActors(mc.level);
            AtlasRecorder.end(Z_ACTORS, fz, 50_000);
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
            // an eclipse's shadow on the far field: the moons near the sun, each surface's sun seen from where it is
            // (the umbra's footprint dark, the land and the air beyond it lit)
            ctx.setEclipse(com.nirncraft.atmosphere.client.NightSky.occulterBlock(),
                    (float) com.nirncraft.atmosphere.MoonMath.KM_PER_BLOCK);
            frame.timeSec = (state.gameTime + state.worldPartialTicks) / 20f;
            // visual tests: the clouds (and their shadows) still, so the views of a pair taken seconds apart see one
            // sky (the day time stands still there: the clock of the scene's time of day)
            if (com.nirncraft.client.autotest.ClientAutotest.ENABLED && mc.level != null)
                frame.timeSec = Math.floorMod(mc.level.getOverworldClockTime(), 24000L) / 20f;
            if (sun[1] < 0f && mc.level != null) {  // night: the pack's moon, not a fixed one (both fields alike)
                float[] key = com.nirncraft.atmosphere.client.LunarKey.compute(mc.level, sun, sunPathRotationDeg,
                        state.worldPartialTicks);
                // (moonless: a token light, not none: the core takes none for its old fixed moonlight)
                for (int i = 3; i < 6; i++) key[i] = Math.max(key[i], 1e-9f);
                try {
                    ctx.setMoon(new float[] {key[0], key[1], key[2]}, new float[] {key[3], key[4], key[5]});
                    AtlasAir.moon(new float[] {key[0], key[1], key[2]}, new float[] {key[3], key[4], key[5]});
                } catch (RuntimeException e) {
                    com.nirncraft.NirnCraft.LOGGER.debug("atlas: moon not set: {}", e.toString());
                }
            }
            // the sky's own light (the unified sky's emitters: the aurora, the airglow, the stars, the fill moon, meteors)
            // in the far field's sky light, as the near pass has it (AtlasLook.light)
            float[] glow = AtlasSky.nightLight();
            try {
                ctx.setSkyGlow(new float[] {glow[0], glow[1], glow[2]}, new float[] {glow[3], glow[4], glow[5]});
            } catch (RuntimeException e) {
                com.nirncraft.NirnCraft.LOGGER.debug("atlas: sky glow not set: {}", e.toString());
            }
            frame.lodBias = quality.lodBias();  // > 1: coarser levels sooner with distance (far detail goes first)
            applyAblate(ctx);
            try {
                ctx.setTextureTicks((int) AtlasTextureClock.ticks());  // (animated bricks: the frame vanilla shows)
            } catch (RuntimeException e) {
                com.nirncraft.NirnCraft.LOGGER.debug("atlas: texture ticks not set: {}", e.toString());
            }
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

            boolean nearOwned = AtlasNearPass.owns();
            boolean nearLit = nearOwned && ctx.hasNearLight() && AtlasNearPass.unified();
            if (nearLit) {
                frame.flags |= AtlasNative.FRAME_NEAR_LIGHT | AtlasNative.FRAME_SHADOWS | AtlasNative.FRAME_ATMOS;
                // atlas's clouds, in its light and haze (NirnCraft's deck steps aside: OverworldClouds), unless weather is off
                if (!com.nirncraft.atmosphere.client.WeatherFieldTexture.isOff()) frame.flags |= AtlasNative.FRAME_CLOUDS;
                frame.flags &= ~AtlasNative.FRAME_VANILLA_LIGHT;
                if (AtlasTruthView.noFog) frame.flags &= ~AtlasNative.FRAME_ATMOS;
                // the unified field: atlas traces every pixel from the camera, near and far lit and hazed alike
                if (AtlasNearPass.unified() && lib.abi() >= 0x001100) frame.flags |= AtlasNative.FRAME_PRIMARY_NEAR_LIT;
            }
            Matrix4f nearProj = AtlasLook.levelProjection(cam);
            if (nearOwned) {
                int gw = mc.getWindow().getWidth(), gh = mc.getWindow().getHeight();
                nearProj = new Matrix4f().translation(-2f * AtlasNearPass.jitterX() / gw, -2f * AtlasNearPass.jitterY() / gh, 0f)
                        .mul(nearProj);
            }
            nearProj.invert().get(io.nearInvProjection);
            io.depthZeroToOne = AtlasTargets.depthZeroToOne();
            io.reversedZ = nearOwned || reversedZ(pack);  // the depth convention the near field's depth was written with
            io.nearDepthTex = 0;
            io.nearInfoTex = 0;
            io.dstColor = io.dstDepth = 1;
            io.dstNormal = 0;
            io.dstSunMap = targets.sunSize() > 0 ? 1 : 0;
            io.dstNearLight = nearLit ? 1 : 0;
            if (firstFrame && guard != null) guard.firstFrames();  // a driver crash from here on is the guard's case
            // the images pre-exposed with this frame's eye (the resolve divides it out at once, in full precision)
            preExposure = ctx.setPreExposure(AtlasLook.frame(mc)[0]) ? AtlasLook.frame(mc)[0] : 1f;
            long g0 = System.nanoTime();
            boolean delivered = false;
            // on Minecraft's device: atlas traces on its own queue; one spliced command buffer copies the frame into
            // the registered textures after a timeline wait (no CPU wait, no host memory)
            // (every splice handed out must reach Minecraft's queue: the next frame waits for its copies, so nothing is
            // traced where it could not be spliced)
            if (AtlasVulkan.canSplice()) {
                long[] img = targets.vkImages(nearLit);
                AtlasNative.VkSplice sp = ctx.vkRenderShared(frame, io, img[0], img[1], img[2], img[3]);
                if (sp == null) {
                    if (!copyReady(() -> AtlasVulkan.finishPending(ctx))) return;
                    sp = ctx.vkRenderShared(frame, io, img[0], img[1], img[2], img[3]);
                    if (sp == null) throw new IllegalStateException("atlas: shared Vulkan frame remained busy after completion");
                }
                if (sp != null) {
                    if (!AtlasVulkan.splice(ctx, sp))
                        throw new IllegalStateException("atlas: Minecraft's encoder refused the splice");
                    delivered = true;
                }
            } else if (!vkPassWarned) {
                vkPassWarned = true;
                com.nirncraft.NirnCraft.LOGGER.warn("atlas: no frame (Minecraft's Vulkan encoder is inside a render pass here)");
            }
            long g1 = System.nanoTime();
            AtlasRecorder.end(Z_GL_RENDER, g0);
            glRenderMs += ((g1 - g0) * 1e-6 - glRenderMs) * 0.05;  // (eased: atlas-info's wall time in the call)
            if (!delivered) {
                skippedFrames++;
                return;
            }
            deliveredFrames++;
            outputDelivered();
            lastDeliveredNs = g1;
            if (lastFrameNs != 0) frameWallMs += ((g1 - lastFrameNs) * 1e-6 - frameWallMs) * 0.05;
            lastFrameNs = g1;
            AtlasLoadStats.frame(g1);
            farThisFrame = true;
            nearLitThisFrame = nearLit;
            boolean fallbackDrawn = pass != null && !pack && !nearOwned && !AtlasFallback.fogHidesFarField(cam.fogData);
            if (fallbackDrawn) {
                AtlasTargets t = targets;
                AtlasGuard.run(AtlasFallback.FEATURE, () -> AtlasFallback.draw(pass, t));
            }
            if (guard != null) guard.frame(now);
            if (firstFrame) {
                firstFrame = false;
                status(AtlasStatusLine.on(ctx.info(), fallbackDrawn && !AtlasGuard.off(AtlasFallback.FEATURE), com.nirncraft.render.quality.QualityClient.qualityName()),
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

    private static void compileLine(net.minecraft.client.gui.GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.screen() != null || lib == null) return;
        AtlasNative.PipelineProgress pp = building();
        if (pp == null) return;
        String text = buildingContext() && pp.total() > 0
                ? "Atlas: compiling its kernels " + Math.min(pp.done() + 1, pp.total()) + " / " + pp.total() + " (" + pp.name() + ")"
                : pp.split() == 1 ? "Atlas: compiling its fast far kernel in the background" : null;
        if (text == null) return;
        g.text(mc.font, net.minecraft.network.chat.Component.literal(text), 4, mc.getWindow().getGuiScaledHeight() - 12,
                0xA0C8C0B0, true);
    }

    // (ATLAS-PLAN 0.9) while atlas's kernels compile, vanilla draws reduced so the machine goes to the compile: a short
    // render distance, the player's own restored the moment the context is made (or failed, or the world is left)
    private static int reducedFrom = -1;
    private static final int REDUCED_DISTANCE = 2;

    private static void reduceVanilla(Minecraft mc, boolean on) {
        if (mc == null || mc.options == null || "false".equalsIgnoreCase(System.getProperty("nirncraft.atlas.reduceWhileLoading"))) return;
        if (on && reducedFrom < 0) {
            int d = mc.options.renderDistance().get();
            if (d <= REDUCED_DISTANCE) return;
            reducedFrom = d;
            mc.options.renderDistance().set(REDUCED_DISTANCE);
            mc.options.broadcastOptions();  // (the integrated server's view distance follows: its chunks are the work too)
            com.nirncraft.NirnCraft.LOGGER.info("atlas: vanilla's render distance {} -> {} while atlas's kernels compile", d, REDUCED_DISTANCE);
        } else if (!on && reducedFrom >= 0) {
            // (only if it is still ours: a change made meanwhile, the player's or a test's, stays)
            if (mc.options.renderDistance().get() == REDUCED_DISTANCE) {
                mc.options.renderDistance().set(reducedFrom);
                mc.options.broadcastOptions();
            }
            com.nirncraft.NirnCraft.LOGGER.info("atlas: vanilla's render distance back to {}", reducedFrom);
            reducedFrom = -1;
        }
    }

    /** The context's kernels as they are built (the loading screen), or null: none being made, an older library. */
    static AtlasNative.PipelineProgress building() {
        AtlasNative l = lib;
        return l == null ? null : l.pipelineProgress();
    }

    /** Whether the context is being made on its thread (its kernels compiling). */
    static boolean buildingContext() {
        java.util.concurrent.CompletableFuture<Object[]> b = ctxBuild;
        return b != null && !b.isDone();
    }

    private static boolean start(Minecraft mc) {
        if (clientStopping) return false;
        // Prior owners may still hold singleton stages or the old device interop. Keep drawing vanilla while
        // their release runs; a failed release deliberately prevents replacement ownership.
        if (!previousRelease.isDone() || previousRelease.isCompletedExceptionally()) return false;
        if (buildingContext()) return false;  // (its kernels still compiling on their thread: vanilla draws this frame)
        try {
            if (!decided) {
                decided = true;
                if ("false".equalsIgnoreCase(System.getProperty("nirncraft.atlas"))) {
                    offForSession("-Dnirncraft.atlas=false", AtlasStatusLine.Off.SETTING, null);
                    return false;
                }
                String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
                if (!AtlasVulkan.vulkan()) {
                    offForSession("the game runs on the " + backend + " renderer backend; atlas requires Minecraft's Vulkan backend",
                            AtlasStatusLine.Off.SETTING, null);
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
            AtlasCacheWipe.applyIfPending(mc);  // (-Dnirncraft.atlas.wipeCache, /nirncraft-atlas-wipecache)
            if (lib == null) {
                lib = loadLibrary(mc.gameDirectory.toPath());
                AtlasRecorder.start(libPath, mc.gameDirectory.toPath());
                // the pipelines warm from the instance's cache (the loading phase): seconds, not minutes, after the first run
                if (lib != null && lib.setPipelineCache(mc.gameDirectory.toPath().resolve("atlas-cache").resolve("pipelines.bin")))
                    com.nirncraft.NirnCraft.LOGGER.info("atlas: pipelines kept in atlas-cache/pipelines.bin");
            }
            // the GPU when there is one; else (no Vulkan device, a driver that refuses) atlas on the CPU, slower and at
            // a lower resolution but the same picture: the near pass's light and the far field on every machine
            // (-Dnirncraft.atlas.backend=cpu forces it: what a machine without a GPU sees). On the CPU the frame is
            // rendered on atlas's worker from the near field's depth and info read back from GL, a few frames behind
            // the camera (atlas_core's CPU GL path; atlas_info's cpuGl block has its times)
            // the context on its own thread: its Vulkan pipelines are the driver's compile, minutes on a cold cache, and the
            // render thread must never wait for it (the window froze, "Not Responding"). Until it is made the frame is
            // vanilla's (start() false), the loading screen shows the kernels as they are built (pipelineProgress)
            if (ctxBuild == null) {
                AtlasNative l = lib;
                long[] share = sharedDeviceArgs(l);
                if (share == null) {
                    offForSession("Minecraft's Vulkan device is not shared (" + AtlasVkShare.why() + ")",
                            AtlasStatusLine.Off.PLATFORM, null);
                    return false;
                }
                java.util.concurrent.CompletableFuture<Object[]> build = new java.util.concurrent.CompletableFuture<>();
                Thread t = new Thread(() -> {
                    try {
                        build.complete(new Object[]{l.createOnVulkan(share[0], share[1], share[2], (int) share[3],
                                (int) share[4], (int) share[5], (int) share[6], (int) share[7], (int) share[8]), false, true});
                    } catch (Throwable e) {
                        build.completeExceptionally(e);
                    }
                }, "atlas context build");
                t.setDaemon(true);
                ctxBuild = build;
                ctxBuildStartNs = System.nanoTime();
                reduceVanilla(mc, true);
                t.start();
            }
            if (!ctxBuild.isDone()) return false;  // (vanilla draws this frame)
            Object[] made;
            try {
                made = ctxBuild.join();
            } catch (java.util.concurrent.CompletionException e) {
                ctxBuild = null;
                reduceVanilla(mc, false);
                throw e.getCause() != null ? e.getCause() : e;
            }
            ctxBuild = null;
            reduceVanilla(mc, false);
            ctx = (AtlasNative.Context) made[0];
            infoReaders = new ContextReaders(ctx, null, new AtlasReleaseGate());
            cpuBackend = false;
            AtlasVulkan.choose(AtlasVulkan.Path.VK_SHARED);
            com.nirncraft.NirnCraft.LOGGER.info("atlas: on Minecraft's Vulkan backend, path {}", AtlasVulkan.path());
            com.nirncraft.NirnCraft.LOGGER.info("atlas: context made in {} s (its kernels built off the render thread)",
                    String.format("%.1f", (System.nanoTime() - ctxBuildStartNs) / 1e9));
            firstFrame = true;
            deliveredFrames = skippedFrames = lastDeliveredNs = lastFrameNs = 0;
            outputReady = false;
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
            // (the client generator's far terrain replaces this source below: none of its GPU set-up, a device and a kernel
            // compiled on this thread, is made for nothing)
            boolean genFar = AtlasClientGen.ON && AtlasClientGen.far() && mc.hasSingleplayerServer()
                    && !"true".equalsIgnoreCase(System.getProperty("nirncraft.atlas.farTiles"));
            if (!genFar && source instanceof AtlasExactSource e && !"false".equals(System.getProperty("nirncraft.atlas.gpuHeight"))) {
                String why = e.enableGpu(lib);
                if (why != null) com.nirncraft.NirnCraft.LOGGER.info("atlas exact heights on the CPU: {}", why);
            }
            // whole far tiles in the generator's kernels (map, height, water on the device; checked per LOD against the
            // CPU path); -Dnirncraft.atlas.gpuTiles=false keeps the path above
            if (!genFar && source instanceof AtlasExactSource e && libPath != null && !"false".equals(System.getProperty("nirncraft.atlas.gpuTiles"))) {
                // (off the render thread: the program and the map take a while; tiles until then take the path above)
                net.minecraft.server.MinecraftServer sp = mc.getSingleplayerServer();
                Path lp = libPath;
                Thread t = new Thread(() -> {
                    String why = e.enableGpuTiles(sp, lp);
                    com.nirncraft.NirnCraft.LOGGER.info("atlas far tiles {}", why == null ? "on the GPU (the generator's kernels)" : "not on the GPU: " + why);
                }, "atlas far tiles setup");
                t.setDaemon(true);
                t.start();
            }
            if (source == null) source = AtlasFarField.defaultSource();
            // the far terrain from the client generator straight into the grid's coarse levels (GPU) is the far field:
            // no terrain tiles at all (no tile columns, no CPU World build of them: the old far field, drawn wherever a
            // coarse cell was not given yet, stale against the generator and never replaced); the lights and the
            // server's sections still go up. -Dnirncraft.atlas.farTiles=true brings the tiles back (the settlements'
            // far stand-ins ride on them)
            if (genFar) {
                if (source instanceof AtlasExactSource e) e.close();
                source = AtlasFarField.noTerrainSource();
                com.nirncraft.NirnCraft.LOGGER.info("atlas far terrain: the client generator's coarse levels only (no terrain tiles)");
            }
            if (source.usesBlockPalette()) ctx.setPalette(AtlasPalette.blocks());  // else atlas's preview palette
            int[] noise = AtlasPalette.cloudNoiseVolume();
            if (noise != null) ctx.setCloudNoise(noise);
            java.util.function.IntBinaryOperator ground = source instanceof AtlasExactSource e ? e::groundAt : null;
            AtlasMaterials mats = new AtlasMaterials();  // one id per block across buildings, landmarks and surfaces
            // (on by default: the near field's every block, its brick, its textures; -Dnirncraft.atlas.groups=false the
            // voxel path, measurements)
            groups = !"false".equalsIgnoreCase(System.getProperty("nirncraft.atlas.groups")) && ctx.hasShapes();
            materials = mats;
            ContextReaders readers = infoReaders;
            if (readers != null && readers.context() == ctx)
                infoReaders = new ContextReaders(ctx, mats, readers.gate());
            if (groups) mats.enableShapes();  // every material's real shape and face tiles; real blocks per state
            // the same ids on every launch (the self-baking cache's fingerprint and stored materials depend on them)
            mats.persistTo(mc.gameDirectory.toPath().resolve("atlas-cache").resolve("materials.log"));
            if (groups) com.nirncraft.NirnCraft.LOGGER.info("atlas: content-shaped groups on (nirncraft.atlas.groups)");
            if (source instanceof AtlasRefiningSource r) r.materials(mats);
            // single player: the world's own trees over the finest LODs (AtlasTrees), else the dressing's canopy
            // (abi 0.13: an older core ignores ATLAS_COLUMN_NO_CANOPY and would grow its canopy under them)
            AtlasTrees trees = source instanceof AtlasExactSource e && lib.abi() >= 0x000D00
                    ? AtlasTrees.forServer(mc.getSingleplayerServer(), e) : null;
            AtlasTrees.activeLod = trees != null ? AtlasTrees.APPROX_LOD : -1;  // before the first tile goes up
            // the far trees from the GPU decoration (the client generator's far chunks): no dressing canopy on any tile,
            // from the first (AtlasClientGen settles it once its decoration is attached)
            AtlasTileUpload.noDressingCanopy = AtlasClientGen.ON && mc.hasSingleplayerServer() && AtlasClientGen.STORE && AtlasClientGen.far()
                    && !"0".equals(System.getenv("ATLAS_FEAT_FAR"));
            farField = new AtlasFarField(ctx, source, () -> AtlasSettlements.create(ground, mats, trees), lods);
            farGenerationExited = new java.util.concurrent.CompletableFuture<>();
            AtlasClientSections.reset();  // a new context holds no real blocks: the loaded chunks go up again
            // the structures from the plans as stages of the client generator's batches (before its program compiles)
            if (AtlasClientGen.ON && mc.hasSingleplayerServer()) AtlasOverlayLive.register(libPath, mc.getSingleplayerServer());
            if (AtlasClientGen.ON && mc.hasSingleplayerServer()) {
                // on its own thread: its device's pipelines and the decoration's kernels are driver compiles too, and the
                // feature program a Java compile (the render thread never waits for them)
                net.minecraft.server.MinecraftServer sp = mc.getSingleplayerServer();
                Path lp = libPath;
                AtlasNative.Context c = ctx;
                int epoch = ++clientGenEpoch;
                var startFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                var startupFailed = new java.util.concurrent.atomic.AtomicBoolean();
                ContextReaders startupReaders = infoReaders;
                var startupFarExited = farGenerationExited;
                var predecessor = previousRelease;
                Thread t = new Thread(() -> {
                    try {
                        predecessor.join(); // old singleton stages are gone before a new owner may prepare
                        synchronized (CLIENT_GEN_LOCK) {
                            if (epoch != clientGenEpoch) return;
                        }
                        AtlasClientGen.start(sp, lp, c, mats, CLIENT_GEN_LOCK,
                                () -> epoch == clientGenEpoch, g -> clientGen = g, () -> {
                                    startupFailed.set(true);
                                    if (startupReaders != null) {
                                        startupReaders.gate().closeAdmission();
                                        boolean interrupted = startupReaders.gate().awaitDrained();
                                        if (interrupted) Thread.currentThread().interrupt();
                                    }
                                    startupFarExited.join();
                                });
                    } catch (Throwable e) {
                        startFailure.set(e);
                        com.nirncraft.NirnCraft.LOGGER.warn("atlas client generation did not start", e);
                    }
                }, "atlas client generator start");
                t.setDaemon(true);
                clientGenStart = t;
                clientGenStartFailure = startFailure;
                clientGenStartupFailed = startupFailed;
                t.start();
            }
            lightBudget = -1;
            startedFor = mc.level;
            startedModels = mc.getModelManager().getBlockStateModelSet();
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
                AtlasNative n = AtlasNative.load(p);
                libPath = p;
                return n;
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

    /** (the loading phase) atlas's context is up; atlas is off for this session. */
    static boolean started() {
        return ctx != null;
    }

    /** The current attachment has delivered an output; shared Vulkan additionally waits for its copy completion. */
    static boolean outputReady() {
        return ctx != null && outputReady;
    }

    static boolean copyReady(java.util.function.BooleanSupplier check) {
        try {
            boolean ready = check.getAsBoolean();
            if (!ready) outputReady = false;
            return ready;
        } catch (RuntimeException | Error failure) {
            outputReady = false;
            throw failure;
        }
    }

    static void outputDelivered() { outputReady = true; }

    /**
     * atlas_info's JSON as the info thread last read it (AtlasReady; the per-tick callers), or null without a context or before
     * the first read. Never waits for the core: the call itself walks books the generator's threads hold.
     */
    static String coreInfoJson() {
        return ctx == null ? null : AtlasInfo.latest();
    }

    /** The running context for AtlasInfo's thread. */
    static AtlasNative.Context infoContext() {
        return ctx;
    }

    static InfoLease acquireInfoContext() {
        ContextReaders readers = infoReaders;
        if (readers == null) return null;
        AtlasReleaseGate.Lease lease = readers.gate().enter();
        return lease == null ? null : new InfoLease(readers.context(), readers.materials(), lease);
    }

    static boolean failedForSession() {
        return failed;
    }

    /**
     * Atlas is off by the player's own choice or by configuration, not by a failure: nothing to wait for (the hold,
     * AtlasReadiness). {@code -Dnirncraft.atlas=false} or an unsupported graphics API (kind SETTING), or no shader pack
     * and no usable compositor (atlas never starts).
     */
    static boolean offByChoice() {
        return failed ? offKind == AtlasStatusLine.Off.SETTING : idleByConfig;
    }

    /** Why atlas is off for the session (the status line), or null. */
    static String offReason() {
        return failed ? lastStatus : null;
    }

    /** A failure that could be transient (memory, a lost device, a frame error): the hold's Retry can try atlas again. */
    static boolean canRetry() {
        return failed && (offKind == AtlasStatusLine.Off.MEMORY || offKind == AtlasStatusLine.Off.DEVICE_LOST || offKind == AtlasStatusLine.Off.ERROR);
    }

    /** The hold's Retry: atlas starts afresh after a transient failure (false when it cannot help). */
    static boolean retry() {
        if (!canRetry()) return false;
        failed = false;
        offKind = null;
        losses = 0;
        retryAt = 0;
        shrunk = null;
        lastStatus = null;
        return true;
    }

    /** Off for the rest of the session: one status line (WARN), a notice where the player can act on it. */
    private static void offForSession(String reason, AtlasStatusLine.Off kind, Throwable t) {
        failed = true;
        offKind = kind;
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
    /** Whether this frame's sky is the unified one (AtlasSky: every layer in one sky; NirnCraft's mesh sky steps aside). */
    public static boolean unifiedSky() {
        return AtlasSky.owns(Minecraft.getInstance());
    }

    /** Whether the sky's layers should go into the reflection cube this frame (atlas lights the near pass). */
    public static boolean wantsSkyCube() {
        return AtlasNearPass.litThisFrame() || AtlasNearPass.owns();
    }

    private static float preExposure = 1f;

    /** The exposure atlas's GL images were written with this frame (1: physical). */
    static float preExposure() {
        return preExposure;
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
    private static double glRenderMs, frameWallMs;
    private static boolean vkPassWarned;

    /**
     * (Minecraft's Vulkan backend) atlas_create_on_vulkan's arguments when atlas can run on Minecraft's own device: the
     * device-sharing mixin ran (AtlasVkShare), the core has the entry, the device is reachable; else null. Render thread.
     */
    private static long[] sharedDeviceArgs(AtlasNative l) {
        if (!AtlasVulkan.vulkan()) return null;
        AtlasVkShare.Shared s = AtlasVkShare.shared();
        if (s == null || !l.hasCreateOnVulkan()) return null;
        var d = AtlasVulkan.device();
        if (d == null) return null;
        int flags = (s.float64() ? 1 : 0) | (s.memoryBudget() ? 2 : 0) | (s.calibrated() ? 4 : 0);
        return new long[] {d.instance().vkInstance().address(), d.vkDevice().getPhysicalDevice().address(),
                d.vkDevice().address(), s.frameFamily(), s.frameIndex(), s.genFamily(), s.genIndex(), flags,
                d.graphicsQueue().queueFamilyIndex()};
    }
    // flight recorder zones (AtlasRecorder)
    private static final AtlasRecorder.Zone Z_INFO = AtlasRecorder.zone("atlas:info (ctx.info)"), Z_STATE_SECTIONS = AtlasRecorder.zone("atlas:state sections"),
            Z_STATE_EDITS = AtlasRecorder.zone("atlas:state edits"), Z_STATE_GEN = AtlasRecorder.zone("atlas:state clientGen");
    private static final AtlasRecorder.Zone Z_RENDER_FAR = AtlasRecorder.zone("atlas:renderFrame"),
            Z_GL_RENDER = AtlasRecorder.zone("atlas:ctx.glRender"), Z_WEATHER = AtlasRecorder.zone("atlas:feedWeather"),
            Z_AIR = AtlasRecorder.zone("atlas:air"), Z_ACTORS = AtlasRecorder.zone("atlas:feedActors"),
            Z_COMPOSITE = AtlasRecorder.zone("atlas:nearPass.composite"), Z_TICK_HOOK = AtlasRecorder.zone("atlas:tickHook"),
            Z_PROFILE = AtlasRecorder.zone("atlas:profileTick"), Z_LOADING = AtlasRecorder.zone("atlas:loadingPhase.tick");
    private static long lastFrameNs;

    private static final boolean PROFILE = Boolean.getBoolean("nirncraft.atlas.profile");
    private static int profileTicks;

    /**
     * (-Dnirncraft.atlas.profile=true, or the harness flags file) every 2 s one line of atlas-info into
     * .minecraft/atlas-profile.log, with the player's position: a passive profiler while someone plays (the autotest's
     * action queue holds the player, so it cannot be used for that).
     */
    private static void profileTick() {
        if (!PROFILE || ++profileTicks % 40 != 0) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null) {
            // Opt-in baseline before terrain/ATLAS startup. Default profiling keeps its usual player-only rows.
            String memory = AtlasVulkanMemory.profile(mc);
            if (!memory.isEmpty()) {
                String line = java.time.LocalTime.now() + " pos=unavailable kind=memory-baseline" + memory + "\n";
                java.nio.file.Path file = mc.gameDirectory.toPath().resolve("atlas-profile.log");
                PROFILE_WRITER.execute(() -> {
                    try {
                        java.nio.file.Files.writeString(file, line, java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.APPEND);
                    } catch (Exception ignored) { }
                });
            }
            return;
        }
        try {
            // (the render thread's part: the position, the frame line, a few reads of the client; the rest is composed and written
            // on a thread of its own: the core's report, the feeds' states and the file append took 0.2 to 4.2 s of a tick)
            String when = java.time.LocalTime.now() + String.format(java.util.Locale.ROOT, " pos=%.1f,%.1f,%.1f ",
                    mc.player.getX(), mc.player.getY(), mc.player.getZ()) + com.nirncraft.render.quality.QualityClient.profileLine() + " ";
            String frame = frameLine(ctx);
            // Vulkan device lifetime belongs to this thread; the writer receives only an immutable sample.
            String memory = AtlasVulkanMemory.profile(mc);
            java.nio.file.Path file = mc.gameDirectory.toPath().resolve("atlas-profile.log");
            PROFILE_WRITER.execute(() -> {
                try {
                    String line = when + debugInfo(frame, AtlasInfo.fresh(1000)).replaceAll("\\s+", " ") + memory + "\n";
                    java.nio.file.Files.writeString(file, line, java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                } catch (Exception e) {
                    // (a profiler never takes the game down)
                }
            });
        } catch (Exception e) {
            // (a profiler never takes the game down)
        }
    }

    private static final java.util.concurrent.ExecutorService PROFILE_WRITER = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
            java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(4), r -> {
                Thread t = new Thread(r, "nirncraft-atlas-profile");
                t.setDaemon(true);
                return t;
            }, new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());  // (bounded: a slow disk drops lines, never backs up ticks)

    /**
     * What atlas makes of one block state (the gallery's "describe" op): its model's quads and parts, whether the near
     * feed keeps it and under which id, its shape and its brick. One line.
     */
    public static String describeState(net.minecraft.world.level.block.state.BlockState s) {
        StringBuilder b = new StringBuilder(net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(s));
        try {
            var quads = AtlasShapes.quads(s);
            b.append(" render=").append(s.getRenderShape()).append(" quads=").append(quads.size()).append(" parts=").append(AtlasShapes.parts(s).size())
                    .append(" solid=").append(s.isSolidRender()).append(" brickable=").append(AtlasShapes.brickable(s, quads))
                    .append(" kept=").append(AtlasSections.kept(s)).append(" nearKept=").append(AtlasClientSections.nearKept(s))
                    .append(" nearId=").append(AtlasClientSections.nearIdOf(s)).append(" ownId=").append(net.minecraft.world.level.block.Block.getId(s));
            var sh = AtlasShapes.of(s, -1);
            b.append(" shape=").append(sh == null ? "none" : Long.toHexString(sh.occupancy()) + (sh.flags() != 0 ? "+cutout" : ""));
            var br = AtlasShapes.brick(s, -1);
            b.append(" brick=").append(br == null ? "none" : br.quads().size() + "q");
            b.append(" fluid=").append(!s.getFluidState().isEmpty()).append(" offset=").append(AtlasShapes.offsetWord(s));
        } catch (RuntimeException e) {
            b.append(" ERROR ").append(e);
        }
        return b.toString();
    }

    /**
     * Whether the core holds this block as the client has it: true for air and for a block atlas does not keep
     * (nearId 0), else the core's material there is not air. Render thread (the gallery's "await").
     */
    public static boolean coreHolds(int x, int y, int z) {
        var mc = Minecraft.getInstance();
        var s = mc.level == null ? null : mc.level.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
        if (s == null || s.isAir() || AtlasClientSections.nearIdOf(s) == 0) return true;
        var c = ctx;
        if (c == null) return true;
        short[] ids = c.debugSection(x >> 4, y >> 4, z >> 4);
        return ids == null || ids[(x & 15) + 16 * ((z & 15) + 16 * (y & 15))] != 0;
    }

    /**
     * One block as the three layers have it (the gallery's "probe" op): the client's state, the near id the feed would
     * give it, and the material the core holds there now.
     */
    public static String probeBlock(int x, int y, int z) {
        var mc = Minecraft.getInstance();
        var s = mc.level.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
        StringBuilder b = new StringBuilder(x + " " + y + " " + z + " client=" + net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(s));
        b.append(" nearId=").append(AtlasClientSections.nearIdOf(s));
        var c = ctx;
        if (c != null) {
            short[] ids = c.debugSection(x >> 4, y >> 4, z >> 4);
            int m = ids == null ? -1 : ids[(x & 15) + 16 * ((z & 15) + 16 * (y & 15))] & 0xFFFF;
            b.append(" core=").append(m).append(' ').append(materials != null && m > 0 ? materials.whoIs(m) : "");
            int[] dm = m > 0 ? c.debugMaterial(m) : null;
            if (dm != null) b.append(" tables{brick=").append(Integer.toHexString(dm[0])).append(" tex=").append(dm[1]).append(" cls=").append(dm[2]).append(" bricks=").append(dm[4]).append('/').append(dm[5]).append('}');
        }
        b.append(" chunk=").append(AtlasClientSections.chunkFlags(x >> 4, z >> 4));
        return b.toString();
    }

    /** The autotest's atlas-info: the core's own report (the grid's pool among it) and the feeds' queues. */
    public static String debugInfo() {
        return debugInfo(frameLine(ctx), null);
    }

    /** The "frame{...}" part of the profile line (the client's own numbers; the render thread). */
    private static String frameLine(AtlasNative.Context c) {
        var lvl = Minecraft.getInstance().level;
        return c != null ? String.format(java.util.Locale.ROOT, "frame{atlasMs=%.2f glRenderWallMs=%.2f frameWallMs=%.2f fps=%d unified=%b loadedChunks=%d delivered=%d skipped=%d outputAgeMs=%.2f t=%d} ",
                c.stats().frameMs(), glRenderMs, frameWallMs, Minecraft.getInstance().getFps(), AtlasNearPass.unified(),
                lvl != null ? lvl.getChunkSource().getLoadedChunksCount() : -1, deliveredFrames, skippedFrames,
                lastDeliveredNs == 0 ? -1 : (System.nanoTime() - lastDeliveredNs) * 1e-6, System.currentTimeMillis() % 1_000_000) : "";
    }

    /** The profile line from a frame part and the core's JSON (null: read it now, at most 0.5 s old). Any thread. */
    private static String debugInfo(String frame, String coreJson) {
        var c = ctx;
        long iz = AtlasRecorder.begin();
        String core = c == null ? "no context" : coreJson != null ? coreJson : AtlasInfo.fresh(500);
        if (core == null) core = "no context";
        AtlasRecorder.end(Z_INFO, iz, 1_000_000);
        long sz = AtlasRecorder.begin();
        String sections = AtlasClientSections.state();
        AtlasRecorder.end(Z_STATE_SECTIONS, sz, 1_000_000);
        sz = AtlasRecorder.begin();
        String edits = AtlasEditsClient.state();
        AtlasRecorder.end(Z_STATE_EDITS, sz, 1_000_000);
        sz = AtlasRecorder.begin();
        String gen = AtlasClientGen.state();
        AtlasRecorder.end(Z_STATE_GEN, sz, 1_000_000);
        return frame + "inside{" + AtlasNearPass.insideWhat + "} " + sections + " " + (materials != null ? materials.state() + " " : "") + edits + " " + gen + " " + AtlasReady.line(core) + " core=" + core + " " + AtlasRecorder.state();
    }

    private static int ablateSet = 0;

    /** (measurements: -Dnirncraft.atlas.ablate=<bits>, or set at run time by the gallery, as ATLAS_ABLATE) */
    private static void applyAblate(AtlasNative.Context ctx) {
        int ab = Integer.getInteger("nirncraft.atlas.ablate", 0);
        if (ab != ablateSet) {
            ablateSet = ab;
            try {
                ctx.debugAblate(ab);
            } catch (RuntimeException e) {
                com.nirncraft.NirnCraft.LOGGER.debug("atlas: ablate not set: {}", e.toString());
            }
        }
    }

    private static void jitter(AtlasNative.Context ctx, boolean on) {
        // (measurements: -Dnirncraft.atlas.ablate=<bits>, or set at run time by the gallery, as ATLAS_ABLATE)
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

    /** (tests) The context and the materials of the running renderer, or null. */
    static AtlasNative.Context testContext() {
        return ctx;
    }

    static AtlasMaterials testMaterials() {
        return materials;
    }

    /** Tears atlas down (start() brings it back with fresh tiles). The library stays loaded. */
    private static void stop() {
        AtlasMaterials m = materials;
        AtlasNative.Context c = ctx;
        // Request cancellation immediately, but keep all targets and context ownership until the last submitted copy
        // has completed. Polling is nonblocking and never submits or cancels an ambiguous splice.
        if (!retirementRequested) {
            retirementRequested = true;
            outputReady = false;
            farThisFrame = false;
            nearLitThisFrame = false;
            synchronized (CLIENT_GEN_LOCK) {
                clientGenEpoch++;
                if (clientGen != null) clientGen.requestStop();
            }
            if (farField != null) farField.requestStop();
            retiringReaders = infoReaders;
            infoReaders = null;
            if (retiringReaders != null) retiringReaders.gate().closeAdmission();
            if (c != null && AtlasVulkan.path() == AtlasVulkan.Path.VK_SHARED) {
                try {
                    retirementCopy = AtlasVulkan.waitCopyAsync(c, AtlasVulkan.Path.VK_SHARED);
                } catch (Throwable t) {
                    retirementCopy = java.util.concurrent.CompletableFuture.failedFuture(t);
                }
            } else retirementCopy = java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        if (retirementCopy == null || !retirementCopy.isDone()) return;
        retirementCopy.join(); // completed only; failures retain every resource and remain visible to shutdown
        if (c != null) AtlasVulkan.copyConfirmed(c);
        if (m != null) m.flushLog();  // (the material numbering of this session: the next launch replays it)
        if (targets != null) targets.close();
        targets = null;
        AtlasVulkan.Path retiredPath = AtlasVulkan.path();
        AtlasFarField oldFar = farField;
        var oldFarExited = farGenerationExited;
        Thread starting = clientGenStart;
        var startFailure = clientGenStartFailure;
        var pendingBuild = ctxBuild;
        ContextReaders readers = retiringReaders;
        AtlasClientGen oldGen;
        synchronized (CLIENT_GEN_LOCK) {
            oldGen = clientGen;
            clientGen = null;
        }
        ctxBuild = null;
        retiringReaders = null;
        if (c != null || oldFar != null || starting != null || pendingBuild != null) {
            ReleaseTicket ticket = new ReleaseTicket(c, retiredPath, starting, startFailure, oldGen, oldFar,
                    oldFarExited == null ? new java.util.concurrent.CompletableFuture<>() : oldFarExited, pendingBuild,
                    readers == null ? null : readers.gate());
            releases.add(ticket);
            previousRelease = java.util.concurrent.CompletableFuture.allOf(previousRelease, ticket.done);
            try { ticket.launch(); }
            catch (Throwable t) {
                ticket.failure = t;
                ticket.done.completeExceptionally(t);
                com.nirncraft.NirnCraft.LOGGER.error("atlas: could not start context release; ownership retained", t);
            }
        }
        clientGenStart = null;
        clientGenStartFailure = null;
        clientGenStartupFailed = null;
        retirementCopy = null;
        retirementRequested = false;
        farField = null;
        farGenerationExited = null;
        AtlasEditsClient.clear();  // the next world's changes come from its own server
        targets = null;
        ctx = null;
        infoLogged = false;
        startedFor = null;
        startedModels = null;
        firstFrame = true;
        deliveredFrames = skippedFrames = lastDeliveredNs = lastFrameNs = 0;
        outputReady = false;
    }

    static GpuFormat colorFormat() {
        return GpuFormat.RGBA16_FLOAT;
    }
}
