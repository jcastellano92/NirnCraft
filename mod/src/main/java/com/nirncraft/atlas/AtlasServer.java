package com.nirncraft.atlas;

import com.nirncraft.network.SkyNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Server side of atlas (dedicated and integrated): the exact far-tile stream and the far-actor feed. Call
 * {@code AtlasServer.init()} from common setup and {@code AtlasNet.register()} from SkyNetwork.init.
 */
public final class AtlasServer {
    private AtlasServer() {}

    public static void init() {
        // the integrated server beside the GPU world (docs/ATLAS-GPU-LOOP.md section 4)
        com.nirncraft.atlas.server.AtlasServerCap.init();
        com.nirncraft.atlas.server.AtlasServerStats.init();
        // never crash the server for the far field: a failing hook turns its feature off with one WARN line
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) ->
                AtlasGuard.run("atlas tile service", () -> AtlasTileServer.start(e.getServer())));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
            AtlasGuard.run("atlas tile service stop", AtlasTileServer::stop);
            AtlasGuard.run("atlas natural sites stop", AtlasNatural::stop);
            AtlasGuard.run("atlas far changes stop", AtlasEdits::stop);
        });
        // the change feed (docs/ATLAS-DELTAS.md): what the world does that the far field's prediction cannot know
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.BlockEvent.NeighborNotifyEvent e) ->
                AtlasGuard.run("atlas far changes", () -> AtlasEdits.onBlockChanged(e)));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Load e) ->
                AtlasGuard.run("atlas far changes", () -> AtlasEdits.onChunkLoaded(e)));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            AtlasGuard.run("atlas tile service", () -> {
                AtlasNet.Hello hello = AtlasTileServer.hello();
                if (hello != null && e.getEntity() instanceof ServerPlayer p) SkyNetwork.sendToPlayer(p, hello);
            });
            AtlasGuard.run("atlas natural sites", () -> AtlasNatural.forget(e.getEntity().getUUID()));
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) AtlasGuard.run("atlas tile service", () -> AtlasTileServer.forget(p));
            AtlasGuard.run("atlas natural sites", () -> AtlasNatural.forget(e.getEntity().getUUID()));
        });
        // a tick that runs long names its stack (ServerStallWatch); our own hooks over 50 ms name themselves
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, (ServerTickEvent.Pre e) ->
                com.nirncraft.atlas.server.ServerStallWatch.tickBegin(e.getServer().getTickCount()));
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, (ServerTickEvent.Post e) ->
                com.nirncraft.atlas.server.ServerStallWatch.tickEnd());
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> com.nirncraft.atlas.server.ServerStallWatch.start());
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> com.nirncraft.atlas.server.ServerStallWatch.stop());
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            timed("atlas tile service", AtlasTileServer::tick);
            timed("atlas far actors", () -> AtlasFarActors.tick(e.getServer()));
            timed("atlas far changes", () -> AtlasEdits.tick(e.getServer()));
            timed("atlas natural sites", () -> AtlasNatural.tick(e.getServer()));
        });
    }

    private static long lastSlowLog;

    /** A server tick hook of ours, guarded, and logged when it took over 50 ms (at most once a second). */
    private static void timed(String what, Runnable r) {
        long t0 = System.nanoTime();
        AtlasGuard.run(what, r);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (ms >= 50 && t0 - lastSlowLog > 1_000_000_000L) {
            lastSlowLog = t0;
            com.nirncraft.NirnCraft.LOGGER.warn("{}: a server tick hook took {} ms", what, ms);
        }
    }
}
