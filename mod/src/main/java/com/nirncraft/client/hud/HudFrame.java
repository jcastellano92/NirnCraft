package com.nirncraft.client.hud;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.HudStack;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Starts the corner layout ({@link HudStack}) for every GUI frame, before any overlay draws, and tells the top-right
 * zone how much room the vanilla status effect icons take (one row at y=1 for beneficial, one at y=26 for harmful
 * effects, 24 px icons).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class HudFrame {
    private HudFrame() {}

    @SubscribeEvent
    public static void onFrame(RenderGuiEvent.Pre event) {
        HudStack.nextFrame(Util.getMillis());
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.getActiveEffects().isEmpty()) return;
        boolean good = false, bad = false;
        for (MobEffectInstance e : player.getActiveEffects()) {
            if (e.getEffect().value().isBeneficial()) good = true;
            else bad = true;
        }
        HudStack.setInset(HudStack.Zone.TOP_RIGHT, bad ? 46 : good ? 22 : 0);
    }
}
