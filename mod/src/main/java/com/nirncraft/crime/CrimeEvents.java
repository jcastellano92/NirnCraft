package com.nirncraft.crime;

import com.nirncraft.NirnCraft;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Per-player crime ticks (once per second) and session cleanup. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class CrimeEvents {
    private CrimeEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % 20 != 0) return;
        Jail.tick(player);
        JailPatrol.tick(player);
        JailVisitors.tick(player);
        Guards.tick(player);
        Theft.tick(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        var id = event.getEntity().getUUID();
        Theft.forget(id);
        Locks.forget(id);
        Guards.forget(id);
        Crimes.forget(id);
        Jail.forget(id);
        JailVisitors.forget(id);
        CrimePackets.forget(id);
    }
}
