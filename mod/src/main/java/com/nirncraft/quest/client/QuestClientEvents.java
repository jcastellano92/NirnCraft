package com.nirncraft.quest.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.client.SkyKeys;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Client: Journal (J) and Party (U) keys, compass refresh, cache cleanup. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class QuestClientEvents {
    private static int ticks;

    private QuestClientEvents() {}

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        while (SkyKeys.JOURNAL.consumeClick()) mc.gui.setScreen(new JournalScreen());
        // the tracked quest lives in synced player data and the dimension can change: refresh twice a second
        if (++ticks % 10 == 0) {
            ClientQuestData.refreshCompass();
            ClientQuestData.refreshPartyCompass();
            ClientQuestData.refreshLocationSilhouettes();
            ClientQuestData.refreshSettlementQuestGivers();
        }
        ClientQuestData.showPendingPrompt();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientQuestData.clear();
    }

    /** Mod-bus registration: the party HUD. */
    @net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
    public static final class Setup {
        private Setup() {}

        @SubscribeEvent
        public static void overlays(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.HOTBAR, net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft", "party_hud"), PartyHud::render);
        }
    }
}
