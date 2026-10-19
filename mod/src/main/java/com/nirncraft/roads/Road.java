package com.nirncraft.roads;

import net.minecraft.nbt.CompoundTag;

/**
 * A planned road between settlements {@link #a} and {@link #b}: a polyline of block columns roughly 2 blocks apart,
 * from the center of {@code a} to the center of {@code b}. Heights are not stored; paving reads the real surface.
 */
public final class Road {
    /** The planner expects water at this point (bridge). */
    public static final byte WATER = 1;
    /** Part of a water crossing longer than the maximum bridge length: nothing is built here. */
    public static final byte LONG_WATER = 2;

    /** Road classes, widest first: highways between capitals and cities, roads to towns and villages, lanes to farms,
     *  and trails to dungeons and landmarks. */
    public static final byte HIGHWAY = 0;
    public static final byte ROAD = 1;
    public static final byte LANE = 2;
    public static final byte TRAIL = 3;

    public final int id;
    public final int a;
    public final int b;
    public final int[] xs;
    public final int[] zs;
    public final byte[] flags;
    /** {@link #HIGHWAY}, {@link #ROAD}, {@link #LANE} or {@link #TRAIL}. */
    public final byte kind;
    /** Named historic road id ({@code gold_road}; lang key {@code road.nirncraft.<id>}), or empty. */
    public final String name;
    /** The polyline was taken from the baked road plan (painted at generation; see {@code PlanRoute}). */
    public boolean plan;
    /** Cumulative arc length (blocks) from point 0, computed on load. */
    final float[] cum;

    public Road(int id, int a, int b, int[] xs, int[] zs, byte[] flags) {
        this(id, a, b, xs, zs, flags, ROAD, "");
    }

    public Road(int id, int a, int b, int[] xs, int[] zs, byte[] flags, byte kind, String name) {
        this.id = id;
        this.kind = kind;
        this.name = name == null ? "" : name;
        this.a = a;
        this.b = b;
        this.xs = xs;
        this.zs = zs;
        this.flags = flags.length == xs.length ? flags : new byte[xs.length];
        this.cum = new float[xs.length];
        for (int i = 1; i < xs.length; i++) {
            double dx = xs[i] - xs[i - 1];
            double dz = zs[i] - zs[i - 1];
            cum[i] = cum[i - 1] + (float) Math.sqrt(dx * dx + dz * dz);
        }
    }

    /**
     * A trail or a gate lane (its second end is virtual: a negative id): a path from a destination or a gate to the
     * nearest road, not a link between two settlements. No traffic, no milestones, its own signpost where it joins.
     */
    public boolean isTrail() {
        return kind == TRAIL || b < 0;
    }

    public int size() {
        return xs.length;
    }

    public float length() {
        return xs.length == 0 ? 0 : cum[xs.length - 1];
    }

    /** Distance along the road from point {@code i} to the end at settlement {@code toward}. */
    public float distanceTo(int i, int toward) {
        return toward == a ? cum[i] : length() - cum[i];
    }

    public int clamp(int i) {
        return Math.max(0, Math.min(xs.length - 1, i));
    }

    public boolean longWater(int i) {
        return (flags[i] & LONG_WATER) != 0;
    }

    public boolean water(int i) {
        return (flags[i] & WATER) != 0;
    }

    /** The settlement at the other end. */
    public int other(int settlement) {
        return settlement == a ? b : a;
    }

    /** Point index at settlement {@code s}'s end. */
    public int endAt(int s) {
        return s == a ? 0 : xs.length - 1;
    }

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putInt("a", a);
        t.putInt("b", b);
        t.putIntArray("x", xs);
        t.putIntArray("z", zs);
        t.putByteArray("f", flags);
        t.putByte("k", kind);
        if (!name.isEmpty()) t.putString("n", name);
        if (plan) t.putBoolean("pl", true);
        return t;
    }

    static Road load(CompoundTag t) {
        int[] xs = t.getIntArray("x").orElseGet(() -> new int[0]);
        int[] zs = t.getIntArray("z").orElseGet(() -> new int[0]);
        if (xs.length < 2 || xs.length != zs.length) return null;
        byte kind = t.contains("k") ? t.getByteOr("k", (byte) 0) : ROAD;
        Road road = new Road(t.getIntOr("id", 0), t.getIntOr("a", 0), t.getIntOr("b", 0), xs, zs, t.getByteArray("f").orElseGet(() -> new byte[0]), kind, t.getStringOr("n", ""));
        road.plan = t.getBooleanOr("pl", false);
        return road;
    }
}
