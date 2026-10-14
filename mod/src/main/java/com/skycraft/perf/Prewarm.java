package com.skycraft.perf;

import com.skycraft.Skycraft;
import com.skycraft.network.SkyNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Loads (and if needed generates) the area around a fast-travel destination before the player is moved, using region
 * tickets that are released shortly after. The player sees a "Travelling to ..." progress bar (chunks ready / needed,
 * {@link PerfPackets.PrewarmProgress}); the travel continuation runs when the area is ready or after a timeout, so
 * arrival is instant and no chunk is generated synchronously on the server thread.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Prewarm {
    private static final TicketType<ChunkPos> TICKET = TicketType.create("skycraft_prewarm", Comparator.comparingLong(ChunkPos::toLong));
    public static final int RADIUS = 3;
    private static final int TIMEOUT = 20 * 25;
    /** Tickets stay this long after completion so the arrival does not unload what was just loaded. */
    private static final int GRACE = 20 * 8;

    private static final class Job {
        ServerLevel level;
        List<ChunkPos> chunks;
        UUID player;
        Component label;
        Runnable then;
        long start;
        long releaseAt = -1;
        int lastSent = -1;
    }

    private static final List<Job> JOBS = new ArrayList<>();

    private Prewarm() {}

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
        ServerLevel level = player.serverLevel();
        Job j = job(level, x, z, RADIUS);
        j.player = player.getUUID();
        j.label = label;
        j.then = then;
        JOBS.add(j);
        return true;
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
                level.getChunkSource().addRegionTicket(TICKET, p, 0, p);
            }
        }
        return j;
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || JOBS.isEmpty()) return;
        long now = event.getServer().getTickCount();
        for (Iterator<Job> it = JOBS.iterator(); it.hasNext(); ) {
            Job j = it.next();
            if (j.releaseAt >= 0 && j.player == null) {
                if (now >= j.releaseAt) {
                    release(j);
                    it.remove();
                }
                continue;
            }
            if (j.player == null) continue;
            int ready = 0;
            for (ChunkPos p : j.chunks) if (j.level.getChunkSource().getChunkNow(p.x, p.z) != null) ready++;
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(j.player);
            if (j.releaseAt < 0) {
                boolean done = ready == j.chunks.size() || now - j.start > TIMEOUT;
                if (player == null || !player.isAlive()) {
                    release(j);
                    it.remove();
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
                    Skycraft.LOGGER.error("[prewarm] travel continuation failed", e);
                }
            } else if (now >= j.releaseAt) {
                release(j);
                it.remove();
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
    }

    private static void release(Job j) {
        for (ChunkPos p : j.chunks) j.level.getChunkSource().removeRegionTicket(TICKET, p, 0, p);
    }
}
