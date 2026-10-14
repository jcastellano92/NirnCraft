package com.skycraft.crafting.recipe;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * One material requirement of a station recipe or tempering step: anything matching {@code test}, {@code count} times.
 * Counted in, and removed from, the player's main inventory and offhand (armor slots are never consumed).
 */
public record Cost(Predicate<ItemStack> test, Supplier<ItemStack> display, int count) {
    /** Inventory slots searched for materials: main inventory (0..35) and offhand (40). */
    private static final int OFFHAND_SLOT = 40;

    public static Cost of(Supplier<? extends ItemLike> item, int count) {
        return new Cost(stack -> stack.is(item.get().asItem()), () -> new ItemStack(item.get()), count);
    }

    public static Cost tag(TagKey<Item> tag, Supplier<? extends ItemLike> fallback, int count) {
        return new Cost(stack -> stack.is(tag), () -> BuiltInRegistries.ITEM.getTag(tag)
                .flatMap(set -> set.stream().findFirst())
                .map(holder -> new ItemStack(holder.value()))
                .orElseGet(() -> new ItemStack(fallback.get())), count);
    }

    public static Cost ingredient(Ingredient ingredient, int count) {
        return new Cost(ingredient, () -> {
            ItemStack[] items = ingredient.getItems();
            return items.length == 0 ? ItemStack.EMPTY : items[0].copy();
        }, count);
    }

    public boolean matches(ItemStack stack) {
        return !stack.isEmpty() && test.test(stack);
    }

    /** How many matching items the inventory holds, ignoring {@code exceptSlot} (-1 for none). */
    public int countIn(Inventory inv, int exceptSlot) {
        int n = 0;
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            if (slot == exceptSlot || !searched(slot)) continue;
            ItemStack stack = inv.getItem(slot);
            if (matches(stack)) n += stack.getCount();
        }
        // the overflow pack (inventory.Pack) counts like slots do
        return n + com.skycraft.inventory.Pack.count(inv.player, this::matches);
    }

    /** Removes {@code amount} matching items; call only after {@link #countIn} confirmed they are there. */
    public void removeFrom(Inventory inv, int amount, int exceptSlot) {
        int left = amount;
        for (int slot = 0; slot < inv.getContainerSize() && left > 0; slot++) {
            if (slot == exceptSlot || !searched(slot)) continue;
            ItemStack stack = inv.getItem(slot);
            if (!matches(stack)) continue;
            int take = Math.min(left, stack.getCount());
            stack.shrink(take);
            if (stack.isEmpty()) inv.setItem(slot, ItemStack.EMPTY);
            left -= take;
        }
        if (left > 0 && !inv.player.level().isClientSide) left -= com.skycraft.inventory.Pack.remove(inv.player, this::matches, left);
        inv.setChanged();
    }

    private static boolean searched(int slot) {
        return slot < 36 || slot == OFFHAND_SLOT;
    }
}
