package com.skycraft.lore.client;

import com.skycraft.Skycraft;
import com.skycraft.lore.LorePackets;
import com.skycraft.network.SkyNetwork;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Forge-bus client events for the lore module: the stone power key and the stone-change HUD banner. */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class LoreClientEvents {
    private LoreClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            StoneHud.reset();
            return;
        }
        while (LoreClientSetup.STONE_POWER.consumeClick()) {
            SkyNetwork.sendToServer(new LorePackets.UsePower());
        }
        StoneHud.tick(mc);
    }
}
