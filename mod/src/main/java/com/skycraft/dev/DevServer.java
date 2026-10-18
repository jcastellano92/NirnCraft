package com.skycraft.dev;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.skycraft.Skycraft;
import com.skycraft.perf.PerfAutorun;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Dev server command inbox (DEV ONLY: inactive unless the JVM has {@code -Dskycraft.dev=<dir>}, which only
 * {@code tools/dev/devserver.ps1} sets). Every second the server takes the oldest {@code <dir>/inbox/*.cmd} job and runs
 * its lines one after another, writing everything to {@code <dir>/outbox/<job>.out} and then {@code <job>.done}
 * ({@code status=OK|FAIL|TIMEOUT}). Line forms:
 * <ul>
 *   <li>a server command (leading {@code /} optional); its feedback is captured. After a command that started a
 *   {@code /skycraft-dev regen}, the job waits for the regen;</li>
 *   <li>{@code task:<name>[:arg]}: an autorun task ({@link PerfAutorun}: roadplan, roadterrain, bake, verify, ...),
 *   awaited;</li>
 *   <li>{@code @wait <ticks>}, {@code @until <regex>} (a captured log or feedback line matches), {@code @idle} (no
 *   chunk work pending), {@code @timeout <seconds>} (for the rest of the job; default 900).</li>
 * </ul>
 * Log lines (INFO and up, any thread) logged while a job runs go to its {@code .out} as {@code [log] ...}. The server
 * writes {@code outbox/ready} (pid) once started and {@code outbox/heartbeat} every second, and drops the spawn-chunk
 * ticket so regen areas can unload (set {@code -Dskycraft.dev.keepSpawn=true} to keep it).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class DevServer {
    static final String DIR = System.getProperty("skycraft.dev");
    private static final ConcurrentLinkedQueue<String> CAPTURED = new ConcurrentLinkedQueue<>();
    private static volatile boolean capturing;
    private static long ticks;
    private static Job job;

    private DevServer() {}

    public static boolean enabled() {
        return DIR != null && !DIR.isBlank();
    }

    private static Path dir(String sub) {
        return Path.of(DIR).resolve(sub);
    }

    /** One inbox job. */
    private static final class Job {
        final String id;
        final Path out;
        final ArrayDeque<String> lines = new ArrayDeque<>();
        final long started = System.currentTimeMillis();
        long deadline = started + 900_000L;
        String waitFor = "";
        long waitTicks;
        Pattern until;
        DevRegen regen;
        volatile boolean taskDone = true;
        boolean failed;
        final List<String> buffer = new ArrayList<>();

        Job(String id, List<String> src) {
            this.id = id;
            this.out = dir("outbox").resolve(id + ".out");
            for (String s : src) if (!s.isBlank() && !s.trim().startsWith("#")) lines.add(s.trim());
        }

        void print(String line) {
            buffer.add(line);
            if (until != null && until.matcher(line).find()) until = null;
        }

        void flush() {
            String s;
            while ((s = CAPTURED.poll()) != null) print("[log] " + s);
            if (buffer.isEmpty()) return;
            try {
                Files.write(out, buffer, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                Skycraft.LOGGER.warn("[dev] can't write {}", out, e);
            }
            buffer.clear();
        }
    }

    /** Captures command feedback into the running job. */
    private static final class Capture implements CommandSource {
        @Override
        public void sendSystemMessage(Component c) {
            if (job != null) job.print("  " + c.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    private static final class LogTap extends AbstractAppender {
        LogTap() {
            super("SkycraftDevTap", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent e) {
            if (!capturing || !e.getLevel().isMoreSpecificThan(Level.INFO)) return;
            if (CAPTURED.size() > 20_000) return;
            String msg = e.getMessage().getFormattedMessage();
            if (e.getLevel().isMoreSpecificThan(Level.WARN)) msg = e.getLevel() + " " + msg;
            if (e.getThrown() != null) msg += " | " + e.getThrown();
            CAPTURED.add(msg);
        }
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        if (!enabled()) return;
        MinecraftServer server = event.getServer();
        if (server.overworld() == null) return;
        DevResources.clear();
        try {
            Files.createDirectories(dir("inbox"));
            Files.createDirectories(dir("outbox"));
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            LogTap tap = new LogTap();
            tap.start();
            ctx.getConfiguration().getRootLogger().addAppender(tap, Level.INFO, null);
            ctx.updateLoggers();
        } catch (IOException | RuntimeException e) {
            Skycraft.LOGGER.error("[dev] inbox setup failed", e);
        }
        if (!Boolean.getBoolean("skycraft.dev.keepSpawn")) {
            ServerLevel ow = server.overworld();
            ow.getChunkSource().removeTicketWithRadius(TicketType.PLAYER_SPAWN,  ChunkPos.containing(ow.getRespawnData().pos()), 3);
        }
        write("ready", ProcessHandle.current().pid() + "\n" + System.currentTimeMillis() + "\n");
        write("bake-packaged-inputs", com.skycraft.bake.Bake.inputsHash() + "\n");
        Path core = Path.of("mods/skycraft-core.jar");
        if (Files.isRegularFile(core)) {
            try {
                write("runtime.json", "{\"coreSha256\":\"" + com.skycraft.bake.Bake.sha(Files.readAllBytes(core))
                        + "\",\"pid\":" + ProcessHandle.current().pid() + "}");
            } catch (IOException e) { Skycraft.LOGGER.warn("[dev] could not record preview jar", e); }
        }
        Skycraft.LOGGER.info("[dev] dev server ready: inbox {}", dir("inbox").toAbsolutePath());
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        if (!enabled()) return;
        DevScenario.clear();
        if (job != null) finish("FAIL", "server stopping");
        DevResources.clear();
        try {
            Files.deleteIfExists(dir("outbox").resolve("ready"));
        } catch (IOException ignored) {
        }
    }

    private static void write(String name, String text) {
        try {
            Path tmp = dir("outbox").resolve(name + ".tmp");
            Files.writeString(tmp, text);
            Files.move(tmp, dir("outbox").resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // the script reading the heartbeat holds it open for a moment on Windows: the next second's write wins
            if (!name.equals("heartbeat")) Skycraft.LOGGER.warn("[dev] can't write {}", name, e);
        }
    }

    @SubscribeEvent
    public static void onTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!enabled() || !(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post)) return;
        MinecraftServer server = event.getServer();
        if (server.overworld() == null) return;
        ticks++;
        if (DevRegen.active != null) DevRegen.active.tick();
        if (ticks % 20 == 0) {
            write("heartbeat", System.currentTimeMillis() + " " + ticks + " " + (job == null ? "-" : job.id)
                    + (DevRegen.active == null ? "" : " regen:" + DevRegen.active.phase) + "\n");
        }
        if (ticks % 5 == 0 && job == null) poll();
        if (job != null) step(server);
    }

    private static void poll() {
        try (Stream<Path> s = Files.list(dir("inbox"))) {
            Path next = s.filter(p -> p.getFileName().toString().endsWith(".cmd")).sorted().findFirst().orElse(null);
            if (next == null) return;
            List<String> lines = Files.readAllLines(next, StandardCharsets.UTF_8);
            Files.delete(next);
            String name = next.getFileName().toString();
            job = new Job(name.substring(0, name.length() - 4), lines);
            CAPTURED.clear();
            capturing = true;
            Skycraft.LOGGER.info("[dev] job {}: {} lines", job.id, job.lines.size());
        } catch (IOException e) {
            Skycraft.LOGGER.warn("[dev] inbox read failed", e);
        }
    }

    private static void step(MinecraftServer server) {
        Job j = job;
        j.flush();
        if (System.currentTimeMillis() > j.deadline) {
            String what = DevRegen.active != null ? "regen " + DevRegen.active.describe() : !j.taskDone ? "task" : j.until != null
                    ? "log line /" + j.until + "/" : j.waitFor;
            if (DevRegen.active != null) DevRegen.active.abort("job timeout");
            j.print("TIMEOUT waiting for " + what);
            finish("TIMEOUT", what);
            return;
        }
        // current wait
        if (DevRegen.active != null || !j.taskDone || j.until != null) return;
        if (j.waitTicks > 0) {
            j.waitTicks--;
            return;
        }
        if (j.waitFor.equals("idle")) {
            // ChunkMap.hasWork also means ordinary resident chunks/tickets, so it never
            // becomes false around a player or /forceload. Wait for actual queued work
            // and requested forced/player-view chunk futures, not world residency.
            for (ServerLevel idleLevel : server.getAllLevels()) {
                var source = idleLevel.getChunkSource();
                if (source.getPendingTasksCount() > 0 || source.getLightEngine().hasLightWork()) return;
                var requested = new java.util.HashSet<Long>();
                for (long packed : idleLevel.getForceLoadedChunks()) requested.add(packed);
                for (var player : idleLevel.players()) {
                    if (player.isFakePlayer()) continue;
                    for (var pos : ChunkPos.rangeClosed(player.chunkPosition(), server.getPlayerList().getViewDistance() + 1).toList()) {
                        if (player.getChunkTrackingView().contains(pos)) requested.add(pos.pack());
                    }
                }
                for (long packed : requested) {
                    var holder = source.chunkMap.getUpdatingChunkIfPresent(packed);
                    if (holder == null || !holder.getFullChunkFuture().getNow(net.minecraft.server.level.ChunkHolder.UNLOADED_LEVEL_CHUNK).isSuccess()
                            || !holder.getSendSyncFuture().isDone() || !idleLevel.areEntitiesLoaded(packed)) return;
                    for (var generation : holder.getAllFutures()) if (!generation.getSecond().isDone()) return;
                }
            }
            j.waitFor = "";
        }
        if (j.regen != null) {
            if (j.regen.failed) j.failed = true;
            j.regen = null;
        }
        String line = j.lines.poll();
        if (line == null) {
            finish(j.failed ? "FAIL" : "OK", "");
            return;
        }
        j.print("> " + line);
        if (line.startsWith("@")) {
            String[] p = line.substring(1).split("\\s+", 2);
            String arg = p.length > 1 ? p[1].trim() : "";
            switch (p[0].toLowerCase(Locale.ROOT)) {
                case "wait" -> j.waitTicks = Long.parseLong(arg);
                case "until" -> j.until = Pattern.compile(arg);
                case "idle" -> j.waitFor = "idle";
                case "timeout" -> j.deadline = System.currentTimeMillis() + Long.parseLong(arg) * 1000L;
                default -> {
                    j.print("  unknown directive " + p[0]);
                    j.failed = true;
                }
            }
            return;
        }
        if (line.startsWith("task:")) {
            String item = line.substring(5).trim();
            j.taskDone = false;
            if (!PerfAutorun.runOne(server, item, () -> j.taskDone = true)) {
                j.taskDone = true;
                j.print("  unknown task " + item);
                j.failed = true;
            }
            return;
        }
        String cmd = line.startsWith("/") ? line.substring(1) : line;
        if (cmd.equals("stop")) {
            finish("OK", "stopping");
            server.halt(false);
            return;
        }
        CommandSourceStack src = server.createCommandSourceStack().withSource(new Capture()).withCallback((success, result) -> { if (!success || result <= 0) j.failed = true; });
        server.getCommands().performPrefixedCommand(src, cmd);
        j.regen = DevRegen.active;
    }

    private static void finish(String status, String note) {
        Job j = job;
        if (j == null) return;
        capturing = false;
        j.flush();
        write(j.id + ".done", "status=" + status + "\nseconds=" + (System.currentTimeMillis() - j.started) / 1000.0
                + (note.isEmpty() ? "" : "\nnote=" + note) + "\n");
        Skycraft.LOGGER.info("[dev] job {} {}", j.id, status);
        job = null;
    }

    // ------------------------------------------------------------------ /skycraft-dev

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        if (!enabled()) return;
        event.getDispatcher().register(Commands.literal("skycraft-dev").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_OWNER))
                .then(DevRoute.command())
                .then(DevScenario.command())
                .then(Commands.literal("network").executes(c -> {
                    String report = com.skycraft.network.SkyNetwork.diagnostics().toString();
                    c.getSource().sendSuccess(() -> Component.literal(report), false);
                    return 1;
                }))
                .then(Commands.literal("save").executes(c -> {
                    boolean saved=c.getSource().getServer().saveEverything(true,true,true);
                    c.getSource().sendSuccess(()->Component.literal("World save completed: "+saved),false);
                    return saved?1:0;
                }))
                .then(Commands.literal("regen")
                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                                .then(Commands.argument("z1", IntegerArgumentType.integer())
                                        .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                        .executes(c -> regen(c.getSource(), c.getSource().getServer().overworld(),
                                                                IntegerArgumentType.getInteger(c, "x1"), IntegerArgumentType.getInteger(c, "z1"),
                                                                IntegerArgumentType.getInteger(c, "x2"), IntegerArgumentType.getInteger(c, "z2")))
                                                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                                                .executes(c -> regen(c.getSource(), DimensionArgument.getDimension(c, "dimension"),
                                                                        IntegerArgumentType.getInteger(c, "x1"), IntegerArgumentType.getInteger(c, "z1"),
                                                                        IntegerArgumentType.getInteger(c, "x2"), IntegerArgumentType.getInteger(c, "z2")))))))))
                .then(Commands.literal("regenchunks")
                        .then(Commands.argument("runs", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(c -> regenChunks(c.getSource(), c.getSource().getServer().overworld(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "runs")))))
                .then(Commands.literal("release").executes(c -> {
                    DevRegen.release();
                    c.getSource().sendSuccess(() -> Component.literal("Released the last regen area."), false);
                    return 1;
                }))
                .then(Commands.literal("status").executes(c -> {
                    String s = "regen: " + (DevRegen.active == null ? "idle" : DevRegen.active.describe()) + "; last: "
                            + (DevRegen.last == null ? "-" : DevRegen.last.result) + "; loaded chunks "
                            + c.getSource().getServer().overworld().getChunkSource().getLoadedChunksCount();
                    c.getSource().sendSuccess(() -> Component.literal(s), false);
                    return 1;
                }))
                .then(Commands.literal("bake-status").executes(c -> {
                    String hash = com.skycraft.bake.Bake.inputsHash();
                    write("bake-inputs", hash + "\n");
                    c.getSource().sendSuccess(() -> Component.literal("Runtime bake inputs: " + hash), false);
                    return 1;
                }))
                .then(Commands.literal("reload")
                        .executes(c -> DevReload.execute(c.getSource(), "all"))
                        .then(Commands.argument("target", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(DevReload.TARGETS, b))
                                .executes(c -> DevReload.execute(c.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "target"))))));
    }

    private static int regenChunks(CommandSourceStack src, ServerLevel level, String runs) {
        String msg;
        try {
            msg = DevRegen.start(level, DevRegen.runs(runs), src);
        } catch (RuntimeException e) {
            msg = "Bad chunk runs (cz:cx1-cx2,cx1-cx2;cz:...): " + e;
        }
        boolean ok = DevRegen.active != null;
        String m = msg;
        if (ok) src.sendSuccess(() -> Component.literal(m), false);
        else src.sendFailure(Component.literal(m));
        return ok ? 1 : 0;
    }

    private static int regen(CommandSourceStack src, ServerLevel level, int x1, int z1, int x2, int z2) {
        String msg = DevRegen.start(level, DevRegen.rect(x1, z1, x2, z2), src);
        boolean ok = DevRegen.active != null;
        if (ok) src.sendSuccess(() -> Component.literal(msg), false);
        else src.sendFailure(Component.literal(msg));
        return ok ? 1 : 0;
    }
}
