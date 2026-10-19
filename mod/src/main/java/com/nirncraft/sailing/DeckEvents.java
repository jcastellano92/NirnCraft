package com.nirncraft.sailing;

import com.nirncraft.NirnCraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Server-side drivers of the walkable decks ({@link DeckPhysics}): the end-of-tick pass per level, player upkeep. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class DeckEvents {
    private DeckEvents() {}

    @SubscribeEvent
    public static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.LevelTickEvent.Post) || !(event.getLevel() instanceof ServerLevel level)) return;
        DeckPhysics.endTick(level, null);
    }

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player)) return;
        DeckPhysics.serverPlayerTick(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        DeckPhysics.forget(event.getEntity());
    }
}
