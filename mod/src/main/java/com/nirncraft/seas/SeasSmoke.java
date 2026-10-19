package com.nirncraft.seas;

import com.nirncraft.NirnCraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Developer smoke test for a dedicated server ({@code -Dnirncraft.seastest.server=true}): once started, it loads a
 * patch of each of Nirn's seas in turn (the Sea of Ghosts, the Inner Sea, the Abecean, the Iliac Bay, the open ocean
 * off Atmora) so the sea, garden, islet and coast features generate, logs the time each took, and stops the server.
 * Does nothing in normal play.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class SeasSmoke {
    private static final boolean ENABLED = Boolean.getBoolean("nirncraft.seastest.server");
    private static final int[][] AREAS = {{-120, -2700}, {6980, 1080}, {-6120, 9380}, {-7320, 4380}, {-120, -5120}};
    private static int stage = -1;
    private static int wait;
    private static long started;

    private SeasSmoke() {}

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        if (!ENABLED) return;
        stage = 0;
        wait = 40;
        NirnCraft.LOGGER.info("Seas smoke: server started, loading the seas");
    }

    @SubscribeEvent
    public static void onTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!ENABLED || stage < 0 || !(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post)) return;
        if (--wait > 0) return;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        if (stage > 0) {
            int[] prev = AREAS[stage - 1];
            NirnCraft.LOGGER.info("Seas smoke: area {} ({}, {}) generated in {} ms; placed so far: {} sea sites, {} gardens, {} islets, {} coast sites",
                    stage, prev[0], prev[1], System.currentTimeMillis() - started, com.nirncraft.seas.world.SeaSiteFeature.PLACED.get(),
                    com.nirncraft.seas.world.SeaGardenFeature.PLACED.get(), com.nirncraft.seas.world.IsletFeature.PLACED.get(),
                    com.nirncraft.seas.world.CoastSiteFeature.PLACED.get());
            for (int cx = (prev[0] >> 4) - 4; cx <= (prev[0] >> 4) + 4; cx++)
                for (int cz = (prev[1] >> 4) - 4; cz <= (prev[1] >> 4) + 4; cz++) level.setChunkForced(cx, cz, false);
        }
        if (stage >= AREAS.length) {
            NirnCraft.LOGGER.info("Seas smoke: done, {} ships of the traffic, stopping", com.nirncraft.seas.traffic.SeaTraffic.traffic(level).size());
            stage = -1;
            server.halt(false);
            return;
        }
        int[] a = AREAS[stage];
        started = System.currentTimeMillis();
        // a 9 x 9 chunk patch, force-loaded (generated off-thread like any chunk): every feature in it runs
        for (int cx = (a[0] >> 4) - 4; cx <= (a[0] >> 4) + 4; cx++)
            for (int cz = (a[1] >> 4) - 4; cz <= (a[1] >> 4) + 4; cz++) level.setChunkForced(cx, cz, true);
        stage++;
        wait = 200;
    }
}
