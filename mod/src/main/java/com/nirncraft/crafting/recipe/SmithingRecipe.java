package com.nirncraft.crafting.recipe;

import com.nirncraft.crafting.CraftingValues;
import com.nirncraft.crafting.StationType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * A station recipe (forge, smelter, tanning rack). {@link #value()} is the gold value of the whole result stack, read
 * from the economy's item values when asked; it drives the Smithing XP (Skyrim: XP depends on the value of what you make).
 */
public record SmithingRecipe(String id, StationType station, String category, Supplier<? extends ItemLike> result, int count,
                             List<Cost> costs, @Nullable String perk) {

    public ItemStack resultStack() {
        return new ItemStack(result.get(), count);
    }

    /** Gold value of one craft (the whole result stack). */
    public int value() {
        return CraftingValues.of(result.get().asItem()) * count;
    }

    public boolean hasMaterials(Inventory inv) {
        for (Cost cost : costs) {
            if (cost.countIn(inv, -1) < cost.count()) return false;
        }
        return true;
    }

    public void consume(Inventory inv) {
        for (Cost cost : costs) cost.removeFrom(inv, cost.count(), -1);
    }
}
