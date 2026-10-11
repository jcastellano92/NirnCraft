package com.skycraft.sailing.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.skycraft.Skycraft;
import com.skycraft.client.SkyKeys;
import com.skycraft.sailing.SailingRegistry;
import com.skycraft.sailing.ShipType;
import com.skycraft.world.client.MapScreen;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/** Mod-bus client registration for sailing: ship renderers, the sail keys, HUD and voyage overlays, the map layer. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SailingClient {
    /** Set more sail (furled -> half -> full) and take sail in. */
    public static final KeyMapping SAIL_UP = new KeyMapping("key.skycraft.sail_up", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, SkyKeys.CATEGORY);
    public static final KeyMapping SAIL_DOWN = new KeyMapping("key.skycraft.sail_down", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, SkyKeys.CATEGORY);

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
    public static void overlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("sailing_hud", SailingHud::render);
        event.registerAboveAll("voyage", VoyageOverlay::render);
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        MapScreen.LAYERS.add(new SeaRouteLayer());
    }
}
