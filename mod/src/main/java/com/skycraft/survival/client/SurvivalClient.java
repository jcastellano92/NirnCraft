package com.skycraft.survival.client;

import com.skycraft.Skycraft;
import com.skycraft.creatures.client.CorpseRenderer;
import com.skycraft.survival.SurvivalRegistry;
import com.skycraft.survival.fishing.FishingRegistry;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.entity.FishingHookRenderer;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.registries.RegistryObject;

/**
 * Mod-bus client setup of the survival module: the cooking pot screen, the Survival Mode meters and the fishing reel
 * bar, the bobber and player body renderers, and the rods' "cast" model.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SurvivalClient {
    private SurvivalClient() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(SurvivalRegistry.COOKING_MENU.get(), CookingScreen::new);
            for (RegistryObject<Item> rod : java.util.List.of(FishingRegistry.FISHING_ROD, FishingRegistry.FINE_FISHING_ROD,
                    FishingRegistry.DWARVEN_FISHING_ROD)) {
                ItemProperties.register(rod.get(), new ResourceLocation("cast"), (stack, level, entity, seed) -> {
                    if (!(entity instanceof Player player) || player.fishing == null) return 0f;
                    boolean main = entity.getMainHandItem() == stack;
                    boolean off = entity.getOffhandItem() == stack && !(entity.getMainHandItem().getItem() instanceof FishingRodItem);
                    return main || off ? 1f : 0f;
                });
            }
        });
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(FishingRegistry.HOOK.get(), FishingHookRenderer::new);
        event.registerEntityRenderer(SurvivalRegistry.PLAYER_CORPSE.get(), CorpseRenderer::new);
    }

    @SubscribeEvent
    public static void overlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "survival_meters", SurvivalHud::render);
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "fishing_reel", FishingHud::render);
    }
}
