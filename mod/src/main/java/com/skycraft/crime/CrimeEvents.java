package com.skycraft.crime;

import com.skycraft.Skycraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Per-player crime ticks (once per second) and session cleanup. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class CrimeEvents {
    private CrimeEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        if (player.tickCount % 20 != 0) return;
        Jail.tick(player);
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
        CrimePackets.forget(id);
    }
}
