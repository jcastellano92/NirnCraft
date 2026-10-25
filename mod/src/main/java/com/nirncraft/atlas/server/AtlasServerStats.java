package com.nirncraft.atlas.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the integrated server costs while a scripted route runs (docs/ATLAS-GPU-LOOP.md section 4's gate): the length of
 * every server tick (p50, p99, max), the CPU the world-generation workers use, the chunks it made, and, from the
 * AtlasChunkGenerator, how many chunks came from the GPU and how many Java made. {@link #begin} starts a recording (the
 * route's start), {@link #end} returns it as JSON for the route report. Server thread only, except the counters.
 *
 * <p>"Worker CPU" is the CPU time of the {@code Worker-*} threads, the pool Minecraft runs its chunk generation tasks
 * on (terrain, biomes, structures, features), summed per second; the server thread's own CPU is kept apart.</p>
 */
public final class AtlasServerStats {
    private AtlasServerStats() {}

    /** Chunks the AtlasChunkGenerator took from the GPU, made in Java (the fallback), and Java's fillFromNoise calls. */
    public static final AtomicLong GPU_CHUNKS = new AtomicLong(), JAVA_CHUNKS = new AtomicLong(), JAVA_TERRAIN_CALLS = new AtomicLong(),
            JAVA_DECORATION_CALLS = new AtomicLong(), GPU_WAIT_NS = new AtomicLong(), GPU_TIMEOUTS = new AtomicLong();
    private static final AtomicLong NEW_CHUNKS = new AtomicLong();

    private static final ThreadMXBean TMX = ManagementFactory.getThreadMXBean();
    private static volatile boolean recording;
    private static long tickStart, t0, lastSecNs, newAtBegin;
    private static float[] ticks = new float[4096];
    private static int tickCount;
    private static final JsonArray ROWS = new JsonArray();
    private static long secTicks, secNew;
    private static double secTickMax;
    private static long prevWorker, prevServer, prevProc;
    private static JsonObject begun = new JsonObject();

    public static void init() {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre e) -> {
            if (recording) tickStart = System.nanoTime();
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            if (recording) guarded(() -> tick(e.getServer()));
        });
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Load e) -> {
            if (e.getLevel().isClientSide() || !e.isNewChunk()) return;
            NEW_CHUNKS.incrementAndGet();
        });
    }

    private static void guarded(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // (a statistic never costs a tick)
        }
    }

    /** Starts a recording (any thread; the next server tick is its first). */
    public static synchronized void begin() {
        tickCount = 0;
        ROWS.asList().clear();
        secTicks = secNew = 0;
        secTickMax = 0;
        t0 = lastSecNs = System.nanoTime();
        newAtBegin = NEW_CHUNKS.get();
        long[] c = cpu();
        prevWorker = c[0];
        prevServer = c[1];
        prevProc = c[2];
        begun = new JsonObject();
        begun.addProperty("gpuChunks", GPU_CHUNKS.get());
        begun.addProperty("javaChunks", JAVA_CHUNKS.get());
        begun.addProperty("javaTerrainCalls", JAVA_TERRAIN_CALLS.get());
        begun.addProperty("javaDecorationCalls", JAVA_DECORATION_CALLS.get());
        begun.addProperty("gpuWaitMs", GPU_WAIT_NS.get() / 1e6);
        begun.addProperty("gpuTimeouts", GPU_TIMEOUTS.get());
        recording = true;
    }

    private static synchronized void tick(MinecraftServer server) {
        double ms = (System.nanoTime() - tickStart) / 1e6;
        if (tickCount == ticks.length) ticks = Arrays.copyOf(ticks, tickCount * 2);
        ticks[tickCount++] = (float) ms;
        secTicks++;
        secTickMax = Math.max(secTickMax, ms);
        long now = System.nanoTime();
        if (now - lastSecNs >= 1_000_000_000L) {
            long[] c = cpu();
            JsonObject r = new JsonObject();
            r.addProperty("sec", ROWS.size());
            r.addProperty("ticks", secTicks);
            r.addProperty("tickMaxMs", secTickMax);
            r.addProperty("workerCpuMs", (c[0] - prevWorker) / 1e6);
            r.addProperty("serverCpuMs", (c[1] - prevServer) / 1e6);
            r.addProperty("processCpuMs", (c[2] - prevProc) / 1e6);
            long nc = NEW_CHUNKS.get();
            r.addProperty("newChunks", nc - newAtBegin - secNew);
            secNew = nc - newAtBegin;
            int loaded = 0;
            for (ServerLevel l : server.getAllLevels()) loaded += l.getChunkSource().getLoadedChunksCount();
            r.addProperty("loadedChunks", loaded);
            r.addProperty("gpuChunks", GPU_CHUNKS.get());
            r.addProperty("javaChunks", JAVA_CHUNKS.get());
            ROWS.add(r);
            prevWorker = c[0];
            prevServer = c[1];
            prevProc = c[2];
            lastSecNs = now;
            secTicks = 0;
            secTickMax = 0;
        }
    }

    /** {worker threads' CPU ns, server thread CPU ns, process CPU ns}. */
    private static long[] cpu() {
        long worker = 0, serverT = 0;
        try {
            long[] ids = TMX.getAllThreadIds();
            ThreadInfo[] infos = TMX.getThreadInfo(ids);
            for (int i = 0; i < ids.length; i++) {
                if (infos[i] == null) continue;
                String n = infos[i].getThreadName();
                long t = TMX.getThreadCpuTime(ids[i]);
                if (t < 0) continue;
                if (n.startsWith("Worker-")) worker += t;
                else if (n.equals("Server thread")) serverT += t;
            }
        } catch (RuntimeException ignored) {
            // (no thread CPU on this JVM)
        }
        long proc = ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os ? os.getProcessCpuTime() : 0;
        return new long[]{worker, serverT, proc};
    }

    /** Ends the recording and returns it: tick percentiles, per-second rows and the totals. */
    public static synchronized JsonObject end(MinecraftServer server) {
        recording = false;
        JsonObject o = new JsonObject();
        float[] a = Arrays.copyOf(ticks, tickCount);
        Arrays.sort(a);
        JsonObject t = new JsonObject();
        t.addProperty("n", a.length);
        if (a.length > 0) {
            double sum = 0;
            int over = 0, over50 = 0;
            for (float f : a) {
                sum += f;
                if (f > 50) over50++;
                if (f > 25) over++;
            }
            t.addProperty("mean", sum / a.length);
            t.addProperty("p50", a[(a.length - 1) / 2]);
            t.addProperty("p95", a[(int) Math.ceil(a.length * .95) - 1]);
            t.addProperty("p99", a[(int) Math.ceil(a.length * .99) - 1]);
            t.addProperty("max", a[a.length - 1]);
            t.addProperty("over25", over);
            t.addProperty("over50", over50);
        }
        o.add("tick", t);
        o.add("seconds", ROWS.deepCopy());
        double worker = 0, serverCpu = 0, proc = 0;
        for (var e : ROWS) {
            JsonObject r = e.getAsJsonObject();
            worker += r.get("workerCpuMs").getAsDouble();
            serverCpu += r.get("serverCpuMs").getAsDouble();
            proc += r.get("processCpuMs").getAsDouble();
        }
        double secs = Math.max(1e-3, (System.nanoTime() - t0) / 1e9);
        o.addProperty("wallSeconds", secs);
        o.addProperty("workerCpuMsPerSec", worker / Math.max(1, ROWS.size()));
        o.addProperty("serverThreadCpuMsPerSec", serverCpu / Math.max(1, ROWS.size()));
        o.addProperty("processCpuMsPerSec", proc / Math.max(1, ROWS.size()));
        o.addProperty("newChunks", NEW_CHUNKS.get() - newAtBegin);
        o.addProperty("gpuChunks", GPU_CHUNKS.get() - begun.get("gpuChunks").getAsLong());
        o.addProperty("javaChunks", JAVA_CHUNKS.get() - begun.get("javaChunks").getAsLong());
        o.addProperty("javaTerrainCalls", JAVA_TERRAIN_CALLS.get() - begun.get("javaTerrainCalls").getAsLong());
        o.addProperty("javaDecorationCalls", JAVA_DECORATION_CALLS.get() - begun.get("javaDecorationCalls").getAsLong());
        o.addProperty("gpuWaitMs", GPU_WAIT_NS.get() / 1e6 - begun.get("gpuWaitMs").getAsDouble());
        o.addProperty("gpuTimeouts", GPU_TIMEOUTS.get() - begun.get("gpuTimeouts").getAsLong());
        if (server != null) {
            o.addProperty("viewDistance", AtlasServerCap.viewDistance(server));
            o.addProperty("playerListViewDistance", server.getPlayerList().getViewDistance());
            o.addProperty("simulationDistance", server.getPlayerList().getSimulationDistance());
            int loaded = 0;
            for (ServerLevel l : server.getAllLevels()) loaded += l.getChunkSource().getLoadedChunksCount();
            o.addProperty("loadedChunksAtEnd", loaded);
            o.addProperty("capped", AtlasServerMode.capView());
            o.addProperty("generator", server.overworld().getChunkSource().getGenerator().getClass().getSimpleName());
        }
        return o;
    }
}
