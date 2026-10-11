package com.skycraft.explore.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/** Client entry points of the exploration module (called through {@code DistExecutor}). */
public final class ExploreClient {
    private ExploreClient() {}

    public static void openTreasureMap(ItemStack stack) {
        if (stack.getTag() == null) return;
        Minecraft.getInstance().setScreen(new TreasureMapScreen(stack.getTag().copy(), stack.getHoverName()));
    }
}
