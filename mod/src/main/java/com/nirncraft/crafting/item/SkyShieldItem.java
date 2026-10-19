package com.nirncraft.crafting.item;

import com.nirncraft.crafting.SkyArmorMaterial;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/** Skyrim shields block normally and supply their material's armor rating in the offhand. */
public class SkyShieldItem extends ShieldItem {
    public final SkyArmorMaterial set;

    public SkyShieldItem(SkyArmorMaterial set, Properties props) {
        super(com.nirncraft.platform.ShieldProperties.apply(props).enchantable(set.getEnchantmentValue()).repairable(set.material()).attributes(attributes(set)));
        this.set = set;
    }

    private static ItemAttributeModifiers attributes(SkyArmorMaterial set) {
        var builder = ItemAttributeModifiers.builder().add(Attributes.ARMOR,
                new AttributeModifier(Identifier.fromNamespaceAndPath("nirncraft", "shield_armor"), set.shieldDefense,
                        AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.OFFHAND);
        if (set.getToughness() > 0) builder.add(Attributes.ARMOR_TOUGHNESS,
                new AttributeModifier(Identifier.fromNamespaceAndPath("nirncraft", "shield_toughness"), set.getToughness() * 0.5,
                        AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.OFFHAND);
        return builder.build();
    }

    @Override
    public net.minecraft.network.chat.Component getName(ItemStack stack) {
        return net.minecraft.network.chat.Component.translatable(getDescriptionId());
    }
}
