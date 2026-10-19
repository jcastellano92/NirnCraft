package com.nirncraft.magic.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.magic.MagicRegistry;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Mod-bus client registration for the magic module: HUD overlay, projectile renderer, bound bow pull animation. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class MagicClientSetup {
    private MagicClientSetup() {}

    @SubscribeEvent
    public static void overlays(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft", "magic_hud"), MagicHud::render);
    }

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) {
        event.register(MagicClientEvents.HANDS_CYCLE);
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(MagicRegistry.SPELL_PROJECTILE.get(), NoopRenderer::new);
    }


}
