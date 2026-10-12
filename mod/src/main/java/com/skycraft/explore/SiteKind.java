package com.skycraft.explore;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Skyrim's "minor locations": the small unmarked sites between the named places. Each kind is built by one method
 * in {@code explore.build}; {@link MinorSites} decides where (culture, terrain and water context), {@link
 * MinorSiteFeature} builds it. {@code radius} is the footprint (blocks from the centre) the builder may touch; it
 * must stay within the 3x3 chunks a feature may write to.
 */
public enum SiteKind {
    // ---- everywhere
    ABANDONED_CAMP(7, true, "travellers camped by the road or water and left in a hurry"),
    HUNTERS_CAMP(7, true, "hunters dressing a kill at the forest edge"),
    WAYSHRINE(5, true, "a roadside shrine where travellers ask for safe passage"),
    TREASURE_CACHE(4, false, "a hidden stash a thief or smuggler meant to come back for"),
    DEAD_ADVENTURER(4, false, "an adventurer who died on the way to a ruin or dungeon"),
    HERB_GARDEN(6, false, "a hedge-witch or herbalist tends wild plants in sheltered ground"),
    RARE_REAGENT(6, false, "alchemical plants grow where the soil or water is unusual"),
    STONE_CIRCLE(7, true, "an old ritual ring on open high ground"),
    ANCIENT_BATTLEFIELD(10, false, "where an old war was fought and the dead left"),
    MAGE_RUIN(7, true, "the tower of a mage who failed or fled"),
    HERMIT_HUT(7, true, "a recluse who left settlements and lives off the land"),
    BANDIT_LOOKOUT(6, true, "bandits watch the road from high ground"),
    OVERLOOK(5, true, "a vantage point on a ridge that travellers climb for the view"),
    MINERAL_VEIN(6, false, "ore exposed by erosion on a rocky slope"),
    // ---- by water
    SHIPWRECK_DEBRIS(10, true, "a ship broke on the shore and the sea left what it could not carry"),
    FISHING_DOCK(9, true, "a fisher's landing at the water he works"),
    // ---- by climate or ground
    HOT_SPRING(6, true, "heat from the ground in volcanic or cold country"),
    DWEMER_VENT(6, true, "a vent of a buried Dwemer works"),
    NORDIC_CAIRN(6, true, "a burial cairn of the old Nords"),
    // ---- by culture
    AYLEID_WELL(5, true, "an Ayleid well-spring in old Cyrodiilic ground"),
    VELOTHI_SHRINE(6, true, "a Dunmer ancestral shrine on ash ground"),
    HIST_SAPLING(7, true, "a Hist sapling in Argonian marsh"),
    MOON_SUGAR_FIELD(8, false, "a Khajiit moon-sugar plot in warm country"),
    GIANT_BEEHIVE(6, true, "wild bees in a hollow of old trees"),
    CRYSTAL_OUTCROP(7, true, "crystal surfacing in crystal or high country"),
    YOKUDAN_OBELISK(5, true, "a Redguard memorial in the desert"),
    BRETON_MENHIR(6, true, "a Breton standing stone of the old clans"),
    AKAVIRI_SHRINE(5, true, "a shrine of the Akaviri who crossed the sea"),
    SEA_SHRINE(5, true, "a shrine to the sea by the coast"),
    REALM_MONOLITH(6, true, "a monolith marking a gate to another realm");

    public static final SiteKind[] VALUES = values();

    public final String id;
    public final int radius;
    /** Spottable from afar: smoke, a glow, a banner, or simply standing tall. */
    public final boolean landmark;
    /** The world reason this site exists (placement lint requires one per kind). */
    public final String purpose;

    SiteKind(int radius, boolean landmark, String purpose) {
        this.purpose = purpose;
        this.id = name().toLowerCase(Locale.ROOT);
        this.radius = radius;
        this.landmark = landmark;
    }

    public Component displayName() {
        return Component.translatable("explore.skycraft.site." + id);
    }

    public static SiteKind byId(String id) {
        for (SiteKind k : VALUES) if (k.id.equals(id)) return k;
        return ABANDONED_CAMP;
    }
}
