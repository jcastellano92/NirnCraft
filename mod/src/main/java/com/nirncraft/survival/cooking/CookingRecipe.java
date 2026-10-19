package com.nirncraft.survival.cooking;

import com.nirncraft.crafting.recipe.Cost;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

import java.util.List;
import java.util.function.Supplier;

/** A cooking pot recipe. {@code category} is one of {@link CookingRecipes#CATEGORIES}. */
public record CookingRecipe(String id, String category, Supplier<? extends ItemLike> result, int count, List<Cost> costs) {

    public ItemStack resultStack() {
        return new ItemStack(result.get(), count);
    }

    public boolean hasMaterials(Inventory inv) {
        for (Cost cost : costs) {
            if (cost.countIn(inv, -1) < cost.count()) return false;
        }
        return true;
    }
}
