package com.skycraft.quest.client;

import com.skycraft.Skycraft;
import com.skycraft.client.SkyKeys;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client: Journal (J) and Party (U) keys, compass refresh, cache cleanup. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class QuestClientEvents {
    private static int ticks;

    private QuestClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        while (SkyKeys.JOURNAL.consumeClick()) mc.setScreen(new JournalScreen());
        // the tracked quest lives in synced player data and the dimension can change: refresh twice a second
        if (++ticks % 10 == 0) {
            ClientQuestData.refreshCompass();
            ClientQuestData.refreshPartyCompass();
        }
        ClientQuestData.showPendingPrompt();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientQuestData.clear();
    }

    /** Mod-bus registration: the party HUD. */
    @Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Setup {
        private Setup() {}

        @SubscribeEvent
        public static void overlays(RegisterGuiOverlaysEvent event) {
            event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "party_hud", PartyHud::render);
        }
    }
}
