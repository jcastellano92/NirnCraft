package com.skycraft.world;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;

/**
 * Location discovery, compass, world map & fast travel, sleeping and waiting, music and Oblivion.
 *
 * <p>Server side: {@link Discovery}, {@link RestManager}, {@link FastTravelHandler} (Forge-bus subscribers).
 * Client side: {@code com.skycraft.world.client} (map, wait/sleep screen, soundtrack, fog, compass markers).</p>
 */
public final class WorldModule {
    private WorldModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        WorldSounds.init(modBus);
        com.skycraft.Skycraft.config(ModConfig.Type.LOCAL, WorldConfig.COMMON_SPEC, "skycraft-world-common.toml");
        com.skycraft.Skycraft.config(ModConfig.Type.CLIENT, WorldConfig.CLIENT_SPEC, "skycraft-world-client.toml");
    }

    /** Registers this module's packets via {@link com.skycraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        WorldPackets.register();
    }
}
