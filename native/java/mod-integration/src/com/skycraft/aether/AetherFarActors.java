package com.skycraft.aether;

import com.skycraft.network.SkyNetwork;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of far actors: twice a second, each overworld player gets the actors it cannot otherwise see (past
 * that entity type's tracking range, capped by the view distance) within FAR blocks, nearest MAX_ACTORS: other
 * players, dragons, ships and large creatures, as aether impostors. Items and small mobs stay near-field only.
 */
final class AetherFarActors {
    private AetherFarActors() {}

    static final double FAR = 4096;
    static final int HUMANOID = 0, CREATURE = 1, DRAGON = 2, SHIP = 3;
    private static final Map<UUID, Integer> lastCount = new HashMap<>();

    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 10 != 0) return;
        ServerLevel level = server.overworld();
        if (level == null) return;
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            lastCount.clear();
            return;
        }
        List<Entity> candidates = new ArrayList<>();
        for (Entity e : level.getAllEntities())
            if (e.isAlive() && !e.isInvisible() && kind(e) >= 0) candidates.add(e);
        double view = server.getPlayerList().getViewDistance() * 16.0;
        for (ServerPlayer p : players) {
            List<double[]> near = new ArrayList<>();  // {distance, index}
            for (int i = 0; i < candidates.size(); i++) {
                Entity e = candidates.get(i);
                if (e == p) continue;
                double d = e.position().distanceTo(p.position());
                double seen = Math.min(e.getType().clientTrackingRange() * 16.0, view) - 8;
                if (d > seen && d < FAR) near.add(new double[] {d, i});
            }
            near.sort((a, b) -> Double.compare(a[0], b[0]));
            int n = Math.min(near.size(), AetherNet.MAX_ACTORS);
            Integer last = lastCount.put(p.getUUID(), n);
            if (n == 0 && (last == null || last == 0)) continue;  // nothing new to say
            double[] xyz = new double[n * 3];
            float[] vel = new float[n * 3], yhr = new float[n * 3];
            int[] kt = new int[n * 2];
            for (int k = 0; k < n; k++) {
                Entity e = candidates.get((int) near.get(k)[1]);
                Vec3 v = e.getDeltaMovement();  // blocks per tick
                xyz[k * 3] = e.getX();
                xyz[k * 3 + 1] = e.getY();
                xyz[k * 3 + 2] = e.getZ();
                vel[k * 3] = (float) v.x * 20f;
                vel[k * 3 + 1] = (float) v.y * 20f;
                vel[k * 3 + 2] = (float) v.z * 20f;
                // aether: 0 faces -Z, forward (sin a, -cos a); Minecraft: 0 faces +Z, forward (-sin y, cos y)
                yhr[k * 3] = (float) (Math.toRadians(e.getYRot()) + Math.PI);
                yhr[k * 3 + 1] = e.getBbHeight();
                yhr[k * 3 + 2] = e.getBbWidth() * 0.5f;
                int kind = kind(e);
                kt[k * 2] = kind;
                kt[k * 2 + 1] = tint(e, kind);
            }
            SkyNetwork.sendToPlayer(p, new AetherNet.FarActors(n, xyz, vel, yhr, kt));
        }
        lastCount.keySet().removeIf(u -> server.getPlayerList().getPlayer(u) == null);
    }

    /** Impostor kind, or -1 for what stays near-field only. */
    static int kind(Entity e) {
        if (e instanceof Player pl) return pl.isSpectator() ? -1 : HUMANOID;
        if (e instanceof EnderDragon) return DRAGON;
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
        if (id.contains("dragon")) return DRAGON;
        if (e instanceof AbstractBoat || id.contains("ship")) return SHIP;
        if (e instanceof LivingEntity && (e.getBbWidth() >= 1.4f || e.getBbHeight() >= 2.6f)) return CREATURE;
        return -1;
    }

    /** RGBA8, r in the low byte: players a muted cloth colour from their UUID, the rest by kind. */
    static int tint(Entity e, int kind) {
        int rgb = switch (kind) {
            case HUMANOID -> {
                long h = e.getUUID().getLeastSignificantBits() * 0x9E3779B97F4A7C15L;
                int r = 70 + (int) (h >>> 58 & 63), g = 60 + (int) (h >>> 52 & 63), b = 55 + (int) (h >>> 46 & 63);
                yield r | g << 8 | b << 16;
            }
            case DRAGON -> 0x44 | 0x3A << 8 | 0x3A << 16;
            case SHIP -> 0x7A | 0x5A << 8 | 0x3A << 16;
            default -> 0x6B | 0x5B << 8 | 0x4B << 16;
        };
        return rgb | 0xFF << 24;
    }
}
