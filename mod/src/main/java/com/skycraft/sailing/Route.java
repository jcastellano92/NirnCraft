package com.skycraft.sailing;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * A sea route between two ports (both directions). Expeditions sail to a foreign landing and back.
 *
 * @param ship  the named ship that sails it (e.g. the Northern Maiden), or null for "a ship"
 * @param fare  fixed fare in Septims, or -1 for the distance-based fare
 */
public record Route(String a, String b, @Nullable Component ship, boolean expedition, int fare) {

    public boolean touches(String port) {
        return a.equals(port) || b.equals(port);
    }

    public String other(String port) {
        return a.equals(port) ? b : a;
    }

    /** Stable key for caches, independent of direction. */
    public String key() {
        return a.compareTo(b) < 0 ? a + ">" + b : b + ">" + a;
    }
}
