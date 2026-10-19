package com.nirncraft.survival.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.creatures.client.CorpseRenderer;
import com.nirncraft.survival.SurvivalRegistry;
import com.nirncraft.survival.fishing.FishingRegistry;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.entity.FishingHookRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import java.util.function.Supplier;

/**
 * Mod-bus client setup of the survival module: the cooking pot screen, the Survival Mode meters and the fishing reel
 * bar, the bobber and player body renderers, and the rods' "cast" model.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class SurvivalClient {
    private SurvivalClient() {}

    @SubscribeEvent
    public static void screens(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) {
        event.register(SurvivalRegistry.COOKING_MENU.get(),CookingScreen::new);
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(FishingRegistry.HOOK.get(), FishingHookRenderer::new);
        event.registerEntityRenderer(SurvivalRegistry.PLAYER_CORPSE.get(), CorpseRenderer::new);
    }

    @SubscribeEvent
    public static void overlays(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft", "survival_meters"), SurvivalHud::render);
        event.registerAbove(VanillaGuiLayers.HOTBAR, net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft", "fishing_reel"), FishingHud::render);
    }
}
