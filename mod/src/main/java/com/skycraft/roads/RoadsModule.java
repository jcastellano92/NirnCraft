package com.skycraft.roads;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Road network between settlements, signposts, and travelers/caravans/patrols walking the roads.
 *
 * <ul>
 *     <li>{@link RoadNetwork}: destination detection ({@code #skycraft:towns} and {@code #skycraft:road_destinations}
 *     structure starts in loaded chunks),
 *     background road planning, feeding loaded road chunks to the {@link Paver}.</li>
 *     <li>{@link RoadPlanner}: A* on the generator's noise heights (never loads chunks).</li>
 *     <li>{@link Paver}: budgeted, idempotent paving, bridges, lantern posts and signposts.</li>
 *     <li>{@link Travelers}: caravans, travelers, guard patrols and bandit ambushes on roads near players.</li>
 *     <li>{@link RoadsPackets}: compass markers for nearby undiscovered settlements (source {@code "roads"}).</li>
 *     <li>{@link RoadsCommands}: {@code /skycraft-roads}.</li>
 * </ul>
 * Forge-bus handlers are registered through {@code @Mod.EventBusSubscriber}.
 */
public final class RoadsModule {
    private RoadsModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, RoadsConfig.SPEC, "skycraft-roads.toml");
        Carriages.register();
    }

    /** Registers this module's packets via {@link com.skycraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        RoadsPackets.register();
    }
}
