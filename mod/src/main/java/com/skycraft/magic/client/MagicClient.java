package com.skycraft.magic.client;

import net.minecraft.client.Minecraft;

/** Contract 7 entry point (docs/PLAYTEST-1.md). Owned by workstream L; the signature is fixed. */
public final class MagicClient {
    private MagicClient() {}

    public static void openMagicMenu() {
        Minecraft.getInstance().gui.setScreen(new MagicMenuScreen());
    }

    /** The Spellmaking Altar at {@code pos} was used. */
    public static void openSpellmaking(net.minecraft.core.BlockPos pos) {
        Minecraft.getInstance().gui.setScreen(new SpellmakingScreen(pos));
    }
}
