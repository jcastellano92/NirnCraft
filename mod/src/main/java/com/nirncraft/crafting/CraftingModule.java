package com.nirncraft.crafting;

import com.nirncraft.crafting.arcane.ArcaneModule;
import com.nirncraft.crafting.menu.CraftingMenus;
import net.neoforged.bus.api.IEventBus;

/**
 * Ores, ingots, Skyrim weapon & armor tiers, the forge, tempering, enchanting, alchemy and soul gems.
 *
 * <p>Smithing: {@link SkyOre} veins and ingots, {@link SmithingTier} weapons ({@link WeaponType}), {@link SkyArmorMaterial}
 * armor, the {@link StationType stations} (blacksmith forge, smelter, tanning rack, grindstone wheel, armor workbench)
 * sharing {@link com.nirncraft.crafting.menu.StationMenu}, station recipes in
 * {@link com.nirncraft.crafting.recipe.SmithingRecipes} and {@link Tempering} (NBT int {@code nirncraft_quality}).</p>
 */
public final class CraftingModule {
    private CraftingModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        CraftingBlocks.BLOCKS.register(modBus);
        CraftingItems.ITEMS.register(modBus);
        CraftingMenus.MENUS.register(modBus);
        RegionalGear.FUNCTIONS.register(modBus);
        // keep: the arcane sub-module (enchanting, soul gems, alchemy) is developed separately
        ArcaneModule.init(modBus);
    }

    /** Registers this module's packets via {@link com.nirncraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        CraftingPackets.register();
        com.nirncraft.homestead.HomesteadPackets.register();
        // keep: arcane packets are registered after the crafting ones
        ArcaneModule.registerPackets();
    }
}
