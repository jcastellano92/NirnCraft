package com.skycraft.aether;

import com.skycraft.network.SkyNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Server side of aether (dedicated and integrated): the exact far-tile stream and the far-actor feed. Call
 * {@code AetherServer.init()} from common setup and {@code AetherNet.register()} from SkyNetwork.init.
 */
public final class AetherServer {
    private AetherServer() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> AetherTileServer.start(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> AetherTileServer.stop());
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            AetherNet.Hello hello = AetherTileServer.hello();
            if (hello != null && e.getEntity() instanceof ServerPlayer p) SkyNetwork.sendToPlayer(p, hello);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) AetherTileServer.forget(p);
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> AetherFarActors.tick(e.getServer()));
    }
}
