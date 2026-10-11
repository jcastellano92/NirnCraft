package com.skycraft.tamriel.settlement;

import java.util.Locale;

/**
 * Points of interest a settlement builder records while it paints (see {@link SettlementAnchors}): where people
 * stand, sleep and work, which containers belong to whom, and the house-for-sale bounds.
 */
public enum AnchorType {
    /** Market square / meeting point (a bell stands there). */
    MEETING,
    /** Behind the inn's bar counter. */
    INN,
    /** A bed in an inn's rentable room. */
    INN_BED,
    /** The inn's performing spot. */
    BARD,
    /** The Jarl's throne (or a stronghold chief's seat). */
    JARL,
    STEWARD,
    /** Court wizard (next to the arcane enchanter). */
    WIZARD,
    HOUSECARL,
    /** At the forge. */
    SMITH,
    /** Behind a shop counter. */
    MERCHANT,
    ALCHEMIST,
    /** Before the temple altar. */
    PRIEST,
    /** Guard post: barracks, gates, towers, keep. */
    GUARD,
    /** Stablemaster's spot. */
    STABLES,
    /** Where carriages wait. */
    CARRIAGE,
    /** A household bed. */
    BED,
    FARMER,
    MILLER,
    MINER,
    LUMBERJACK,
    /** A container owned by a household or shop. */
    CONTAINER,
    /** House for sale: the owner's bed. */
    HOUSE_BED,
    /** House for sale: the front door (lower half). */
    HOUSE_DOOR,
    /** House for sale: bounding box corners (inclusive). */
    HOUSE_MIN,
    HOUSE_MAX,
    /** A homestead plot's building site (drafting table spot). */
    HOMESTEAD;

    public static final AnchorType[] VALUES = values();

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AnchorType byId(String id) {
        for (AnchorType t : VALUES) if (t.id().equals(id)) return t;
        return null;
    }
}
