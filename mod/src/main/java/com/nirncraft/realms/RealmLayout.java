package com.nirncraft.realms;

import net.minecraft.util.Mth;

/**
 * The fixed geography of the realms, shared by terrain ({@code worldgen.RealmTerrain}), biomes, structures and travel.
 * Everything here is pure arithmetic (thread-safe, allocation-free).
 *
 * <ul>
 *     <li><b>Deadlands</b>: a Sigil Keep (arrival gate, Great Sigil Tower, Sigil Stone) stands in every 640-block grid
 *     cell. An Oblivion gate in Nirn leads to the keep of the cell picked from the gate's position
 *     ({@link #deadlandsCellX}), so every gate has its own island and its own tower, and party members who follow
 *     through the same gate arrive together.</li>
 *     <li><b>Sovngarde</b>: arrival in the misty vale (south), Alduin's arena at the dragon fight's origin (0, 0), the
 *     chasm crossed by the Whalebone Bridge, the Hall of Valor on its heights (north), a ring of mountains.</li>
 *     <li><b>Shivering Isles</b>: one great isle; New Sheoth on its central hill, Mania north, Dementia south, the
 *     Fringe and the Gates of Madness on the eastern shore where travellers arrive.</li>
 *     <li><b>Apocrypha</b>: chapters on a 512-block grid; the Summit (Miraak's sanctum) in cell (0, 0) and the
 *     chapter path along +x ({@link #APOCRYPHA_CHAPTERS} chapters, each leading to the next, the last to the Summit).</li>
 *     <li><b>Soul Cairn</b> and <b>Coldharbour</b>: an arrival point south of a central landmark.</li>
 * </ul>
 */
public final class RealmLayout {
    private RealmLayout() {}

    // ------------------------------------------------------------------ the Deadlands
    /** Blocks between Sigil Keeps (40 chunks). */
    public static final int DL_SPACING = 640;
    /** Size of the Nirn cell that maps an Oblivion gate to its keep. */
    public static final int DL_GATE_CELL = 256;
    /** The arrival gate's platform: this far south (+z) of the tower's centre. */
    public static final int DL_ARRIVAL_DZ = 62;
    /** Height of the Great Sigil Tower's base floor (the keep builds its own island up to it). */
    public static final int DL_KEEP_FLOOR = 72;
    /** Floor of the Sigil Stone chamber at the top of the tower. */
    public static final int DL_SIGIL_FLOOR = 168;

    public static int deadlandsCellX(int nirnX) {
        return Math.floorDiv(nirnX, DL_GATE_CELL);
    }

    public static int deadlandsCellZ(int nirnZ) {
        return Math.floorDiv(nirnZ, DL_GATE_CELL);
    }

    /** Block x of the Sigil Tower in keep cell {@code cx}. */
    public static int keepX(int cx) {
        return cx * DL_SPACING + 8;
    }

    public static int keepZ(int cz) {
        return cz * DL_SPACING + 8;
    }

    /** The keep cell nearest to a Deadlands position. */
    public static int keepCellOf(int deadlandsCoord) {
        return Math.floorDiv(deadlandsCoord + DL_SPACING / 2, DL_SPACING);
    }

    // ------------------------------------------------------------------ Sovngarde
    public static final int SG_ARENA_R = 52;
    public static final int SG_ARRIVAL_Z = 300;
    public static final int SG_HALL_Z = -275;
    public static final int SG_HALL_R = 70;
    /** The chasm's centre line wanders around this z. */
    public static final int SG_CHASM_Z = -125;
    public static final int SG_CHASM_HALF = 18;
    /** Ends of the Whalebone Bridge (it runs along x = 0). */
    public static final int SG_BRIDGE_SOUTH = -86;
    public static final int SG_BRIDGE_NORTH = -166;
    /** The mountain ring around the valley (radius band). */
    public static final int SG_RING_IN = 900;
    public static final int SG_RING_OUT = 1500;

    /** Centre line of the chasm at x. */
    public static double sovngardeChasmZ(double x) {
        return SG_CHASM_Z + 10 * Math.sin(x / 83.0) + 4 * Math.sin(x / 31.0 + 1.3);
    }

    // ------------------------------------------------------------------ the Shivering Isles
    public static final int SI_RADIUS = 1000;
    public static final int SI_SHEOTH_R = 130;
    public static final int SI_FRINGE_X = 760;
    public static final int SI_FRINGE_R = 110;
    /** The Gates of Madness stand west of the Fringe's arrival, facing the isle. */
    public static final int SI_GATES_X = 670;

    // ------------------------------------------------------------------ Apocrypha
    public static final int AP_SPACING = 512;
    public static final int APOCRYPHA_CHAPTERS = 5;
    /** Top of every chapter's arrival platform (the ink sea is at y 52; the floor around a chapter at y 30). */
    public static final int AP_PLATFORM_Y = 60;

    public static int chapterX(int cx) {
        return cx * AP_SPACING + 8;
    }

    public static int chapterZ(int cz) {
        return cz * AP_SPACING + 8;
    }

    // ------------------------------------------------------------------ the Soul Cairn
    public static final int SC_ARRIVAL_Z = 220;
    public static final int SC_KEEP_Z = -180;
    public static final int SC_BONEYARD_X = 480;
    public static final int SC_BONEYARD_Z = -60;

    // ------------------------------------------------------------------ Coldharbour
    public static final int CH_ARRIVAL_Z = 150;
    public static final int CH_CITY_Z = -200;
    public static final int CH_CITY_R = 110;

    // ------------------------------------------------------------------ helpers

    public static double dist(double x, double z, double cx, double cz) {
        double dx = x - cx, dz = z - cz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static double smoothstep(double e0, double e1, double x) {
        double t = Mth.clamp((x - e0) / (e1 - e0), 0.0, 1.0);
        return t * t * (3 - 2 * t);
    }
}
