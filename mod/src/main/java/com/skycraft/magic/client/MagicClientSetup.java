package com.skycraft.magic.client;

import com.skycraft.Skycraft;
import com.skycraft.magic.MagicRegistry;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Mod-bus client registration for the magic module: HUD overlay, projectile renderer, bound bow pull animation. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MagicClientSetup {
    private MagicClientSetup() {}

    @SubscribeEvent
    public static void overlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "magic_hud", MagicHud::render);
    }

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) {
        event.register(MagicClientEvents.HANDS_CYCLE);
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(MagicRegistry.SPELL_PROJECTILE.get(), NoopRenderer::new);
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ItemProperties.register(MagicRegistry.BOUND_BOW.get(), new ResourceLocation("pull"), (stack, level, entity, seed) -> {
                if (entity == null || entity.getUseItem() != stack) return 0f;
                return (stack.getUseDuration() - entity.getUseItemRemainingTicks()) / 20f;
            });
            ItemProperties.register(MagicRegistry.BOUND_BOW.get(), new ResourceLocation("pulling"),
                    (stack, level, entity, seed) -> entity != null && entity.isUsingItem() && entity.getUseItem() == stack ? 1f : 0f);
        });
    }
}
