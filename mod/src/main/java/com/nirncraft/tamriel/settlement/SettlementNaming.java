package com.nirncraft.tamriel.settlement;

import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

/**
 * Names of named settlements for the world module's location ids ({@code dim|structure|cx,cz}): a NirnCraft settlement
 * structure started in a named place's anchor chunk is that place ("Whiterun", "Riverwood"), so discovery, the map,
 * roads and signposts all agree on the name.
 */
public final class SettlementNaming {
    private SettlementNaming() {}

    /** The place name for a location id, or null if it is not a named NirnCraft settlement. */
    @Nullable
    public static String forLocation(String locationId) {
        int a = locationId.indexOf('|');
        int b = locationId.lastIndexOf('|');
        if (a < 0 || b <= a) return null;
        String structure = locationId.substring(a + 1, b);
        if (!structure.startsWith("nirncraft:settlement_") && !structure.equals("nirncraft:homestead_plot")) return null;
        String[] xz = locationId.substring(b + 1).split(",");
        if (xz.length != 2) return null;
        try {
            ChunkPos cp = new ChunkPos(Integer.parseInt(xz[0].trim()), Integer.parseInt(xz[1].trim()));
            for (Place p : Places.inChunk(cp)) {
                if (p.type().isSettlement()) return p.name().getString();
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }
}
