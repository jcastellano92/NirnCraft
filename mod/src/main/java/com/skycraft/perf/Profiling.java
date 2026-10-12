package com.skycraft.perf;

import com.skycraft.Skycraft;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import net.minecraft.util.profiling.jfr.JvmProfiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-process Java Flight Recorder session for the worldgen benchmark: CPU samples of every thread, allocation
 * samples, and Minecraft's own {@code minecraft.ChunkGeneration} events (one per chunk status, with its duration).
 * {@link #analyse} turns the recording into a per-stage CPU breakdown of the worldgen workers and the top Skycraft
 * hot spots.
 *
 * <p>Stage attribution walks each sample's stack from the root and takes the first generator entry point it meets
 * (structure starts call the noise generator for heights, but count as structure starts). JFR keeps only the top 64
 * frames by default, which cuts the root off deep density-function stacks: launch the JVM with
 * {@code -XX:FlightRecorderOptions:stackdepth=1024} (the benchmark scripts set it through {@code JAVA_TOOL_OPTIONS}).
 * Truncated samples are reported as {@code (truncated)}. Method names are matched in Mojang mappings, so the stage
 * split works in the dev environment; production (SRG) names fall into {@code other}.</p>
 */
public final class Profiling {
    private Profiling() {}

    /** Whether the JFR API can be used in this JVM. */
    public static boolean available() {
        try {
            return JvmProfiler.INSTANCE.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Starts a recording (10 ms CPU samples, throttled allocation samples, chunk generation events). */
    public static Recording start() {
        // touching the profiler registers Minecraft's custom JFR events, so the recording can enable them
        JvmProfiler.INSTANCE.isAvailable();
        Recording r = new Recording();
        r.setName("skycraft-perf");
        r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
        r.enable("jdk.ObjectAllocationSample").with("throttle", "300/s");
        r.enable("jdk.GarbageCollection");
        r.enable("minecraft.ChunkGeneration").withThreshold(Duration.ZERO);
        r.setToDisk(true);
        r.start();
        return r;
    }

    /** Stops and writes the recording; returns the file (or null). */
    public static Path stop(Recording r, Path dir, String name) {
        try {
            r.stop();
            Files.createDirectories(dir);
            Path out = dir.resolve(name + ".jfr");
            r.dump(out);
            r.close();
            return out;
        } catch (IOException | RuntimeException e) {
            Skycraft.LOGGER.warn("[perf] could not write the flight recording", e);
            return null;
        }
    }

    // ----------------------------------------------------------------------------------------------- analysis

    /** Generator entry points, matched root first: class simple name + method name -> stage. */
    private static final String[][] STAGES = {
            {"ChunkGenerator", "createStructures", "structure_starts"},
            {"ChunkGenerator", "createReferences", "structure_refs"},
            {"NoiseBasedChunkGenerator", "doCreateBiomes", "biomes"},
            {"NoiseBasedChunkGenerator", "createBiomes", "biomes"},
            {"NoiseBasedChunkGenerator", "doFill", "noise"},
            {"NoiseBasedChunkGenerator", "fillFromNoise", "noise"},
            {"NoiseBasedChunkGenerator", "buildSurface", "surface"},
            {"NoiseBasedChunkGenerator", "applyCarvers", "carvers"},
            {"StructureStart", "placeInChunk", "features:structure_pieces"},
            {"ChunkGenerator", "applyBiomeDecoration", "features:decoration"},
            {"ThreadedLevelLightEngine", "", "light"},
            {"LightEngine", "", "light"},
            {"ChunkMap", "protoChunkToFullChunk", "full"},
            {"LevelChunk", "postProcessGeneration", "full"},
            {"ChunkSerializer", "", "io"},
            {"RegionFile", "", "io"},
            {"IOWorker", "", "io"},
    };

    /** The result of {@link #analyse}. */
    public static final class Report {
        public long workerSamples, serverSamples, truncated;
        public final Map<String, Long> stageSamples = new LinkedHashMap<>();
        /** Status -> {count, total nanos} from minecraft.ChunkGeneration events. */
        public final Map<String, long[]> statusEvents = new LinkedHashMap<>();
        public final Map<String, Long> selfTop = new HashMap<>();
        public final Map<String, Map<String, Long>> stageSelf = new HashMap<>();
        public final Map<String, Long> skycraftInclusive = new HashMap<>();
        public final Map<String, Long> skycraftEntry = new HashMap<>();
        public final Map<String, Long> skycraftServer = new HashMap<>();
        public final Map<String, Long> allocSkycraft = new HashMap<>();
        public long allocTotal;
        public long gcCount, gcPauseNanos;
        public final Set<String> workerThreads = new HashSet<>();
    }

    public static Report analyse(Path file) throws IOException {
        Report rep = new Report();
        for (String[] s : STAGES) rep.stageSamples.putIfAbsent(s[2], 0L);
        rep.stageSamples.put("other", 0L);
        rep.stageSamples.put("(truncated)", 0L);
        try (RecordingFile rf = new RecordingFile(file)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                String type = e.getEventType().getName();
                switch (type) {
                    case "jdk.ExecutionSample" -> sample(rep, e);
                    case "jdk.ObjectAllocationSample" -> alloc(rep, e);
                    case "jdk.GarbageCollection" -> {
                        rep.gcCount++;
                        rep.gcPauseNanos += e.getDuration("sumOfPauses").toNanos();
                    }
                    case "minecraft.ChunkGeneration" -> {
                        String status = e.hasField("status") ? e.getString("status") : "?";
                        long[] v = rep.statusEvents.computeIfAbsent(status, k -> new long[2]);
                        v[0]++;
                        v[1] += e.getDuration().toNanos();
                    }
                    default -> {
                    }
                }
            }
        }
        return rep;
    }

    private static boolean isWorker(String thread) {
        return thread != null && thread.startsWith("Worker-");
    }

    private static void sample(Report rep, RecordedEvent e) {
        RecordedThread t = e.getThread("sampledThread");
        String name = t == null ? null : t.getJavaName();
        RecordedStackTrace st = e.getStackTrace();
        if (st == null) return;
        List<RecordedFrame> frames = st.getFrames();
        if (frames.isEmpty()) return;
        boolean worker = isWorker(name);
        if (worker) {
            rep.workerSamples++;
            rep.workerThreads.add(name);
            String stage = null;
            int at = frames.size() - 1;
            for (; at >= 0 && stage == null; at--) stage = stageOf(frames.get(at).getMethod());
            // features: split structure pieces from decoration (pieces are placed inside applyBiomeDecoration)
            if ("features:decoration".equals(stage)) {
                for (; at >= 0; at--) {
                    if ("features:structure_pieces".equals(stageOf(frames.get(at).getMethod()))) {
                        stage = "features:structure_pieces";
                        break;
                    }
                }
            }
            if (stage == null) stage = st.isTruncated() ? "(truncated)" : "other";
            if (st.isTruncated()) rep.truncated++;
            rep.stageSamples.merge(stage, 1L, Long::sum);
            String self = name(frames.get(0).getMethod());
            rep.selfTop.merge(self, 1L, Long::sum);
            // per stage: the self frame with its first two callers
            StringBuilder chain = new StringBuilder(self);
            for (int i = 1; i < Math.min(3, frames.size()); i++) chain.append(" < ").append(name(frames.get(i).getMethod()));
            rep.stageSelf.computeIfAbsent(stage, k -> new HashMap<>()).merge(chain.toString(), 1L, Long::sum);
            // Skycraft: inclusive time per method, and the outermost Skycraft frame (the entry point vanilla called)
            Set<String> seen = new HashSet<>();
            String entry = null;
            for (int i = frames.size() - 1; i >= 0; i--) {
                RecordedMethod m = frames.get(i).getMethod();
                if (!isSkycraft(m)) continue;
                String n = name(m);
                if (entry == null) entry = n;
                if (seen.add(n)) rep.skycraftInclusive.merge(n, 1L, Long::sum);
            }
            if (entry != null) rep.skycraftEntry.merge(entry, 1L, Long::sum);
        } else if ("Server thread".equals(name)) {
            rep.serverSamples++;
            for (int i = frames.size() - 1; i >= 0; i--) {
                RecordedMethod m = frames.get(i).getMethod();
                if (isSkycraft(m)) {
                    rep.skycraftServer.merge(name(m), 1L, Long::sum);
                    break;
                }
            }
        }
    }

    private static void alloc(Report rep, RecordedEvent e) {
        long weight = e.getLong("weight");
        rep.allocTotal += weight;
        RecordedStackTrace st = e.getStackTrace();
        if (st == null) return;
        for (RecordedFrame f : st.getFrames()) {
            if (isSkycraft(f.getMethod())) {
                String cls = e.getClass("objectClass") != null ? e.getClass("objectClass").getName() : "?";
                rep.allocSkycraft.merge(name(f.getMethod()) + " -> " + simple(cls), weight, Long::sum);
                return;
            }
        }
    }

    private static String stageOf(RecordedMethod m) {
        if (m == null || m.getType() == null) return null;
        String cls = simple(m.getType().getName());
        String method = m.getName();
        for (String[] s : STAGES) {
            if (cls.equals(s[0]) && (s[1].isEmpty() || method.equals(s[1]) || method.startsWith("lambda$" + s[1]))) return s[2];
        }
        return null;
    }

    private static boolean isSkycraft(RecordedMethod m) {
        return m != null && m.getType() != null && m.getType().getName().startsWith("com.skycraft.");
    }

    private static String name(RecordedMethod m) {
        if (m == null || m.getType() == null) return "?";
        String cls = m.getType().getName();
        if (cls.startsWith("com.skycraft.")) cls = cls.substring("com.skycraft.".length());
        else cls = simple(cls);
        return cls + "." + m.getName();
    }

    private static String simple(String cls) {
        int i = cls.lastIndexOf('.');
        return i < 0 ? cls : cls.substring(i + 1);
    }

    /**
     * A plain-text report: the stage table and hot spot lists. JFR samples only a few threads per period, so absolute
     * CPU time can't come from sample counts: {@code workerMsPerChunk} (wall time x workers / chunks, from the
     * benchmark; 0 if unknown) is split by the sample shares instead.
     */
    public static List<String> format(Report rep, int chunks, double workerMsPerChunk) {
        List<String> out = new ArrayList<>();
        double total = Math.max(1, rep.workerSamples);
        out.add(String.format(Locale.ROOT, "worker CPU samples: %d (%d threads), truncated stacks %.1f%%, server thread samples %d",
                rep.workerSamples, rep.workerThreads.size(), 100.0 * rep.truncated / total, rep.serverSamples));
        out.add("stage                        share   worker ms/chunk");
        for (Map.Entry<String, Long> e : rep.stageSamples.entrySet()) {
            if (e.getValue() == 0) continue;
            double share = e.getValue() / total;
            out.add(String.format(Locale.ROOT, "  %-26s %5.1f%%  %8.1f", e.getKey(), 100.0 * share, share * workerMsPerChunk));
        }
        if (!rep.statusEvents.isEmpty()) {
            out.add("chunk status events (wall time per status task, includes queueing):");
            for (Map.Entry<String, long[]> e : rep.statusEvents.entrySet()) {
                long[] v = e.getValue();
                out.add(String.format(Locale.ROOT, "  %-26s n=%6d  mean %8.3f ms  total %8.1f s", e.getKey(), v[0],
                        v[0] == 0 ? 0 : v[1] / 1e6 / v[0], v[1] / 1e9));
            }
        }
        top(out, "top self frames (all code, worker threads)", rep.selfTop, total, 20);
        for (String stage : List.of("noise", "surface", "features:decoration", "biomes", "structure_starts")) {
            Map<String, Long> m = rep.stageSelf.get(stage);
            if (m != null) top(out, "  in " + stage + " (self < caller < caller)", m, total, 10);
        }
        top(out, "Skycraft inclusive (worker threads)", rep.skycraftInclusive, total, 30);
        top(out, "Skycraft entry points (outermost Skycraft frame)", rep.skycraftEntry, total, 15);
        top(out, "Skycraft on the server thread", rep.skycraftServer, Math.max(1, rep.serverSamples), 12);
        if (rep.allocTotal > 0) {
            out.add(String.format(Locale.ROOT, "allocation (sampled weight) total %.1f GB", rep.allocTotal / 1e9));
            List<Map.Entry<String, Long>> l = new ArrayList<>(rep.allocSkycraft.entrySet());
            l.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            for (int i = 0; i < Math.min(15, l.size()); i++) {
                out.add(String.format(Locale.ROOT, "  %7.1f MB  %s", l.get(i).getValue() / 1e6, l.get(i).getKey()));
            }
        }
        out.add(String.format(Locale.ROOT, "GC: %d collections, %.0f ms total pause", rep.gcCount, rep.gcPauseNanos / 1e6));
        return out;
    }

    private static void top(List<String> out, String title, Map<String, Long> map, double total, int n) {
        if (map.isEmpty()) return;
        out.add(title + ":");
        List<Map.Entry<String, Long>> l = new ArrayList<>(map.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(n, l.size()); i++) {
            out.add(String.format(Locale.ROOT, "  %5.1f%%  %s", 100.0 * l.get(i).getValue() / total, l.get(i).getKey()));
        }
    }
}
