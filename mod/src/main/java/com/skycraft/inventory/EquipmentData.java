package com.skycraft.inventory;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorMaterials;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.Equippable;
import org.jetbrains.annotations.Nullable;

/** Equipment classification follows real item components, covering vanilla and Skycraft equipment alike. */
public final class EquipmentData {
    private static final ArmorMaterial[] ARMOR_MATERIALS = {ArmorMaterials.LEATHER, ArmorMaterials.COPPER, ArmorMaterials.CHAINMAIL,
            ArmorMaterials.IRON, ArmorMaterials.GOLD, ArmorMaterials.DIAMOND, ArmorMaterials.NETHERITE,
            ArmorMaterials.TURTLE_SCUTE, ArmorMaterials.ARMADILLO_SCUTE};
    private static final ToolMaterial[] TOOL_MATERIALS = {ToolMaterial.WOOD, ToolMaterial.STONE, ToolMaterial.COPPER, ToolMaterial.IRON,
            ToolMaterial.GOLD, ToolMaterial.DIAMOND, ToolMaterial.NETHERITE};

    private EquipmentData() {}

    public static boolean isArmor(ItemStack stack) {
        EquipmentSlot slot = slot(stack);
        if (slot == null || slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) return false;
        // Real armor carries an armor modifier even when its defense is zero (robes).
        // Other wearable components, such as pumpkins and elytra, do not.
        return stack.getItem() instanceof com.skycraft.crafting.item.SkyArmorItem
                || stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                        net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY).modifiers().stream()
                .anyMatch(entry -> entry.attribute().equals(Attributes.ARMOR) && entry.slot().test(slot));
    }

    public static boolean isBodyArmor(ItemStack stack) {
        return slot(stack) == EquipmentSlot.BODY && stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY).modifiers().stream()
                .anyMatch(entry -> entry.attribute().equals(Attributes.ARMOR) && entry.slot().test(EquipmentSlot.BODY));
    }

    public static @Nullable EquipmentSlot slot(ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        return equippable == null ? null : equippable.slot();
    }

    public static @Nullable ArmorType armorType(ItemStack stack) {
        EquipmentSlot slot = slot(stack);
        if (slot == null) return null;
        return switch (slot) {
            case HEAD -> ArmorType.HELMET;
            case CHEST -> ArmorType.CHESTPLATE;
            case LEGS -> ArmorType.LEGGINGS;
            case FEET -> ArmorType.BOOTS;
            case BODY -> ArmorType.BODY;
            default -> null;
        };
    }

    public static float defense(ItemStack stack) {
        return attribute(stack, Attributes.ARMOR);
    }

    public static float toughness(ItemStack stack) {
        return attribute(stack, Attributes.ARMOR_TOUGHNESS);
    }

    private static float attribute(ItemStack stack, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
        EquipmentSlot slot = slot(stack);
        if (slot == null) slot = EquipmentSlot.OFFHAND;
        return (float) stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY).compute(attribute, 0.0, slot);
    }

    public static @Nullable ArmorMaterial armorMaterial(ItemStack stack) {
        if (stack.getItem() instanceof com.skycraft.crafting.item.SkyArmorItem armor) return armor.set.vanilla();
        var equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null || equippable.assetId().isEmpty()) return null;
        for (var material : ARMOR_MATERIALS) {
            if (equippable.assetId().get().equals(material.assetId())) return material;
        }
        return null;
    }

    public static @Nullable ToolMaterial toolMaterial(ItemStack stack) {
        var repairable = stack.get(DataComponents.REPAIRABLE);
        if (repairable == null) return null;
        var key = repairable.items().unwrapKey();
        if (key.isEmpty()) return null;
        for (var material : TOOL_MATERIALS) {
            if (key.get().equals(material.repairItems())) return material;
        }
        return null;
    }
}
