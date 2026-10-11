package com.skycraft.crafting.item;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.skycraft.crafting.SkyArmorMaterial;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;

import java.util.UUID;

/**
 * A Skyrim shield (Hide, Iron, Steel, Elven, Dwarven ... Daedric, Dragonplate, the Imperial and Stormcloak shields and
 * the shields of the other provinces). It blocks like the vanilla shield and, held in the off hand, adds its armour
 * rating like Skyrim's shields do. The 3D item model is generated per set by {@code tools/textures/gear.py}.
 */
public class SkyShieldItem extends ShieldItem {
    private static final UUID ARMOR_UUID = UUID.fromString("8e3c1f0a-6b2d-4f7e-9a15-3c7d2e9b4a61");
    public final SkyArmorMaterial set;
    private final Multimap<Attribute, AttributeModifier> offhand;

    public SkyShieldItem(SkyArmorMaterial set, Properties props) {
        super(props);
        this.set = set;
        ImmutableMultimap.Builder<Attribute, AttributeModifier> b = ImmutableMultimap.builder();
        b.put(Attributes.ARMOR, new AttributeModifier(ARMOR_UUID, "Shield", set.shieldDefense, AttributeModifier.Operation.ADDITION));
        if (set.getToughness() > 0) {
            b.put(Attributes.ARMOR_TOUGHNESS, new AttributeModifier(ARMOR_UUID, "Shield toughness", set.getToughness() * 0.5f,
                    AttributeModifier.Operation.ADDITION));
        }
        this.offhand = b.build();
    }

    @Override
    public String getDescriptionId(ItemStack stack) {
        // ShieldItem appends the banner colour to the description id; Skyrim shields never carry a banner.
        return getDescriptionId();
    }

    @Override
    @SuppressWarnings("deprecation")
    public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
        return slot == EquipmentSlot.OFFHAND ? offhand : super.getDefaultAttributeModifiers(slot);
    }

    @Override
    public int getEnchantmentValue() {
        return set.getEnchantmentValue();
    }

    @Override
    public boolean isValidRepairItem(ItemStack toRepair, ItemStack repair) {
        return set.getRepairIngredient().test(repair);
    }
}
