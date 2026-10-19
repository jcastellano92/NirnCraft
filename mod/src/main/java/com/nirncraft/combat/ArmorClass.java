package com.nirncraft.combat;

import com.nirncraft.NirnCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.equipment.ArmorMaterials;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Light vs heavy armor classification (tags first, vanilla material heuristic as fallback). */
public final class ArmorClass {
    public static final TagKey<Item> HEAVY = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "heavy_armor"));
    public static final TagKey<Item> LIGHT = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "light_armor"));
    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private ArmorClass() {}

    public static boolean isHeavy(ItemStack stack) {
        if (stack.isEmpty() || !com.nirncraft.inventory.EquipmentData.isArmor(stack)) return false;
        if (stack.is(HEAVY)) return true;
        if (stack.is(LIGHT)) return false;
        var mat = com.nirncraft.inventory.EquipmentData.armorMaterial(stack);
        return mat != ArmorMaterials.LEATHER && mat != ArmorMaterials.CHAINMAIL && mat != ArmorMaterials.TURTLE_SCUTE;
    }

    public static boolean isLight(ItemStack stack) {
        return !stack.isEmpty() && com.nirncraft.inventory.EquipmentData.isArmor(stack) && !isHeavy(stack);
    }

    public static int countHeavy(LivingEntity entity) {
        int n = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) if (isHeavy(entity.getItemBySlot(slot))) n++;
        return n;
    }

    public static int countLight(LivingEntity entity) {
        int n = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) if (isLight(entity.getItemBySlot(slot))) n++;
        return n;
    }

    /** True if all four pieces are armor of the same material (Skyrim "Matching Set"). */
    public static boolean matchingSet(LivingEntity entity) {
        Object material = null;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (!com.nirncraft.inventory.EquipmentData.isArmor(stack)) return false;
            if (material == null) material = com.nirncraft.inventory.EquipmentData.armorMaterial(stack);
            else if (material != com.nirncraft.inventory.EquipmentData.armorMaterial(stack)) return false;
        }
        return true;
    }

    public static boolean wearsNoArmor(LivingEntity entity) {
        for (EquipmentSlot slot : ARMOR_SLOTS) if (!entity.getItemBySlot(slot).isEmpty()) return false;
        return true;
    }
}
