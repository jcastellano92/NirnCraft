package com.nirncraft.tamriel.settlement.grammar;

import java.util.Locale;

/** How well off a household or business is: picks the style sheet tier (materials, size, decoration). */
public enum Wealth {
    POOR, COMMON, WEALTHY, NOBLE;

    public static final Wealth[] VALUES = values();

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Wealth byOrdinal(int i) {
        return i >= 0 && i < VALUES.length ? VALUES[i] : COMMON;
    }

    public boolean atLeast(Wealth w) {
        return ordinal() >= w.ordinal();
    }

    public Wealth up() {
        return byOrdinal(Math.min(VALUES.length - 1, ordinal() + 1));
    }

    public Wealth down() {
        return byOrdinal(Math.max(0, ordinal() - 1));
    }
}
