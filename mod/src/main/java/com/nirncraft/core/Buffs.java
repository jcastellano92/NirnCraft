package com.nirncraft.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/**
 * Simple timed flags stored on the player ("adrenaline_rush", "highborn", "fire_cloak", ...), keyed by game time.
 * Used for effects that don't map cleanly onto vanilla mob effects.
 */
public final class Buffs {
    private Buffs() {}

    public static void apply(Player player, String buff, int durationTicks) {
        PlayerData data = SkyData.get(player);
        CompoundTag tag = data.module("buffs");
        tag.putLong(buff, player.level().getGameTime() + durationTicks);
        data.markDirty();
    }

    public static boolean active(Player player, String buff) {
        CompoundTag tag = SkyData.get(player).module("buffs");
        return tag.contains(buff) && tag.getLongOr(buff, 0L) > player.level().getGameTime();
    }

    public static void clear(Player player, String buff) {
        PlayerData data = SkyData.get(player);
        data.module("buffs").remove(buff);
        data.markDirty();
    }
}
