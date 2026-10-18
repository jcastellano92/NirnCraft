package com.skycraft.fire.client;

import com.skycraft.Skycraft;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Forge-bus client events of the fire module: the far fire draw, the ambience tick, clean-up on logout. */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class FireClientEvents {
    private FireClientEvents() {}

    /** After particles (and so after translucent terrain): the far fire sprites, smoke and glow. */
    @SubscribeEvent
    public static void onExtract(net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent event) {
        try {
            FarFireRenderer.extract(event);
        } catch (RuntimeException e) {
            // never let a decoration break the frame; the next frame tries again
            if (!failed) {
                failed = true;
                Skycraft.LOGGER.error("Far fire render failed", e);
            }
        }
    }

    @SubscribeEvent public static void onSubmit(net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent event){FarFireRenderer.submit(event);}

    private static boolean failed;

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.client.event.ClientTickEvent.Post)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.isPaused()) return;
        ClientFire.tick();
        FireAmbience.tick(mc);
        WeepParticles.tick(mc);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientFire.clear();
        FireAmbience.clear();
    }
}
