package com.skycraft.worldplan.scene;

import java.util.Set;

/**
 * Which kinds belong to the scene plan rather than to a per-chunk or per-cell roll. Scatter kinds tagged
 * {@code human} in {@code skycraft/scatter/kinds.json} are scene kinds by their tag; the landmark vignettes of the
 * builder families below (tiers S and M of {@code skycraft/landmarks/kinds.json}: graves, cairns, packs, camps,
 * offerings, ambushes, digs...) are human traces too, so the landmark grid no longer places them and the scene plan
 * sets them at an anchor instead ({@code lm:<kind id>} props).
 */
public final class SceneKinds {
    /** Landmark builder families that are human traces (placed only by scenes). */
    public static final Set<String> LANDMARK_BUILDERS = Set.of("grave", "cairn_token", "bedroll", "offering", "pack", "warning",
            "remains", "fire_ring", "charms", "pilgrim", "hunters", "travellers", "smuggler", "ambush", "skirmish", "boat_wreck",
            "sacrifice", "blind", "claim", "dig", "beacon",
            // trailside and trail's-end vignettes
            "adit", "overlook", "treehouse", "frozen", "castaway", "angler", "waystone", "den", "cabin", "alcove", "well", "apiary",
            "herbs", "watch_post");
    /** Landmark place variants whose edge is a ruin's edge. */
    public static final Set<String> RUIN_VARIANTS = Set.of("ruin", "dwemer", "altar", "monument", "stones", "black_book_altar");

    private SceneKinds() {}

    /** True when a landmark vignette of this builder family is placed by the scene plan, not by the landmark grid. */
    public static boolean planned(String landmarkBuilder) {
        return LANDMARK_BUILDERS.contains(landmarkBuilder);
    }
}
