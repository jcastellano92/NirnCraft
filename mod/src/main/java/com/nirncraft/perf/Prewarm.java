package com.nirncraft.perf;

import com.nirncraft.NirnCraft;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Loads (and if needed generates) the area around a fast-travel destination before the player is moved, using region
 * tickets that are released shortly after. The player sees a "Travelling to ..." progress bar (chunks ready / needed,
 * {@link PerfPackets.PrewarmProgress}); the travel continuation runs when the area is ready; unready timed-out journeys are cancelled, so
 * arrival is instant and no chunk is generated synchronously on the server thread.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class Prewarm {
    private static final java.util.function.Supplier<TicketType> TICKET = com.nirncraft.perf.PerfTickets.PREWARM;
    public static final int RADIUS = 3;
    private static final int TIMEOUT = 20 * 25;
    /** Tickets stay this long after completion so the arrival does not unload what was just loaded. */
    private static final int GRACE = 20 * 8;

    private static final class Job {
        final UUID ticket = UUID.randomUUID();
        boolean released;
        ServerPlayer requester;
        ServerLevel level;
        List<ChunkPos> chunks;
        UUID player;
        Component label;
        Runnable then;
        long start;
        long releaseAt = -1;
        int lastSent = -1;
        /** Maximum wait; an unready journey is cancelled without running the continuation. */
        int timeout = TIMEOUT;
    }

    private static final List<Job> JOBS = new ArrayList<>();
    private static final java.util.Map<ServerLevel, LeaseSet<Long, UUID>> LEASES = new java.util.IdentityHashMap<>();

    private Prewarm() {}

    public enum Result { READY, STARTED, BUSY, FAILED }

    /** The caller executes its continuation only for READY; STARTED owns it until completion/cancellation. */
    public static Result request(ServerPlayer player, int x, int z, Component label, Runnable then) {
        if (player == null || !player.isAlive()) return Result.FAILED;
        if (busy(player.getUUID())) return Result.BUSY;
        if (isReady(player.level(), x, z)) return Result.READY;
        return start(player, x, z, label, then) ? Result.STARTED : Result.BUSY;
    }

    /** True when the destination chunk (and its 3x3 neighbourhood) is already fully loaded. */
    public static boolean isReady(ServerLevel level, int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) == null) return false;
            }
        }
        return true;
    }

    public static boolean busy(UUID player) {
        for (Job j : JOBS) if (player.equals(j.player) && j.releaseAt < 0) return true;
        return false;
    }

    /**
     * Prewarms the area and runs {@code then} (server thread) when ready. Returns false (nothing started) when this
     * player already has a prewarm running.
     */
    public static boolean start(ServerPlayer player, int x, int z, Component label, Runnable then) {
        if (busy(player.getUUID())) return false;
        ServerLevel level = player.level();
        Job j = job(level, x, z, RADIUS);
        j.player = player.getUUID();
        j.requester = player;
        j.label = label;
        j.then = then;
        JOBS.add(j);
        return true;
    }

    /**
     * Prewarms several areas at once ({@code points}: {x, z, radiusInChunks} each, x and z in blocks) and runs {@code then}
     * when all are ready; cancels after {@code timeoutTicks} if they are still unready. For a jail: the settlement's start chunk (which records its
     * anchors) and the building's own chunks. Returns false when this player already has a prewarm running.
     */
    public static boolean start(ServerPlayer player, java.util.List<int[]> points, Component label, int timeoutTicks, Runnable then) {
        if (busy(player.getUUID()) || points.isEmpty()) return false;
        ServerLevel level = player.level();
        Job j = new Job();
        j.level = level;
        j.start = level.getServer().getTickCount();
        j.chunks = new ArrayList<>();
        j.timeout = timeoutTicks;
        for (int[] pt : points) addArea(j, level, pt[0] >> 4, pt[1] >> 4, pt[2]);
        j.player = player.getUUID();
        j.requester = player;
        j.label = label;
        j.then = then;
        JOBS.add(j);
        return true;
    }

    private static void addArea(Job j, ServerLevel level, int cx, int cz, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos p = new ChunkPos(cx + dx, cz + dz);
                if (j.chunks.contains(p)) continue;
                j.chunks.add(p);
                acquire(level, p, j.ticket);
            }
        }
    }

    /** Silent prewarm (no player, no continuation): keeps the area loaded for {@code ticks} (a ride or voyage). */
    public static void hold(ServerLevel level, int x, int z, int radius, int ticks) {
        Job j = job(level, x, z, radius);
        j.releaseAt = level.getServer().getTickCount() + ticks;
        JOBS.add(j);
    }

    private static Job job(ServerLevel level, int x, int z, int radius) {
        Job j = new Job();
        j.level = level;
        j.start = level.getServer().getTickCount();
        j.chunks = new ArrayList<>();
        int cx = x >> 4, cz = z >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos p = new ChunkPos(cx + dx, cz + dz);
                j.chunks.add(p);
                acquire(level, p, j.ticket);
            }
        }
        return j;
    }

    @SubscribeEvent
    public static void onTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post) || JOBS.isEmpty()) return;
        long now = event.getServer().getTickCount();
        // Continuations can enqueue holds or another job; iterate the initial snapshot safely.
        for (Job j : List.copyOf(JOBS)) {
            if (j.releaseAt >= 0 && j.player == null) {
                if (now >= j.releaseAt) {
                    release(j);
                    JOBS.remove(j);
                }
                continue;
            }
            if (j.player == null) continue;
            int ready = 0;
            for (ChunkPos p : j.chunks) if (j.level.getChunkSource().getChunkNow(p.x(), p.z()) != null) ready++;
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(j.player);
            if (j.releaseAt < 0) {
                boolean done = ready == j.chunks.size();
                if (player == null || player != j.requester || !player.isAlive() || player.level() != j.level
                        || (!done && now - j.start > j.timeout)) {
                    release(j);
                    JOBS.remove(j);
                    if (player != null) SkyNetwork.sendToPlayer(player, new PerfPackets.PrewarmProgress(0, 0, Component.empty()));
                    continue;
                }
                if (!done) {
                    if (ready != j.lastSent && (now % 4 == 0)) {
                        j.lastSent = ready;
                        SkyNetwork.sendToPlayer(player, new PerfPackets.PrewarmProgress(ready, j.chunks.size(), j.label));
                    }
                    continue;
                }
                SkyNetwork.sendToPlayer(player, new PerfPackets.PrewarmProgress(0, 0, Component.empty()));
                j.releaseAt = now + GRACE;
                try {
                    j.then.run();
                } catch (RuntimeException e) {
                    NirnCraft.LOGGER.error("[prewarm] travel continuation failed", e);
                }
            } else if (now >= j.releaseAt) {
                release(j);
                JOBS.remove(j);
            }
        }
    }

    /** Drops every job on server stop: the closures would otherwise keep the old server's players and levels alive. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        for (Job j : JOBS) {
            try {
                release(j);
            } catch (RuntimeException ignored) {
                // the level is already shutting down
            }
        }
        JOBS.clear();
        LEASES.clear();
    }

    private static void acquire(ServerLevel level, ChunkPos pos, UUID owner) {
        LEASES.computeIfAbsent(level, ignored -> new LeaseSet<>()).acquire(pos.pack(), owner,
                () -> level.getChunkSource().addTicketWithRadius(TICKET.get(), pos, 0));
    }

    private static void release(ServerLevel level, ChunkPos pos, UUID owner) {
        LeaseSet<Long, UUID> leases = LEASES.get(level);
        if (leases == null) return;
        leases.release(pos.pack(), owner, () -> level.getChunkSource().removeTicketWithRadius(TICKET.get(), pos, 0));
        if (leases.isEmpty()) LEASES.remove(level);
    }

    private static void release(Job j) {
        if (j.released) return;
        j.released = true;
        for (ChunkPos p : j.chunks) release(j.level, p, j.ticket);
    }
}
