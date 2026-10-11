package com.skycraft.sailing;

import com.skycraft.Skycraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Server-side drivers of the walkable decks ({@link DeckPhysics}): the end-of-tick pass per level, player upkeep. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class DeckEvents {
    private DeckEvents() {}

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !event.side.isServer() || !(event.level instanceof ServerLevel level)) return;
        DeckPhysics.endTick(level, null);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        DeckPhysics.serverPlayerTick(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        DeckPhysics.forget(event.getEntity());
    }
}
