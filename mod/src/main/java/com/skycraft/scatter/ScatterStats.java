package com.skycraft.scatter;

import com.skycraft.Skycraft;
import com.skycraft.scatter.ScatterRules.Kind;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * What the feature placed this session, for {@code /skycraft-scatter stats} and the offline density analysis. With
 * {@code -Dskycraft.scatter.log=<file>} every placement is also appended as {@code x,z,zone,kind,human,humanity}.
 */
public final class ScatterStats {
    /** Per chunk: the zone it was rolled in and how many of each kind it got. */
    public record ChunkRecord(ScatterZone zone, Map<String, Integer> kinds, int items) {
    }

    private static final ConcurrentHashMap<Long, ChunkRecord> CHUNKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> CLUSTERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> DISCOVERIES = new ConcurrentHashMap<>();
    private static final LongAdder TOTAL = new LongAdder();
    private static final LongAdder NANOS = new LongAdder(), CALLS = new LongAdder();
    private static volatile long maxNanos;
    private static PrintWriter log;

    static {
        String path = System.getProperty("skycraft.scatter.log");
        if (path != null && !path.isBlank()) {
            try {
                Path p = Paths.get(path);
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                log = new PrintWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND), true);
            } catch (IOException e) {
                Skycraft.LOGGER.warn("Skycraft scatter: cannot open log {}", path, e);
            }
        }
    }

    private ScatterStats() {}

    static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    static void placed(int cx, int cz, ScatterZone zone, Kind k, int x, int z, float humanity) {
        TOTAL.increment();
        CHUNKS.compute(key(cx, cz), (key, old) -> {
            Map<String, Integer> m = old == null ? new TreeMap<>() : new TreeMap<>(old.kinds());
            m.merge(k.id, 1, Integer::sum);
            return new ChunkRecord(zone, m, (old == null ? 0 : old.items()) + Math.max(1, k.items));
        });
        PrintWriter l = log;
        if (l != null) {
            synchronized (ScatterStats.class) {
                l.println(x + "," + z + "," + zone.id + "," + k.id + "," + (k.human ? 1 : 0) + "," + String.format("%.2f", humanity));
            }
        }
    }

    /** A chunk was rolled and (maybe) got nothing: keep its zone so density per zone can be computed. */
    static void rolled(int cx, int cz, ScatterZone zone) {
        CHUNKS.putIfAbsent(key(cx, cz), new ChunkRecord(zone, new TreeMap<>(), 0));
        PrintWriter l = log;
        if (l != null) {
            synchronized (ScatterStats.class) {
                l.println((cx * 16 + 8) + "," + (cz * 16 + 8) + "," + zone.id + ",#chunk,0,0");
            }
        }
    }

    static void time(long nanos) {
        NANOS.add(nanos);
        CALLS.increment();
        if (nanos > maxNanos) maxNanos = nanos;
    }

    /** Average and worst feature time per chunk, in microseconds. */
    public static String timing() {
        long n = Math.max(1, CALLS.sum());
        return String.format("%d chunks, avg %d us, max %d us per chunk", CALLS.sum(), NANOS.sum() / n / 1000, maxNanos / 1000);
    }

    static void cluster(ScatterZone zone, String kind) {
        CLUSTERS.computeIfAbsent(zone.id + "|" + kind, k -> new LongAdder()).increment();
    }

    static void discovery(Discoveries.Type t) {
        DISCOVERIES.computeIfAbsent(t.id(), k -> new LongAdder()).increment();
    }

    public static Map<String, Long> discoveries() {
        Map<String, Long> out = new TreeMap<>();
        DISCOVERIES.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    public static Map<String, Long> clusters() {
        Map<String, Long> out = new TreeMap<>();
        CLUSTERS.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    public static ChunkRecord chunk(int cx, int cz) {
        return CHUNKS.get(key(cx, cz));
    }

    public static long total() {
        return TOTAL.sum();
    }

    public static int generatedChunks() {
        return CHUNKS.size();
    }

    public static void reset() {
        CHUNKS.clear();
        CLUSTERS.clear();
        DISCOVERIES.clear();
        TOTAL.reset();
        NANOS.reset();
        CALLS.reset();
        maxNanos = 0;
    }
}
