package com.skycraft.quest.client;

import net.minecraft.client.Minecraft;

/** Contract 7 entry point (docs/PLAYTEST-1.md). Owned by workstream K; the signature is fixed. */
public final class JournalClient {
    private JournalClient() {}

    public static void open() {
        Minecraft.getInstance().gui.setScreen(new JournalScreen());
    }
}
