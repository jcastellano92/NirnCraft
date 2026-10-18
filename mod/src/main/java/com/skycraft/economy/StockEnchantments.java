package com.skycraft.economy;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/** Stock rolls resolve the current world's enchantments and never retain registry holders. */
final class StockEnchantments {
    private StockEnchantments() {}
    static ItemStack enchant(RandomSource random, ItemStack stack, int cost) {
        var registry = com.skycraft.inventory.ItemData.currentRegistries().lookupOrThrow(Registries.ENCHANTMENT);
        return EnchantmentHelper.enchantItem(random, stack, cost, registry.listElements()
                .filter(entry -> entry.is(EnchantmentTags.IN_ENCHANTING_TABLE))
                .map(entry -> (Holder<Enchantment>) entry));
    }
}
