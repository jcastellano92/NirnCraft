package com.nirncraft.crafting;

import com.nirncraft.economy.ItemValues;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Base gold values used by this module for Smithing XP (Skyrim awards smithing XP based on the value of the created
 * item). There is one source of truth: the economy's {@link ItemValues} table, loaded from
 * {@code data/<ns>/nirncraft_values/*.json} (this module's items are priced in {@code nirncraft_values/crafting.json},
 * vanilla ones in {@code vanilla.json}) and synced to clients.
 */
public final class CraftingValues {
    private CraftingValues() {}

    public static int of(ItemStack stack) {
        return stack.isEmpty() ? 0 : of(stack.getItem());
    }

    public static int of(Item item) {
        return ItemValues.base(item);
    }
}
