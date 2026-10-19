package com.nirncraft.lore;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The six All-Maker stones of Solstheim, sacred to the Skaal. Freed from Miraak's shell, each grants a blessing
 * that sits beside (never replaces) the birthsign of the Standing Stones; only one All-Maker blessing at a time.
 * The active one is {@code data.module("lore").getStringOr("allmaker","")}.
 */
public enum AllMakerStone {
    BEAST(0xC89058),
    EARTH(0x8A7A5A),
    SUN(0xF8D050),
    TREE(0x58B048),
    WATER(0x4890E0),
    WIND(0xD8E8F0);

    public static final AllMakerStone[] VALUES = values();

    public final int color;

    AllMakerStone(int color) {
        this.color = color;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** "The Beast Stone" */
    public Component stoneName() {
        return Component.translatable("stone.nirncraft.allmaker." + id());
    }

    public Component description() {
        return Component.translatable("stone.nirncraft.allmaker." + id() + ".desc");
    }

    @Nullable
    public static AllMakerStone byId(String id) {
        if (id == null || id.isEmpty()) return null;
        for (AllMakerStone s : VALUES) if (s.id().equals(id)) return s;
        return null;
    }

    public static AllMakerStone byIndex(int i) {
        return VALUES[Math.floorMod(i, VALUES.length)];
    }
}
