package com.nirncraft.sailing;

import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * The ships of Nirn. Each type is its own entity type (fixed hit box) sharing {@link ShipEntity}; this enum holds the
 * handling, the seats and the hull layout. Ship-local coordinates: +Z is the bow, +X is port (left), y = 0 is the
 * keel, which floats {@link #draft} below the water surface.
 */
public enum ShipType {
    //        len    beam  draft  hull  oars   sail   upwind turn   drag    max  cargo horse height
    ROWBOAT(4.6f, 1.6f, 0.30f, 20f, 0.28f, 0.00f, 0f, 0.90f, 0.080f, 0.30f, 9, 0, 0.62f,
            new Vec3[]{new Vec3(0, 0.30, -0.75), new Vec3(0, 0.30, 0.95)},
            new Vec3[0],
            new float[0]),
    LONGSHIP(13.0f, 3.0f, 0.50f, 60f, 0.24f, 0.52f, 125f, 0.45f, 0.035f, 0.60f, 18, 1, 1.4f,
            new Vec3[]{
                    new Vec3(-0.45, 0.62, -5.0),
                    new Vec3(0.75, 0.62, -3.4), new Vec3(-0.75, 0.62, -3.4),
                    new Vec3(0.75, 0.62, -2.0), new Vec3(-0.75, 0.62, -2.0),
                    new Vec3(0.75, 0.62, 3.5), new Vec3(-0.75, 0.62, 3.5),
                    new Vec3(0, 0.62, 4.7)},
            new Vec3[]{new Vec3(0, 0.55, 1.9)},
            new float[]{-4.6f, -1.6f, 1.6f, 4.6f}),
    COG(12.0f, 3.4f, 0.90f, 90f, 0.08f, 0.56f, 140f, 0.34f, 0.025f, 0.58f, 54, 1, 1.5f,
            new Vec3[]{
                    new Vec3(-0.5, 3.05, -4.7),
                    new Vec3(1.25, 1.55, -2.4), new Vec3(-1.25, 1.55, -2.4),
                    new Vec3(1.1, 1.55, 2.3), new Vec3(-1.1, 1.55, 2.3),
                    new Vec3(0, 3.05, 4.5)},
            new Vec3[]{new Vec3(0, 1.55, -0.9)},
            new float[]{-4.3f, -1.5f, 1.5f, 4.3f});

    public static final ShipType[] VALUES = values();

    public final String id;
    /** Hull length (bow to stern) and beam in blocks. */
    public final float length, beam;
    /** How deep the keel sits under the water surface. */
    public final float draft;
    public final float maxHull;
    /** Cruising speed under oars alone (blocks/tick); 0 = no oars. */
    public final float oarSpeed;
    /** Speed with the sail full and a full-strength wind on the best point of sail; 0 = no sail. */
    public final float sailSpeed;
    /** The sail draws until the wind comes from this many degrees off dead astern (larger = sails closer to it). */
    public final float upwindLimit;
    /** Turning acceleration (degrees/tick²). */
    public final float turn;
    /** Fraction of its speed the hull loses per tick: low = heavy ship that keeps its way longer. */
    public final float drag;
    /** Top speed (blocks/tick). */
    public final float maxSpeed;
    public final int cargoSlots;
    public final int horseSeats;
    /** Height of the main hit box (kept at about the deck, so people walking the deck can be aimed at over it). */
    public final float height;
    /** Seats for people (index 0 is the helm) and for horses. */
    public final Vec3[] seats;
    public final Vec3[] horseSpots;
    /** Z offsets of the extra hull hit boxes, so the whole hull can be clicked and runs aground. */
    public final float[] parts;

    ShipType(float length, float beam, float draft, float maxHull, float oarSpeed, float sailSpeed, float upwindLimit,
             float turn, float drag, float maxSpeed, int cargoSlots, int horseSeats, float height, Vec3[] seats,
             Vec3[] horseSpots, float[] parts) {
        this.id = name().toLowerCase(Locale.ROOT);
        this.length = length;
        this.beam = beam;
        this.draft = draft;
        this.maxHull = maxHull;
        this.oarSpeed = oarSpeed;
        this.sailSpeed = sailSpeed;
        this.upwindLimit = upwindLimit;
        this.turn = turn;
        this.drag = drag;
        this.maxSpeed = maxSpeed;
        this.cargoSlots = cargoSlots;
        this.horseSeats = horseSeats;
        this.height = height;
        this.seats = seats;
        this.horseSpots = horseSpots;
        this.parts = parts;
    }

    public boolean hasSail() {
        return sailSpeed > 0;
    }

    public boolean hasOars() {
        return oarSpeed > 0;
    }

    /** Width of the square main hit box (and of the part boxes). */
    public float hitWidth() {
        return Math.min(beam, 3.2f);
    }

    /**
     * How well the sail draws with the wind coming {@code off} degrees away from dead astern (0 = wind from behind,
     * 180 = head-on): best on a broad reach, falling to nothing at the type's upwind limit.
     */
    public float sailEfficiency(float off) {
        float a = Math.abs(off);
        if (!hasSail() || a >= upwindLimit) return 0f;
        if (a <= 100f) return 0.85f + 0.15f * (float) Math.sin(Math.toRadians(a * 0.9));
        return (upwindLimit - a) / (upwindLimit - 100f);
    }

    public static ShipType byId(String id) {
        for (ShipType t : VALUES) if (t.id.equals(id)) return t;
        return ROWBOAT;
    }
}
