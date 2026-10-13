package com.skycraft.fire.client;

import com.skycraft.Skycraft;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Forge-bus client events of the fire module: the far fire draw, the ambience tick, clean-up on logout. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class FireClientEvents {
    private FireClientEvents() {}

    /** After particles (and so after translucent terrain): the far fire sprites, smoke and glow. */
    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        try {
            FarFireRenderer.render(event);
        } catch (RuntimeException e) {
            // never let a decoration break the frame; the next frame tries again
            if (!failed) {
                failed = true;
                Skycraft.LOGGER.error("Far fire render failed", e);
            }
        }
    }

    private static boolean failed;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
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
