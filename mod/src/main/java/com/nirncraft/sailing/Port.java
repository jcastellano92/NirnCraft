package com.nirncraft.sailing;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * A port of {@code nirncraft/tamriel/ports.json}: a harbour near a coastal city ({@code kind = "port"}) or an
 * expedition landing on a foreign coast ({@code kind = "landing"}). {@code (x, z)} is a hint near the harbour; the
 * pier itself goes on the nearest suitable water ({@link PortSites}).
 *
 * @param place    the {@code places.json} id of the city it serves, or empty
 * @param style    building style of the pier and hut (a culture id: nord, imperial, dunmer, ...)
 * @param ferry    ship moored at the pier
 * @param land     for landings: the foreign land ({@code atmora}, {@code akavir}...)
 * @param approach for landings: the port the expedition sails from (the landing is on the coast facing it)
 * @param dock     explicit water point for the pier, or null to search from the hint
 */
public record Port(String id, Component name, String kind, String place, String style, ShipType ferry,
                   int x, int z, String land, String approach, int @Nullable [] dock) {

    public boolean isLanding() {
        return "landing".equals(kind);
    }

    public String langKey() {
        return "sailing.nirncraft.port." + id;
    }
}
