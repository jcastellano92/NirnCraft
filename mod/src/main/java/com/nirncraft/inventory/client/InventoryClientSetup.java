package com.nirncraft.inventory.client;

import com.nirncraft.NirnCraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Mod-bus client registration for the inventory module: the favorites key and the carry weight HUD. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class InventoryClientSetup {
    private InventoryClientSetup() {}

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) {
        event.register(InventoryKeys.FAVORITES);
    }

    @SubscribeEvent
    public static void overlays(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft", "carry_weight"), CarryHud::render);
    }
}
