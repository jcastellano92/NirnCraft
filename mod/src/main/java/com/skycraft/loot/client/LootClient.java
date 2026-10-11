package com.skycraft.loot.client;

import com.skycraft.Skycraft;
import com.skycraft.loot.CollectibleItem;
import com.skycraft.loot.LootModule;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client side of the loot module: each regional treasure tints its relic layer in its own colour. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class LootClient {
    private LootClient() {}

    @SubscribeEvent
    public static void onItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tint) -> tint == 0 ? 0xFF000000 | CollectibleItem.color(stack) : 0xFFFFFFFF, LootModule.REGIONAL_TREASURE.get());
    }
}
