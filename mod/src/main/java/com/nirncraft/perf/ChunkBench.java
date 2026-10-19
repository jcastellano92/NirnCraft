package com.nirncraft.perf;

import com.nirncraft.NirnCraft;
import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.TamrielMap;
import jdk.jfr.Recording;
import net.minecraft.util.Util;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The worldgen benchmark ({@code /nirncraft-perf bench}): generates fixed square areas of chunks to {@code FULL} through
 * the real chunk system (region tickets, like a pre-generator), one area at a time, and reports chunks per second
 * overall and per worldgen worker. A small warm-up area runs first (JIT), unmeasured. A flight recording runs over
 * the measured areas; {@link Profiling} turns it into a per-stage breakdown and the NirnCraft hot spots.
 *
 * <p>Areas: Whiterun (Skyrim tundra, a capital), the Throat of the World (peaks), Skingrad (Cyrodiil, a city),
 * Balmora (Vvardenfell) and the outer ocean beyond the fixed lands. Run it on a fresh world: chunks that already exist
 * are loaded, not generated ({@code tools/bake/bench.ps1} deletes the world first).</p>
 */
public final class ChunkBench {
    public record Area(String name, int x, int z) {
    }

    private static final java.util.function.Supplier<TicketType> TICKET = com.nirncraft.perf.PerfTickets.BENCH;
    private static volatile ChunkBench running;

    private final ServerLevel level;
    private final List<Area> areas;
    private final int radius;
    private final Consumer<String> out;
    private final Runnable onFinish;
    private final List<String> lines = new ArrayList<>();
    private final List<String> hashes = new ArrayList<>();
    private final AtomicInteger done = new AtomicInteger();
    private final List<ChunkPos> held = new ArrayList<>();
    private Recording recording;
    /** -1 = warm-up, then the index of the area being generated. */
    private int index = -2;
    private int total;
    private long areaStart, measuredNanos;
    private int measuredChunks;

    private ChunkBench(ServerLevel level, List<Area> areas, int radius, Consumer<String> out, Runnable onFinish) {
        this.level = level;
        this.areas = areas;
        this.radius = radius;
        this.out = out;
        this.onFinish = onFinish;
    }

    public static boolean isRunning() {
        return running != null;
    }

    /** Starts the benchmark (server thread). False if one is already running. */
    public static boolean start(ServerLevel level, int radius, List<Area> areas, Consumer<String> out, Runnable onFinish) {
        if (running != null) return false;
        running = new ChunkBench(level, areas, radius, out, onFinish);
        return true;
    }

    /** The default areas (deterministic for every seed and build). */
    public static List<Area> defaultAreas() {
        List<Area> list = new ArrayList<>();
        list.add(place("whiterun", 0, 0));
        list.add(place("throat_of_the_world", 579, 652));
        list.add(place("skingrad", -1700, 6700));
        list.add(place("balmora", 6300, 500));
        list.add(realm());
        return list;
    }

    private static Area place(String id, int x, int z) {
        return Places.byId(id).map(p -> new Area(id, p.x(), p.z())).orElse(new Area(id, x, z));
    }

    /** The first column of solid land far beyond Tamriel on a fixed search grid. */
    static Area realm() {
        if (!com.nirncraft.tamriel.terrain.Beyond.ENDLESS_LANDS_ENABLED)
            return new Area("outer_ocean", 61440, 40960);
        MapSample s = new MapSample();
        for (int k = 0; k < 2000; k++) {
            int x = 40960 + (k % 40) * 1024, z = 36864 + (k / 40) * 1024;
            TamrielMap.sample(x, z, s);
            if (!s.inTamriel && s.coastDistance > 500) return new Area("realm", x, z);
        }
        return new Area("realm", 61440, 40960);
    }

    static int workers() {
        java.util.concurrent.Executor ex = Util.backgroundExecutor();
        return ex instanceof ForkJoinPool fj ? fj.getParallelism() : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    /** Called every server tick (end phase). */
    static void tick() {
        ChunkBench b = running;
        if (b == null) return;
        try {
            b.step();
        } catch (RuntimeException e) {
            NirnCraft.LOGGER.error("[perf] benchmark failed", e);
            b.release();
            running = null;
            b.finish(false);
        }
    }

    private void step() {
        if (index >= -1 && done.get() < total) return;
        if (index >= -1) {
            long nanos = System.nanoTime() - areaStart;
            if (index >= 0) hashArea(areas.get(index));
            release();
            if (index >= 0) {
                Area a = areas.get(index);
                measuredNanos += nanos;
                measuredChunks += total;
                double s = nanos / 1e9;
                line(String.format(Locale.ROOT, "area %-20s (%6d, %6d): %4d chunks in %6.2f s = %6.2f chunks/s (%5.2f per worker)",
                        a.name(), a.x(), a.z(), total, s, total / s, total / s / workers()));
            } else {
                line(String.format(Locale.ROOT, "warm-up: %d chunks in %.2f s", total, nanos / 1e9));
                if (Profiling.available()) recording = Profiling.start();
            }
        } else {
            line(String.format(Locale.ROOT, "benchmark: %d areas of %dx%d chunks, %d worldgen workers, %d cores, max heap %d MB",
                    areas.size(), radius * 2 + 1, radius * 2 + 1, workers(), Runtime.getRuntime().availableProcessors(),
                    Runtime.getRuntime().maxMemory() >> 20));
        }
        index++;
        if (index >= areas.size()) {
            running = null;
            finish(true);
            return;
        }
        Area a = index < 0 ? new Area("warmup", -1536, 1536) : areas.get(index);
        int r = index < 0 ? Math.min(2, radius) : radius;
        begin(a, r);
    }

    private void begin(Area a, int r) {
        ServerChunkCache cache = level.getChunkSource();
        int cx = a.x() >> 4, cz = a.z() >> 4;
        done.set(0);
        total = (2 * r + 1) * (2 * r + 1);
        areaStart = System.nanoTime();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(cx + dx, cz + dz);
                cache.addTicketWithRadius(TICKET.get(),  pos,  0);
                held.add(pos);
            }
        }
        for (ChunkPos pos : held) {
            cache.getChunkFuture(pos.x(), pos.z(), ChunkStatus.FULL, true).whenComplete((res, err) -> done.incrementAndGet());
        }
    }

    /**
     * Records a hash of every block state of each generated chunk (written next to the report), so two runs - e.g.
     * before and after an optimisation - can be compared chunk by chunk with {@code tools/bake/compare_hashes.ps1}.
     * Chunks are hashed in place (still held by the benchmark's tickets) after generation, before unloading.
     */
    private void hashArea(Area a) {
        long areaHash = 1125899906842597L;
        for (ChunkPos pos : held) {
            net.minecraft.world.level.chunk.LevelChunk c = level.getChunkSource().getChunkNow(pos.x(), pos.z());
            long h = 1125899906842597L;
            if (c != null) {
                for (net.minecraft.world.level.chunk.LevelChunkSection s : c.getSections()) {
                    if (s.hasOnlyAir()) {
                        h = h * 31 + 7;
                        continue;
                    }
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                h = h * 31 + net.minecraft.world.level.block.Block.getId(s.getBlockState(x, y, z));
                            }
                        }
                    }
                }
            }
            hashes.add(a.name() + " " + pos.x() + " " + pos.z() + " " + Long.toHexString(h));
            areaHash = areaHash * 31 + h;
        }
        line(String.format(Locale.ROOT, "area %s block hash %016x", a.name(), areaHash));
    }

    private void release() {
        ServerChunkCache cache = level.getChunkSource();
        for (ChunkPos pos : held) cache.removeTicketWithRadius(TICKET.get(),  pos,  0);
        held.clear();
    }

    private void line(String s) {
        lines.add(s);
        out.accept(s);
        NirnCraft.LOGGER.info("[perf] {}", s);
    }

    private void finish(boolean ok) {
        if (ok && measuredNanos > 0) {
            double s = measuredNanos / 1e9;
            line(String.format(Locale.ROOT, "TOTAL: %d chunks in %.2f s = %.2f chunks/s (%.2f per worker, %.1f ms worker-time per chunk)",
                    measuredChunks, s, measuredChunks / s, measuredChunks / s / workers(), s * 1000 * workers() / measuredChunks));
        }
        Path dir = level.getServer().getServerDirectory().resolve("nirncraft-perf");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Recording rec = recording;
        int chunks = measuredChunks;
        double msPerChunk = measuredChunks == 0 ? 0 : measuredNanos / 1e6 * workers() / measuredChunks;
        // analysing the recording takes a few seconds: keep it off the server thread
        Thread t = new Thread(() -> {
            if (rec != null) {
                Path jfr = Profiling.stop(rec, dir, "bench-" + stamp);
                if (jfr != null) {
                    try {
                        for (String s : Profiling.format(Profiling.analyse(jfr), chunks, msPerChunk)) line(s);
                        line("recording: " + jfr.toAbsolutePath());
                    } catch (IOException | RuntimeException e) {
                        NirnCraft.LOGGER.warn("[perf] could not analyse {}", jfr, e);
                    }
                }
            } else {
                line("(no flight recording: JFR is not available in this JVM)");
            }
            try {
                Files.createDirectories(dir);
                Path txt = dir.resolve("bench-" + stamp + ".txt");
                Files.write(txt, lines, StandardCharsets.UTF_8);
                Files.write(dir.resolve("bench-" + stamp + ".hashes"), hashes, StandardCharsets.UTF_8);
                line("report: " + txt.toAbsolutePath());
            } catch (IOException e) {
                NirnCraft.LOGGER.warn("[perf] could not write the report", e);
            }
            line("bench done");
            if (onFinish != null) level.getServer().execute(onFinish);
        }, "nirncraft-perf-report");
        t.setDaemon(true);
        t.start();
    }
}
