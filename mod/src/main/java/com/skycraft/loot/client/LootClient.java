package com.skycraft.loot.client;

import com.skycraft.Skycraft;
import com.skycraft.loot.CollectibleItem;
import com.skycraft.loot.LootModule;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Client side of the loot module: each regional treasure tints its relic layer in its own colour. */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class LootClient {
    private LootClient() {}

    public record TreasureTint() implements net.minecraft.client.color.item.ItemTintSource {
        static final com.mojang.serialization.MapCodec<TreasureTint> CODEC=com.mojang.serialization.MapCodec.unit(new TreasureTint());
        @Override public int calculate(net.minecraft.world.item.ItemStack stack,net.minecraft.client.multiplayer.ClientLevel level,net.minecraft.world.entity.LivingEntity owner){return 0xff000000|CollectibleItem.color(stack);}
        @Override public com.mojang.serialization.MapCodec<TreasureTint> type(){return CODEC;}
    }
    @SubscribeEvent
    public static void onItemColors(RegisterColorHandlersEvent.ItemTintSources event) {
        event.register(net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft","treasure"),TreasureTint.CODEC);
    }
}
