package com.nirncraft.explore;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Exploration settings: {@code config/nirncraft-explore.toml}. */
public final class ExploreConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue MINOR_SITES;
    public static final ModConfigSpec.DoubleValue DENSITY;
    public static final ModConfigSpec.DoubleValue DISCOVERY_XP;
    public static final ModConfigSpec.DoubleValue MINOR_SITE_XP;
    public static final ModConfigSpec.BooleanValue FOUND_MESSAGES;
    public static final ModConfigSpec.BooleanValue TREASURE_MAP_MARKERS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("explore");
        MINOR_SITES = b.comment("Generate minor sites (camps, wayshrines, caches, cairns, wells, shrines...) between the named places.",
                        "Changing this only affects newly generated chunks.")
                .define("minorSites", true);
        DENSITY = b.comment("Multiplier on how many minor-site cells are occupied (1 = Skyrim-dense; 0.5 = half as many).",
                        "Changing this only affects newly generated chunks and must match the density audit's assumption.")
                .defineInRange("density", 1.0, 0.0, 1.5);
        DISCOVERY_XP = b.comment("Athletics experience (in skill-use units, scaled like other skill XP) for discovering a named place or",
                        "dungeon. Skyrim gives none; 0 turns it off.")
                .defineInRange("discoveryXp", 6.0, 0.0, 1000.0);
        MINOR_SITE_XP = b.comment("Athletics experience for coming upon a minor site for the first time; 0 turns it off.")
                .defineInRange("minorSiteXp", 2.0, 0.0, 1000.0);
        FOUND_MESSAGES = b.comment("Show a quiet \"Found: Hunter's Camp\" line the first time you come upon a minor site.")
                .define("foundMessages", true);
        TREASURE_MAP_MARKERS = b.comment("Reading a treasure map also marks the cache on the world map (Skyrim makes you find it from",
                        "the drawing alone; set false for that).")
                .define("treasureMapMarkers", true);
        b.pop();
        SPEC = b.build();
    }

    private ExploreConfig() {}

    /** The density multiplier; 1 before the config is loaded (worldgen threads may ask early). */
    public static double densityScale() {
        try {
            return MINOR_SITES.get() ? DENSITY.get() : 0.0;
        } catch (IllegalStateException e) {
            return 1.0;
        }
    }

    static double value(ModConfigSpec.DoubleValue v, double fallback) {
        try {
            return v.get();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    static boolean flag(ModConfigSpec.BooleanValue v, boolean fallback) {
        try {
            return v.get();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }
}
