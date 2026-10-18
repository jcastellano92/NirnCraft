package com.skycraft.sailing;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;

/**
 * Crossing the seas of Nirn: ships, ports, ferries and the sea itself.
 *
 * <ul>
 *     <li>Ships ({@link ShipEntity}, {@link ShipType}): rowboat, Nord longship and Imperial cog with oars, sails that
 *     draw from the regional wind ({@link SeaWind}), momentum, grounding, hull damage, sinking and plank repairs,
 *     seats for a crew, a horse stall and a cargo hold. Built at the shipwright's bench ({@link Shipwright}) or bought
 *     from harbormasters; launched from their item ({@link ShipItem}).</li>
 *     <li>Ports ({@link Ports}, data {@code skycraft/tamriel/ports.json}; sites from the map in {@link PortSites}):
 *     piers built on demand ({@link DockBuilder}, {@link Harbors}, settlements may register their own dock with
 *     {@link Harbors#registerDock}) with a harbormaster from the society module, and expedition landings with a camp on
 *     the foreign coasts.</li>
 *     <li>Ferries ({@link Ferries}): passage along the sea routes ({@link SeaRoutes} charts them) and expeditions, with
 *     the voyage screen; party members, mounts and followers sail along.</li>
 *     <li>The sea: wrecks on the sea bed and floating flotsam ({@code world.*}), storms that batter ships far from
 *     shore.</li>
 *     <li>Client ({@code client.*}): ship renderer and models, helm and sail keys, the sailing HUD, voyage screen,
 *     shipwright screen, sea routes and ports on the map, compass marker for the nearest port.</li>
 *     <li>{@code /skycraft-sailing}: {@link SailingCommands}.</li>
 * </ul>
 */
public final class SailingModule {
    private SailingModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        com.skycraft.Skycraft.config(ModConfig.Type.LOCAL, SailingConfig.SPEC, "skycraft-sailing.toml");
        SailingRegistry.init(modBus);
        Harbors.register(modBus);
        Ferries.register();
        com.skycraft.seas.SeasModule.init(modBus);
    }

    /** Registers this module's packets via {@link com.skycraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        SailingPackets.register();
    }
}
