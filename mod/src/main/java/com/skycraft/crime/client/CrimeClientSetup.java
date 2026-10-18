package com.skycraft.crime.client;

import com.skycraft.Skycraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Mod-bus client registration for the crime module: the bounty / sentence / steal-hint HUD overlay. */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class CrimeClientSetup {
    private CrimeClientSetup() {}

    @SubscribeEvent
    public static void overlays(RegisterGuiLayersEvent event) {
        event.registerAboveAll(net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft", "crime"), CrimeHud::render);
    }
}
