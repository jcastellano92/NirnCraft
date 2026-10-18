package com.skycraft.crafting.client;
import com.skycraft.crafting.menu.CraftingMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
/** Menus; authored bow/shield variants are native assets/skycraft/items definitions. */
@EventBusSubscriber(modid="skycraft",value=Dist.CLIENT)
public final class CraftingClient {
    @SubscribeEvent public static void screens(RegisterMenuScreensEvent event) {
        event.register(CraftingMenus.STATION.get(),StationScreen::new);
        event.register(CraftingMenus.HOMESTEAD.get(),com.skycraft.homestead.client.HomesteadScreen::new);
    }
}
