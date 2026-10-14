package com.skycraft.client.hud;

import com.skycraft.Skycraft;
import com.skycraft.core.HudStack;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Starts the corner layout ({@link HudStack}) for every GUI frame, before any overlay draws, and tells the top-right
 * zone how much room the vanilla status effect icons take (one row at y=1 for beneficial, one at y=26 for harmful
 * effects, 24 px icons).
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class HudFrame {
    private HudFrame() {}

    @SubscribeEvent
    public static void onFrame(RenderGuiEvent.Pre event) {
        HudStack.nextFrame(Util.getMillis());
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.getActiveEffects().isEmpty()) return;
        boolean good = false, bad = false;
        for (MobEffectInstance e : player.getActiveEffects()) {
            if (e.getEffect().isBeneficial()) good = true;
            else bad = true;
        }
        HudStack.setInset(HudStack.Zone.TOP_RIGHT, bad ? 46 : good ? 22 : 0);
    }
}
