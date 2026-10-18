package com.skycraft.client;

import com.skycraft.Skycraft;
import com.skycraft.client.hud.SkyHud;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Mod-bus client registration for the core: key mappings and HUD overlays. */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {}

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) {
        event.registerCategory(SkyKeys.CATEGORY);
        for (KeyMapping key : SkyKeys.ALL) event.register(key);
    }

    @SubscribeEvent
    public static void overlays(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, net.minecraft.resources.Identifier.fromNamespaceAndPath(Skycraft.MODID, "vitals"), SkyHud::renderVitals);
        event.registerAbove(VanillaGuiLayers.HOTBAR, net.minecraft.resources.Identifier.fromNamespaceAndPath(Skycraft.MODID, "sneak_eye"), SkyHud::renderSneakEye);
        event.registerAboveAll(net.minecraft.resources.Identifier.fromNamespaceAndPath(Skycraft.MODID, "compass"), com.skycraft.client.hud.Compass::render);
        event.registerAboveAll(net.minecraft.resources.Identifier.fromNamespaceAndPath(Skycraft.MODID, "notifications"), SkyHud::renderNotifications);
    }

    @SubscribeEvent
    public static void itemPreviews(net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent event) {
        event.register(com.skycraft.inventory.client.SkyItemRender.State.class, com.skycraft.inventory.client.SkyItemRender::new);
    }
    @SubscribeEvent
    public static void playerStates(net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent event) {
        event.registerEntityModifier(new com.google.common.reflect.TypeToken<net.minecraft.client.renderer.entity.LivingEntityRenderer<net.minecraft.world.entity.LivingEntity,net.minecraft.client.renderer.entity.state.LivingEntityRenderState,?>>() {},(entity,state)->{
            com.skycraft.society.client.SocietyClientEvents.extract(entity,state);
            com.skycraft.arsenal.client.KillCamClient.extract(entity,state);
            com.skycraft.mounts.client.MountDownedClient.extract(entity,state);
        });
        event.registerAvatarEntityModifier(new net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier() {
            @Override
            public <T extends net.minecraft.world.entity.Avatar & net.minecraft.client.entity.ClientAvatarEntity>
            void accept(T avatar, net.minecraft.client.renderer.entity.state.AvatarRenderState state) {
                if (avatar instanceof net.minecraft.world.entity.player.Player player) {
                    RaceSkins.extractAppearance(player, state);
                    ClientEvents.extractAnimations(player, state);
                    com.skycraft.curse.client.FormRenderer.extract(player,state);
                    com.skycraft.quest.client.IntroClient.extractPlayer(player,state);
                    com.skycraft.society.client.CosmeticLayer.extract(player, state);
                    com.skycraft.society.client.JewelryLayer.extract(player, state);
                }
            }
        });
    }

}
