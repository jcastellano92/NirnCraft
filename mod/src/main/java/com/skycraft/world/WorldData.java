package com.skycraft.world;

import com.skycraft.core.PlayerData;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * Accessors for {@code data.module("world")}:
 * <ul>
 *     <li>{@code discovered}: list of {id, name, type, x, y, z, dim, t, ax, ay, az, box[6], cleared, known, place};
 *     named places of Tamriel have the id {@code place|<place id>} and {@code place} set</li>
 *     <li>{@code holds}: list of region ids the player has entered ({@code skyrim/whiterun})</li>
 *     <li>{@code seen_nether}, {@code seen_end}: realm titles already shown</li>
 * </ul>
 * (x, y, z) is the centre of the location; (ax, ay, az) is where the player stood when discovering it (the
 * fast-travel arrival point).
 */
public final class WorldData {
    public static final String MODULE = "world";

    private WorldData() {}

    public static CompoundTag root(PlayerData data) {
        return data.module(MODULE);
    }

    public static ListTag discovered(PlayerData data) {
        CompoundTag m = root(data);
        if (!com.skycraft.platform.NbtData.hasType(m, "discovered", Tag.TAG_LIST)) m.put("discovered", new ListTag());
        return m.getListOrEmpty("discovered");
    }

    public static CompoundTag find(PlayerData data, String id) {
        ListTag list = discovered(data);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompoundOrEmpty(i);
            if (t.getStringOr("id", "").equals(id)) return t;
        }
        return null;
    }

    public static boolean hasVisitedHold(PlayerData data, String hold) {
        ListTag list = root(data).getListOrEmpty("holds");
        for (int i = 0; i < list.size(); i++) if (list.getStringOr(i, "").equals(hold)) return true;
        return false;
    }

    public static void addVisitedHold(PlayerData data, String hold) {
        CompoundTag m = root(data);
        if (!com.skycraft.platform.NbtData.hasType(m, "holds", Tag.TAG_LIST)) m.put("holds", new ListTag());
        m.getListOrEmpty("holds").add(StringTag.valueOf(hold));
        data.markDirty();
    }

    // ------------------------------------------------------------------ named places

    public static final String PLACE_PREFIX = "place|";

    /** Location id of a named place of Tamriel ({@code place|bleak_falls_barrow}). */
    public static String placeLocId(String placeId) {
        return PLACE_PREFIX + placeId;
    }

    /** The named place a location entry stands for, if it is one. */
    public static Optional<Place> place(CompoundTag loc) {
        String id = loc.getStringOr("place", "");
        return id.isEmpty() ? Optional.empty() : Places.byId(id);
    }

    /**
     * The name to show for a location entry: a named place's (translated) name, otherwise the generated name stored
     * when it was discovered.
     */
    public static Component displayName(CompoundTag loc) {
        return place(loc).map(Place::name).orElseGet(() -> Component.literal(loc.getStringOr("name", "")));
    }

    /** Whether the player has discovered (not merely heard of) the named place. */
    public static boolean discoveredPlace(PlayerData data, String placeId) {
        CompoundTag loc = find(data, placeLocId(placeId));
        return loc != null && !loc.getBooleanOr("known", false);
    }

    /** True if (x, y, z) lies within the stored bounding box of a location, grown by {@code margin}. */
    public static boolean inBox(CompoundTag loc, double x, double y, double z, int margin) {
        int[] b = loc.getIntArray("box").orElseGet(() -> new int[0]);
        if (b.length != 6) return false;
        return x >= b[0] - margin && x < b[3] + 1 + margin && y >= b[1] - margin && y < b[4] + 1 + margin
                && z >= b[2] - margin && z < b[5] + 1 + margin;
    }

    /** Whether the player has already discovered a location in the same dimension within radius blocks. */
    public static boolean hasDiscoveredNear(PlayerData data, String dim, int x, int z, int radius) {
        ListTag list = discovered(data);
        long r2 = (long) radius * radius;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompoundOrEmpty(i);
            if (t.getBooleanOr("known", false)) continue;
            if (!t.getStringOr("dim", "").equals(dim)) continue;
            int dx = t.getIntOr("x", 0) - x;
            int dz = t.getIntOr("z", 0) - z;
            if ((long) dx * dx + (long) dz * dz <= r2) return true;
        }
        return false;
    }
}
