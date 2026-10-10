package com.skycraft.magic.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Contract 7 entry point (docs/PLAYTEST-1.md). Owned by workstream L; the signature is fixed. */
@OnlyIn(Dist.CLIENT)
public final class MagicClient {
    private MagicClient() {}

    public static void openMagicMenu() {
        Minecraft.getInstance().setScreen(new MagicMenuScreen());
    }

    /** The Spellmaking Altar at {@code pos} was used. */
    public static void openSpellmaking(net.minecraft.core.BlockPos pos) {
        Minecraft.getInstance().setScreen(new SpellmakingScreen(pos));
    }
}
