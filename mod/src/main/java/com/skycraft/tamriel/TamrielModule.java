package com.skycraft.tamriel;

import net.minecraftforge.eventbus.api.IEventBus;

/**
 * Nirn worldgen and geography: the Tamriel map, terrain, biomes, regions, places and their structures.
 * See docs/TAMRIEL.md. Each part registers its own content from its own setup class.
 */
public final class TamrielModule {
    private TamrielModule() {}

    public static void init(IEventBus modBus) {
        TamrielTerrain.init(modBus);
        TamrielBiomes.init(modBus);
        TamrielStructures.init(modBus);
        TamrielRegionsSetup.init(modBus);
    }
}
