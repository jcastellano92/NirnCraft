package com.nirncraft.perf;

import com.nirncraft.NirnCraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Headless automation for the benchmark and bake scripts: when the environment variable {@code NIRNCRAFT_AUTORUN}
 * (or the system property {@code nirncraft.autorun}) is set, the server runs the listed tasks once it has started,
 * in order, and then shuts down cleanly. Tasks: {@code columns}, {@code bench[:radius]}, {@code bake[:dir]},
 * {@code verify}, {@code stop} (implied at the end), e.g. {@code NIRNCRAFT_AUTORUN=columns,bench:5}.
 *
 * <p>Other modules add tasks with {@link #register}. Each task gets the server and a callback to run when it's done
 * (on any thread).</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class PerfAutorun {
    private static final Map<String, BiConsumer<Task, Runnable>> TASKS = new HashMap<>();

    /** One task invocation: the server, the argument after {@code :} (or ""), and a console command source. */
    public record Task(MinecraftServer server, String arg, CommandSourceStack console) {
    }

    static {
        register("columns", (t, done) -> {
            PerfCommands.columns(t.console());
            done.run();
        });
        register("bake", (t, done) -> {
            try {
                NirnCraft.LOGGER.info("[bake] {}", com.nirncraft.bake.Bake.bakeAll(
                        java.nio.file.Path.of(t.arg().isEmpty() ? "nirncraft-bake" : t.arg())));
            } catch (java.io.IOException e) {
                NirnCraft.LOGGER.error("[bake] failed", e);
            }
            done.run();
        });
        register("verify", (t, done) -> {
            var bad = com.nirncraft.bake.Bake.check();
            if (bad.isEmpty()) NirnCraft.LOGGER.info("[bake] up to date");
            else NirnCraft.LOGGER.error("[bake] STALE: {}", bad);
            done.run();
        });
        register("stages", (t, done) -> StageBench.run(t.server().overworld(),
                t.arg().isEmpty() ? 16 : Integer.parseInt(t.arg()), s -> {}, done));
        register("bench", (t, done) -> {
            int r = t.arg().isEmpty() ? 5 : Integer.parseInt(t.arg());
            ChunkBench.start(t.server().overworld(), r, ChunkBench.defaultAreas(), s -> {}, done);
        });
    }

    private PerfAutorun() {}

    public static void register(String name, BiConsumer<Task, Runnable> task) {
        TASKS.put(name, task);
    }

    /**
     * Runs one task {@code name[:arg]} now (dev command inbox, {@code com.nirncraft.dev}); {@code done} runs when it
     * finishes. False if no such task.
     */
    public static boolean runOne(MinecraftServer server, String item, Runnable done) {
        int colon = item.indexOf(':');
        BiConsumer<Task, Runnable> task = TASKS.get((colon < 0 ? item : item.substring(0, colon)).toLowerCase(Locale.ROOT));
        if (task == null) return false;
        try {
            task.accept(new Task(server, colon < 0 ? "" : item.substring(colon + 1), server.createCommandSourceStack()), done);
        } catch (RuntimeException e) {
            NirnCraft.LOGGER.error("[autorun] task {} failed", item, e);
            done.run();
        }
        return true;
    }

    /** The task list from the environment, or null. */
    static String spec() {
        String s = System.getProperty("nirncraft.perfrun");
        if (s == null || s.isBlank()) s = System.getenv("NIRNCRAFT_AUTORUN");
        return s == null || s.isBlank() ? null : s.trim();
    }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        String spec = spec();
        if (spec == null || event.getServer().overworld() == null) return;
        Deque<String> queue = new ArrayDeque<>();
        for (String s : spec.split(",")) if (!s.isBlank()) queue.add(s.trim());
        NirnCraft.LOGGER.info("[autorun] tasks: {}", queue);
        next(event.getServer(), queue);
    }

    private static void next(MinecraftServer server, Deque<String> queue) {
        String item = queue.poll();
        if (item == null || item.equals("stop")) {
            NirnCraft.LOGGER.info("[autorun] all tasks done, stopping the server");
            server.execute(() -> server.halt(false));
            return;
        }
        int colon = item.indexOf(':');
        String name = (colon < 0 ? item : item.substring(0, colon)).toLowerCase(Locale.ROOT);
        String arg = colon < 0 ? "" : item.substring(colon + 1);
        BiConsumer<Task, Runnable> task = TASKS.get(name);
        if (task == null) {
            NirnCraft.LOGGER.error("[autorun] unknown task {}", name);
            next(server, queue);
            return;
        }
        NirnCraft.LOGGER.info("[autorun] running {}", item);
        Runnable done = () -> server.execute(() -> {
            NirnCraft.LOGGER.info("[autorun] finished {}", item);
            next(server, queue);
        });
        server.execute(() -> {
            try {
                task.accept(new Task(server, arg, server.createCommandSourceStack()), done);
            } catch (RuntimeException e) {
                NirnCraft.LOGGER.error("[autorun] task {} failed", item, e);
                done.run();
            }
        });
    }
}
