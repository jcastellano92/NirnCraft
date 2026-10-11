package com.skycraft.crafting.item;

import com.skycraft.crafting.SkyArmorMaterial;
import net.minecraft.world.item.ArmorItem;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

/**
 * A piece of a Skyrim armour set. Worn, it renders with the set's 3D model (horned helms, pauldrons, hoods, gauntlets
 * on the forearms...) instead of the vanilla armour boxes; see {@code crafting.client.armor.GearArmorModels}.
 */
public class SkyArmorItem extends ArmorItem {
    public final SkyArmorMaterial set;

    public SkyArmorItem(SkyArmorMaterial set, Type type, Properties props) {
        super(set, type, props);
        this.set = set;
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(com.skycraft.crafting.client.armor.GearArmorClient.EXTENSIONS);
    }
}
