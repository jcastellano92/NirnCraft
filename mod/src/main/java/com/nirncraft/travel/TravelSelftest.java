package com.nirncraft.travel;

import com.nirncraft.NirnCraft;
import com.nirncraft.perf.PerfAutorun;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Forge event subscriber for {@link TravelNetworks}: runs the MS11 / F-TRAVEL selftest on server start, registers the
 * {@code travel} autorun task, and ticks wilderness node discovery + minor blessing refresh on player visit.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class TravelSelftest {
    static {
        PerfAutorun.register("travel", (t, done) -> {
            TravelNetworks.selftest();
            done.run();
        });
    }

    private TravelSelftest() {}

    public static void init() {
        TravelNetworks.init();
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        TravelNetworks.init();
        TravelNetworks.selftest();
    }

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if ((player.level().getGameTime() + player.getId() * 7L) % 40 != 0) return;
        TravelNetworks.tickPlayer(player);
    }
}
