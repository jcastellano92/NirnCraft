package com.nirncraft.world.client;

import net.minecraft.client.Minecraft;

/** Contract 7 entry point (docs/PLAYTEST-1.md). Owned by workstream C; the signature is fixed. */
public final class MapClient {
    private MapClient() {}

    public static void openMap() {
        Minecraft.getInstance().gui.setScreen(new MapScreen());
    }
}
