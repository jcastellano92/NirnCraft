package com.skycraft.world;

import com.skycraft.society.NpcPlacement;
import com.skycraft.society.Schedule;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the townsfolk consistent with a time skip ({@link RestManager}). While the clock races, the loaded townspeople
 * near the resting players take big steps along their real navigation path towards where their schedule says they
 * belong now (so they visibly walk to their stations, open the shops, go home and go to bed at accelerated speed);
 * when the skip ends or is cancelled, every loaded scheduled person who is not at the place the schedule gives for the
 * time that actually passed is put there ({@link NpcPlacement#snapToSchedule}: a standable cell beside the anchor, in
 * the bed for sleepers). Server side only, throttled by the caller.
 */
final class RestSim {
    /** Townspeople are only stepped within this many blocks of a resting player. */
    private static final double HOP_RADIUS = 64;
    /** Townspeople this far from a resting player (and loaded) are settled at the end. */
    private static final double SETTLE_RADIUS = 160;
    /** Nodes of the path moved per hop (big steps; a node is a walkable cell). */
    private static final int HOP_NODES = 14;

    private RestSim() {}

    /** A person who keeps a daily routine (same conditions as {@code NpcEntity#placeBySchedule}). */
    private static boolean scheduled(NpcEntity npc) {
        return npc.isAlive() && !npc.isRemoved() && com.skycraft.society.SocietyConfig.ROUTINES.get() && npc.getHome() != null
                && !npc.isBusy() && npc.getTarget() == null && !npc.isPrisoner() && !npc.isConversing()
                && (npc.role().townsfolk() || com.skycraft.society.Marriage.isSpouse(npc))
                && !npc.social().hasUUID("engaged")
                && !npc.getPersistentData().contains("skycraft_traveler");
    }

    /** The activity the schedule gives this person now, with the night rule of {@link NpcPlacement#snapToSchedule}. */
    @Nullable
    private static Schedule.Activity activityNow(NpcEntity npc) {
        Schedule.Activity a = Schedule.activity(npc);
        if (a == Schedule.Activity.IDLE) return null;
        if (com.skycraft.society.Npcs.isNight(npc.level()) && NpcPlacement.outAtNight(npc) && a != Schedule.Activity.SLEEP) return null;
        return a;
    }

    /**
     * One accelerated step for up to {@code cap} loaded townspeople around {@code player}: whoever is away from the
     * anchor of the current activity hops up to {@link #HOP_NODES} nodes along the path to it.
     * Returns how many were handled (so the caller can share a budget between players).
     */
    static int hop(ServerPlayer player, int cap, int offset) {
        if (cap <= 0) return 0;
        ServerLevel level = player.serverLevel();
        AABB box = player.getBoundingBox().inflate(HOP_RADIUS, 32, HOP_RADIUS);
        List<NpcEntity> npcs = level.getEntitiesOfClass(NpcEntity.class, box, RestSim::scheduled);
        if (npcs.isEmpty()) return 0;
        int handled = 0;
        int n = npcs.size();
        for (int i = 0; i < n && handled < cap; i++) {
            NpcEntity npc = npcs.get(Math.floorMod(i + offset, n));
            Schedule.Activity a = activityNow(npc);
            if (a == null) continue;
            if (a != Schedule.Activity.SLEEP && npc.isSleeping()) npc.stopSleeping();
            if (npc.isSleeping()) continue;
            BlockPos anchor = NpcPlacement.anchorFor(npc, a);
            if (anchor == null || !level.isLoaded(anchor)) continue;
            npc.activity = a;
            double dx = anchor.getX() + 0.5 - npc.getX(), dz = anchor.getZ() + 0.5 - npc.getZ();
            if (dx * dx + dz * dz <= 3.0 * 3.0 && Math.abs(npc.getY() - anchor.getY()) < 4) continue;
            handled++;
            Path path = npc.getNavigation().createPath(anchor, 2);
            if (path == null || path.getNodeCount() < 2) continue;
            int idx = Math.min(path.getNodeCount() - 1, HOP_NODES);
            BlockPos step = path.getNode(idx).asBlockPos();
            if (!level.isLoaded(step)) continue;
            NpcPlacement.moveTo(npc, step);
        }
        return handled;
    }

    /**
     * The skip is over (or was cancelled): every loaded scheduled townsperson near the given players who is not
     * where the schedule puts them for the time that really passed is moved there. Returns how many were moved.
     */
    static int settle(List<ServerPlayer> players) {
        Set<UUID> seen = new HashSet<>();
        int moved = 0;
        for (ServerPlayer p : players) {
            ServerLevel level = p.serverLevel();
            AABB box = p.getBoundingBox().inflate(SETTLE_RADIUS, 96, SETTLE_RADIUS);
            for (NpcEntity npc : level.getEntitiesOfClass(NpcEntity.class, box, RestSim::scheduled)) {
                if (!seen.add(npc.getUUID())) continue;
                try {
                    Schedule.Activity a = activityNow(npc);
                    if (a == null) continue;
                    if (a != Schedule.Activity.SLEEP && npc.isSleeping()) npc.stopSleeping();
                    double before = npc.getX() * 31 + npc.getZ();
                    NpcPlacement.snapToSchedule(npc);
                    if (npc.getX() * 31 + npc.getZ() != before) moved++;
                } catch (RuntimeException ignored) {
                    // one odd NPC must not break the wake-up of the others
                }
            }
        }
        return moved;
    }
}
