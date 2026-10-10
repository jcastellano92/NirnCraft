package com.skycraft.dungeons.puzzle;

import java.util.Locale;

/**
 * The dragon claws that open Nordic claw doors. Each claw's palm shows the three glyphs, top to bottom, that the
 * door's rings must show before the claw turns in the keyhole.
 */
public enum Claw {
    GOLDEN(0xE8B830, Symbol.BEAR, Symbol.HAWK, Symbol.WOLF),
    IVORY(0xF0E8D0, Symbol.WOLF, Symbol.WHALE, Symbol.SNAKE),
    CORAL(0xF07860, Symbol.HAWK, Symbol.HAWK, Symbol.DRAGON),
    RUBY(0xD02838, Symbol.DRAGON, Symbol.BEAR, Symbol.SNAKE),
    SAPPHIRE(0x3060E0, Symbol.WHALE, Symbol.SNAKE, Symbol.WHALE),
    EMERALD(0x30C060, Symbol.SNAKE, Symbol.WOLF, Symbol.HAWK),
    GLASS(0xB0F0C8, Symbol.HAWK, Symbol.DRAGON, Symbol.BEAR),
    IRON(0x8C8C90, Symbol.WOLF, Symbol.BEAR, Symbol.WOLF),
    EBONY(0x302838, Symbol.DRAGON, Symbol.SNAKE, Symbol.DRAGON),
    DIAMOND(0xB8F0F8, Symbol.BEAR, Symbol.DRAGON, Symbol.WHALE);

    public static final Claw[] VALUES = values();

    public final int color;
    private final Symbol[] combo;

    Claw(int color, Symbol top, Symbol middle, Symbol bottom) {
        this.color = color;
        this.combo = new Symbol[]{top, middle, bottom};
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Registry path of the claw item, e.g. {@code golden_claw}. */
    public String itemId() {
        return id() + "_claw";
    }

    /** Glyph for ring {@code i}: 0 = top, 2 = bottom. */
    public Symbol symbol(int i) {
        return combo[i];
    }

    public static Claw byIndex(int i) {
        return VALUES[Math.floorMod(i, VALUES.length)];
    }
}
