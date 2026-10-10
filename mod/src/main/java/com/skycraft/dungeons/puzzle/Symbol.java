package com.skycraft.dungeons.puzzle;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/** The animal glyphs carved on Nordic puzzle pillars, claw door rings and dragon claw palms. */
public enum Symbol {
    BEAR, WOLF, HAWK, SNAKE, WHALE, DRAGON;

    public static final Symbol[] VALUES = values();
    public static final int COUNT = VALUES.length;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Component displayName() {
        return Component.translatable("symbol.skycraft." + id());
    }

    public static Symbol byIndex(int i) {
        return VALUES[Math.floorMod(i, COUNT)];
    }
}
