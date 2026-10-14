package com.skycraft.creatures.spawn;

import com.skycraft.Skycraft;
import com.skycraft.creatures.CreaturesConfig;
import com.skycraft.roads.RoadCells;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pacing rules shared by every source of hostile encounters (the region spawner, the vanilla night replacements and
 * the wilderness encounters): calm zones around the world spawn and settlements, safer roads, a per-player cooldown
 * between hostile events with a grace period after (re)spawning, and no bosses or bandit camps near the start.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class SpawnPacing {
    /** Entity ids (skycraft namespace) never placed near the start: bosses and the bandits that make camps. */
    private static final Set<String> START_BANNED = Set.of("bandit_chief", "bandit", "draugr_deathlord", "giant", "dragon",
            "dragon_priest", "vampire_lord", "frost_giant", "hagraven");

    private static final Map<UUID, Long> LAST_HOSTILE = new HashMap<>();
    /** Game time (per player) before which no hostile event may happen: set on login and on respawn. */
    private static final Map<UUID, Long> QUIET_UNTIL = new HashMap<>();

    private SpawnPacing() {}

    // ------------------------------------------------------------------ zones

    /** Horizontal distance from the world spawn, or a huge number outside the overworld. */
    public static double distFromStart(Level level, double x, double z) {
        if (level.dimension() != Level.OVERWORLD) return Double.MAX_VALUE;
        BlockPos s = level.getSharedSpawnPos();
        return Math.hypot(x - s.getX(), z - s.getZ());
    }

    /** No natural hostile spawns here: around the world spawn and within the configured radius of any settlement. */
    public static boolean calm(Level level, BlockPos pos) {
        int r = CreaturesConfig.CALM_RADIUS.get();
        if (r <= 0 || level.dimension() != Level.OVERWORLD) return false;
        if (distFromStart(level, pos.getX(), pos.getZ()) < r) return true;
        for (Place p : Places.overlapping(pos.getX() - r, pos.getZ() - r, pos.getX() + r, pos.getZ() + r)) {
            if (p.type().isSettlement()) return true;
        }
        return false;
    }

    /** The start area (bosses and bandit camps stay out of it). */
    public static boolean nearStart(Level level, double x, double z) {
        int r = CreaturesConfig.START_AREA_RADIUS.get();
        return r > 0 && distFromStart(level, x, z) < r;
    }

    /** Whether this skycraft entity id (path) may not be placed near the world spawn. */
    public static boolean bannedNearStart(String path) {
        return START_BANNED.contains(path);
    }

    /** A road or its verge: hostile natural spawns are rare here. */
    public static boolean onRoad(Level level, BlockPos pos) {
        try {
            return RoadCells.isRoad(level, pos.getX(), pos.getZ(), 8);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Whether any settlement's footprint lies within {@code radius} blocks (box test) of the position. */
    public static boolean settlementWithin(Level level, BlockPos pos, int radius) {
        if (radius <= 0 || level.dimension() != Level.OVERWORLD) return false;
        for (Place p : Places.overlapping(pos.getX() - radius, pos.getZ() - radius, pos.getX() + radius, pos.getZ() + radius)) {
            if (p.type().isSettlement()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ per-player cooldown

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        quiet(event.getEntity());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        quiet(event.getEntity());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_HOSTILE.remove(event.getEntity().getUUID());
        QUIET_UNTIL.remove(event.getEntity().getUUID());
    }

    private static void quiet(net.minecraft.world.entity.player.Player player) {
        if (player.level().isClientSide) return;
        QUIET_UNTIL.put(player.getUUID(), player.level().getGameTime() + CreaturesConfig.GRACE_SECONDS.get() * 20L);
    }

    /** True when a hostile event near this player may happen now (not in the grace period, cooldown over). */
    public static boolean hostileReady(ServerPlayer player) {
        // nobody is hunted while they or the world around them rest (world/RestManager)
        if (com.skycraft.world.RestManager.spawnsBlocked(player.serverLevel(), player.getX(), player.getY(), player.getZ())) return false;
        long now = player.level().getGameTime();
        if (now < QUIET_UNTIL.getOrDefault(player.getUUID(), 0L)) return false;
        Long last = LAST_HOSTILE.get(player.getUUID());
        return last == null || now - last >= CreaturesConfig.HOSTILE_COOLDOWN_SECONDS.get() * 20L;
    }

    /** Records that a hostile event just happened near this player. */
    public static void noteHostile(ServerPlayer player) {
        LAST_HOSTILE.put(player.getUUID(), player.level().getGameTime());
    }

    /** Nearest player within range (vanilla spawn replacements have no player of their own), or null. */
    public static ServerPlayer playerNear(ServerLevel level, BlockPos pos, double range) {
        return level.getNearestPlayer(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, range, p -> !p.isSpectator()) instanceof ServerPlayer sp
                ? sp : null;
    }
}
