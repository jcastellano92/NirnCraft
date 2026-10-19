package com.nirncraft.tamriel;

import java.util.Locale;

/** Kinds of named places in {@code places.json}. See docs/TAMRIEL.md section 6. */
public enum PlaceType {
    CAPITAL, CITY, TOWN, VILLAGE, FARM,
    FORT, STANDING_STONE, WORD_WALL, DRAGON_MOUND, DAEDRIC_SHRINE, LANDMARK, DUNGEON_FIXED;

    public boolean isSettlement() {
        return this == CAPITAL || this == CITY || this == TOWN || this == VILLAGE || this == FARM;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static PlaceType byId(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }
}
