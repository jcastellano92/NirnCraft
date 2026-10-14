package com.skycraft.inventory;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Skyrim inventory screen, item weights, carry weight, favorites, hand assignment and item persistence.
 *
 * <p>Server side: {@link ItemWeights} (data-driven weights, contract 19), {@link CarryWeight} (capacity and
 * encumbrance), {@link InventoryActions} (equip/use/drop/favorite/read requests), {@link ItemPersistence}
 * (player-dropped items never despawn). Client side: {@code com.skycraft.inventory.client} (Skyrim inventory screen
 * replacing the vanilla survival inventory, favorites quick menu, carry weight HUD).</p>
 */
public final class InventoryModule {
    private InventoryModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, InventoryConfig.COMMON_SPEC, "skycraft-inventory-common.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, InventoryConfig.CLIENT_SPEC, "skycraft-inventory-client.toml");
        PackSelfTest.registerAutorun();
    }

    /** Registers this module's packets via {@link com.skycraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        InventoryPackets.register();
    }
}
