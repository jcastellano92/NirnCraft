package com.nirncraft.sailing.client;

import com.nirncraft.sailing.ShipDeck;
import com.nirncraft.sailing.ShipType;
import com.nirncraft.sailing.client.ShipMesh.Mat;

import java.util.EnumMap;
import java.util.Map;

/**
 * The block-built models of the ships, generated in code from hull profiles: clinker strakes slice by slice along
 * the keel (so the hull steps like a ship built of blocks), floorboards, benches and fittings. Tiles refer to the
 * sailing atlas painted by {@code tools/textures/sailing.py}.
 *
 * <ul>
 *     <li><b>Rowboat</b>: three strakes with a blue sheer strake, thwarts, two oars.</li>
 *     <li><b>Longship</b>: tarred clinker hull with a red sheer strake, a row of painted round shields along each side,
 *     a carved dragon prow and a curled stern, sea-chest benches, four oars a side, a steering oar to starboard, a
 *     square red-and-white sail on a braced yard, stays and shrouds.</li>
 *     <li><b>Cog</b>: a deep, full Imperial hull with a red and gilt wale, raised fore- and aftcastles with
 *     crenellated bulwarks, a stern rudder, bowsprit, crow's nest, a big cream sail with the Imperial dragon, cargo
 *     on deck and a stern lantern.</li>
 * </ul>
 */
public final class ShipModels {
    // atlas tiles (see tools/textures/sailing.py)
    public static final Mat PLANK_DARK = Mat.of(0), PLANK = Mat.of(1), DECK = Mat.of(2), PLANK_RED = Mat.of(3),
            PLANK_BLUE = Mat.of(4), PLANK_WHITE = Mat.of(5), GOLD = Mat.of(6), IRON = Mat.of(7), ROPE = Mat.of(8),
            MAST = Mat.of(9), SHIELD_A = Mat.of(10).stretched(), SHIELD_B = Mat.of(11).stretched(),
            SHIELD_C = Mat.of(12).stretched(), DRAGON = Mat.of(13), EYE = Mat.of(14).stretched(), CANVAS = Mat.of(15),
            CRATE = Mat.of(16).stretched(), BARREL = Mat.of(17), LANTERN = Mat.of(18).stretched(), PLANK_BLACK = Mat.of(19),
            BLADE = Mat.of(20), CLOTH_RED = Mat.of(21), YARD = Mat.of(22);

    /** Sail texture regions in the atlas (pixels): x, y, w, h. */
    public static final int[] SAIL_NORD = {0, 192, 64, 64}, SAIL_IMPERIAL = {64, 192, 64, 64};

    /** Everything the renderer needs for one ship type. */
    public static final class Model {
        public final ShipMesh hull = new ShipMesh();
        public final ShipMesh oar = new ShipMesh();
        public final ShipMesh rudder = new ShipMesh();
        public final ShipMesh lantern = new ShipMesh();
        /** Oarlocks: x, y, z, side (+1 port, -1 starboard). */
        public float[][] oars = new float[0][];
        public float oarLength;
        /** Rudder hinge: x, y, z; and whether it is a side steering oar. */
        public float[] rudderPivot = {0, 0, 0};
        /** Mast: z, step y, top y; yard y, half span; sail bottom y; sail texture; pennant at the top. */
        public float mastZ, mastBase, mastTop, yardY, yardHalf, sailBottom;
        public int[] sailTex;
    }

    private static final Map<ShipType, Model> MODELS = new EnumMap<>(ShipType.class);

    private ShipModels() {}

    public static Model get(ShipType type) {
        return MODELS.computeIfAbsent(type, t -> switch (t) {
            case ROWBOAT -> rowboat();
            case LONGSHIP -> longship();
            case COG -> cog();
        });
    }

    /** Drops cached models (resource reload). */
    public static void reset() {
        MODELS.clear();
    }

    // ------------------------------------------------------------------ hull builder

    interface Profile {
        float f(float z);
    }

    /**
     * Clinker hull: per slice along the keel, a floor and {@code strakes.length} overlapping strakes per side, each a
     * little wider than the one below (flare). Returns nothing; boxes go into {@code m}.
     */
    private static void hull(ShipMesh m, float halfLen, float slice, Profile beam, Profile floor, Profile top, Mat[] strakes,
                             float thick, Mat floorMat) {
        int n = strakes.length;
        for (float z = -halfLen; z < halfLen - 1e-3f; z += slice) {
            float z1 = Math.min(halfLen, z + slice);
            float zc = (z + z1) / 2f;
            float w = beam.f(zc);
            if (w < 0.1f) continue;
            float fy = floor.f(zc), ty = top.f(zc);
            float w0 = w * flare(1, n);
            m.box(-w0 + thick * 0.5f, fy, z, w0 - thick * 0.5f, fy + 0.14f, z1, floorMat);
            for (int k = 0; k < n; k++) {
                float y0 = fy + (ty - fy) * k / n;
                float y1 = fy + (ty - fy) * (k + 1) / n + 0.05f;
                float wk = w * flare(k + 1, n);
                m.box(wk - thick, y0, z, wk, y1, z1, strakes[k]);
                m.box(-wk, y0, z, -wk + thick, y1, z1, strakes[k]);
            }
            // gunwale cap
            float wt = w * flare(n, n);
            m.box(wt - thick - 0.05f, ty, z, wt + 0.03f, ty + 0.08f, z1, DECK);
            m.box(-wt - 0.03f, ty, z, -wt + thick + 0.05f, ty + 0.08f, z1, DECK);
        }
        // keel
        m.box(-0.09f, -0.06f, -halfLen + 0.1f, 0.09f, floor.f(0) + 0.1f, halfLen - 0.1f, PLANK_DARK);
    }

    private static float flare(int k, int n) {
        return 0.55f + 0.45f * (float) Math.pow(k / (float) n, 0.6);
    }

    /** Half width at height y of a slice (between floor and top), following the flare. */
    private static float widthAt(float w, float fy, float ty, float y, int n) {
        float t = Math.max(0, Math.min(1, (y - fy) / Math.max(0.01f, ty - fy)));
        return w * (0.55f + 0.45f * (float) Math.pow(Math.max(t, 1f / n), 0.6));
    }

    private static void oarMesh(ShipMesh m, float length, float blade) {
        m.box(-0.045f, -0.045f, 0, 0.045f, 0.045f, length - blade, MAST);
        m.box(-0.025f, -0.13f, length - blade, 0.025f, 0.13f, length, BLADE);
        m.box(-0.06f, -0.06f, -0.35f, 0.06f, 0.06f, 0, PLANK_DARK); // grip
    }

    // ------------------------------------------------------------------ rowboat

    private static Model rowboat() {
        Model md = new Model();
        ShipMesh m = md.hull;
        float hl = 2.3f;
        Profile beam = z -> 0.80f * (float) Math.pow(Math.max(0, 1 - Math.pow(Math.abs(z) / 2.38, 2)), 0.6);
        Profile floor = z -> 0.0f + 0.22f * (float) Math.pow(Math.abs(z) / hl, 2);
        Profile top = z -> 0.70f + 0.24f * (float) Math.pow(Math.abs(z) / hl, 2.5);
        hull(m, hl, 0.25f, beam, floor, top, new Mat[]{PLANK, PLANK, PLANK_BLUE}, 0.1f, DECK);
        // floorboards and thwarts
        for (float z = -1.8f; z < 1.8f; z += 0.3f) {
            float w = beam.f(z + 0.15f) * 0.62f;
            m.box(-w, 0.12f, z, w, 0.17f, z + 0.3f, DECK);
        }
        for (float zc : new float[]{-1.55f, -0.75f, 0.95f}) {
            float w = widthAt(beam.f(zc), floor.f(zc), top.f(zc), 0.42f, 3) - 0.08f;
            m.box(-w, 0.36f, zc - 0.17f, w, 0.44f, zc + 0.17f, DECK);
        }
        // stem and stern posts
        m.line(0, 0.1f, hl - 0.1f, 0, 1.05f, hl + 0.15f, 0.14f, PLANK_DARK);
        m.line(0, 0.1f, -hl + 0.1f, 0, 0.95f, -hl - 0.1f, 0.14f, PLANK_DARK);
        // oarlocks
        float ow = beam.f(-0.55f) * flare(3, 3);
        m.box(ow - 0.05f, 0.76f, -0.6f, ow + 0.05f, 0.9f, -0.5f, IRON);
        m.box(-ow - 0.05f, 0.76f, -0.6f, -ow + 0.05f, 0.9f, -0.5f, IRON);
        md.oars = new float[][]{{ow, 0.86f, -0.55f, 1}, {-ow, 0.86f, -0.55f, -1}};
        md.oarLength = 2.3f;
        oarMesh(md.oar, md.oarLength, 0.6f);
        md.rudderPivot = null;
        return md;
    }

    // ------------------------------------------------------------------ longship

    private static Model longship() {
        Model md = new Model();
        ShipMesh m = md.hull;
        float hl = 6.5f;
        Profile beam = z -> 1.55f * (float) Math.pow(Math.max(0, 1 - Math.pow(Math.abs(z) / 6.62, 2.2)), 0.75);
        Profile floor = z -> 0.05f + 0.55f * (float) Math.pow(Math.abs(z) / hl, 3);
        Profile top = z -> 1.2f + 0.9f * (float) Math.pow(Math.abs(z) / hl, 3.5);
        hull(m, hl, 0.5f, beam, floor, top, new Mat[]{PLANK_DARK, PLANK, PLANK_DARK, PLANK_RED}, 0.12f, PLANK);
        // floorboards
        for (float z = -5.5f; z < 5.5f; z += 0.5f) {
            float zc = z + 0.25f;
            float w = widthAt(beam.f(zc), floor.f(zc), top.f(zc), 0.45f, 4) - 0.1f;
            if (w > 0.1f) m.box(-w, 0.48f, z, w, 0.56f, z + 0.5f, DECK); // the walkable floor (ShipDeck)
        }
        // sea chests the rowers sit on
        for (float zc : new float[]{-3.4f, -2.0f, 3.5f, -0.6f}) {
            for (float x : new float[]{0.75f, -0.75f}) {
                m.box(x - 0.28f, 0.56f, zc - 0.22f, x + 0.28f, 0.82f, zc + 0.22f, CRATE);
            }
        }
        // shields along both sides
        Mat[] shields = {SHIELD_A, SHIELD_B, SHIELD_C};
        int si = 0;
        for (float zc = -4.2f; zc <= 4.25f; zc += 1.05f) {
            float w = beam.f(zc) * flare(4, 4);
            float cy = top.f(zc) - 0.18f;
            m.box(w + 0.02f, cy - 0.36f, zc - 0.36f, w + 0.1f, cy + 0.36f, zc + 0.36f, shields[si % 3]);
            m.box(-w - 0.1f, cy - 0.36f, zc - 0.36f, -w - 0.02f, cy + 0.36f, zc + 0.36f, shields[(si + 1) % 3]);
            m.box(w + 0.1f, cy - 0.06f, zc - 0.06f, w + 0.16f, cy + 0.06f, zc + 0.06f, IRON); // boss
            m.box(-w - 0.16f, cy - 0.06f, zc - 0.06f, -w - 0.1f, cy + 0.06f, zc + 0.06f, IRON);
            si++;
        }
        // dragon prow: the stem rises and curves forward into a neck and a carved head
        float[][] neck = {{6.35f, 0.3f}, {6.65f, 1.2f}, {6.85f, 2.1f}, {6.95f, 2.8f}, {7.05f, 3.35f}, {7.3f, 3.75f}};
        for (int i = 0; i < neck.length - 1; i++) {
            float th = 0.42f - i * 0.03f;
            m.line(0, neck[i][1], neck[i][0], 0, neck[i + 1][1], neck[i + 1][0], th, i == 0 ? PLANK_DARK : DRAGON);
        }
        m.push().translate(0, 3.78f, 7.3f).rotate(12, 0, 0);
        m.box(-0.2f, -0.18f, -0.1f, 0.2f, 0.2f, 0.42f, DRAGON);      // skull
        m.box(-0.14f, -0.12f, 0.42f, 0.14f, 0.12f, 0.95f, DRAGON);   // snout
        m.box(-0.12f, -0.3f, 0.3f, 0.12f, -0.14f, 0.85f, DRAGON);    // jaw
        m.box(-0.13f, -0.16f, 0.88f, 0.13f, -0.1f, 0.98f, GOLD);     // teeth
        m.box(0.2f, 0.0f, 0.15f, 0.24f, 0.12f, 0.3f, EYE);
        m.box(-0.24f, 0.0f, 0.15f, -0.2f, 0.12f, 0.3f, EYE);
        m.line(0.12f, 0.15f, 0.0f, 0.22f, 0.55f, -0.45f, 0.07f, GOLD); // horns
        m.line(-0.12f, 0.15f, 0.0f, -0.22f, 0.55f, -0.45f, 0.07f, GOLD);
        m.line(0, 0.18f, -0.05f, 0, 0.3f, -0.6f, 0.05f, PLANK_RED);    // crest
        m.pop();
        // stern post curls up and back over the steersman
        float[][] tail = {{-6.35f, 0.3f}, {-6.6f, 1.2f}, {-6.85f, 2.1f}, {-6.95f, 2.75f}, {-6.85f, 3.2f}, {-6.6f, 3.4f}, {-6.4f, 3.3f}};
        for (int i = 0; i < tail.length - 1; i++) {
            m.line(0, tail[i][1], tail[i][0], 0, tail[i + 1][1], tail[i + 1][0], 0.36f - i * 0.03f, i == 0 ? PLANK_DARK : DRAGON);
        }
        // mast, mast fish, stays and shrouds
        md.mastZ = 0.2f;
        md.mastBase = 0.4f;
        md.mastTop = 8.2f;
        md.yardY = 7.5f;
        md.yardHalf = 2.9f;
        md.sailBottom = 3.0f;
        md.sailTex = SAIL_NORD;
        m.box(-0.16f, md.mastBase, md.mastZ - 0.16f, 0.16f, md.mastTop, md.mastZ + 0.16f, MAST);
        m.box(-0.3f, 0.56f, md.mastZ - 0.9f, 0.3f, 0.7f, md.mastZ + 0.9f, PLANK_DARK);
        m.line(0, md.mastTop - 0.2f, md.mastZ, 0, 3.0f, 6.9f, 0.05f, ROPE);
        m.line(0, md.mastTop - 0.2f, md.mastZ, 0, 2.7f, -6.8f, 0.05f, ROPE);
        for (float s : new float[]{1, -1}) {
            for (float zc : new float[]{-0.9f, -1.6f}) {
                float w = beam.f(zc) * flare(4, 4);
                m.line(0, md.mastTop - 0.6f, md.mastZ, s * w, top.f(zc) + 0.05f, zc, 0.04f, ROPE);
            }
        }
        // oars: four a side through the oar holes
        float[] oarZ = {-3.6f, -2.2f, 2.0f, 3.4f};
        md.oars = new float[oarZ.length * 2][];
        for (int i = 0; i < oarZ.length; i++) {
            float w = beam.f(oarZ[i]) * flare(4, 4);
            float y = top.f(oarZ[i]) - 0.32f;
            md.oars[i * 2] = new float[]{w, y, oarZ[i], 1};
            md.oars[i * 2 + 1] = new float[]{-w, y, oarZ[i], -1};
        }
        md.oarLength = 4.2f;
        oarMesh(md.oar, md.oarLength, 1.0f);
        // steering oar on the starboard quarter
        float rw = beam.f(-5.3f) * flare(4, 4) + 0.12f;
        md.rudderPivot = new float[]{-rw, top.f(-5.3f), -5.3f, 1};
        md.rudder.line(0, 0.4f, 0, 0, -1.5f, -0.35f, 0.14f, MAST);
        md.rudder.box(-0.05f, -1.75f, -0.85f, 0.05f, -0.75f, -0.15f, PLANK_DARK);
        md.rudder.box(-0.05f, 0.25f, 0, 0.05f, 0.35f, 0.9f, MAST); // tiller
        m.box(-rw - 0.05f, top.f(-5.3f) - 0.2f, -5.45f, -rw + 0.1f, top.f(-5.3f) + 0.05f, -5.15f, IRON);
        return md;
    }

    // ------------------------------------------------------------------ cog

    private static Model cog() {
        Model md = new Model();
        ShipMesh m = md.hull;
        float hl = 6.0f;
        Profile beam = z -> 1.85f * (float) Math.pow(Math.max(0, 1 - Math.pow(Math.abs(z) / 6.15, 4)), 0.5);
        Profile floor = z -> 0.0f + 0.35f * (float) Math.pow(Math.abs(z) / hl, 3);
        Profile top = z -> 2.3f + 0.35f * (float) Math.pow(Math.abs(z) / hl, 2);
        hull(m, hl, 0.5f, beam, floor, top, new Mat[]{PLANK, PLANK_DARK, PLANK, PLANK, PLANK_DARK, PLANK_RED}, 0.14f, PLANK);
        // gilt wale along the sheer strake
        for (float z = -5.5f; z < 5.5f; z += 0.5f) {
            float zc = z + 0.25f;
            float w = beam.f(zc) * flare(6, 6);
            float y = top.f(zc) - 0.45f;
            m.box(w - 0.02f, y, z, w + 0.06f, y + 0.12f, z + 0.5f, GOLD);
            m.box(-w - 0.06f, y, z, -w + 0.02f, y + 0.12f, z + 0.5f, GOLD);
        }
        // main deck
        for (float z = -5.8f; z < 5.8f; z += 0.5f) {
            float zc = z + 0.25f;
            float w = widthAt(beam.f(zc), floor.f(zc), top.f(zc), 1.45f, 6) - 0.12f;
            if (w > 0.1f) m.box(-w, 1.36f, z, w, 1.46f, z + 0.5f, DECK);
        }
        // castles: raised decks with crenellated bulwarks
        castle(m, beam, top, -6.0f, -3.4f, true, ShipDeck.STAIR_X0, ShipDeck.STAIR_X1);
        castle(m, beam, top, 3.6f, 6.0f, false, -ShipDeck.STAIR_X1, -ShipDeck.STAIR_X0);
        // stairs up to the castles: the aftcastle's on the port side, the forecastle's to starboard
        stairs(m, ShipDeck.STAIR_X0, ShipDeck.STAIR_X1, -2.4f, -1);
        stairs(m, -ShipDeck.STAIR_X1, -ShipDeck.STAIR_X0, 2.6f, 1);
        // cabin door under the aftcastle and a stern lantern
        m.box(-0.35f, 1.46f, -3.34f, 0.35f, 2.75f, -3.29f, PLANK_DARK);
        m.box(0.2f, 2.0f, -3.29f, 0.28f, 2.08f, -3.25f, GOLD);
        md.lantern.box(-0.16f, 0, -0.16f, 0.16f, 0.4f, 0.16f, LANTERN);
        md.lantern.box(-0.2f, 0.4f, -0.2f, 0.2f, 0.48f, 0.2f, IRON);
        m.line(0, 3.8f, -6.0f, 0, 4.25f, -6.35f, 0.08f, IRON);
        // cargo on deck
        m.box(-0.55f, 1.46f, 0.7f, 0.55f, 1.56f, 1.8f, CRATE);
        m.box(0.6f, 1.46f, -2.1f, 1.2f, 2.06f, -1.5f, CRATE);
        m.box(-1.25f, 1.46f, 1.3f, -0.75f, 2.1f, 1.8f, BARREL);
        m.box(-1.25f, 1.46f, -1.9f, -0.75f, 2.1f, -1.4f, BARREL);
        // mast, crow's nest, bowsprit, rigging
        md.mastZ = 0.3f;
        md.mastBase = 1.4f;
        md.mastTop = 11.0f;
        md.yardY = 9.9f;
        md.yardHalf = 3.6f;
        md.sailBottom = 3.7f;
        md.sailTex = SAIL_IMPERIAL;
        m.box(-0.19f, md.mastBase, md.mastZ - 0.19f, 0.19f, md.mastTop, md.mastZ + 0.19f, MAST);
        // the crow's nest, big enough to stand in (ShipDeck), and the rungs up the mast to it
        float nest = ShipDeck.COG_NEST, nh = ShipDeck.COG_NEST_HALF, ni = nh - 0.1f;
        m.box(-nh, nest - 0.1f, md.mastZ - nh, nh, nest, md.mastZ + nh, DECK);
        m.box(-nh, nest, md.mastZ - nh, nh, nest + 0.5f, md.mastZ - ni, PLANK_RED);
        m.box(-nh, nest, md.mastZ + ni, nh, nest + 0.5f, md.mastZ + nh, PLANK_RED);
        m.box(-nh, nest, md.mastZ - ni, -ni, nest + 0.5f, md.mastZ + ni, PLANK_RED);
        m.box(ni, nest, md.mastZ - ni, nh, nest + 0.5f, md.mastZ + ni, PLANK_RED);
        m.box(-nh - 0.02f, nest + 0.42f, md.mastZ - nh - 0.02f, nh + 0.02f, nest + 0.5f, md.mastZ - ni, GOLD);
        for (float y = md.mastBase + 0.45f; y < nest - 0.2f; y += 0.42f) {
            m.box(-0.16f, y, md.mastZ - 0.27f, 0.16f, y + 0.06f, md.mastZ - 0.19f, PLANK_DARK);
        }
        m.line(0, 3.1f, 5.4f, 0, 4.2f, 8.1f, 0.24f, MAST);
        m.line(0, md.mastTop - 0.3f, md.mastZ, 0, 4.15f, 8.0f, 0.05f, ROPE);
        m.line(0, md.mastTop - 0.3f, md.mastZ, 0, 3.8f, -5.8f, 0.05f, ROPE);
        for (float s : new float[]{1, -1}) {
            for (float zc : new float[]{-0.6f, -1.3f, -2.0f}) {
                float w = beam.f(zc) * flare(6, 6) + 0.05f;
                m.line(0, nest - 0.15f, md.mastZ, s * w, top.f(zc) + 0.05f, zc, 0.045f, ROPE);
            }
            m.line(0, md.mastTop - 1.2f, md.mastZ, s * beam.f(4.4f) * 0.9f, 3.9f, 4.4f, 0.04f, ROPE);
        }
        // sweeps: two a side
        float[] oarZ = {-1.1f, 1.6f};
        md.oars = new float[oarZ.length * 2][];
        for (int i = 0; i < oarZ.length; i++) {
            float w = beam.f(oarZ[i]) * flare(6, 6);
            md.oars[i * 2] = new float[]{w, top.f(oarZ[i]) - 0.2f, oarZ[i], 1};
            md.oars[i * 2 + 1] = new float[]{-w, top.f(oarZ[i]) - 0.2f, oarZ[i], -1};
        }
        md.oarLength = 4.8f;
        oarMesh(md.oar, md.oarLength, 1.1f);
        // stern rudder on the sternpost
        md.rudderPivot = new float[]{0, 0.2f, -6.05f, 0};
        md.rudder.box(-0.07f, -0.45f, -1.0f, 0.07f, 2.35f, 0, PLANK_DARK);
        md.rudder.box(-0.09f, 0.4f, -0.12f, 0.09f, 0.55f, 0.05f, IRON);
        md.rudder.box(-0.09f, 1.5f, -0.12f, 0.09f, 1.65f, 0.05f, IRON);
        m.line(0, 0.1f, -5.9f, 0, 3.0f, -6.1f, 0.22f, PLANK_DARK); // sternpost
        m.line(0, 0.1f, 5.9f, 0, 3.4f, 6.2f, 0.22f, PLANK_DARK);   // stem
        return md;
    }

    /** A castle deck between z0 and z1 with bulwarks and crenels; {@code aft} gets a front wall with a rail. */
    /** A flight of two steps from the main deck up to a castle; {@code dir} -1 climbs aft, +1 forward. */
    private static void stairs(ShipMesh m, float x0, float x1, float zStart, int dir) {
        float[][] steps = {{1.96f}, {2.46f}};
        for (int i = 0; i < steps.length; i++) {
            float za = zStart + dir * 0.5f * i, zb = za + dir * 0.5f;
            float lo = Math.min(za, zb), hi = Math.max(za, zb);
            m.box(x0, ShipDeck.COG_DECK, lo, x1, steps[i][0] - 0.08f, hi, PLANK_DARK);
            m.box(x0, steps[i][0] - 0.08f, lo, x1, steps[i][0], hi, DECK);
        }
    }

    private static void castle(ShipMesh m, Profile beam, Profile top, float z0, float z1, boolean aft, float gap0, float gap1) {
        float deck = 2.95f;
        for (float z = z0; z < z1 - 1e-3f; z += 0.5f) {
            float zc = z + 0.25f;
            float w = beam.f(zc) * flare(6, 6);
            if (w < 0.15f) continue;
            m.box(-w + 0.12f, deck - 0.1f, z, w - 0.12f, deck, z + 0.5f, DECK);
            float ty = top.f(zc);
            m.box(w - 0.14f, ty, z, w, deck + 0.75f, z + 0.5f, PLANK_WHITE);
            m.box(-w, ty, z, -w + 0.14f, deck + 0.75f, z + 0.5f, PLANK_WHITE);
            if (((int) Math.floor(z * 2)) % 2 == 0) {
                m.box(w - 0.16f, deck + 0.75f, z, w + 0.02f, deck + 1.0f, z + 0.25f, PLANK_RED);
                m.box(-w - 0.02f, deck + 0.75f, z, -w + 0.16f, deck + 1.0f, z + 0.25f, PLANK_RED);
            }
            m.box(w - 0.18f, deck + 0.7f, z, w + 0.04f, deck + 0.78f, z + 0.5f, GOLD);
            m.box(-w - 0.04f, deck + 0.7f, z, -w + 0.18f, deck + 0.78f, z + 0.5f, GOLD);
        }
        float edge = aft ? z1 : z0;
        float w = beam.f(edge) * flare(6, 6);
        // the castle's inner wall (down to the main deck) and its rail
        m.box(-w + 0.12f, 1.46f, edge - 0.06f, w - 0.12f, deck, edge + 0.06f, PLANK_WHITE);
        for (float x = -w + 0.3f; x < w - 0.2f; x += 0.45f) {
            if (x + 0.08f > gap0 && x < gap1) continue; // the head of the stair
            m.box(x, deck, edge - 0.04f, x + 0.08f, deck + 0.6f, edge + 0.04f, MAST);
        }
        m.box(-w + 0.12f, deck + 0.6f, edge - 0.06f, gap0, deck + 0.7f, edge + 0.06f, GOLD);
        m.box(gap1, deck + 0.6f, edge - 0.06f, w - 0.12f, deck + 0.7f, edge + 0.06f, GOLD);
        m.box(gap0 - 0.08f, deck, edge - 0.06f, gap0, deck + 0.7f, edge + 0.06f, MAST);
        m.box(gap1, deck, edge - 0.06f, gap1 + 0.08f, deck + 0.7f, edge + 0.06f, MAST);
        // the far end closes the castle (transom or bow bulkhead)
        float far = aft ? z0 : z1;
        float fw = beam.f(far + (aft ? 0.25f : -0.25f)) * flare(6, 6);
        if (fw > 0.15f) m.box(-fw, top.f(far), far - 0.07f, fw, deck + 0.75f, far + 0.07f, PLANK_WHITE);
    }
}
