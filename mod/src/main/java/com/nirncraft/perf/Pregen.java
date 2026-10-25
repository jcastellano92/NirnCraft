package com.nirncraft.perf;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.nirncraft.NirnCraft;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Idle pre-generator: generates ordinary full chunks in the background, at low priority, through the chunk system's
 * own tickets (a region ticket per requested chunk, released as soon as the chunk is ready; generated chunks are saved
 * with the world like any explored chunk, so Distant Horizons and the map see them).
 *
 * <p>Sources, in priority order: (1) a direction-aware frontier around every online player, a cone ahead of the
 * smoothed (about 30 s) movement vector first and the area behind last; (2) a spiral around the world spawn up to
 * {@code autoRadius}; (3) spirals around the players' last known positions up to half that. Spiral progress and the
 * last positions are saved in the overworld's data ({@code nirncraft_pregen}), so a restart continues where it stopped.
 * Everything is gated by the server's tick time (average above {@code maxTickMs} pauses it), by fast-moving players
 * (elytra, mounts, ships: vanilla's own loading needs the CPU) and by a small in-flight cap, so it never causes
 * "Can't keep up". {@code /nirncraft-pregen start <blocks> [now]} runs a spiral around spawn on demand.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class Pregen {
    private static final java.util.function.Supplier<TicketType> TICKET = com.nirncraft.perf.PerfTickets.PREGEN;
    private static final String DATA = "nirncraft_pregen";
    private static final int MAX_LAST = 6;
    /** Players faster than this (blocks/s) pause everything. */
    private static final double FAST = 12.0;
    private static final int TIMEOUT_TICKS = 20 * 60;
    private static final int STARTUP_GRACE = 20 * 45;

    // ------------------------------------------------------------------------------------------------ saved progress

    static final class Data extends SavedData {
        long spawnIndex;
        /** Spawn spiral radius (chunks) this index is counted against; a bigger setting continues the same spiral. */
        final List<long[]> last = new ArrayList<>(); // {cx, cz, index}

        static Data load(CompoundTag tag) {
            Data d = new Data();
            d.spawnIndex = Math.max(0, tag.getLongOr("spawn",0L) - 8);
            ListTag l = tag.getListOrEmpty("last");
            for (int i = 0; i < l.size() && i < MAX_LAST; i++) {
                CompoundTag t = l.getCompoundOrEmpty(i);
                d.last.add(new long[]{t.getIntOr("x",0), t.getIntOr("z",0), Math.max(0, t.getLongOr("i",0L) - 8)});
            }
            return d;
        }

        public CompoundTag save(CompoundTag tag) {
            tag.putLong("spawn", spawnIndex);
            ListTag l = new ListTag();
            for (long[] p : last) {
                CompoundTag t = new CompoundTag();
                t.putInt("x", (int) p[0]);
                t.putInt("z", (int) p[1]);
                t.putLong("i", p[2]);
                l.add(t);
            }
            tag.put("last", l);
            return tag;
        }
    }

    // ------------------------------------------------------------------------------------------------ runtime state

    private record Flight(ChunkPos pos, long since) {
    }

    private static final Map<Long, Flight> IN_FLIGHT = new HashMap<>();
    private static final LongOpenHashSet DONE = new LongOpenHashSet();
    private static final Map<UUID, double[]> MOTION = new HashMap<>(); // x, z, vx, vz, lastInstSpeed, fastUntilTick
    private static final int[][] OFFSETS = offsets(48);
    private static long manualIndex = -1;
    private static int manualRadiusChunks;
    private static boolean manualNow;
    private static boolean autoOff;
    private static long generated;
    private static long lastSaveTick;
    private static long pausedTicks;
    private static String why = "";

    private Pregen() {}

    private static int[][] offsets(int r) {
        List<int[]> l = new ArrayList<>();
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) if (x * x + z * z <= r * r) l.add(new int[]{x, z});
        l.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        return l.toArray(new int[0][]);
    }

    /** Ring-ordered square spiral: index 0 is the centre, ring k holds 8k cells. */
    static int[] spiral(long n) {
        if (n <= 0) return new int[]{0, 0};
        long k = (long) ((Math.sqrt((double) n) - 1) / 2) + 1;
        while ((2 * k - 1) * (2 * k - 1) > n) k--;
        while ((2 * k + 1) * (2 * k + 1) <= n) k++;
        long j = n - (2 * k - 1) * (2 * k - 1);
        long side = j / (2 * k), pos = j % (2 * k);
        return switch ((int) side) {
            case 0 -> new int[]{(int) k, (int) (-k + 1 + pos)};
            case 1 -> new int[]{(int) (k - 1 - pos), (int) k};
            case 2 -> new int[]{(int) -k, (int) (k - 1 - pos)};
            default -> new int[]{(int) (-k + 1 + pos), (int) -k};
        };
    }

    private static long cellsFor(int radiusChunks) {
        long s = 2L * radiusChunks + 1;
        return s * s;
    }

    private static Data data(ServerLevel level) {
        return com.nirncraft.platform.SavedDataTypes.get(level, DATA, Data::load, Data::new, value263 -> value263.save(new net.minecraft.nbt.CompoundTag()));
    }

    // ------------------------------------------------------------------------------------------------ tick

    @SubscribeEvent
    public static void onTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post)) return;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        if (level == null) return;
        long tick = server.getTickCount();
        collect(level, tick);
        if (tick % 20 == 0) sampleMotion(server, tick);
        boolean manual = manualIndex >= 0;
        boolean auto = !autoOff && autoEnabled(server);
        if (!manual && !auto) {
            why = autoOff ? "stopped" : "off";
            return;
        }
        if (!manual && tick < STARTUP_GRACE) {
            why = "starting up";
            return;
        }
        if (server.getCurrentSmoothedTickTime() > PerfConfig.get(PerfConfig.MAX_TICK_MS)) {
            pausedTicks++;
            why = "paused: server tick time " + Math.round(server.getCurrentSmoothedTickTime()) + " ms";
            return;
        }
        boolean anyFast = false;
        for (double[] m : MOTION.values()) if (m[5] > tick) anyFast = true;
        if (anyFast) {
            pausedTicks++;
            why = "paused: a player is moving fast";
            return;
        }
        int players = server.getPlayerList().getPlayerCount();
        int cap = inFlightCap(server, players);
        if (IN_FLIGHT.size() >= cap) return;
        why = "running";
        Data d = data(level);
        int budget = Math.min(2, cap - IN_FLIGHT.size());
        for (int i = 0; i < budget; i++) {
            ChunkPos next = null;
            if (manual && (players == 0 || manualNow || !PerfConfig.get(PerfConfig.PAUSE_WITH_PLAYERS))) {
                next = nextSpiral(level, level.getRespawnData().pos().getX() >> 4, level.getRespawnData().pos().getZ() >> 4,
                        manualRadiusChunks, true, d);
                if (next == null && manualIndex >= cellsFor(manualRadiusChunks)) {
                    NirnCraft.LOGGER.info("[pregen] manual pre-generation finished ({} chunks this session)", generated);
                    manualIndex = -1;
                    manual = false;
                }
            } else if (manual) {
                why = "manual job waits for players to leave (use 'now')";
            }
            if (next == null && auto) {
                next = nextAhead(server, level);
                if (next == null && (players == 0 || !PerfConfig.get(PerfConfig.PAUSE_WITH_PLAYERS))) {
                    int r = Math.max(0, PerfConfig.get(PerfConfig.AUTO_RADIUS)) >> 4;
                    next = nextSpiral(level, level.getRespawnData().pos().getX() >> 4, level.getRespawnData().pos().getZ() >> 4, r, false, d);
                    if (next == null) next = nextLast(level, d, r / 2);
                }
            }
            if (next == null) break;
            request(level, next, tick);
        }
        if (tick - lastSaveTick > 600) {
            lastSaveTick = tick;
            d.setDirty();
        }
    }

    private static boolean autoEnabled(MinecraftServer server) {
        // the GPU world draws what the server would only pre-generate beyond its radius (atlas.server.AtlasServerMode)
        if (com.nirncraft.atlas.server.AtlasServerMode.capView()) return false;
        return PerfConfig.get(server.isDedicatedServer() ? PerfConfig.AUTO : PerfConfig.AUTO_INTEGRATED);
    }

    private static int inFlightCap(MinecraftServer server, int players) {
        int cfg = PerfConfig.get(PerfConfig.MAX_IN_FLIGHT);
        if (cfg > 0) return cfg;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        int cap = players == 0 ? Math.min(4, Math.max(1, threads / 4)) : 1;
        if (ModList.get().isLoaded("distanthorizons")) cap = Math.max(1, cap / 2);
        return cap;
    }

    private static void request(ServerLevel level, ChunkPos pos, long tick) {
        if (level.getChunkSource().getChunkNow(pos.x(), pos.z()) != null) {
            DONE.add(pos.pack());
            return;
        }
        level.getChunkSource().addTicketWithRadius(TICKET.get(),  pos,  0);
        IN_FLIGHT.put(pos.pack(), new Flight(pos, tick));
    }

    private static void collect(ServerLevel level, long tick) {
        if (IN_FLIGHT.isEmpty()) return;
        var it = IN_FLIGHT.values().iterator();
        while (it.hasNext()) {
            Flight f = it.next();
            boolean ready = level.getChunkSource().getChunkNow(f.pos().x(), f.pos().z()) != null;
            if (ready || tick - f.since() > TIMEOUT_TICKS) {
                level.getChunkSource().removeTicketWithRadius(TICKET.get(),  f.pos(),  0);
                if (ready) generated++;
                DONE.add(f.pos().pack());
                it.remove();
            }
        }
        if (DONE.size() > 400_000) DONE.clear();
    }

    /** Releases every ticket (server stopping, {@code /nirncraft-pregen stop}). */
    private static void releaseAll(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (level != null) {
            for (Flight f : IN_FLIGHT.values()) level.getChunkSource().removeTicketWithRadius(TICKET.get(),  f.pos(),  0);
        }
        IN_FLIGHT.clear();
    }

    @SubscribeEvent
    public static void onStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        releaseAll(event.getServer());
        ServerLevel level = event.getServer().overworld();
        if (level != null) data(level).setDirty();
        DONE.clear();
        MOTION.clear();
        manualIndex = -1;
    }

    // ------------------------------------------------------------------------------------------------ sources

    private static ChunkPos nextSpiral(ServerLevel level, int cx, int cz, int radius, boolean manual, Data d) {
        long limit = cellsFor(radius);
        for (int guard = 0; guard < 512; guard++) {
            long idx = manual ? manualIndex : d.spawnIndex;
            if (idx >= limit) return null;
            if (manual) manualIndex++;
            else d.spawnIndex++;
            int[] o = spiral(idx);
            ChunkPos p = new ChunkPos(cx + o[0], cz + o[1]);
            if (DONE.contains(p.pack()) || IN_FLIGHT.containsKey(p.pack())) continue;
            if (level.getChunkSource().getChunkNow(p.x(), p.z()) != null) {
                DONE.add(p.pack());
                continue;
            }
            d.setDirty();
            return p;
        }
        return null;
    }

    private static ChunkPos nextLast(ServerLevel level, Data d, int radius) {
        for (long[] p : d.last) {
            if (p[2] >= cellsFor(radius)) continue;
            for (int guard = 0; guard < 512 && p[2] < cellsFor(radius); guard++) {
                int[] o = spiral(p[2]++);
                ChunkPos c = new ChunkPos((int) p[0] + o[0], (int) p[1] + o[1]);
                if (DONE.contains(c.pack()) || IN_FLIGHT.containsKey(c.pack())) continue;
                if (level.getChunkSource().getChunkNow(c.x(), c.z()) != null) {
                    DONE.add(c.pack());
                    continue;
                }
                d.setDirty();
                return c;
            }
        }
        return null;
    }

    /** The cheapest not-yet-generated chunk around any player, weighted towards where the player is heading. */
    private static ChunkPos nextAhead(MinecraftServer server, ServerLevel level) {
        int view = com.nirncraft.atlas.server.AtlasServerCap.viewDistance(server);
        int ahead = Math.max(2, PerfConfig.get(PerfConfig.AHEAD_CHUNKS));
        double bestScore = Double.MAX_VALUE;
        ChunkPos best = null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() != level || p.isSpectator()) continue;
            double[] m = MOTION.get(p.getUUID());
            double vx = m == null ? 0 : m[2], vz = m == null ? 0 : m[3];
            double speed = Math.sqrt(vx * vx + vz * vz);
            double ux = speed > 0.4 ? vx / speed : 0, uz = speed > 0.4 ? vz / speed : 0;
            // how strongly the heading matters: nothing when standing, full once walking briskly
            double w = speed > 0.4 ? Math.min(1.0, speed / 4.0) * 3.0 : 0;
            int pcx = p.blockPosition().getX() >> 4, pcz = p.blockPosition().getZ() >> 4;
            int lo = view + 1, hi = view + ahead;
            for (int[] o : OFFSETS) {
                double d2 = o[0] * o[0] + o[1] * o[1];
                if (d2 < lo * lo) continue;
                if (d2 > hi * hi) break;
                double dist = Math.sqrt(d2);
                double cos = ux == 0 && uz == 0 ? 1 : (o[0] * ux + o[1] * uz) / dist;
                double score = dist * (1 + w * (1 - cos) * 0.5) + (cos < -0.2 ? w * 4 : 0);
                if (score >= bestScore) continue;
                long key = ChunkPos.pack(pcx + o[0], pcz + o[1]);
                if (DONE.contains(key) || IN_FLIGHT.containsKey(key)) continue;
                bestScore = score;
                best = new ChunkPos(pcx + o[0], pcz + o[1]);
            }
        }
        if (best != null && level.getChunkSource().getChunkNow(best.x(), best.z()) != null) {
            DONE.add(best.pack());
            return null;
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------ player motion

    private static void sampleMotion(MinecraftServer server, long tick) {
        Data d = null;
        MOTION.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            double[] m = MOTION.computeIfAbsent(p.getUUID(), k -> new double[]{p.getX(), p.getZ(), 0, 0, 0, 0});
            double ix = p.getX() - m[0], iz = p.getZ() - m[1]; // per second: sampled every 20 ticks
            m[0] = p.getX();
            m[1] = p.getZ();
            double inst = Math.sqrt(ix * ix + iz * iz);
            if (inst > 120) continue; // a teleport, not travel
            m[2] += (ix - m[2]) / 30.0;
            m[3] += (iz - m[3]) / 30.0;
            m[4] = inst;
            if (inst > FAST) m[5] = tick + 100;
            if (tick % 1200 == 0 && p.level() == server.overworld()) {
                if (d == null) d = data(server.overworld());
                rememberPosition(d, p.blockPosition().getX() >> 4, p.blockPosition().getZ() >> 4);
            }
        }
    }

    private static void rememberPosition(Data d, int cx, int cz) {
        for (long[] p : d.last) {
            long dx = p[0] - cx, dz = p[1] - cz;
            if (dx * dx + dz * dz < 24 * 24) return; // already covered by a spiral around a nearby spot
        }
        d.last.add(0, new long[]{cx, cz, 0});
        while (d.last.size() > MAX_LAST) d.last.remove(d.last.size() - 1);
        d.setDirty();
    }

    // ------------------------------------------------------------------------------------------------ commands

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("nirncraft-pregen").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("start")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(16, 30000))
                                .executes(c -> start(c.getSource().getServer(), IntegerArgumentType.getInteger(c, "radius"), false, c.getSource()))
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .executes(c -> start(c.getSource().getServer(), IntegerArgumentType.getInteger(c, "radius"),
                                                "now".equals(StringArgumentType.getString(c, "mode")), c.getSource())))))
                .then(Commands.literal("stop").executes(c -> {
                    MinecraftServer s = c.getSource().getServer();
                    manualIndex = -1;
                    autoOff = true;
                    releaseAll(s);
                    c.getSource().sendSuccess(() -> Component.literal("Pre-generation stopped (until '/nirncraft-pregen start' or a restart)."), true);
                    return 1;
                }))
                .then(Commands.literal("status").executes(c -> {
                    MinecraftServer s = c.getSource().getServer();
                    Data d = data(s.overworld());
                    String msg = String.format("pregen: %s | manual %s | spawn spiral %d/%d chunks | %d generated this session, %d in flight, %d tick(s) paused | %d remembered position(s) | avg tick %.1f ms",
                            why, manualIndex < 0 ? "idle" : manualIndex + "/" + cellsFor(manualRadiusChunks),
                            d.spawnIndex, cellsFor(Math.max(0, PerfConfig.get(PerfConfig.AUTO_RADIUS)) >> 4),
                            generated, IN_FLIGHT.size(), pausedTicks, d.last.size(), s.getCurrentSmoothedTickTime());
                    c.getSource().sendSuccess(() -> Component.literal(msg), false);
                    return 1;
                })));
    }

    private static int start(MinecraftServer server, int radiusBlocks, boolean now, net.minecraft.commands.CommandSourceStack src) {
        manualRadiusChunks = Math.max(1, radiusBlocks >> 4);
        manualIndex = 0;
        manualNow = now;
        autoOff = false;
        src.sendSuccess(() -> Component.literal("Pre-generating " + cellsFor(manualRadiusChunks) + " chunks around spawn at low priority"
                + (now ? " (ignoring online players)" : " (waits while players are online; add 'now' to ignore)")
                + ". Progress: /nirncraft-pregen status"), true);
        return 1;
    }
}
