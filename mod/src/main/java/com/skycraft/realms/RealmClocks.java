package com.skycraft.realms;

import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Keeps the realms' authored fixed times independent of the overworld clock. */
public final class RealmClocks {
    private static final Map<String, Long> FIXED_TIMES = Map.of(
            "the_nether", 13200L, "the_end", 7000L, "apocrypha", 18000L,
            "soul_cairn", 15500L, "coldharbour", 17000L, "jail", 18000L);
    private RealmClocks() {}

    public static void onStarted(ServerStartedEvent event) {
        var server = event.getServer();
        // Registry-only native test servers have clocks but no global game rules.
        if (server.overworld() == null) return;
        var clocks = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK);
        FIXED_TIMES.forEach((name, ticks) -> clocks.get(ResourceKey.create(Registries.WORLD_CLOCK,
                Identifier.fromNamespaceAndPath("skycraft", "fixed/" + name))).ifPresent(clock -> {
            server.clockManager().setTotalTicks(clock, ticks);
            server.clockManager().setPaused(clock, true);
        }));
    }
}
