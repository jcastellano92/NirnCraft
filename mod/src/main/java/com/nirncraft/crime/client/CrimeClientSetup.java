package com.nirncraft.crime.client;

import com.nirncraft.NirnCraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Mod-bus client registration for the crime module: the bounty / sentence / steal-hint HUD overlay. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class CrimeClientSetup {
    private CrimeClientSetup() {}

    @SubscribeEvent
    public static void overlays(RegisterGuiLayersEvent event) {
        event.registerAboveAll(net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft", "crime"), CrimeHud::render);
    }
}
