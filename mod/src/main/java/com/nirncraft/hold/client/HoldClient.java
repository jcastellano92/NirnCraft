package com.nirncraft.hold.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.hold.HoldPackets;
import com.nirncraft.network.SkyNetwork;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * The client's side of the player hold (docs/PLAYER-HOLD.md): whether the server holds this player, and the one message
 * back, "my loading is complete". The loading screen (atlas/AtlasLoadingScreen) decides when that is true
 * (atlas/AtlasReadiness) and calls {@link #sendReady}; the server releases the player and says so, and only then does
 * the screen close. Nothing here releases anyone by itself.
 */
public final class HoldClient {
    /** Test knob: this client never reports ready (a client that cannot finish loading). */
    public static final boolean NEVER_READY = Boolean.getBoolean("nirncraft.hold.neverReady");

    private static volatile boolean held;
    private static volatile long token;
    private static volatile long sinceMs;

    private HoldClient() {}

    /** True while the server holds this player. */
    public static boolean held() {
        return held;
    }

    public static long heldMillis() {
        return held ? System.currentTimeMillis() - sinceMs : 0;
    }

    public static void onHeld(HoldPackets.Held m) {
        if (m.held() && !held) sinceMs = System.currentTimeMillis();
        held = m.held();
        token = m.token();
        NirnCraft.LOGGER.info("hold: the server {} this player (hold {})", m.held() ? "holds" : "released", m.token());
    }

    /** Reports that loading is complete ({@code fallback}: the player chose to enter without it). False when not sent. */
    public static boolean sendReady(boolean fallback) {
        if (!held || (NEVER_READY && !fallback)) return false;
        SkyNetwork.sendToServer(new HoldPackets.Ready(token, fallback));
        return true;
    }

    @EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
    public static final class Events {
        private Events() {}

        /** A new connection starts un-held; the server says at login whether it holds this player. */
        @SubscribeEvent
        public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            held = false;
        }
    }
}
