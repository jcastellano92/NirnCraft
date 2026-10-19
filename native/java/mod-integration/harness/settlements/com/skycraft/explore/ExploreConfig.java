package com.skycraft.explore;

/** Harness stand-in for the mod's explore config (its ModConfigSpec needs NeoForge's config runtime): defaults. */
public final class ExploreConfig {
    private ExploreConfig() {}

    /** The density multiplier at its default (1: minor sites and natural landmarks on). */
    public static double densityScale() {
        return 1.0;
    }
}
