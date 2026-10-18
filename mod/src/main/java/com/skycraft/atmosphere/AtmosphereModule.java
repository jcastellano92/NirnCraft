package com.skycraft.atmosphere;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Skyrim sky (Masser and Secunda, aurora, stars) and the original Skycraft soundtrack.
 *
 * <p>Soundtrack: {@code assets/skycraft/sounds/music/*.ogg}, composed and synthesised by {@code tools/music/compose.py}
 * and mapped onto the world module's {@code skycraft:music.*} events in {@code sounds.json}; this module adds
 * {@code music.boss} and {@code music.level_up} ({@link AtmosphereSounds}). Sky: {@code atmosphere.client.SkyRenderer}
 * draws in {@code RenderLevelStageEvent.Stage.AFTER_SKY}; Masser is the vanilla moon with a new texture.</p>
 *
 * <p>Weather: {@code weather.RegionalWeather} gives every region near a player its own weather from the region's
 * climate profile and syncs it; {@code client.ClientWeather} renders it locally (rain level, fog, overcast, custom
 * snow/ash/sand). The module also hosts {@code com.skycraft.visual} (particles, HUD and menu art).</p>
 */
public final class AtmosphereModule {
    private AtmosphereModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        AtmosphereSounds.init(modBus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, AtmosphereConfig.CLIENT_SPEC, "skycraft-atmosphere-client.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, AtmosphereConfig.SERVER_SPEC, "skycraft-atmosphere-server.toml");
        com.skycraft.visual.VisualModule.init(modBus);
    }

    /** Registers this module's packets via {@link com.skycraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        com.skycraft.atmosphere.weather.WeatherPackets.register();
        DungeonAir.register();
    }
}
