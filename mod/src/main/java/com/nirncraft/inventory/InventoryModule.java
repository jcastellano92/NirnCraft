package com.nirncraft.inventory;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;

/**
 * Skyrim inventory screen, item weights, carry weight, favorites, hand assignment and item persistence.
 *
 * <p>Server side: {@link ItemWeights} (data-driven weights, contract 19), {@link CarryWeight} (capacity and
 * encumbrance), {@link InventoryActions} (equip/use/drop/favorite/read requests), {@link ItemPersistence}
 * (player-dropped items never despawn). Client side: {@code com.nirncraft.inventory.client} (Skyrim inventory screen
 * replacing the vanilla survival inventory, favorites quick menu, carry weight HUD).</p>
 */
public final class InventoryModule {
    private InventoryModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        ContainerOverflow.init(modBus);
        com.nirncraft.NirnCraft.config(ModConfig.Type.LOCAL, InventoryConfig.COMMON_SPEC, "nirncraft-inventory-common.toml");
        com.nirncraft.NirnCraft.config(ModConfig.Type.CLIENT, InventoryConfig.CLIENT_SPEC, "nirncraft-inventory-client.toml");
        PackSelfTest.registerAutorun();
    }

    /** Registers this module's packets via {@link com.nirncraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        InventoryPackets.register();
    }
}
