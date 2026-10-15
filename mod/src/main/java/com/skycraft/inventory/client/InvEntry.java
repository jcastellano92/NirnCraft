package com.skycraft.inventory.client;

import com.skycraft.economy.ItemValues;
import com.skycraft.inventory.InvCategory;
import com.skycraft.inventory.InventoryActions;
import com.skycraft.inventory.ItemWeights;
import com.skycraft.inventory.Pack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;

import java.util.ArrayList;
import java.util.List;

/**
 * One row of the Skyrim inventory: every unequipped stack of the same item (and NBT) merged into one line, equipped
 * items (right hand, left hand, worn) on lines of their own.
 */
public final class InvEntry {
    public static final int NONE = 0, RIGHT = 1, LEFT = 2, WORN = 3;

    /** Copy of the first stack with the total count of the row. */
    public final ItemStack stack;
    /** Copy with count 1, for icon rendering (durability bar, no count). */
    public final ItemStack icon;
    public final List<Integer> slots = new ArrayList<>();
    public final int equip;
    public final InvCategory category;
    public final float weight;
    public final int value;
    /** Gold per weight unit of one item ({@link com.skycraft.inventory.ValueDensity}), or -1 for a weightless item. */
    public final float valuePerWeight;
    public final boolean favorite;
    public final boolean stolen;
    public final String name;
    public final ResourceLocation itemId;

    private InvEntry(ItemStack first, int slot, int equip) {
        this.stack = first.copy();
        this.icon = first.copy();
        this.icon.setCount(1);
        this.slots.add(slot);
        this.equip = equip;
        this.category = InvCategory.of(first);
        this.weight = ItemWeights.get(first);
        this.value = ItemValues.get(first);
        this.valuePerWeight = com.skycraft.inventory.ValueDensity.of(this.value, this.weight);
        this.favorite = InvCategory.isFavorite(first);
        this.stolen = InvCategory.isStolen(first);
        this.name = first.getHoverName().getString();
        this.itemId = BuiltInRegistries.ITEM.getKey(first.getItem());
    }

    public int count() {
        return stack.getCount();
    }

    public int primarySlot() {
        return slots.get(0);
    }

    public boolean equipped() {
        return equip != NONE;
    }

    /** True if this row is the same item, NBT and equip state as {@code other} (to keep the selection across rebuilds). */
    public boolean sameAs(InvEntry other) {
        return other != null && other.equip == equip && ItemStack.isSameItemSameTags(other.stack, stack);
    }

    // ------------------------------------------------------------------ what can be done with it

    public boolean consumable() {
        return InvCategory.isConsumable(stack);
    }

    public boolean readable() {
        return InvCategory.isReadable(stack);
    }

    public boolean drink() {
        return stack.getUseAnimation() == UseAnim.DRINK || stack.getItem() instanceof net.minecraft.world.item.PotionItem;
    }

    public boolean isArmor() {
        EquipmentSlot slot = InventoryActions.equipSlotFor(stack);
        return slot != null && slot.getType() == EquipmentSlot.Type.ARMOR;
    }

    /**
     * Equippable items rule: only items meant for combat, defense, light or harvesting
     * can be equipped (weapons, shields, armor, tools, torches). Blocks and misc items cannot be held.
     */
    public boolean isUsableEquipment() {
        if (isArmor()) return true;
        if (com.skycraft.economy.ItemCategory.isJewelry(stack)) return true;
        net.minecraft.world.item.Item it = stack.getItem();
        if (it instanceof net.minecraft.world.item.SwordItem || it instanceof net.minecraft.world.item.DiggerItem
                || it instanceof net.minecraft.world.item.ProjectileWeaponItem || it instanceof net.minecraft.world.item.ShieldItem
                || it instanceof net.minecraft.world.item.TridentItem || it instanceof net.minecraft.world.item.FishingRodItem
                || it instanceof net.minecraft.world.item.ShearsItem) {
            return true;
        }
        if (it == net.minecraft.world.item.Items.TORCH || it == net.minecraft.world.item.Items.SOUL_TORCH) {
            return true;
        }
        // saddles are held in the right hand, to be used on a wild mount
        return com.skycraft.mounts.MountItems.isSaddle(stack);
    }

    /** Can be held in the left hand (shields, torches, one-handed weapons, offhand tools). */
    public boolean leftHandable() {
        return !isArmor() && !consumable() && isUsableEquipment() && !com.skycraft.mounts.MountItems.isSaddle(stack)
                && !com.skycraft.magic.Hands.twoHanded(stack);
    }

    // ------------------------------------------------------------------ building

    /** All rows of the player's inventory (main, armor, offhand), unsorted. */
    public static List<InvEntry> build(Player player) {
        Inventory inv = player.getInventory();
        List<InvEntry> out = new ArrayList<>();
        int size = Math.min(inv.getContainerSize(), InventoryActions.SLOT_COUNT);
        for (int i = 0; i < size; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            int equip = InventoryActions.isArmorSlot(i) ? WORN : i == InventoryActions.OFFHAND ? LEFT : i == inv.selected ? RIGHT : NONE;
            if (equip == NONE && com.skycraft.economy.ItemCategory.isJewelry(s)) {
                net.minecraft.nbt.CompoundTag apparel = com.skycraft.core.SkyData.get(player).module("apparel");
                net.minecraft.resources.ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem());
                String itemId = key != null ? key.toString() : "";
                if (itemId.equals(apparel.getString("necklace")) || itemId.equals(apparel.getString("ring")) || itemId.equals(apparel.getString("circlet"))) {
                    equip = WORN;
                }
            }
            if (equip == NONE) {
                InvEntry merged = null;
                for (InvEntry e : out) {
                    if (e.equip == NONE && ItemStack.isSameItemSameTags(e.stack, s)) {
                        merged = e;
                        break;
                    }
                }
                if (merged != null) {
                    merged.stack.grow(s.getCount());
                    merged.slots.add(i);
                    continue;
                }
            }
            out.add(new InvEntry(s, i, equip));
        }
        // The overflow pack (inventory.Pack): the same rows as slot items; a pack entry is "slot" VIRTUAL_BASE + index
        List<ItemStack> pack = Pack.entries(player);
        for (int p = 0; p < pack.size(); p++) {
            ItemStack s = pack.get(p);
            if (s.isEmpty()) continue;
            int virtual = Pack.VIRTUAL_BASE + p;
            int equip = InventoryActions.isWornJewelry(player, s) ? WORN : NONE;
            InvEntry merged = null;
            if (equip == NONE) {
                for (InvEntry e : out) {
                    if (e.equip == NONE && ItemStack.isSameItemSameTags(e.stack, s)) {
                        merged = e;
                        break;
                    }
                }
            }
            if (merged != null) {
                merged.stack.grow(s.getCount());
                merged.slots.add(virtual);
            } else {
                out.add(new InvEntry(s, virtual, equip));
            }
        }
        return out;
    }
}
