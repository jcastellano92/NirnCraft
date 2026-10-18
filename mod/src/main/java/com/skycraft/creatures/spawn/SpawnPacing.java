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
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pacing rules shared by every source of hostile encounters (the region spawner, the vanilla night replacements and
 * the wilderness encounters): calm zones around the world spawn and settlements, safer roads, a per-player cooldown
 * between hostile events with a grace period after (re)spawning, and no bosses or bandit camps near the start.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class SpawnPacing {
    /** Entity ids (skycraft namespace) never placed near the start or at very low player levels. */
    private static final Set<String> START_BANNED = Set.of(
            "bandit_chief", "bandit", "draugr_deathlord", "giant", "dragon",
            "dragon_priest", "vampire_lord", "frost_giant", "hagraven",
            "troll", "sabre_cat", "senche_tiger", "bear", "spriggan",
            "wamasu", "swamp_leviathan", "chaurus", "chaurus_hunter",
            "gargoyle", "werewolf", "minotaur", "ogre", "sand_serpent",
            "giant_scorpion", "harpy", "wispmother", "dremora",
            "frost_atronach", "storm_atronach"
    );

    private static final Map<UUID, Long> LAST_HOSTILE = new HashMap<>();
    /** Game time (per player) before which no hostile event may happen: set on login and on respawn. */
    private static final Map<UUID, Long> QUIET_UNTIL = new HashMap<>();

    private SpawnPacing() {}

    // ------------------------------------------------------------------ zones

    /**
     * Horizontal distance from the starter corridor (world spawn, Start Cottage at -223,1791,
     * Helgen at -152,1696, Riverwood at -24,1143, and the Helgen-to-Riverwood road segment),
     * or a huge number outside the overworld.
     */
    public static double distFromStart(Level level, double x, double z) {
        if (level.dimension() != Level.OVERWORLD) return Double.MAX_VALUE;
        BlockPos s = level.getRespawnData().pos();
        double dSpawn = Math.hypot(x - s.getX(), z - s.getZ());
        double dCottage = Math.hypot(x - (-223.0), z - 1791.0);
        double dHelgen = Math.hypot(x - (-152.0), z - 1696.0);
        double dRiverwood = Math.hypot(x - (-24.0), z - 1143.0);
        double dCorridor = distToSegment(x, z, -152.0, 1696.0, -24.0, 1143.0);
        return Math.min(Math.min(dSpawn, dCottage), Math.min(Math.min(dHelgen, dRiverwood), dCorridor));
    }

    private static double distToSegment(double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax, vz = bz - az;
        double len2 = vx * vx + vz * vz;
        if (len2 <= 1e-6) return Math.hypot(px - ax, pz - az);
        double t = Math.max(0.0, Math.min(1.0, ((px - ax) * vx + (pz - az) * vz) / len2));
        double cx = ax + t * vx, cz = az + t * vz;
        return Math.hypot(px - cx, pz - cz);
    }

    /** True inside the initial walk corridor from the Start Cottage (-223, 1791) to Helgen (-152, 1696). */
    public static boolean inStarterSafeCorridor(Level level, double x, double z) {
        if (level.dimension() != Level.OVERWORLD) return false;
        BlockPos s = level.getRespawnData().pos();
        if (Math.hypot(x - s.getX(), z - s.getZ()) < 96.0) return true;
        return distToSegment(x, z, -223.0, 1791.0, -152.0, 1696.0) < 96.0;
    }

    /** No natural hostile spawns here: around the world spawn, starter walk to Helgen, and within the configured radius of any settlement. */
    public static boolean calm(Level level, BlockPos pos) {
        int r = CreaturesConfig.CALM_RADIUS.get();
        if (r <= 0 || level.dimension() != Level.OVERWORLD) return false;
        if (inStarterSafeCorridor(level, pos.getX(), pos.getZ())) return true;
        BlockPos s = level.getRespawnData().pos();
        if (Math.hypot(pos.getX() - s.getX(), pos.getZ() - s.getZ()) < r) return true;
        if (Math.hypot(pos.getX() - (-223.0), pos.getZ() - 1791.0) < r) return true;
        for (Place p : Places.overlapping(pos.getX() - r, pos.getZ() - r, pos.getX() + r, pos.getZ() + r)) {
            if (p.type().isSettlement()) return true;
        }
        return false;
    }

    /** The start area (bosses, apex predators, and bandit camps stay out of it). */
    public static boolean nearStart(Level level, double x, double z) {
        int r = CreaturesConfig.START_AREA_RADIUS.get();
        return r > 0 && distFromStart(level, x, z) < r;
    }

    /** Whether this skycraft entity id (path) may not be placed near the start or at level 1-3. */
    public static boolean bannedNearStart(String path) {
        return START_BANNED.contains(path);
    }

    /** A road or its verge: hostile natural spawns are rare here. */
    public static boolean onRoad(Level level, BlockPos pos) {
        try {
            if (RoadCells.isRoad(level, pos.getX(), pos.getZ(), 18)) return true;
            com.skycraft.worldplan.RoadPlan.Hit hit = new com.skycraft.worldplan.RoadPlan.Hit();
            return com.skycraft.worldplan.RoadPlan.get().at(pos.getX() + 0.5, pos.getZ() + 0.5, hit)
                    && hit.distance <= hit.halfWidth + 18f;
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
        if (player.level().isClientSide()) return;
        QUIET_UNTIL.put(player.getUUID(), player.level().getGameTime() + CreaturesConfig.GRACE_SECONDS.get() * 20L);
    }

    /** True when a hostile event near this player may happen now (not in the grace period, cooldown over). */
    public static boolean hostileReady(ServerPlayer player) {
        // nobody is hunted while they or the world around them rest (world/RestManager)
        if (com.skycraft.world.RestManager.spawnsBlocked(player.level(), player.getX(), player.getY(), player.getZ())) return false;
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
