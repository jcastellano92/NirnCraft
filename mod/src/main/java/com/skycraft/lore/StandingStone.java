package com.skycraft.lore;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The thirteen Standing Stones (the birthsigns of the north). Touching a stone grants its blessing; only one stone's
 * blessing can be active at a time. The active sign id is stored in {@code data.module("lore").getStringOr("stone","")}.
 */
public enum StandingStone {
    WARRIOR(0xD85A48, true, false),
    MAGE(0x5C8CF0, true, false),
    THIEF(0x5CCB62, true, false),
    LADY(0xF0B8D8, false, false),
    LORD(0xE8C060, false, false),
    LOVER(0xFF7FA0, false, false),
    ATRONACH(0x9070F0, false, false),
    APPRENTICE(0x70D0F0, false, false),
    RITUAL(0xC8F0A0, false, true),
    SERPENT(0x70F0B0, false, true),
    SHADOW(0xA090C0, false, true),
    STEED(0xF0A050, false, false),
    TOWER(0xE0E0F8, false, true);

    public static final StandingStone[] VALUES = values();

    /** Glow color of the sigil (RGB). */
    public final int color;
    /** Warrior, Mage and Thief: the Guardian Stones, which always stand together. */
    public final boolean guardian;
    /** Signs with a once-a-day power (used with the stone power key). */
    public final boolean hasPower;

    StandingStone(int color, boolean guardian, boolean hasPower) {
        this.color = color;
        this.guardian = guardian;
        this.hasPower = hasPower;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** "The Warrior" */
    public Component signName() {
        return Component.translatable("stone.skycraft." + id());
    }

    /** "The Warrior Stone" */
    public Component stoneName() {
        return Component.translatable("stone.skycraft." + id() + ".stone");
    }

    /** One-line description of the blessing. */
    public Component description() {
        return Component.translatable("stone.skycraft." + id() + ".desc");
    }

    /** Name of the daily power, for signs that have one. */
    public Component powerName() {
        return Component.translatable("stone.skycraft." + id() + ".power");
    }

    @Nullable
    public static StandingStone byId(String id) {
        if (id == null || id.isEmpty()) return null;
        for (StandingStone s : VALUES) {
            if (s.id().equals(id)) return s;
        }
        return null;
    }
}
