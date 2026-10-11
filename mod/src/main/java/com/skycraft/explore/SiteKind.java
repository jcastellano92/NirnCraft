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
    ABANDONED_CAMP(7, true),
    HUNTERS_CAMP(7, true),
    WAYSHRINE(5, true),
    TREASURE_CACHE(4, false),
    DEAD_ADVENTURER(4, false),
    HERB_GARDEN(6, false),
    RARE_REAGENT(6, false),
    STONE_CIRCLE(7, true),
    ANCIENT_BATTLEFIELD(10, false),
    MAGE_RUIN(7, true),
    HERMIT_HUT(7, true),
    BANDIT_LOOKOUT(6, true),
    OVERLOOK(5, true),
    MINERAL_VEIN(6, false),
    // ---- by water
    SHIPWRECK_DEBRIS(10, true),
    FISHING_DOCK(9, true),
    // ---- by climate or ground
    HOT_SPRING(6, true),
    DWEMER_VENT(6, true),
    NORDIC_CAIRN(6, true),
    // ---- by culture
    AYLEID_WELL(5, true),
    VELOTHI_SHRINE(6, true),
    HIST_SAPLING(7, true),
    MOON_SUGAR_FIELD(8, false),
    GIANT_BEEHIVE(6, true),
    CRYSTAL_OUTCROP(7, true),
    YOKUDAN_OBELISK(5, true),
    BRETON_MENHIR(6, true),
    AKAVIRI_SHRINE(5, true),
    SEA_SHRINE(5, true),
    REALM_MONOLITH(6, true);

    public static final SiteKind[] VALUES = values();

    public final String id;
    public final int radius;
    /** Spottable from afar: smoke, a glow, a banner, or simply standing tall. */
    public final boolean landmark;

    SiteKind(int radius, boolean landmark) {
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
