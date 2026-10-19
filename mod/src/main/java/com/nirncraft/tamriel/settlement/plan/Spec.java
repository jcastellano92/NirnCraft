package com.nirncraft.tamriel.settlement.plan;

import net.minecraft.core.Direction;

/**
 * One planned piece of a settlement, in world coordinates. Buildings are centred on (x, z) with their front facing
 * {@link #facing}; line works (streets, walls, bridges) run from (x, z) to (x2, z2). The plan fills in {@link #floor}
 * (the y of the floor block) from the terrain before the piece is created.
 */
public final class Spec {
    public static final int UNSET = Integer.MIN_VALUE;

    // flags
    /** The house for sale (its name is the house id). */
    public static final int FOR_SALE = 1;
    /** Two storeys. */
    public static final int UPPER = 2;
    /** A wealthy household (richer furniture, bigger rooms). */
    public static final int RICH = 4;
    /** Built over water on posts. */
    public static final int WATER = 8;
    /** The beardifier leaves the terrain alone (streets, docks, walls on slopes, bridges). */
    public static final int NO_BEARD = 16;
    /** {@link #floor} was set by the layout, keep it. */
    public static final int FIXED = 32;
    /** Walls: a walkway with crenellations; streets: the main street. */
    public static final int MAJOR = 64;
    /** A wooden palisade instead of a stone wall. */
    public static final int PALISADE = 128;
    /** Ruined (Winterhold, Helgen's old tower...). */
    public static final int RUINED = 256;
    /** Sign with the building's name over the door. */
    public static final int SIGN = 512;
    /** The seat of the settlement (Jarl's palace, chief's longhouse). */
    public static final int SEAT = 1024;
    /** Ground works (terraces, rock plateaus): other pieces may stand on them. */
    public static final int GROUND = 2048;
    /** A plot kept clear and level for a building another module puts up (player homes, {@link HousePlots}). */
    public static final int RESERVED = 4096;
    /**
     * Left out by the street plan (docs/WORLDGEN-PIPELINE.md P5): a prop with no anchors standing on a doorstep path,
     * a street or a planned road. It keeps its place (and index) in the plan but is never built.
     */
    public static final int OMIT = 8192;
    /** Built without its back door: the street plan found no way from it (it would open onto a wall or a terrace face). */
    public static final int NO_BACK_DOOR = 16384;
    /**
     * Burnt: the building's wooden blocks are charred, its windows and cloth gone, soot and ash on its floors
     * (com.nirncraft.fire.aftermath.Charring). With {@link #RUINED} it is gutted: holes in the roof and walls, rubble
     * and fires inside (variant bit 0 = still burning, bit 1 = a collapsed quarter); without it only scorched.
     */
    public static final int BURNT = 32768;

    public Archetype kind;
    /** Culture ordinal, or -1 for the plan's culture. */
    public int culture = -1;
    /** Kit style, or -1 for the plan's style. */
    public int style = -1;
    public int x, z;
    public int x2, z2;
    public int w, d, h;
    public Direction facing = Direction.NORTH;
    public int floor = UNSET;
    /** Floor at the line's end (bridges, walls) or {@link #UNSET} to sample. */
    public int floor2 = UNSET;
    /** Minimum floor height relative to the settlement base (terraces), or {@link #UNSET}. */
    public int minFloor = UNSET;
    public int flags;
    /** Archetype-specific parameter (landmark id, number of stalls, wall height...). */
    public int variant;
    /** Owner label / house id / lang key suffix of the building's name. */
    public String name = "";
    /** Extra blocks around the footprint the piece may paint (eaves, porches, yards). */
    public int margin = 1;
    /**
     * Building grammar ({@link com.nirncraft.tamriel.settlement.grammar}): the building's function
     * ({@link com.nirncraft.tamriel.settlement.grammar.Use} ordinal, -1 for the legacy archetype builders), its wealth
     * tier, the seed its blueprint is derived from, and the terrain spread under it at planning time.
     */
    public int use = -1;
    public int wealth = 1;
    public int gseed;
    public int slope;

    public Spec(Archetype kind) {
        this.kind = kind;
        this.w = kind.w;
        this.d = kind.d;
        this.h = kind.h;
    }

    public static Spec at(Archetype kind, int x, int z, Direction facing) {
        Spec s = new Spec(kind);
        s.x = x;
        s.z = z;
        s.facing = facing;
        return s;
    }

    public static Spec line(Archetype kind, int x, int z, int x2, int z2, int width) {
        Spec s = new Spec(kind);
        s.x = x;
        s.z = z;
        s.x2 = x2;
        s.z2 = z2;
        s.w = width;
        s.flags |= NO_BEARD;
        s.margin = 0;
        return s;
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    public Spec flag(int f) {
        flags |= f;
        return this;
    }

    public Spec size(int w, int d) {
        this.w = w;
        this.d = d;
        return this;
    }

    public Spec size(int w, int d, int h) {
        this.w = w;
        this.d = d;
        this.h = h;
        return this;
    }

    public Spec named(String name) {
        this.name = name;
        return this;
    }

    public Spec variant(int v) {
        this.variant = v;
        return this;
    }

    /** Builds this spec with the building grammar as {@code use} at wealth tier {@code wealth}. */
    public Spec use(com.nirncraft.tamriel.settlement.grammar.Use use, com.nirncraft.tamriel.settlement.grammar.Wealth wealth) {
        this.use = use.ordinal();
        this.wealth = wealth.ordinal();
        return this;
    }

    public boolean grammar() {
        return use >= 0;
    }

    public Spec style(int culture, int style) {
        this.culture = culture;
        this.style = style;
        return this;
    }

    public boolean isLine() {
        return kind == Archetype.STREET || kind == Archetype.WALL || kind == Archetype.BRIDGE || kind == Archetype.CANAL;
    }

    /** Half the footprint's extent along world x / z (for buildings, rotation aware). */
    public int halfX() {
        if (isLine()) return Math.abs(x2 - x) / 2 + w / 2 + 1;
        return (facing.getAxis() == Direction.Axis.Z ? w : d) / 2 + margin;
    }

    public int halfZ() {
        if (isLine()) return Math.abs(z2 - z) / 2 + w / 2 + 1;
        return (facing.getAxis() == Direction.Axis.Z ? d : w) / 2 + margin;
    }

    public int centerX() {
        return isLine() ? (x + x2) / 2 : x;
    }

    public int centerZ() {
        return isLine() ? (z + z2) / 2 : z;
    }

    /** True if the footprints (plus {@code gap}) of two specs overlap. */
    public boolean overlaps(Spec o, int gap) {
        return Math.abs(centerX() - o.centerX()) < halfX() + o.halfX() + gap
                && Math.abs(centerZ() - o.centerZ()) < halfZ() + o.halfZ() + gap;
    }
}
