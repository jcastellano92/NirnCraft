package com.skycraft.world.client;

import com.skycraft.Skycraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Mod-bus client registration for the world module (the WAIT/MAP key mappings themselves are registered by the core). */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class WorldClientSetup {
    private WorldClientSetup() {}

    @SubscribeEvent
    public static void overlays(RegisterGuiLayersEvent event) {
        event.registerAboveAll(net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft", "region_banner"), RegionBanner::render);
        event.registerAboveAll(net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft", "world_fade"), ScreenFade::render);
        event.registerAboveAll(net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft", "rest_hud"), RestClient::render);
    }
}
