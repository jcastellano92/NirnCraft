package com.skycraft.sailing.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.skycraft.Skycraft;
import com.skycraft.client.SkyKeys;
import com.skycraft.sailing.SailingRegistry;
import com.skycraft.sailing.ShipType;
import com.skycraft.world.client.MapScreen;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Mod-bus client registration for sailing: ship renderers, the sail keys, HUD and voyage overlays, the map layer. */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class SailingClient {
    /** Set more sail (furled -> half -> full) and take sail in. */
    public static final KeyMapping SAIL_UP = new KeyMapping("key.skycraft.sail_up", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYBOARD, com.mojang.blaze3d.platform.InputConstants.KEY_EQUALS, SkyKeys.CATEGORY);
    public static final KeyMapping SAIL_DOWN = new KeyMapping("key.skycraft.sail_down", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYBOARD, com.mojang.blaze3d.platform.InputConstants.KEY_MINUS, SkyKeys.CATEGORY);

    private SailingClient() {}

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        for (ShipType t : ShipType.VALUES) event.registerEntityRenderer(SailingRegistry.type(t), ShipRenderer::new);
    }

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) {
        event.register(SAIL_UP);
        event.register(SAIL_DOWN);
    }

    @SubscribeEvent
    public static void overlays(RegisterGuiLayersEvent event) {
        event.registerAboveAll(net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft", "sailing_hud"), SailingHud::render);
        event.registerAboveAll(net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft", "voyage"), VoyageOverlay::render);
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        MapScreen.LAYERS.add(new SeaRouteLayer());
    }
}
