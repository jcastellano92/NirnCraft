package com.skycraft.crafting.client.armor;

import com.skycraft.crafting.item.SkyArmorItem;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.NotNull;

/** Client item extension of every Skyrim armour piece: swaps the vanilla armour boxes for the set's 3D model. */
public final class GearArmorClient {
    public static final IClientItemExtensions EXTENSIONS = new IClientItemExtensions() {
        @Override
        public @NotNull HumanoidModel<?> getHumanoidArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot,
                                                               HumanoidModel<?> original) {
            if (stack.getItem() instanceof SkyArmorItem armor) {
                SkyArmorModel model = GearArmorModels.get(armor.set.style, slot);
                if (model != null) return model;
            }
            return original;
        }
    };

    private GearArmorClient() {}
}
