package com.nirncraft.fire;

import com.nirncraft.NirnCraft;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.LogicalSide;
import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tells each player about the fire sites near them: a full list within {@link FireConfig#SYNC_RADIUS} blocks, sent on
 * login, on dimension change, when the sites changed, and when the player has walked far enough that the set of
 * nearby sites would differ. At most one packet per player every few seconds; the list is capped at 400 sites
 * (nearest first), a few KB.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class FireSync {
    private static final int MAX_SITES = 400;
    private static final int MOVE_RESEND = 160;

    private record Sent(int version, int x, int z, String dim) {
    }

    private static final Map<UUID, Sent> SENT = new HashMap<>();

    private FireSync() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SENT.clear();
    }

    @SubscribeEvent
    public static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.LevelTickEvent.Post) || !(event.getLevel() instanceof ServerLevel level)) return;
        long t = level.getGameTime();
        if (t % 20 != 7) return;
        try {
            FireSites.expire(level);
            FireSites.Data data = FireSites.data(level);
            for (ServerPlayer p : level.players()) {
                send(p, level, data, false);
            }
        } catch (RuntimeException e) {
            NirnCraft.LOGGER.error("Fire site sync failed", e);
        }
    }

    /** Sends the list now when it changed (or when {@code force}). */
    public static void send(ServerPlayer p, ServerLevel level, FireSites.Data data, boolean force) {
        String dim = level.dimension().identifier().toString();
        Sent last = SENT.get(p.getUUID());
        int px = p.getBlockX(), pz = p.getBlockZ();
        boolean moved = last == null || Math.abs(px - last.x) > MOVE_RESEND || Math.abs(pz - last.z) > MOVE_RESEND;
        if (!force && last != null && last.version == data.version() && last.dim.equals(dim) && !moved) return;
        if (!force && last == null && data.sites.isEmpty()) {
            SENT.put(p.getUUID(), new Sent(data.version(), px, pz, dim));
            return;
        }
        int r = FireConfig.SYNC_RADIUS.get();
        long r2 = (long) r * r;
        List<FireSites.Site> near = new ArrayList<>();
        for (FireSites.Site s : data.sites.values()) {
            long dx = s.x() - px, dz = s.z() - pz;
            if (dx * dx + dz * dz <= r2) near.add(s);
        }
        if (near.size() > MAX_SITES) {
            near.sort(Comparator.comparingLong(s -> {
                long dx = s.x() - px, dz = s.z() - pz;
                return dx * dx + dz * dz;
            }));
            near = new ArrayList<>(near.subList(0, MAX_SITES));
        }
        SkyNetwork.sendToPlayer(p, new FirePackets.SyncSites(level.dimension().identifier(), near));
        SENT.put(p.getUUID(), new Sent(data.version(), px, pz, dim));
    }
}
