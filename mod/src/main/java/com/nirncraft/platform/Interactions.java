package com.nirncraft.platform;

import net.minecraft.world.InteractionResult;

/** Retains predicted client swings and consumed server actions at migrated interaction boundaries. */
public final class Interactions {
    private Interactions() {}
    public static InteractionResult.Success sidedSuccess(boolean clientSide) {
        return clientSide ? InteractionResult.SUCCESS : InteractionResult.CONSUME;
    }
}
