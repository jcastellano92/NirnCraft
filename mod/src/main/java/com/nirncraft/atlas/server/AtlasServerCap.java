package com.nirncraft.atlas.server;

import com.nirncraft.NirnCraft;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Quick win of docs/ATLAS-GPU-LOOP.md section 4: with Infinite ATLAS drawing everything from the GPU world, the integrated
 * server loads (and generates) only the simulation radius, not the render distance. Atlas draws everything beyond.
 *
 * <p>The radius is the player-list simulation distance, at most 8 chunks ({@code -Dnirncraft.atlas.serverView=N} sets it,
 * {@code -1} turns the cap off). It goes to every level's chunk map ({@code ServerChunkCache.setViewDistance}, public API)
 * and to the clients as the chunk-cache radius, so their vanilla chunk loading and {@code getEffectiveRenderDistance}
 * follow. {@code PlayerList.setViewDistance} would be undone on the next tick: the integrated server sets it back to the
 * client's render distance whenever the two differ, so the player list keeps the option's value and the chunk maps the
 * cap; this class puts the cap back whenever the list's value moves (the options screen).</p>
 */
public final class AtlasServerCap {
    private AtlasServerCap() {}

    /** The largest cap taken from the simulation distance (chunks). */
    static final int MAX = 8;

    private static int applied = -1, listSeen = -1;

    public static void init() {
        if (!AtlasServerMode.capView()) return;
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre e) -> guarded(() -> apply(e.getServer())));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> guarded(() -> {
            if (e.getEntity() instanceof ServerPlayer p && applied > 0) p.connection.send(new ClientboundSetChunkCacheRadiusPacket(applied));
        }));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e) -> {
            applied = listSeen = -1;
        });
    }

    private static boolean failed;

    private static void guarded(Runnable r) {
        if (failed) return;
        try {
            r.run();
        } catch (Throwable t) {
            failed = true;  // (never crash the server for the far field: one WARN, then the vanilla radius stays)
            NirnCraft.LOGGER.warn("atlas server view cap off for this session: {}", t.toString());
        }
    }

    /** The radius the server's chunk maps use: the cap, or the player list's own value where there is none. */
    public static int viewDistance(MinecraftServer server) {
        int list = server.getPlayerList().getViewDistance();
        return AtlasServerMode.capView() && !failed ? Math.min(list, target(server)) : list;
    }

    private static int target(MinecraftServer server) {
        int forced = Integer.getInteger("nirncraft.atlas.serverView", 0);
        int sim = server.getPlayerList().getSimulationDistance();
        int cap = forced > 0 ? forced : Math.min(sim > 0 ? sim : MAX, MAX);
        return Math.max(2, cap);
    }

    private static void apply(MinecraftServer server) {
        int list = server.getPlayerList().getViewDistance();
        if (list <= 0) return;  // (not set yet)
        int want = Math.min(list, target(server));
        if (want == applied && list == listSeen) return;
        for (ServerLevel level : server.getAllLevels()) level.getChunkSource().setViewDistance(want);
        if (want != applied || list != listSeen) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) p.connection.send(new ClientboundSetChunkCacheRadiusPacket(want));
            NirnCraft.LOGGER.info("atlas: the server loads {} chunks around the player (render distance {}, simulation distance {}): the GPU world draws the rest",
                    want, list, server.getPlayerList().getSimulationDistance());
        }
        applied = want;
        listSeen = list;
    }
}
