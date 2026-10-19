package com.nirncraft.inventory;

import com.nirncraft.economy.ItemCategory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;

import java.util.Locale;

/**
 * The Skyrim inventory's category list. Classification reuses the economy's {@link ItemCategory} (the barter tabs) and
 * splits spell tomes out of books. {@link #FAVORITES} and {@link #ALL} are filters, never an item's own category.
 */
public enum InvCategory {
    FAVORITES, ALL, WEAPONS, APPAREL, POTIONS, FOOD, INGREDIENTS, BOOKS, SPELL_TOMES, MISC;

    public static final InvCategory[] VALUES = values();
    /** Stack NBT (boolean) marking a favorite. */
    public static final String FAVORITE_NBT = "nirncraft_favorite";
    /** Stack NBT (boolean) set by the crime module on stolen goods (contract 5). */
    public static final String STOLEN_NBT = "nirncraft_stolen";

    public Component displayName() {
        return Component.translatable("inventory.nirncraft.category." + name().toLowerCase(Locale.ROOT));
    }

    /** The category an item is listed under (never FAVORITES/ALL). */
    public static InvCategory of(ItemStack stack) {
        if (stack.is(ItemCategory.SPELL_TOMES)) return SPELL_TOMES;
        return switch (ItemCategory.of(stack)) {
            case WEAPONS -> WEAPONS;
            case APPAREL -> APPAREL;
            case POTIONS -> POTIONS;
            case INGREDIENTS -> INGREDIENTS;
            case BOOKS -> BOOKS;
            case FOOD -> FOOD;
            default -> stack.getUseAnimation() == ItemUseAnimation.DRINK ? POTIONS : MISC;
        };
    }

    public boolean matches(ItemStack stack) {
        return switch (this) {
            case ALL -> true;
            case FAVORITES -> isFavorite(stack);
            default -> of(stack) == this;
        };
    }

    public static boolean isFavorite(ItemStack stack) {
        if (!ItemData.containsCustom(stack, FAVORITE_NBT)) return false;
        CompoundTag tag = com.nirncraft.inventory.ItemData.custom(stack);
        return tag != null && tag.getBooleanOr(FAVORITE_NBT, false);
    }

    public static boolean isStolen(ItemStack stack) {
        if (!ItemData.containsCustom(stack, STOLEN_NBT)) return false;
        CompoundTag tag = com.nirncraft.inventory.ItemData.custom(stack);
        return tag != null && tag.getBooleanOr(STOLEN_NBT, false);
    }

    /** Eaten or drunk straight from the inventory. */
    public static boolean isConsumable(ItemStack stack) {
        if (stack.has(net.minecraft.core.component.DataComponents.FOOD) || com.nirncraft.crafting.arcane.alchemy.Ingredients.isIngredient(stack)) return true;
        if (stack.getItem() instanceof net.minecraft.world.item.PotionItem) return true;
        ItemUseAnimation anim = stack.getUseAnimation();
        return anim == ItemUseAnimation.DRINK;
    }

    /** "Read" instead of "Equip". */
    public static boolean isReadable(ItemStack stack) {
        // blank books and enchanted books have nothing to read
        if (stack.is(net.minecraft.world.item.Items.BOOK) || stack.is(net.minecraft.world.item.Items.ENCHANTED_BOOK)) return false;
        InvCategory c = of(stack);
        return c == BOOKS || c == SPELL_TOMES;
    }
}
