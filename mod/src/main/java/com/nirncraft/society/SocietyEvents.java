package com.nirncraft.society;

import com.nirncraft.NirnCraft;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Per-player server ticking of the society systems, spread over different ticks of each second. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class SocietyEvents {
    private SocietyEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player) || !player.isAlive()) return;
        int phase = player.tickCount % 20;
        try {
            switch (phase) {
                case 3 -> {
                    ReputationEvents.recover(player);
                    HitSquads.tick(player);
                }
                case 9 -> Encounters.tick(player);
                case 15 -> {
                    int slot = (player.tickCount / 20) % 5;
                    if (slot == 0) ReputationEvents.scan(player);
                    else if (slot == 2) Settlers.tick(player);
                    else if (slot == 4) Cosmetics.validate(player);
                }
                default -> {
                }
            }
        } catch (RuntimeException e) {
            NirnCraft.LOGGER.warn("NirnCraft society: player tick failed", e);
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        SocietyCommands.register(event.getDispatcher());
    }
}
