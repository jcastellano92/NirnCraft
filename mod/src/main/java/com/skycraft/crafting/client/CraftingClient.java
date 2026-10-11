package com.skycraft.crafting.client;

import com.skycraft.Skycraft;
import com.skycraft.crafting.CraftingItems;
import com.skycraft.crafting.SmithingTier;
import com.skycraft.crafting.WeaponType;
import com.skycraft.crafting.menu.CraftingMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Mod-bus client setup of the smithing module: station screen, bow pull animations and shield blocking poses. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CraftingClient {
    private CraftingClient() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(CraftingMenus.STATION.get(), StationScreen::new);
            MenuScreens.register(CraftingMenus.HOMESTEAD.get(), com.skycraft.homestead.client.HomesteadScreen::new);
            ResourceLocation pull = new ResourceLocation("pull");
            ResourceLocation pulling = new ResourceLocation("pulling");
            for (SmithingTier tier : SmithingTier.values()) {
                if (!tier.has(WeaponType.BOW)) continue;
                Item bow = CraftingItems.WEAPONS.get(tier).get(WeaponType.BOW).get();
                // same predicates as the vanilla bow
                ItemProperties.register(bow, pull, (stack, level, entity, seed) -> {
                    if (entity == null || entity.getUseItem() != stack) return 0f;
                    return (stack.getUseDuration() - entity.getUseItemRemainingTicks()) / 20f;
                });
                ItemProperties.register(bow, pulling, (stack, level, entity, seed) ->
                        entity != null && entity.isUsingItem() && entity.getUseItem() == stack ? 1f : 0f);
            }
            // shields raise to the blocking pose like the vanilla shield
            ResourceLocation blocking = new ResourceLocation("blocking");
            for (var shield : CraftingItems.SHIELDS.values()) {
                ItemProperties.register(shield.get(), blocking, (stack, level, entity, seed) ->
                        entity != null && entity.isUsingItem() && entity.getUseItem() == stack ? 1f : 0f);
            }
        });
    }
}
