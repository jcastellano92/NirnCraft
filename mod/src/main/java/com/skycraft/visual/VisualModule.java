package com.skycraft.visual;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;

/**
 * Skyrim's look and feel in Skycraft's own code: atmospheric particles (frost breath, drifting snow, falling leaves,
 * fireflies, ash, embers, sand, marsh wisps, dungeon dust), the HUD and menu art helpers, the loading screen and
 * its lore tips. Everything here is presentation; the regional weather that drives much of it lives in
 * {@code com.skycraft.atmosphere.weather}. Initialised from {@link com.skycraft.atmosphere.AtmosphereModule}.
 */
public final class VisualModule {
    private VisualModule() {}

    public static void init(IEventBus modBus) {
        VisualParticles.init(modBus);
        com.skycraft.Skycraft.config(ModConfig.Type.CLIENT, VisualConfig.CLIENT_SPEC, "skycraft-visual-client.toml");
    }
}
