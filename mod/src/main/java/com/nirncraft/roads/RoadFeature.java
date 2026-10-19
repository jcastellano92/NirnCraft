package com.nirncraft.roads;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/** A roadside object placed when its chunk is paved: a lantern post, a signpost, a milestone or a wayside shrine. */
public final class RoadFeature {
    public static final int LANTERN = 0;
    public static final int SIGNPOST = 1;
    /** A stone post with the distances to both ends of the road. */
    public static final int MILESTONE = 2;
    /** A small shrine of one of the Divines beside a highway ({@link #dests} empty; {@link #variant} = which). */
    public static final int SHRINE = 3;
    /**
     * Roadside dressing (a cart, barrels, hay, a fence run, a cairn, a camp, a woodpile): {@link #variant} holds the
     * kind (low 4 bits, see {@link Dressing}) and the direction away from the road (2D data value, bits 4-5).
     */
    public static final int DRESSING = 4;
    /** A signpost carries at most this many destination signs. */
    public static final int MAX_DESTINATIONS = 3;

    /**
     * One sign: destination settlement, distance in blocks, and the direction (unit vector) the road leaves toward it.
     */
    public record Dest(int settlement, int distance, float dirX, float dirZ, String roadName) {
        public Dest(int settlement, int distance, float dirX, float dirZ) {
            this(settlement, distance, dirX, dirZ, "");
        }
    }

    public final int type;
    public final int x;
    public final int z;
    /** Road that created this feature. */
    public final int road;
    public final List<Dest> dests = new ArrayList<>();
    /** Type-specific choice (the Divine of a shrine). */
    public int variant;

    public RoadFeature(int type, int x, int z, int road) {
        this.type = type;
        this.x = x;
        this.z = z;
        this.road = road;
    }

    public boolean hasDest(int settlement) {
        for (Dest d : dests) if (d.settlement() == settlement) return true;
        return false;
    }

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("type", type);
        t.putInt("x", x);
        t.putInt("z", z);
        t.putInt("road", road);
        if (variant != 0) t.putInt("v", variant);
        if (!dests.isEmpty()) {
            ListTag list = new ListTag();
            for (Dest d : dests) {
                CompoundTag dt = new CompoundTag();
                dt.putInt("s", d.settlement());
                dt.putInt("d", d.distance());
                dt.putFloat("dx", d.dirX());
                dt.putFloat("dz", d.dirZ());
                if (!d.roadName().isEmpty()) dt.putString("n", d.roadName());
                list.add(dt);
            }
            t.put("dests", list);
        }
        return t;
    }

    static RoadFeature load(CompoundTag t) {
        RoadFeature f = new RoadFeature(t.getIntOr("type", 0), t.getIntOr("x", 0), t.getIntOr("z", 0), t.getIntOr("road", 0));
        f.variant = t.getIntOr("v", 0);
        ListTag list = t.getListOrEmpty("dests");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag dt = list.getCompoundOrEmpty(i);
            f.dests.add(new Dest(dt.getIntOr("s", 0), dt.getIntOr("d", 0), dt.getFloatOr("dx", 0f), dt.getFloatOr("dz", 0f), dt.getStringOr("n", "")));
        }
        return f;
    }
}
