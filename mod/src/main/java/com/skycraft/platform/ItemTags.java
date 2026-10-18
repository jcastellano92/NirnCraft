package com.skycraft.platform;

import com.skycraft.inventory.ItemData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Owned custom-data children are copied for reading and explicitly committed after editing. */
public final class ItemTags {
    private ItemTags() {}
    public static @Nullable CompoundTag child(ItemStack stack, String key) {
        CompoundTag root = ItemData.custom(stack);
        return root == null ? null : root.getCompound(key).orElse(null);
    }
    public static CompoundTag childOrEmpty(ItemStack stack, String key) {
        CompoundTag child = child(stack, key);
        return child == null ? new CompoundTag() : child;
    }
    public static void setChild(ItemStack stack, String key, CompoundTag child) {
        ItemData.updateCustom(stack, root -> root.put(key, child));
    }
    public static CompoundTag saveInto(ItemStack stack, CompoundTag target) {
        target.merge(ItemData.save(stack));
        return target;
    }
}
