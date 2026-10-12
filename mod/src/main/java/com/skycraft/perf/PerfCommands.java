package com.skycraft.perf;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.skycraft.Skycraft;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.TamrielMap;
import jdk.jfr.Recording;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Consumer;

/**
 * {@code /skycraft-perf} (permission 2) - worldgen performance tools:
 * <ul>
 *     <li>{@code bench [radius]}: the chunk benchmark ({@link ChunkBench}) with a flight recording and stage report;</li>
 *     <li>{@code columns}: per-call costs of the map, the biome source and the noise column heights;</li>
 *     <li>{@code jfr start|stop}: record any stretch of play (e.g. flying around) and print the same report.</li>
 * </ul>
 * Reports also go to the log and to {@code skycraft-perf/} in the server folder.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class PerfCommands {
    private static Recording manual;

    private PerfCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skycraft-perf").requires(s -> s.hasPermission(2))
                .then(Commands.literal("bench")
                        .executes(c -> bench(c.getSource(), 5))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                .executes(c -> bench(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("columns").executes(c -> columns(c.getSource())))
                .then(Commands.literal("stages")
                        .executes(c -> stages(c.getSource(), 16))
                        .then(Commands.argument("chunks", IntegerArgumentType.integer(1, 400))
                                .executes(c -> stages(c.getSource(), IntegerArgumentType.getInteger(c, "chunks")))))
                .then(Commands.literal("jfr")
                        .then(Commands.literal("start").executes(c -> jfrStart(c.getSource())))
                        .then(Commands.literal("stop").executes(c -> jfrStop(c.getSource())))));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) ChunkBench.tick();
    }

    /** Thread-safe feedback to a command source (messages are delivered on the server thread). */
    static Consumer<String> feedback(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        return s -> server.execute(() -> src.sendSuccess(() -> Component.literal(s), false));
    }

    static int bench(CommandSourceStack src, int radius) {
        ServerLevel level = src.getServer().overworld();
        if (!ChunkBench.start(level, radius, ChunkBench.defaultAreas(), feedback(src), null)) {
            src.sendFailure(Component.literal("A benchmark is already running."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Benchmark started (watch the log / chat)."), false);
        return 1;
    }

    static int stages(CommandSourceStack src, int perArea) {
        src.sendSuccess(() -> Component.literal("Stage benchmark started (off-thread)."), false);
        StageBench.run(src.getServer().overworld(), perArea, feedback(src), null);
        return 1;
    }

    private static int jfrStart(CommandSourceStack src) {
        if (manual != null || !Profiling.available()) {
            src.sendFailure(Component.literal(manual != null ? "Already recording." : "JFR is not available in this JVM."));
            return 0;
        }
        manual = Profiling.start();
        src.sendSuccess(() -> Component.literal("Recording worldgen (stop with /skycraft-perf jfr stop)."), false);
        return 1;
    }

    private static int jfrStop(CommandSourceStack src) {
        Recording rec = manual;
        manual = null;
        if (rec == null) {
            src.sendFailure(Component.literal("Not recording."));
            return 0;
        }
        Consumer<String> out = feedback(src);
        Path dir = src.getServer().getServerDirectory().toPath().resolve("skycraft-perf");
        Thread t = new Thread(() -> {
            Path jfr = Profiling.stop(rec, dir, "manual-" + System.currentTimeMillis());
            if (jfr == null) return;
            try {
                Profiling.Report rep = Profiling.analyse(jfr);
                long[] full = rep.statusEvents.get("minecraft:full");
                int chunks = full == null ? 0 : (int) full[0];
                for (String s : Profiling.format(rep, chunks, 0)) {
                    out.accept(s);
                    Skycraft.LOGGER.info("[perf] {}", s);
                }
                out.accept("recording: " + jfr.toAbsolutePath());
            } catch (IOException | RuntimeException e) {
                Skycraft.LOGGER.warn("[perf] could not analyse {}", jfr, e);
            }
        }, "skycraft-perf-report");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    /** Per-call costs of the column functions the generator leans on (single thread, warmed up). */
    static int columns(CommandSourceStack src) {
        ServerLevel level = src.getServer().overworld();
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        BiomeSource biomes = gen.getBiomeSource();
        Climate.Sampler sampler = rs.sampler();
        Random rnd = new Random(42);
        int n = 200_000;
        int[] xs = new int[n], zs = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = rnd.nextInt(24000) - 12000;
            zs[i] = rnd.nextInt(16000) - 3000;
        }
        MapSample s = new MapSample();
        double sink = 0;
        for (int i = 0; i < n; i++) {
            TamrielMap.sample(xs[i], zs[i], s);
            sink += s.elevation;
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            TamrielMap.sample(xs[i], zs[i], s);
            sink += s.elevation;
        }
        double map = (System.nanoTime() - t0) / (double) n;
        int nb = 20_000;
        for (int i = 0; i < nb; i++) sink += biomes.getNoiseBiome(xs[i] >> 2, 16, zs[i] >> 2, sampler).hashCode();
        t0 = System.nanoTime();
        for (int i = nb; i < 2 * nb; i++) sink += biomes.getNoiseBiome(xs[i] >> 2, 16, zs[i] >> 2, sampler).hashCode();
        double biome = (System.nanoTime() - t0) / (double) nb / 1000.0;
        // a chunk's worth of quarts (4x4 columns x 112 cells) at one spot: the column cache at work
        t0 = System.nanoTime();
        int qx0 = xs[7] >> 2, qz0 = zs[7] >> 2;
        for (int qx = 0; qx < 4; qx++) {
            for (int qz = 0; qz < 4; qz++) {
                for (int qy = -16; qy < 96; qy++) sink += biomes.getNoiseBiome(qx0 + qx, qy, qz0 + qz, sampler).hashCode();
            }
        }
        double chunkBiomes = (System.nanoTime() - t0) / 1e6;
        int cols = 2000;
        for (int i = 0; i < 200; i++) sink += gen.getBaseHeight(xs[i], zs[i], Heightmap.Types.WORLD_SURFACE_WG, level, rs);
        t0 = System.nanoTime();
        for (int i = 200; i < 200 + cols; i++) sink += gen.getBaseHeight(xs[i], zs[i], Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
        double height = (System.nanoTime() - t0) / (double) cols / 1000.0;
        t0 = System.nanoTime();
        for (int i = 0; i < n; i++) sink += Places.overlapping(xs[i] - 48, zs[i] - 48, xs[i] + 48, zs[i] + 48).size();
        double places = (System.nanoTime() - t0) / (double) n;
        // the spatial index must answer exactly like a scan of every place (same set of places; bucket order differs from list order)
        int bad = 0;
        for (int i = 0; i < 20_000; i++) {
            int r = 16 << (i % 7);
            int x = xs[i] / 2, z = zs[i] / 2;
            List<com.skycraft.tamriel.Place> fast = Places.overlapping(x - r, z - r, x + r, z + r);
            List<com.skycraft.tamriel.Place> slow = new java.util.ArrayList<>();
            for (com.skycraft.tamriel.Place p : Places.all()) {
                if (p.x() + p.radius() >= x - r && p.x() - p.radius() <= x + r && p.z() + p.radius() >= z - r && p.z() - p.radius() <= z + r) slow.add(p);
            }
            if (fast.size() != slow.size() || !new java.util.HashSet<>(fast).equals(new java.util.HashSet<>(slow))) bad++;
        }
        if (bad > 0) Skycraft.LOGGER.error("[perf] Places.overlapping index disagrees with a full scan in {} of 20000 queries", bad);
        String msg = String.format(Locale.ROOT,
                "TamrielMap.sample %.0f ns | getNoiseBiome %.2f us (cold column), one chunk of quarts %.2f ms | getBaseHeight %.1f us | Places.overlapping %.0f ns (index check: %d mismatches) [%s]",
                map, biome, chunkBiomes, height, places, bad, sink != 0 ? "ok" : "?");
        Skycraft.LOGGER.info("[perf] {}", msg);
        src.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }
}
