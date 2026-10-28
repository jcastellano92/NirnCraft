package com.nirncraft.render.quality;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.nirncraft.NirnCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;
import java.util.function.IntConsumer;

/**
 * Client glue of adaptive quality v2 ({@link QualityController}): measures every frame's GPU time with timestamps
 * written on the shared encoder outside render passes (RenderFrameEvent Pre / Post), asks the controller for the
 * frame's knobs, hands atlas its knobs (the atlas package reads {@link #knobs()}), writes the pack's knob texture
 * when its bytes change (RenderFrameEvent.Pre, before any pass, where the mod's other data textures upload too; never
 * inside a level pass), loads and saves the per-machine profile, and applies the host-load policy (ClientTickEvent.Post)
 * to atlas's column threads.
 *
 * <p>Properties: {@code -Dnirncraft.quality.adaptive=false} (everything at the profile's maximum, no texture),
 * {@code -Dnirncraft.quality.targetFps=N} (instead of the GPU class's default; still capped by the game's frame limit
 * and the refresh rate), {@code -Dnirncraft.quality.log=true} (a status line every 10 s).</p>
 *
 * <p>Wiring: {@link #init()} once on the client (AtlasClient.init calls it).</p>
 */
public final class QualityClient {
    private static final int SLOTS = 8;
    // visual tests render at the tier's fixed settings: a controller still tuning (or a learned profile) made the
    // first capture after a start differ from the later ones (far shadows, the sun map, atlas's scale)
    private static final boolean TESTING = Boolean.getBoolean("nirncraft.autotest");
    private static final boolean ADAPTIVE = !"false".equals(System.getProperty("nirncraft.quality.adaptive")) && !TESTING;
    private static final boolean LOG = Boolean.getBoolean("nirncraft.quality.log");
    private static final String FILE = "nirncraft-quality.properties";

    private static QualityController controller;
    private static String profileKey, gpuName;
    private static int profileW, profileH;
    private static final QualityTexture texture = new QualityTexture();

    // whole-frame GPU timestamps (two per slot), the knobs each slot's frame used
    private static GpuQueryPool pool;
    private static GpuDevice poolDevice;
    private static double period;
    private static final boolean[] pending = new boolean[SLOTS];
    private static final boolean[] slotLevel = new boolean[SLOTS];
    private static final double[][] slotKnobs = new double[SLOTS][];
    private static long frameIndex, lastPre;
    private static boolean measuring, levelThisFrame;
    private static double lastWallMs;
    private static boolean stopping, shutdownAttempted;
    private static Throwable shutdownFailure;

    // the knobs drawn this frame: the controller's, with the pack's replaced by what its texture holds
    private static final double[] frameKnobs = new double[Knob.COUNT];
    private static final double[] packApplied = new double[Knob.COUNT];
    private static final double[][] recent = new double[4][Knob.COUNT];
    private static double lastAtlasMs = -1;
    private static boolean atlasDrawing;

    private static long lastSave, lastLog, liveSince = -1;
    private static IntConsumer columnThreads = n -> {};
    private static volatile int workerRestMs;

    private QualityClient() {}

    public static void init() {
        java.util.Arrays.fill(frameKnobs, 1);
        java.util.Arrays.fill(packApplied, 1);
        NeoForge.EVENT_BUS.addListener(QualityClient::beginFrame);
        NeoForge.EVENT_BUS.addListener(QualityClient::endFrame);
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterOpaqueBlocks e) -> levelThisFrame = true);
        NeoForge.EVENT_BUS.addListener(QualityClient::tick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> save());
        NeoForge.EVENT_BUS.addListener((ClientStoppingEvent e) -> save());
    }

    /** This frame's knobs (Knob order; 1 everywhere before the first frame or when not adaptive). Render thread. */
    public static double[] knobs() {
        return frameKnobs;
    }

    /** 0 normal, 1 the far field at its lowest, 2 the far field off. */
    public static int fallbackTier() {
        return controller == null ? 0 : controller.fallbackTier();
    }

    /** atlas reports each frame whether it drew and its latest GPU time (ms; atlas's stats, about two frames late). */
    public static void atlasFrame(boolean drew, double gpuMs) {
        atlasDrawing = drew;
        if (controller == null || !drew || !(gpuMs > 0) || gpuMs == lastAtlasMs) return;
        lastAtlasMs = gpuMs;
        controller.observeAtlas(recent[(int) ((frameIndex - 2) & 3)], gpuMs);
    }

    private static double[] atlasLimits;  // scale ceiling, shadow ceiling, quarter clouds (0/1), initial tier

    /**
     * The machine's memory limits for atlas (AtlasMemory's plan, from the atlas package): knob ceilings for its
     * render scale and sun map, quarter-resolution clouds, and the fallback tier to start from. Kept for a controller
     * that starts later (the first world frame); render thread.
     */
    public static void setAtlasLimits(double scaleCeil, double shadowCeil, boolean quarterClouds, int initialTier) {
        atlasLimits = new double[] {scaleCeil, shadowCeil, quarterClouds ? 1 : 0, initialTier};
        if (controller != null) {
            try {
                controller.limitAtlas(scaleCeil, shadowCeil, quarterClouds, initialTier);
            } catch (Throwable t) {
                fail(t);
            }
        }
    }

    /** The GPU class the controller works with ("HIGH", "DECK", ...; "adaptive off" appended), or null before start. */
    public static String qualityName() {
        if (controller == null) return null;
        String n = controller.hardware().tier().name();
        if (!ADAPTIVE) n += ", adaptive off";
        else if (failed) n += ", adaptive failed";
        return n;
    }

    /** How atlas's exact far field sets its column threads (AtlasClient installs it). */
    public static void setColumnThreadSink(IntConsumer sink) {
        columnThreads = sink == null ? n -> {} : sink;
    }

    /** Extra rest (ms) for atlas's client far-field worker while this machine hosts a busy server. */
    public static int workerRestMs() {
        return workerRestMs;
    }

    public static String describe() {
        return controller == null ? "quality: not started" : controller.describe();
    }

    /** The controller's block for the profile line ("" before it starts). */
    public static String profileLine() {
        QualityController c = controller;
        return c == null ? "" : c.profileLine();
    }

    // ------------------------------------------------------------------ frames

    private static void beginFrame(RenderFrameEvent.Pre e) {
        if (stopping || failed) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            long now = System.nanoTime();
            if (lastPre != 0) lastWallMs = (now - lastPre) / 1e6;
            lastPre = now;
            levelThisFrame = false;
            if (mc.level == null) return;
            if (controller == null && !start(mc)) return;
            controller.context.megapixels = mc.getWindow().getWidth() * (double) mc.getWindow().getHeight() / 1e6;
            controller.context.packActive = irisPackInUse();
            controller.context.atlasActive = atlasDrawing && controller.fallbackTier() < 2;
            controller.setCovered(covered(mc));
            if ((frameIndex & 63) == 0) controller.setTargetFps(targetFps(mc, controller.hardware().tier()));
            double[] q = controller.frame(now);
            if (com.nirncraft.devtest.DevTestState.isActive(mc.player)) com.nirncraft.devtest.DevTestState.clampKnobs(q);
            // the pack's knobs: uploaded here, before any pass, when their bytes change (64 bytes)
            if (ADAPTIVE && texture.fill(q, controller.level()) && texture.upload())
                for (Knob k : Knob.ALL) if (k.owner == Knob.Owner.PACK) packApplied[k.ordinal()] = q[k.ordinal()];
            for (Knob k : Knob.ALL) frameKnobs[k.ordinal()] = k.owner == Knob.Owner.PACK ? packApplied[k.ordinal()] : q[k.ordinal()];
            System.arraycopy(frameKnobs, 0, recent[(int) (frameIndex & 3)], 0, Knob.COUNT);
            timestamps(now);
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void endFrame(RenderFrameEvent.Post e) {
        if (stopping || failed) return;
        try {
            if (!measuring) return;
            measuring = false;
            int slot = (int) (frameIndex & (SLOTS - 1));
            poolDevice.createCommandEncoder().writeTimestamp(pool, slot * 2 + 1);
            pending[slot] = true;
            slotLevel[slot] = levelThisFrame;
            frameIndex++;
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Reads the slot's previous result, then opens this frame's timestamp (outside passes: Pre). */
    private static void timestamps(long now) {
        if (stopping) return;
        GpuDevice device = RenderSystem.getDevice();
        if (pool == null || poolDevice != device) {
            closePool();
            poolDevice = device;
            period = device.getDeviceInfo().timestampPeriod();
            if (!(period > 0) || !Double.isFinite(period)) {
                pool = null;
            } else {
                pool = device.createTimestampQueryPool(SLOTS * 2);
            }
        }
        if (pool == null) {
            // no GPU timer on this device: the wall clock stands in (it includes the CPU's share of the frame)
            frameIndex++;
            if (lastWallMs > 0) controller.observeFrame(frameKnobs.clone(), lastWallMs, lastWallMs, now);
            return;
        }
        int slot = (int) (frameIndex & (SLOTS - 1));
        if (pending[slot]) {
            var v = pool.getValues(slot * 2, 2);
            pending[slot] = false;
            if (v[0].isPresent() && v[1].isPresent() && slotLevel[slot] && slotKnobs[slot] != null) {
                double ms = (v[1].getAsLong() - v[0].getAsLong()) * period / 1e6;
                controller.observeFrame(slotKnobs[slot], ms, lastWallMs, now);
            }
        }
        if (slotKnobs[slot] == null) slotKnobs[slot] = new double[Knob.COUNT];
        System.arraycopy(frameKnobs, 0, slotKnobs[slot], 0, Knob.COUNT);
        device.createCommandEncoder().writeTimestamp(pool, slot * 2);
        measuring = true;
    }

    private static void closePool() {
        if (pool != null) pool.close();
        pool = null;
        poolDevice = null;
        java.util.Arrays.fill(pending, false);
        measuring = false;
    }

    /** Render-thread terminal close; the host encoder retires this pool before its device is destroyed. */
    public static void closeAtShutdown() {
        stopping = true;
        if (!shutdownAttempted) {
            shutdownAttempted = true;
            try { closePool(); }
            catch (Throwable failure) { shutdownFailure = failure; }
        }
        if (shutdownFailure != null)
            throw new IllegalStateException("adaptive quality: terminal query close failed; owner retained", shutdownFailure);
    }

    // ------------------------------------------------------------------ start, profile

    private static boolean start(Minecraft mc) {
        if (stopping) return false;
        DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
        int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
        HardwareTier.Guess guess = HardwareTier.classify(info.name(), info.vendorName(), info.type() == DeviceType.INTEGRATED, w, h);
        controller = new QualityController(guess, targetFps(mc, guess.tier()), ADAPTIVE);
        gpuName = info.name();
        profileW = w;
        profileH = h;
        profileKey = QualityProfiles.key(info.name(), info.driverInfo(), w, h);
        QualityProfiles.Profile p = QualityProfiles.find(QualityProfiles.load(file(mc)), profileKey, gpuName, w, h);
        if (!TESTING) controller.restore(p);
        double[] lim = atlasLimits;
        if (lim != null) controller.limitAtlas(lim[0], lim[1], lim[2] != 0, (int) lim[3]);
        NirnCraft.LOGGER.info("adaptive quality: {} ({}, x{} of a GTX 1080's frame time), {} fps target, {}",
                info.name(), guess.tier(), guess.speed(), Math.round(controller.targetFps()),
                !ADAPTIVE ? "off (nirncraft.quality.adaptive=false)" : p != null ? "profile " + p.key() : "calibrating");
        return true;
    }

    private static Path file(Minecraft mc) {
        return mc.gameDirectory.toPath().resolve("config").resolve(FILE);
    }

    private static void save() {
        Minecraft mc = Minecraft.getInstance();
        if (controller == null || !ADAPTIVE || controller.phase() != QualityController.Phase.LIVE) return;
        try {
            QualityProfiles.save(file(mc), controller.snapshot(profileKey, gpuName, profileW, profileH, System.currentTimeMillis()));
            lastSave = System.nanoTime();
        } catch (java.io.IOException | RuntimeException e) {
            NirnCraft.LOGGER.warn("adaptive quality: profile not saved: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ tick

    private static void tick(ClientTickEvent.Post e) {
        if (stopping || controller == null || failed) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            hostLoad(mc);
            long now = System.nanoTime();
            if (controller.phase() == QualityController.Phase.LIVE) {
                if (liveSince < 0) liveSince = now;
                // the first save a minute into live play, then every five minutes
                if (now - liveSince > 60_000_000_000L && (lastSave == 0 || now - lastSave > 300_000_000_000L)) save();
            }
            if (LOG && now - lastLog > 10_000_000_000L) {
                lastLog = now;
                NirnCraft.LOGGER.info("{}", controller.describe());
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void hostLoad(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        boolean hosting = server != null && server.isPublished() && server.getPlayerCount() > 1;
        double mspt = server == null ? 0 : server.getAverageTickTimeNanos() / 1e6;
        int cores = Runtime.getRuntime().availableProcessors();
        columnThreads.accept(HostLoad.clientColumnThreads(cores, hosting, mspt));
        workerRestMs = HostLoad.clientWorkerRestMs(hosting, mspt);
        controller.setHostBusy(hosting && mspt > HostLoad.BUSY_MS);
    }

    // ------------------------------------------------------------------ helpers

    /** NirnCraft's own loading covers hide the world (calibration may show anything behind them). */
    private static boolean covered(Minecraft mc) {
        var screen = mc.gui.screen();
        if (screen == null) return false;
        String n = screen.getClass().getSimpleName();
        return n.equals("IntroLoadingScreen") || n.equals("LoadingCover");
    }

    /** The GPU class's target, or the property's, capped by the game's frame limit and the monitor's refresh rate. */
    static double targetFps(Minecraft mc, HardwareTier tier) {
        double fps = Integer.getInteger("nirncraft.quality.targetFps", tier.targetFps);
        try {
            int limit = mc.options.framerateLimit().get();
            if (limit > 0 && limit < 260) fps = Math.min(fps, limit);  // 260: "unlimited"
            Monitor m = mc.getWindow().findBestMonitor();
            if (m != null && m.currentMode() != null) {
                double hz = m.currentMode().getRefreshRate();
                if (hz >= 24) fps = Math.min(fps, hz);
            }
        } catch (RuntimeException ignored) {
            // no monitor information (a headless test): the class's target
        }
        return Math.max(24, fps);
    }

    private static Object irisApi;
    private static java.lang.reflect.Method irisInUse;
    private static boolean irisChecked;

    private static boolean irisPackInUse() {
        try {
            if (!irisChecked) {
                irisChecked = true;
                Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = c.getMethod("getInstance").invoke(null);
                irisInUse = c.getMethod("isShaderPackInUse");
            }
            return irisApi != null && Boolean.TRUE.equals(irisInUse.invoke(irisApi));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static boolean failed;

    /** Any error: adaptive quality stands down for the session (everything at the maximum; the far field as before). */
    private static void fail(Throwable t) {
        if (failed) return;
        failed = true;
        java.util.Arrays.fill(frameKnobs, 1);
        NirnCraft.LOGGER.warn("adaptive quality off: {}", t.toString());
        try {
            closePool();
        } catch (RuntimeException ignored) {
            // best effort
        }
    }
}
