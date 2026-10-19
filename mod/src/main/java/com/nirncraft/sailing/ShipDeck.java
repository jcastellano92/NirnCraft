package com.nirncraft.sailing;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The walkable shape of each ship: the hull, decks, gunwales, castles and their stairs, cargo, the mast and the cog's
 * crow's nest as axis-aligned boxes in ship-local space ({@link ShipType}: x = port, y = up from the keel, z = bow).
 * {@link DeckPhysics} collides entities against them and carries whoever stands on them. The boxes follow the hull
 * profiles the client models are built from ({@code client.ShipModels}), slice by slice.
 *
 * <p>Floors sit a little above the waterline (the hull is open to the sea in the world: the water inside it is still
 * world water), so people on deck stay dry.</p>
 */
public final class ShipDeck {
    /** Blocks movement. */
    public static final int SOLID = 1;
    /** Can be stepped up onto (stairs, floorboards, low cargo) up to the walker's step height. */
    public static final int STEP = 2;
    /** Climbable: the mast, the boarding nets amidships. */
    public static final int CLIMB = 4;
    /** One-way platform: only holds from above (the crow's nest floor a climber passes through). */
    public static final int PASS_UP = 8;

    /** One box: local bounds and flags. */
    public record Box(float x0, float y0, float z0, float x1, float y1, float z1, int flags) {
        public boolean has(int flag) {
            return (flags & flag) != 0;
        }
    }

    /** A ship's deck: its boxes, local bounds, and the floor height under the main seats (for dismounting). */
    public static final class Deck {
        public final List<Box> boxes = new ArrayList<>();
        public float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        public float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        /** Main deck height (where people stand most of the time). */
        public float mainDeck;

        void add(float x0, float y0, float z0, float x1, float y1, float z1, int flags) {
            if (x1 <= x0 || y1 <= y0 || z1 <= z0) return;
            boxes.add(new Box(x0, y0, z0, x1, y1, z1, flags));
            minX = Math.min(minX, x0);
            minY = Math.min(minY, y0);
            minZ = Math.min(minZ, z0);
            maxX = Math.max(maxX, x1);
            maxY = Math.max(maxY, y1);
            maxZ = Math.max(maxZ, z1);
        }

        /** Whether a local point (x, z) is over the deck's footprint, with a margin. */
        public boolean over(double x, double z, double margin) {
            return x >= minX - margin && x <= maxX + margin && z >= minZ - margin && z <= maxZ + margin;
        }

        /** The highest walkable surface at local (x, z) at or below {@code y} (NaN when none). */
        public float floorAt(double x, double z, double y) {
            float best = Float.NaN;
            for (Box b : boxes) {
                if (b.has(CLIMB) && !b.has(STEP) && b.x1 - b.x0 < 0.5f) continue; // the mast is no floor
                if (x < b.x0 || x > b.x1 || z < b.z0 || z > b.z1 || b.y1 > y + 0.01) continue;
                if (Float.isNaN(best) || b.y1 > best) best = b.y1;
            }
            return best;
        }
    }

    private static final Map<ShipType, Deck> DECKS = new EnumMap<>(ShipType.class);

    private ShipDeck() {}

    public static synchronized Deck of(ShipType type) {
        return DECKS.computeIfAbsent(type, t -> switch (t) {
            case ROWBOAT -> rowboat();
            case LONGSHIP -> longship();
            case COG -> cog();
        });
    }

    // ------------------------------------------------------------------ hull profiles (as in client.ShipModels)

    public static float rowboatBeam(float z) {
        return 0.80f * (float) Math.pow(Math.max(0, 1 - Math.pow(Math.abs(z) / 2.38, 2)), 0.6);
    }

    public static float rowboatTop(float z) {
        return 0.70f + 0.24f * (float) Math.pow(Math.abs(z) / 2.3, 2.5);
    }

    public static float longshipBeam(float z) {
        return 1.55f * (float) Math.pow(Math.max(0, 1 - Math.pow(Math.abs(z) / 6.62, 2.2)), 0.75);
    }

    public static float longshipTop(float z) {
        return 1.2f + 0.9f * (float) Math.pow(Math.abs(z) / 6.5, 3.5);
    }

    public static float cogBeam(float z) {
        return 1.85f * (float) Math.pow(Math.max(0, 1 - Math.pow(Math.abs(z) / 6.15, 4)), 0.5);
    }

    public static float cogTop(float z) {
        return 2.3f + 0.35f * (float) Math.pow(Math.abs(z) / 6.0, 2);
    }

    /** Floor heights: walkable surfaces sit just above the waterline (draft) so people on board stay dry. */
    public static final float ROWBOAT_FLOOR = 0.36f, LONGSHIP_FLOOR = 0.56f, COG_DECK = 1.46f, COG_CASTLE = 2.95f;
    /** The cog's crow's nest: floor height and half size (also drawn this size by the model). */
    public static final float COG_NEST = 10.25f, COG_NEST_HALF = 0.95f, COG_MAST_Z = 0.3f;
    /** The cog's castle stairs: x range of the after (port side) and fore (starboard side) flights. */
    public static final float STAIR_X0 = 0.35f, STAIR_X1 = 1.25f;

    // ------------------------------------------------------------------ the decks

    private static Deck rowboat() {
        Deck d = new Deck();
        float hl = 2.3f, fl = ROWBOAT_FLOOR;
        for (float z = -hl; z < hl - 1e-3f; z += 0.25f) {
            float zc = z + 0.125f;
            float w = rowboatBeam(zc);
            if (w < 0.08f) continue;
            float top = rowboatTop(zc);
            d.add(-w, 0, z, w, fl, z + 0.25f, SOLID | STEP);
            d.add(w - 0.13f, fl, z - 0.03f, w + 0.03f, top, z + 0.28f, SOLID);
            d.add(-w - 0.03f, fl, z - 0.03f, -w + 0.13f, top, z + 0.28f, SOLID);
        }
        d.add(-0.45f, 0, hl - 0.35f, 0.45f, rowboatTop(hl) + 0.1f, hl + 0.15f, SOLID);
        d.add(-0.45f, 0, -hl - 0.15f, 0.45f, rowboatTop(hl) + 0.05f, -hl + 0.35f, SOLID);
        d.mainDeck = fl;
        return d;
    }

    private static Deck longship() {
        Deck d = new Deck();
        float fl = LONGSHIP_FLOOR;
        for (float z = -6.0f; z < 6.0f - 1e-3f; z += 0.5f) {
            float zc = z + 0.25f;
            float w = longshipBeam(zc);
            if (w < 0.1f) continue;
            float top = longshipTop(zc);
            int net = Math.abs(zc) < 3.0f ? CLIMB : 0; // boarding nets amidships: climb up from the water
            d.add(-w, 0, z, w, fl, z + 0.5f, SOLID | STEP | net);
            float in = Math.max(0.1f, w * 0.86f - 0.05f);
            d.add(in, fl, z - 0.04f, w + 0.12f, top, z + 0.54f, SOLID | net);
            d.add(-w - 0.12f, fl, z - 0.04f, -in, top, z + 0.54f, SOLID | net);
        }
        // the stem and stern posts close the ends
        d.add(-0.7f, 0, 5.9f, 0.7f, longshipTop(6.2f), 7.0f, SOLID);
        d.add(-0.7f, 0, -7.0f, 0.7f, longshipTop(6.2f), -5.9f, SOLID);
        // sea chests the rowers sit on, the mast fish and the mast
        for (float zc : new float[]{-3.4f, -2.0f, 3.5f, -0.6f}) {
            for (float x : new float[]{0.75f, -0.75f}) d.add(x - 0.28f, fl, zc - 0.22f, x + 0.28f, 0.82f, zc + 0.22f, SOLID | STEP);
        }
        d.add(-0.3f, fl, 0.2f - 0.9f, 0.3f, 0.7f, 0.2f + 0.9f, SOLID | STEP);
        d.add(-0.16f, 0.7f, 0.2f - 0.16f, 0.16f, 8.2f, 0.2f + 0.16f, SOLID);
        d.mainDeck = fl;
        return d;
    }

    private static Deck cog() {
        Deck d = new Deck();
        float md = COG_DECK, ct = COG_CASTLE;
        for (float z = -6.0f; z < 6.0f - 1e-3f; z += 0.5f) {
            float zc = z + 0.25f;
            float w = cogBeam(zc);
            if (w < 0.15f) continue;
            boolean castle = zc < -3.4f || zc > 3.6f;
            float floor = castle ? ct : md;
            int net = Math.abs(zc) < 2.5f ? CLIMB : 0;
            d.add(-w, 0, z, w, floor, z + 0.5f, SOLID | STEP | net);
            float top = castle ? ct + 0.75f : cogTop(zc);
            d.add(w - 0.16f, floor, z - 0.04f, w + 0.07f, top, z + 0.54f, SOLID | net);
            d.add(-w - 0.07f, floor, z - 0.04f, -w + 0.16f, top, z + 0.54f, SOLID | net);
        }
        // transom and bow bulkhead
        d.add(-1.05f, 0, -6.2f, 1.05f, ct + 0.75f, -5.95f, SOLID);
        d.add(-1.05f, 0, 5.95f, 1.05f, ct + 0.75f, 6.2f, SOLID);
        // the castles' edge rails, with a gap at the head of each stair
        float wa = cogBeam(-3.4f) - 0.12f, wf = cogBeam(3.6f) - 0.12f;
        d.add(-wa, ct, -3.46f, STAIR_X0, ct + 0.7f, -3.34f, SOLID);
        d.add(STAIR_X1, ct, -3.46f, wa, ct + 0.7f, -3.34f, SOLID);
        d.add(-wf, ct, 3.54f, -STAIR_X1, ct + 0.7f, 3.66f, SOLID);
        d.add(-STAIR_X0, ct, 3.54f, wf, ct + 0.7f, 3.66f, SOLID);
        // stairs up to the aftcastle (port side) and the forecastle (starboard side)
        d.add(STAIR_X0, md, -2.9f, STAIR_X1, 1.96f, -2.4f, SOLID | STEP);
        d.add(STAIR_X0, md, -3.4f, STAIR_X1, 2.46f, -2.9f, SOLID | STEP);
        d.add(-STAIR_X1, md, 2.6f, -STAIR_X0, 1.96f, 3.1f, SOLID | STEP);
        d.add(-STAIR_X1, md, 3.1f, -STAIR_X0, 2.46f, 3.6f, SOLID | STEP);
        // cargo on deck
        d.add(-0.55f, md, 0.7f, 0.55f, 1.56f, 1.8f, SOLID | STEP);
        d.add(0.6f, md, -2.1f, 1.2f, 2.06f, -1.5f, SOLID | STEP);
        d.add(-1.25f, md, 1.3f, -0.75f, 2.1f, 1.8f, SOLID | STEP);
        d.add(-1.25f, md, -1.9f, -0.75f, 2.1f, -1.4f, SOLID | STEP);
        // the mast (climbable) and the crow's nest: a one-way floor a climber passes up through, and its rails
        float mz = COG_MAST_Z, nh = COG_NEST_HALF, nest = COG_NEST;
        d.add(-0.19f, md, mz - 0.19f, 0.19f, 11.0f, mz + 0.19f, SOLID | CLIMB);
        d.add(-nh, nest - 0.1f, mz - nh, nh, nest, mz + nh, PASS_UP);
        d.add(-nh, nest, mz - nh, nh, nest + 0.5f, mz - nh + 0.1f, SOLID);
        d.add(-nh, nest, mz + nh - 0.1f, nh, nest + 0.5f, mz + nh, SOLID);
        d.add(-nh, nest, mz - nh, -nh + 0.1f, nest + 0.5f, mz + nh, SOLID);
        d.add(nh - 0.1f, nest, mz - nh, nh, nest + 0.5f, mz + nh, SOLID);
        d.mainDeck = md;
        return d;
    }
}
