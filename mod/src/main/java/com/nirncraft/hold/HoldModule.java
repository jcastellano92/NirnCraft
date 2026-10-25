package com.nirncraft.hold;

/** The player hold's wiring: its two payloads (SkyNetwork.init). Events and the command register themselves. */
public final class HoldModule {
    private HoldModule() {}

    public static void registerPackets() {
        HoldPackets.register();
    }
}
