package com.nirncraft.world.client;

import com.nirncraft.world.WorldPackets;
import net.minecraft.network.chat.Component;

/** Client-side entry points for world packets and common-side events (only class-loaded on the client). */
public final class WorldClient {
    private WorldClient() {}

    /** The enclosed place the player is in (LocationKind.icon), -1 outside: the pack's interior grade follows it. */
    public static volatile int interior = -1;

    public static void interior(int kind) {
        interior = kind;
    }

    public static void cue(int cue) {
        if (cue == WorldPackets.Cue.DISCOVERY) MusicController.playDiscoverySting();
    }

    public static void fade(int hours, int kind) {
        Component text;
        if (kind == WorldPackets.RestFade.TRAVEL) {
            RegionBanner.reset(); // arriving by fast travel or carriage is not a border crossing
            text = hours > 0 ? Component.translatable("world.nirncraft.fade.travel_hours", hours) : Component.empty();
        } else {
            text = Component.translatable(hours == 1 ? "world.nirncraft.fade.hour" : "world.nirncraft.fade.hours", hours);
        }
        ScreenFade.start(text, kind == WorldPackets.RestFade.TRAVEL ? 1200 : 1800);
    }

    public static void restState(WorldPackets.RestState m) {
        RestClient.onState(m);
    }

    public static void restHint(WorldPackets.RestHint m) {
        RestClient.onHint(m);
    }
}
