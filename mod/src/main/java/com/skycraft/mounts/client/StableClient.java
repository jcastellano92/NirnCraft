package com.skycraft.mounts.client;

import com.skycraft.mounts.StablePackets;
import net.minecraft.client.Minecraft;

/** Client entry of the stable master's screen (called through {@code DistExecutor} from the packet). */
public final class StableClient {
    private StableClient() {}

    public static void handle(StablePackets.State state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof StableScreen screen && screen.npcId() == state.npcId()) {
            screen.update(state);
        } else if (state.open()) {
            mc.gui.setScreen(new StableScreen(state));
        }
    }
}
