package com.skycraft.explore.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/** Client entry points of the exploration module (called through {@code DistExecutor}). */
public final class ExploreClient {
    private ExploreClient() {}

    public static void openTreasureMap(ItemStack stack) {
        if (com.skycraft.inventory.ItemData.custom(stack) == null) return;
        Minecraft.getInstance().gui.setScreen(new TreasureMapScreen(com.skycraft.inventory.ItemData.custom(stack).copy(), stack.getHoverName()));
    }
}
