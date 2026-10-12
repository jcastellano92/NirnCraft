package com.skycraft.roads;

import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * A road destination: a town, village or tavern ({@code #skycraft:towns}, {@link #town} true) or a dungeon the roads
 * lead to ({@code #skycraft:road_destinations}). Only towns get people (settlers, innkeepers) and are returned by
 * {@link RoadsData#settlements()}. Positions are the structure's bounding box center; the y coordinate is refined from
 * the real heightmap once the center chunk is loaded.
 */
public final class Settlement {
    /** Extra margin around the structure bounding box in which nothing is paved. */
    public static final int BOX_MARGIN = 6;
    /** Bounding boxes larger than this (per side from the center) are clamped, so huge modded towns stay sane. */
    private static final int MAX_HALF_EXTENT = 240;

    public final int id;
    /** Start chunk ({@code ChunkPos.toLong()}): unique per structure start. */
    public final long key;
    public final int x;
    public final int z;
    public int y;
    public boolean yResolved;
    public final int minX, minZ, maxX, maxZ;
    public final String name;
    /** Structure registry id, e.g. {@code minecraft:village_plains}. */
    public final String structure;
    /** The world module's location id ({@code dim|structure|cx,cz}) used to check discovery. */
    public final String locationId;
    /** An inhabited town ({@code #skycraft:towns}); false for dungeons reached by a spur road. */
    public boolean town = true;
    /** The named {@link Place} this destination is (planned at world start), or empty for one found in the world. */
    public String placeId = "";

    public Settlement(int id, long key, int x, int y, int z, int minX, int minZ, int maxX, int maxZ, String name,
                      String structure, String locationId, boolean yResolved) {
        this.id = id;
        this.key = key;
        this.x = x;
        this.y = y;
        this.z = z;
        this.minX = Math.max(minX, x - MAX_HALF_EXTENT);
        this.minZ = Math.max(minZ, z - MAX_HALF_EXTENT);
        this.maxX = Math.min(maxX, x + MAX_HALF_EXTENT);
        this.maxZ = Math.min(maxZ, z + MAX_HALF_EXTENT);
        this.name = name;
        this.structure = structure;
        this.locationId = locationId;
        this.yResolved = yResolved;
    }

    /** True if (px, pz) is inside the settlement: within {@code radius} of the center or inside its box (+margin). */
    public boolean inZone(int px, int pz, int radius) {
        long dx = px - x;
        long dz = pz - z;
        if (dx * dx + dz * dz <= (long) radius * radius) return true;
        return px >= minX - BOX_MARGIN && px <= maxX + BOX_MARGIN && pz >= minZ - BOX_MARGIN && pz <= maxZ + BOX_MARGIN;
    }

    /** Radius of the built town (its nearest gate) from {@link GateEnds}; -1 = not computed yet, 0 = unknown. */
    private int core = -1;

    /**
     * Roads leave this unpaved: the zone of {@link #inZone}, but for a planned town only the ground inside its gates
     * (so roads are paved right up to the gate instead of stopping at the edge of the zone box, which for a city lies
     * far outside its walls).
     */
    boolean noRoadZone(int px, int pz, int radius) {
        if (!inZone(px, pz, radius)) return false;
        if (core < 0) {
            int c = 0;
            if (town && !placeId.isEmpty()) {
                try {
                    c = place().filter(p -> p.type().isSettlement()).map(GateEnds::coreRadius).orElse(0);
                } catch (RuntimeException e) {
                    c = 0;
                }
            }
            core = c;
        }
        if (!town) {
            // a dungeon mouth, camp or landmark: only its own footprint is left alone, the road is paved up to it
            long ddx = px - x, ddz = pz - z;
            return ddx * ddx + ddz * ddz <= 36L || (px >= minX && px <= maxX && pz >= minZ && pz <= maxZ);
        }
        if (core <= 0) return true;
        double dx = px - x, dz = pz - z;
        return dx * dx + dz * dz <= (double) core * core;
    }

    /** The named place, if this destination is one. */
    public java.util.Optional<Place> place() {
        return placeId.isEmpty() ? java.util.Optional.empty() : Places.byId(placeId);
    }

    /** The name for signs and maps: the place's translatable name (shown in each reader's language), else the literal. */
    public Component displayName() {
        return place().map(Place::name).orElseGet(() -> Component.literal(name));
    }

    public double distSq(double px, double pz) {
        double dx = px - (x + 0.5);
        double dz = pz - (z + 0.5);
        return dx * dx + dz * dz;
    }

    public int zoneMinX(int radius) {
        return Math.min(x - radius, minX - BOX_MARGIN);
    }

    public int zoneMaxX(int radius) {
        return Math.max(x + radius, maxX + BOX_MARGIN);
    }

    public int zoneMinZ(int radius) {
        return Math.min(z - radius, minZ - BOX_MARGIN);
    }

    public int zoneMaxZ(int radius) {
        return Math.max(z + radius, maxZ + BOX_MARGIN);
    }

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putLong("key", key);
        t.putIntArray("pos", new int[]{x, y, z});
        t.putIntArray("box", new int[]{minX, minZ, maxX, maxZ});
        t.putString("name", name);
        t.putString("structure", structure);
        t.putString("loc", locationId);
        t.putBoolean("yres", yResolved);
        if (!town) t.putBoolean("dest", true);
        if (!placeId.isEmpty()) t.putString("place", placeId);
        return t;
    }

    static Settlement load(CompoundTag t) {
        int[] p = t.getIntArray("pos");
        int[] b = t.getIntArray("box");
        if (p.length < 3 || b.length < 4) return null;
        Settlement s = new Settlement(t.getInt("id"), t.getLong("key"), p[0], p[1], p[2], b[0], b[1], b[2], b[3],
                t.getString("name"), t.getString("structure"), t.getString("loc"), t.getBoolean("yres"));
        s.town = !t.getBoolean("dest");
        s.placeId = t.getString("place");
        return s;
    }
}
