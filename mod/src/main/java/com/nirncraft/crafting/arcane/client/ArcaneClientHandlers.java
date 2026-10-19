package com.nirncraft.crafting.arcane.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Client-side handlers for arcane S2C packets (only ever class-loaded on the client). */
public final class ArcaneClientHandlers {
    private ArcaneClientHandlers() {}

    public static void openStation(int kind, BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (kind == 0) mc.gui.setScreen(new ArcaneEnchanterScreen(pos));
        else mc.gui.setScreen(new AlchemyScreen(pos));
    }
}
