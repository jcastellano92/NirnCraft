package com.skycraft.roads;

import com.skycraft.core.PlayerData;
import com.skycraft.world.LocationKind;
import com.skycraft.world.LocationNames;
import com.skycraft.world.WorldData;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Settlement names and the link to the world module's location discovery. Names come from the world module's
 * {@link LocationNames} with the same location id it uses ({@code dim|structure|cx,cz}), so the signposts say the same
 * name as the "DISCOVERED" popup and the map.
 */
final class SettlementNames {
    private static final String[] FALLBACK = {
            "Riverwood", "Rorikstead", "Ivarstead", "Dragon Bridge", "Karthwasten", "Kynesgrove", "Shor's Stone",
            "Helgen", "Stonehills", "Old Hroldan", "Rimerock", "Heartwood Mill", "Anga's Mill", "Lakeview", "Windstad"
    };

    private SettlementNames() {}

    /** The world module's location id for a structure start. */
    static String locationId(ResourceKey<Level> dimension, ResourceLocation structure, ChunkPos start) {
        return dimension.location() + "|" + structure + "|" + start.x + "," + start.z;
    }

    /** The same name the world module gives this location (same classifier, same id). */
    static String name(ResourceKey<Level> dimension, Holder<Structure> holder, ResourceLocation structure, String locationId) {
        try {
            LocationKind kind = LocationKind.classify(holder, dimension);
            String name = LocationNames.generate(locationId, kind == null ? LocationKind.TOWN : kind, structure.getPath());
            if (name != null && !name.isEmpty()) return name;
        } catch (RuntimeException ignored) {
            // fall through to our own deterministic name
        }
        return FALLBACK[Math.floorMod(LocationNames.hash(locationId), FALLBACK.length)];
    }

    /** True if the player has discovered this settlement through the world module (it then has its own marker). */
    static boolean discovered(PlayerData data, Settlement s) {
        try {
            return WorldData.find(data, s.locationId) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
