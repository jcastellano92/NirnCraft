package com.skycraft.travel;

import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * The canonical travel modes across Tamriel's provincial travel networks (MS11).
 */
public enum TravelMode {
    CARRIAGE("carriage", false),
    STRIDER("strider", false),
    BOAT("boat", false),
    RIVER_BOAT("river_boat", false),
    BARGE("barge", false),
    CARAVAN("caravan", false),
    GUILD_GUIDE("guild_guide", true),
    LIFT("lift", false),
    PROPYLON("propylon", false),
    INTERVENTION("intervention", false),
    WAYSHRINE("wayshrine", false);

    private final String id;
    private final boolean allowAllToAll;

    TravelMode(String id, boolean allowAllToAll) {
        this.id = id;
        this.allowAllToAll = allowAllToAll;
    }

    public String id() {
        return id;
    }

    /** True only when the travel mode is all-to-all in Elder Scrolls lore (Mages Guild guides). */
    public boolean allowAllToAll() {
        return allowAllToAll;
    }

    public boolean isWater() {
        return this == BOAT || this == RIVER_BOAT || this == BARGE;
    }

    public boolean isRoad() {
        return this == CARRIAGE || this == CARAVAN || this == STRIDER;
    }

    public Component displayName() {
        return Component.translatable("travel.skycraft.mode." + id);
    }

    public static Optional<TravelMode> byId(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String norm = raw.trim().toLowerCase(Locale.ROOT);
        for (TravelMode m : values()) {
            if (m.id.equals(norm)) return Optional.of(m);
        }
        return Optional.empty();
    }
}
