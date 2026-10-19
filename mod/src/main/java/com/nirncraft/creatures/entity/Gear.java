package com.nirncraft.creatures.entity;

import com.nirncraft.NirnCraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;

/** Equipment helpers for humanoid creatures. Skyrim weapons from the crafting module are used when present. */
public final class Gear {
    public static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private Gear() {}

    /** A {@code nirncraft:<path>} item (owned by another module) or the vanilla fallback when it doesn't exist. */
    public static Item modItem(String path, Item fallback) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(NirnCraft.MODID, path));
        return item == null || item == Items.AIR ? fallback : item;
    }

    public static ItemStack pick(RandomSource random, Item... items) {
        return new ItemStack(items[random.nextInt(items.length)]);
    }

    public static ItemStack dyedLeather(Item item, int color) {
        ItemStack stack = new ItemStack(item);
        stack.set(net.minecraft.core.component.DataComponents.DYED_COLOR, new net.minecraft.world.item.component.DyedItemColor(color));
        return stack;
    }

    public static Item leather(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> Items.LEATHER_HELMET;
            case CHEST -> Items.LEATHER_CHESTPLATE;
            case LEGS -> Items.LEATHER_LEGGINGS;
            default -> Items.LEATHER_BOOTS;
        };
    }

    public static Item chainmail(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> Items.CHAINMAIL_HELMET;
            case CHEST -> Items.CHAINMAIL_CHESTPLATE;
            case LEGS -> Items.CHAINMAIL_LEGGINGS;
            default -> Items.CHAINMAIL_BOOTS;
        };
    }

    public static Item iron(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> Items.IRON_HELMET;
            case CHEST -> Items.IRON_CHESTPLATE;
            case LEGS -> Items.IRON_LEGGINGS;
            default -> Items.IRON_BOOTS;
        };
    }

    /** Equips {@code stack} in {@code slot} with the given drop chance. */
    public static void equip(Mob mob, EquipmentSlot slot, ItemStack stack, float dropChance) {
        mob.setItemSlot(slot, stack);
        mob.setDropChance(slot, dropChance);
    }
}
