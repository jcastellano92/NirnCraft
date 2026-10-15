package com.skycraft.travel;

import com.skycraft.Skycraft;
import com.skycraft.perf.PerfAutorun;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge event subscriber for {@link TravelNetworks}: runs the MS11 / F-TRAVEL selftest on server start, registers the
 * {@code travel} autorun task, and ticks wilderness node discovery + minor blessing refresh on player visit.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
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
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        if ((player.level().getGameTime() + player.getId() * 7L) % 40 != 0) return;
        TravelNetworks.tickPlayer(player);
    }
}
