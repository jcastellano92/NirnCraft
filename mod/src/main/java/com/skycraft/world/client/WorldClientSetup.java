package com.skycraft.world.client;

import com.skycraft.Skycraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Mod-bus client registration for the world module (the WAIT/MAP key mappings themselves are registered by the core). */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class WorldClientSetup {
    private WorldClientSetup() {}

    @SubscribeEvent
    public static void overlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("region_banner", RegionBanner::render);
        event.registerAboveAll("world_fade", ScreenFade::render);
        event.registerAboveAll("rest_hud", RestClient::render);
    }
}
