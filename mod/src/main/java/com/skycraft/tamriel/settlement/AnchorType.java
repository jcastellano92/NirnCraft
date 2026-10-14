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
    HOMESTEAD,
    // ---- guild halls (the owner label is "<faction id>#<piece>", see plan/Halls)
    /** Where the hall's head stands (desk / dais). */
    GUILD_LEADER,
    /** A trainer's spot by the practice area or the study. */
    GUILD_TRAINER,
    /** Behind the hall's counter (guild vendor). */
    GUILD_VENDOR,
    /** A members' bed (free for members, trespass for others). */
    GUILD_BED,
    // ---- jails (crime.Jail reads these; a jail's anchors are recorded in cell order)
    /** Where a prisoner stands in a cell (one per cell). */
    JAIL_CELL,
    /** Lower half of a cell's door (one per cell). */
    JAIL_DOOR,
    /** Older jails: the two halves of a cell's evidence chest (two per cell, right then left); migrated into the stores. */
    JAIL_EVIDENCE,
    /** The locked door between the cell block and the guard room. */
    JAIL_GATE,
    /** Where released prisoners walk out (in front of the jail door). */
    JAIL_EXIT,
    /** Jail bounds (inclusive corners): leaving them is an escape. */
    JAIL_MIN,
    JAIL_MAX,
    /** A cell kept by an NPC prisoner. */
    JAIL_PRISONER,
    // ---- building grammar trades (appended: ordinals are saved)
    /** Behind a jeweller's counter. */
    JEWELER,
    /** Behind a bookseller's or scribe's desk. */
    BOOKSELLER,
    /** A tailor / clothier at the loom. */
    TAILOR,
    /** At the butcher's block. */
    BUTCHER,
    /** A fletcher / bowyer at the fletching table. */
    FLETCHER,
    /** The fence, hidden in a cellar. */
    FENCE,
    /** At the fishery, the docks or a fishing camp. */
    FISHER,
    /** A hunter at the tanning rack of a hunting camp or tannery. */
    HUNTER,
    /** A labourer at a sawmill, smelter, quarry, warehouse or plantation. */
    WORKER,
    /**
     * A settlement gate: where internal streets end and outer roads join (main gate first). Recorded per gate opening,
     * on the ground in the middle of the passage.
     */
    GATE,
    /** An inn room's door (lower half): locked unless the player has rented a room there. */
    INN_DOOR,
    /** A public bed (an inn's dormitory): anyone may sleep in it. */
    GUEST_BED,
    // ---- aftermath scenes (com.skycraft.fire.aftermath; appended: ordinals are saved)
    /** A person or body of an aftermath scene; the owner label is {@code pose|role|option} (see fire.aftermath.Tableau). */
    TABLEAU,
    /** A fire seen from afar; the owner label is {@code intensity|height|smoke[|flags]} (see fire.FireAnchors). */
    FIRE_SITE,
    // ---- jail evidence (crime.JailEvidence; appended: ordinals are saved)
    /** The evidence chest: gives a prisoner back what the jail took from them (never in a cell). */
    JAIL_PERSONAL,
    /** The confiscated goods: the shared, locked store of stolen goods taken from prisoners (never in a cell). */
    JAIL_CONFISCATED;

    public static final AnchorType[] VALUES = values();

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AnchorType byId(String id) {
        for (AnchorType t : VALUES) if (t.id().equals(id)) return t;
        return null;
    }
}
