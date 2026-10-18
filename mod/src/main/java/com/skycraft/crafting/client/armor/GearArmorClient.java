package com.skycraft.crafting.client.armor;

import com.skycraft.crafting.item.SkyArmorItem;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.NotNull;

/** Client item extension of every Skyrim armour piece: swaps the vanilla armour boxes for the set's 3D model. */
@net.neoforged.fml.common.EventBusSubscriber(modid = com.skycraft.Skycraft.MODID, value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class GearArmorClient {
    public static final IClientItemExtensions EXTENSIONS = new IClientItemExtensions() {
        @Override
        public @NotNull Model getHumanoidArmorModel(ItemStack stack, EquipmentClientInfo.LayerType layerType, Model original) {
            if (stack.getItem() instanceof SkyArmorItem armor) {
                EquipmentSlot slot = com.skycraft.inventory.EquipmentData.slot(stack);
                SkyArmorModel model = slot == null ? null : GearArmorModels.get(armor.set.style, slot);
                if (model != null) return model;
            }
            return original;
        }
    };

    private GearArmorClient() {}

    @net.neoforged.bus.api.SubscribeEvent
    public static void extensions(net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent event) {
        net.minecraft.world.item.Item[] armor = com.skycraft.crafting.CraftingItems.ARMOR.values().stream()
                .flatMap(set -> set.values().stream()).map(java.util.function.Supplier::get)
                .toArray(net.minecraft.world.item.Item[]::new);
        event.registerItem(EXTENSIONS, armor);
    }
}
