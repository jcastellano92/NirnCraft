package com.skycraft.curse;

import java.util.Locale;

/** The two curses a player can carry, for {@link Curses#cure} and quest hooks ({@code cure:vampirism}). */
public enum CurseType {
    /** Sanguinare Vampiris and full vampirism (with the Vampire Lord gift). */
    VAMPIRISM,
    /** The beast blood of the Companions' Circle. */
    LYCANTHROPY;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static CurseType byId(String id) {
        for (CurseType t : values()) {
            if (t.id().equals(id)) return t;
        }
        if ("beast_blood".equals(id) || "werewolf".equals(id)) return LYCANTHROPY;
        if ("vampire".equals(id) || "sanguinare".equals(id)) return VAMPIRISM;
        return null;
    }
}
