package com.skycraft.inventory;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Native inventory snapshots used by conservation/relog checks, with the same slot codec as player saves. */
public final class InventoryData {
    private InventoryData() {}
    public static ListTag save(Inventory inventory, HolderLookup.Provider registries) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        inventory.save(output.list("Inventory", ItemStackWithSlot.CODEC));
        return output.buildResult().getListOrEmpty("Inventory");
    }
    public static ListTag save(Inventory inventory) { return save(inventory, ItemData.currentRegistries()); }
    public static void load(Inventory inventory, ListTag items, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put("Inventory", items);
        inventory.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag).listOrEmpty("Inventory", ItemStackWithSlot.CODEC));
    }
    public static void load(Inventory inventory, ListTag items) { load(inventory, items, ItemData.currentRegistries()); }
}
