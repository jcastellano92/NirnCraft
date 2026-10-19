package com.nirncraft.tamriel;

import net.neoforged.bus.api.IEventBus;

/**
 * Nirn worldgen and geography: the Tamriel map, terrain, biomes, regions, places and their structures.
 * See docs/TAMRIEL.md. Each part registers its own content from its own setup class.
 */
public final class TamrielModule {
    private TamrielModule() {}

    public static void init(IEventBus modBus) {
        modBus.addListener(com.nirncraft.worldgen.NativeRecipeRules::addPacks);
        com.nirncraft.world.ocean.OceanWorldConfig.register();
        TamrielTerrain.init(modBus);
        TamrielBiomes.init(modBus);
        TamrielStructures.init(modBus);
        TamrielRegionsSetup.init(modBus);
    }
}
