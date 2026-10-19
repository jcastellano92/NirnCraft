package com.nirncraft.perf;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Chunks ahead of each player are generated first: a short-lived loading ticket on the chunks in the cone the player
 * looks into (and, when moving fast, the cone along the movement), so what comes into view is there before what is
 * behind. The tickets expire on their own (PerfTickets.LOOKAHEAD, 2 s); re-adding them while the chunk stays ahead
 * keeps them, turning away lets them lapse. Within view distance only: nothing is loaded that would not be anyway,
 * only sooner. World generation is order independent, so this changes timing, never the world.
 */
public final class Lookahead {
    private Lookahead() {}

    private static final int EVERY = 5;        // ticks between refreshes
    private static final int MAX_PER_PLAYER = 64;

    public static void init() {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            if (e.getServer().getTickCount() % EVERY != 0) return;
            for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) refresh(p);
        });
    }

    static void refresh(ServerPlayer p) {
        ServerLevel level = p.level();
        int view = Math.max(2, level.getServer().getPlayerList().getViewDistance());
        double yaw = Math.toRadians(p.getYRot());
        ChunkPos c = p.chunkPosition();
        int added = cone(level, c, -Math.sin(yaw), Math.cos(yaw), Math.min(view, 8), 0);
        Vec3 v = p.getKnownMovement();
        double speed = Math.hypot(v.x, v.z);  // blocks per tick
        if (speed > 0.25)  // riding, sailing, flying: also where the player is about to be
            cone(level, c, v.x / speed, v.z / speed, Math.min(view, 8 + (int) (speed * 6)), added);
    }

    /** Tickets on the chunks within about 35 degrees of (dx, dz), from 2 to depth chunks out. */
    private static int cone(ServerLevel level, ChunkPos c, double dx, double dz, int depth, int already) {
        int added = 0;
        for (int d = 2; d <= depth && already + added < MAX_PER_PLAYER; d++) {
            int half = Math.max(1, (int) Math.round(d * 0.7));
            double px = -dz, pz = dx;  // perpendicular
            for (int s = -half; s <= half && already + added < MAX_PER_PLAYER; s++) {
                int x = c.x() + (int) Math.round(dx * d + px * s * 0.9);
                int z = c.z() + (int) Math.round(dz * d + pz * s * 0.9);
                level.getChunkSource().addTicketWithRadius(PerfTickets.LOOKAHEAD.get(), new ChunkPos(x, z), 0);
                added++;
            }
        }
        return added;
    }
}
