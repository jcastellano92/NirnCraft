package com.skycraft.inventory.client;

import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Count-independent item identity: names, enchantments and every other component keep separate rows. */
record ItemRowKey(Item item, DataComponentPatch components) {
    static ItemRowKey of(ItemStack stack) {
        return new ItemRowKey(stack.getItem(), stack.getComponentsPatch());
    }
}
