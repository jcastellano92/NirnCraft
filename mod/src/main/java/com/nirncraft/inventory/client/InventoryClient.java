package com.nirncraft.inventory.client;

import net.minecraft.client.Minecraft;

/** Contract 7 entry point (docs/PLAYTEST-1.md). Owned by workstream B; the signature is fixed. */
public final class InventoryClient {
    private InventoryClient() {}

    public static void open() {
        Minecraft.getInstance().gui.setScreen(new SkyrimInventoryScreen());
    }
}
