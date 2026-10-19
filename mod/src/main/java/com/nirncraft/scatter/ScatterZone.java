package com.nirncraft.scatter;

import java.util.Locale;

/**
 * What a stretch of land is for, as far as clutter goes. Decided per chunk by {@link ZoneModel} from how near the
 * nearest settlement, road, water, dungeon and ruin are. The rule table ({@code nirncraft/scatter/rules.json}) lists
 * what each zone gets.
 */
public enum ScatterZone {
    /** Within a settlement's reach: carts, crates, rubbish, graves, firewood, hay. */
    URBAN_FRINGE,
    /** Further out: fields, pens, fences, haystacks, the odd cart; nature creeps back. */
    FARMLAND,
    /** Beside a road away from towns: wayside leavings. */
    ROADSIDE,
    /** By a lake, river or coast away from towns. */
    SHORE,
    /** Around forts, landmarks, battlefields and mage ruins. */
    RUIN_ADJACENT,
    /** Around dungeon entrances: bones, warning cairns, blood trails. */
    DUNGEON_ADJACENT,
    /** Open country: few human traces, natural features. */
    WILD,
    /** Far from everything: almost no human traces, the most natural formations. */
    DEEP_WILD,
    /** Open sea, lake bed, or nothing to say. */
    NONE;

    public static final ScatterZone[] VALUES = values();
    public final String id = name().toLowerCase(Locale.ROOT);

    public static ScatterZone byId(String id) {
        for (ScatterZone z : VALUES) if (z.id.equals(id)) return z;
        return null;
    }
}
