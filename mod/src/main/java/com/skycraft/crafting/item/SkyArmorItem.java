package com.skycraft.crafting.item;

import com.skycraft.crafting.SkyArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.equipment.ArmorType;

/** Original Skyrim armor geometry is supplied by the physical-client equipment renderer. */
public class SkyArmorItem extends Item {
    public final SkyArmorMaterial set;
    private final ArmorType type;

    public SkyArmorItem(SkyArmorMaterial set, ArmorType type, Properties props) {
        super(props.humanoidArmor(set.vanilla(), type).repairable(set.material()));
        this.set = set;
        this.type = type;
    }

    public ArmorType getType() { return type; }
    public int getDefense() { return set.getDefenseForType(type); }
    public float getToughness() { return set.getToughness(); }
    public net.minecraft.world.item.equipment.ArmorMaterial getMaterial() { return set.vanilla(); }
}
