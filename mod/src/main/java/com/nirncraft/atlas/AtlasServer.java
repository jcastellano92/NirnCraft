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
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            AtlasGuard.run("atlas tile service", AtlasTileServer::tick);
            AtlasGuard.run("atlas far actors", () -> AtlasFarActors.tick(e.getServer()));
            AtlasGuard.run("atlas far changes", () -> AtlasEdits.tick(e.getServer()));
            AtlasGuard.run("atlas natural sites", () -> AtlasNatural.tick(e.getServer()));
        });
    }
}
