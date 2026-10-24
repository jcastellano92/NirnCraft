package com.nirncraft.atlas;

import com.mojang.brigadier.Command;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The Java side of the flight recorder (native/src/flight_recorder.hpp): zones of the client tick, the render-thread
 * atlas frame, the staging and ingest calls and the generator's loop, plus the JVM's GC pauses, recorded into the same
 * per-thread rings as the native zones through one tiny downcall. Any frame over the spike threshold (50 ms) dumps the
 * 30 s around it to {@code .minecraft/atlas-trace/<time>/} (trace.json for Perfetto / chrome://tracing, summary.txt);
 * {@code /nirncraft-atlas-trace} dumps on demand.
 *
 * <p>{@code -Dnirncraft.atlas.recorder=true|false} (default: on in a dev instance, i.e. when the instance has an
 * atlas-dev folder or any of the unified / clientGen / profile flags, or outside a production install),
 * {@code .spikeMs} (50), {@code .windowSec} (30), {@code .gapSec} (10: at most one dump per this many seconds).</p>
 *
 * <p>A zone is {@code long t = AtlasRecorder.begin(); ... AtlasRecorder.end(ZONE, t);} (about 60 ns; a disabled
 * recorder is one volatile read). Zones are static {@link Zone} constants; their native ids resolve on first use.</p>
 */
public final class AtlasRecorder {
    private AtlasRecorder() {}

    /** A named zone; the native id is looked up on first use. */
    public static final class Zone {
        final String name;
        volatile int id = -2;

        Zone(String name) {
            this.name = name;
        }
    }

    public static Zone zone(String name) {
        return new Zone(name);
    }

    // the zones of this class
    static final Zone CLIENT_TICK = zone("mc:clientTick");

    private static volatile boolean on;
    private static MethodHandle zoneH, zoneIdH, trackIdH, nameH, nowH, configureH, dumpH, statusH, countH, lastH;
    private static long offset;  // native clock - System.nanoTime()
    private static int gcTrack, gcConcurrentTrack;
    private static long tickT0;
    private static long seenDumps;
    private static volatile Path traceDir;

    public static boolean on() {
        return on;
    }

    /** Starts a zone: the time to hand to {@link #end}, or 0 when the recorder is off. */
    public static long begin() {
        return on ? System.nanoTime() : 0L;
    }

    public static void end(Zone z, long t0) {
        if (t0 != 0L) record(z, t0, 0L, 0, 0);
    }

    /** Ends a zone, recorded only when it ran at least minNanos. */
    public static void end(Zone z, long t0, long minNanos) {
        if (t0 != 0L) record(z, t0, minNanos, 0, 0);
    }

    /** Ends a zone with a number the trace shows (a count, bytes >> 10 ...). */
    public static void end(Zone z, long t0, long minNanos, int arg) {
        if (t0 != 0L) record(z, t0, minNanos, arg, 0);
    }

    private static void record(Zone z, long t0, long minNanos, int arg, int track) {
        if (!on) return;
        long t1 = System.nanoTime();
        if (t1 - t0 < minNanos) return;
        int id = z.id;
        if (id == -2) z.id = id = idOf(z.name);
        if (id < 0) return;
        try {
            int unnamed = (int) zoneH.invokeExact(id, t0 + offset, t1 + offset, arg, track);
            if (unnamed == 1) nameThread();
        } catch (Throwable t) {
            on = false;  // (a profiler never takes the game down)
        }
    }

    private static int idOf(String name) {
        try (Arena a = Arena.ofConfined()) {
            return (int) zoneIdH.invokeExact(a.allocateFrom(name));
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void nameThread() {
        try (Arena a = Arena.ofConfined()) {
            nameH.invokeExact(a.allocateFrom(Thread.currentThread().getName()));
        } catch (Throwable t) {
            // unnamed lane
        }
    }

    // ------------------------------------------------------------------------------------------------ start

    private static boolean wanted(Path gameDir) {
        String p = System.getProperty("nirncraft.atlas.recorder");
        if (p != null && !p.isBlank()) return Boolean.parseBoolean(p.trim());
        if (Boolean.getBoolean("nirncraft.atlas.unified") || Boolean.getBoolean("nirncraft.atlas.clientGen")
                || Boolean.getBoolean("nirncraft.atlas.profile") || Files.isDirectory(gameDir.resolve("atlas-dev")))
            return true;
        try {
            return !net.neoforged.fml.loading.FMLLoader.getCurrent().isProduction();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Binds the recorder of the loaded core and switches it on when wanted. Idempotent; never throws. */
    static void start(Path library, Path gameDir) {
        if (zoneH != null) return;
        try {
            if (!wanted(gameDir)) return;
            SymbolLookup lib = SymbolLookup.libraryLookup(library, Arena.global());
            if (lib.find("atlas_fr_configure").isEmpty()) {
                com.nirncraft.NirnCraft.LOGGER.info("atlas recorder: the loaded core has no flight recorder");
                return;
            }
            Linker l = Linker.nativeLinker();
            configureH = bind(l, lib, "atlas_fr_configure", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE));
            nowH = bind(l, lib, "atlas_fr_now", FunctionDescriptor.of(JAVA_LONG), Linker.Option.critical(false));
            zoneIdH = bind(l, lib, "atlas_fr_zone_id", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            trackIdH = bind(l, lib, "atlas_fr_track_id", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            zoneH = bind(l, lib, "atlas_fr_zone", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT),
                    Linker.Option.critical(false));
            nameH = bind(l, lib, "atlas_fr_thread_name", FunctionDescriptor.ofVoid(ADDRESS));
            dumpH = bind(l, lib, "atlas_fr_dump", FunctionDescriptor.of(ADDRESS, ADDRESS));
            statusH = bind(l, lib, "atlas_fr_status", FunctionDescriptor.of(ADDRESS));
            countH = bind(l, lib, "atlas_fr_dump_count", FunctionDescriptor.of(JAVA_LONG));
            lastH = bind(l, lib, "atlas_fr_last_dump", FunctionDescriptor.of(ADDRESS));
            Path dir = gameDir.resolve("atlas-trace");
            traceDir = dir;
            double spike = Double.parseDouble(System.getProperty("nirncraft.atlas.recorder.spikeMs", "50"));
            double window = Double.parseDouble(System.getProperty("nirncraft.atlas.recorder.windowSec", "30"));
            double gap = Double.parseDouble(System.getProperty("nirncraft.atlas.recorder.gapSec", "10"));
            // the native clock against nanoTime: the closest of a few pairs (the same clock on Windows, offset ~0)
            long best = Long.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                long a = System.nanoTime();
                long n = (long) nowH.invokeExact();
                long b = System.nanoTime();
                if (b - a < best) {
                    best = b - a;
                    offset = n - (a + (b - a) / 2);
                }
            }
            try (Arena a = Arena.ofConfined()) {
                configureH.invokeExact(1, a.allocateFrom(dir.toAbsolutePath().toString()), spike, window, gap);
                gcTrack = (int) trackIdH.invokeExact(a.allocateFrom("GC"));
                gcConcurrentTrack = (int) trackIdH.invokeExact(a.allocateFrom("GC concurrent"));
            }
            on = true;
            installGcListeners();
            long created = (long) countH.invokeExact();
            seenDumps = created;
            com.nirncraft.NirnCraft.LOGGER.info("atlas recorder: on, frames over {} ms dump {} s to {} (at most one per {} s); /nirncraft-atlas-trace dumps now",
                    spike, window, dir, gap);
        } catch (Throwable t) {
            on = false;
            com.nirncraft.NirnCraft.LOGGER.warn("atlas recorder: not started ({})", t.toString());
        }
    }

    private static MethodHandle bind(Linker l, SymbolLookup lib, String name, FunctionDescriptor fd, Linker.Option... opts) {
        return l.downcallHandle(lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError("atlas_core lacks " + name)), fd, opts);
    }

    /** Registers the client tick zone and the dump command (once, from AtlasClient.init). */
    static void init() {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (ClientTickEvent.Pre e) -> tickT0 = begin());
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ClientTickEvent.Post e) -> {
            end(CLIENT_TICK, tickT0);
            tickT0 = 0L;
            tick();
        });
        NeoForge.EVENT_BUS.addListener(AtlasRecorder::onCommands);
    }

    // ------------------------------------------------------------------------------------------------ GC pauses

    private static void installGcListeners() {
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!(bean instanceof NotificationEmitter emitter)) continue;
            String bn = bean.getName();
            boolean concurrent = bn.contains("Cycles") || bn.contains("Concurrent");
            emitter.addNotificationListener((notification, handback) -> {
                if (!on || !com.sun.management.GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType()))
                    return;
                try {
                    var info = com.sun.management.GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
                    var gc = info.getGcInfo();
                    // gc times are ms since JVM start: on our clock through the wall clock at the notification
                    long nowMs = System.currentTimeMillis();
                    long endEpoch = ManagementFactory.getRuntimeMXBean().getStartTime() + gc.getEndTime();
                    long nowNative = System.nanoTime() + offset;
                    long t1 = nowNative - Math.max(0L, nowMs - endEpoch) * 1_000_000L;
                    long t0 = t1 - Math.max(1L, gc.getDuration()) * 1_000_000L;
                    Zone z = gcZone(info.getGcName() + ": " + info.getGcAction() + " (" + info.getGcCause() + ")");
                    int id = z.id == -2 ? (z.id = idOf(z.name)) : z.id;
                    if (id >= 0) {
                        int unnamed = (int) zoneH.invokeExact(id, t0, t1, (int) Math.min(gc.getDuration(), Integer.MAX_VALUE),
                                concurrent ? gcConcurrentTrack : gcTrack);
                        if (unnamed == 1) nameThread();
                    }
                } catch (Throwable t) {
                    // (a GC record lost)
                }
            }, null, null);
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Zone> GC_ZONES = new java.util.concurrent.ConcurrentHashMap<>();

    private static Zone gcZone(String name) {
        return GC_ZONES.computeIfAbsent(name, Zone::new);
    }

    // ------------------------------------------------------------------------------------------------ dumps

    /** Every second: log (and tell the player) a dump the native watcher wrote since last time. */
    private static int tickCount;

    private static void tick() {
        if (!on || ++tickCount % 20 != 0) return;
        try {
            long n = (long) countH.invokeExact();
            if (n == seenDumps) return;
            seenDumps = n;
            String dir = ((MemorySegment) lastH.invokeExact()).reinterpret(4096).getString(0);
            String verdict = verdictOf(dir);
            com.nirncraft.NirnCraft.LOGGER.warn("atlas recorder: frame spike dumped to {}: {}", dir, verdict);
            var mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal("atlas trace: " + dir + " - " + verdict));
        } catch (Throwable t) {
            // ignore
        }
    }

    private static String verdictOf(String dir) {
        try {
            for (String line : Files.readAllLines(Path.of(dir).resolve("summary.txt"))) {
                if (line.startsWith("verdict:")) return line.substring(8).trim();
            }
        } catch (Exception e) {
            // no summary
        }
        return "(no verdict)";
    }

    /** One line for atlas-info / the profile log. */
    static String state() {
        if (!on) return "recorder{off}";
        try {
            return "recorder" + ((MemorySegment) statusH.invokeExact()).reinterpret(4096).getString(0);
        } catch (Throwable t) {
            return "recorder{error}";
        }
    }

    /** A dump of the last window now: the directory, or "error: ...". */
    public static String dumpNow(String reason) {
        if (!on) return "error: the recorder is off (-Dnirncraft.atlas.recorder=true, and the atlas must have started)";
        try (Arena a = Arena.ofConfined()) {
            String dir = ((MemorySegment) dumpH.invokeExact(a.allocateFrom(reason))).reinterpret(4096).getString(0);
            seenDumps = (long) countH.invokeExact();  // (a manual dump is not announced again by tick)
            return dir;
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    static void onCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("nirncraft-atlas-trace").executes(ctx -> {
            String dir = dumpNow("manual");
            String msg = dir.startsWith("error") ? "atlas trace: " + dir
                    : "atlas trace: " + dir + " (summary.txt, trace.json) - " + verdictOf(dir);
            ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
            return Command.SINGLE_SUCCESS;
        }).then(Commands.literal("status").executes(ctx -> {
            ctx.getSource().sendSuccess(() -> Component.literal(state()), false);
            return Command.SINGLE_SUCCESS;
        })));
    }
}
