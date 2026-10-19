package com.nirncraft.travel;

import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * A node in a provincial travel network (capital, town, port, landing, lift, standing stone, shrine, or wayshrine).
 */
public record TravelNode(
        String id,
        String province,
        String region,
        @Nullable String place,
        String nameKey,
        int x,
        int z,
        TravelMode primaryMode,
        List<TravelMode> modes,
        String kind,
        boolean discoveredOnArrival,
        boolean free,
        boolean hashed,
        @Nullable String blessing,
        List<String> why
) {
    public Component displayName() {
        if (nameKey != null && !nameKey.isBlank() && !nameKey.startsWith("place.nirncraft.")) {
            return Component.translatable(nameKey);
        }
        if (place != null && !place.isBlank()) {
            Optional<Place> p = Places.byId(place);
            if (p.isPresent()) return p.get().name();
        }
        if (nameKey != null && !nameKey.isBlank()) {
            return Component.translatable(nameKey);
        }
        return Component.literal(id);
    }

    public BlockPos pos(int y) {
        return new BlockPos(x, y, z);
    }

    public boolean supports(TravelMode mode) {
        return primaryMode == mode || modes.contains(mode);
    }

    public boolean isWilderness() {
        return free
                || "standing_stone".equals(kind)
                || "shrine".equals(kind)
                || "wayshrine".equals(kind)
                || primaryMode == TravelMode.WAYSHRINE;
    }

    public double distanceTo(int bx, int bz) {
        return Math.hypot(x - bx, z - bz);
    }
}
