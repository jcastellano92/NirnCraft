package com.nirncraft.economy;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The key to a player home, given with the deed when a house is bought. NBT {@code house} holds the house id; whoever
 * carries the key may open the house's doors (the owner can hand copies to friends).
 */
public class HouseKeyItem extends Item {
    public static final String TAG = "house";

    public HouseKeyItem(Properties props) {
        super(props);
    }

    public static ItemStack forHouse(String houseId) {
        ItemStack stack = new ItemStack(EconomyItems.HOUSE_KEY.get());
        com.nirncraft.inventory.ItemData.updateCustom(stack, customData263 -> customData263.putString(TAG, houseId));
        return stack;
    }

    @Nullable
    public static String houseOf(ItemStack stack) {
        return com.nirncraft.inventory.ItemData.custom(stack) != null && com.nirncraft.inventory.ItemData.custom(stack).contains(TAG) ? com.nirncraft.inventory.ItemData.custom(stack).getStringOr(TAG, "") : null;
    }

    @Override
    public Component getName(ItemStack stack) {
        String house = houseOf(stack);
        if (house == null) return super.getName(stack);
        return Component.translatable("item.nirncraft.house_key.named", Component.translatable("settlement.nirncraft.name." + house));
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        String house = houseOf(stack);
        if (house != null) {
            tooltip.accept(Component.translatable("item.nirncraft.house_key.tooltip", Component.translatable("settlement.nirncraft.name." + house)));
        }
    }
}
