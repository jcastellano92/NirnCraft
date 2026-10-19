package com.nirncraft.realms.worldgen;

import com.nirncraft.realms.Realm;
import com.nirncraft.realms.RealmLayout;
import net.minecraft.util.Mth;
import com.nirncraft.worldgen.WorldNoise;

import static com.nirncraft.realms.RealmLayout.dist;
import static com.nirncraft.realms.RealmLayout.smoothstep;

/**
 * The 2D terrain shape of every realm: surface height plus three helper channels the noise settings and the biome
 * source read. Pure functions of the position and three seeded noises (large, mid, detail), so thread-safe and
 * allocation-free.
 *
 * <ul>
 *     <li>{@code height}: surface height in blocks.</li>
 *     <li>{@code jag}: 0..1, how much 3D crag noise the column gets (overhangs, broken cliffs).</li>
 *     <li>{@code shape}: 0..1, the realm's main mask (land vs lava/ink/sea, the mountain ring...), the router's
 *     continentalness for the biome source.</li>
 *     <li>{@code feature}: 0..1, a second mask (spires, mesas, crags), the router's erosion.</li>
 * </ul>
 */
public final class RealmTerrain {
    private RealmTerrain() {}

    public enum Channel { HEIGHT, JAG, SHAPE, FEATURE }

    /** One column's values. */
    public static final class Column {
        public double height, jag, shape, feature;
    }

    private static final ThreadLocal<Column> SCRATCH = ThreadLocal.withInitial(Column::new);

    public static double sample(Realm realm, Channel channel, int x, int z, WorldNoise a,
                                WorldNoise b, WorldNoise c) {
        Column col = SCRATCH.get();
        column(realm, x, z, a, b, c, col);
        return switch (channel) {
            case HEIGHT -> col.height;
            case JAG -> col.jag;
            case SHAPE -> col.shape;
            case FEATURE -> col.feature;
        };
    }

    public static void column(Realm realm, int x, int z, WorldNoise a, WorldNoise b,
                              WorldNoise c, Column out) {
        switch (realm) {
            case DEADLANDS -> deadlands(x, z, a, b, c, out);
            case SOVNGARDE -> sovngarde(x, z, a, b, c, out);
            case SHIVERING_ISLES -> shiveringIsles(x, z, a, b, c, out);
            case APOCRYPHA -> apocrypha(x, z, a, b, c, out);
            case SOUL_CAIRN -> soulCairn(x, z, a, b, c, out);
            case COLDHARBOUR -> coldharbour(x, z, a, b, c, out);
        }
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }

    private static double terrace(double h, double step, double sharp) {
        double t = h / step;
        double fl = Math.floor(t);
        return (fl + smoothstep(sharp, 1.0, t - fl)) * step;
    }

    // ================================================================================================ Deadlands

    /** Jagged red-black islands rising from a lava sea (y 40), tall thin spires, a flat shelf around every keep. */
    private static void deadlands(int x, int z, WorldNoise a, WorldNoise b,
                                  WorldNoise c, Column out) {
        double la = a.getValue(x, 0, z);
        double mb = b.getValue(x, 0, z);
        double dc = c.getValue(x, 0, z);
        double island = smoothstep(-0.22, 0.20, la + 0.35 * mb);
        int kx = RealmLayout.keepCellOf(x), kz = RealmLayout.keepCellOf(z);
        double dk = dist(x, z, RealmLayout.keepX(kx), RealmLayout.keepZ(kz) + RealmLayout.DL_ARRIVAL_DZ * 0.45);
        island = Math.max(island, 1 - smoothstep(80, 170, dk));
        double ridge = 1 - Math.abs(b.getValue(x * 1.6, 40, z * 1.6));
        double h = 17 + island * (30 + 36 * ridge * ridge * ridge + 12 * la) + island * dc * 5;
        double s = c.getValue(x * 0.55 + 31, 77, z * 0.55 - 17);
        double spire = Math.max(0, s - 0.40) / 0.60;
        spire = spire * spire * island;
        h += spire * 80;
        // the keep's shelf
        double flat = 1 - smoothstep(48, 100, dk);
        h = lerp(flat, h, RealmLayout.DL_KEEP_FLOOR - 1 + dc * 1.5);
        out.height = Mth.clamp(h, 4, 240);
        out.jag = island * (0.55 + 0.45 * ridge) * (1 - flat);
        out.shape = island;
        out.feature = Mth.clamp(spire * 2 + (ridge - 0.7) * island, 0, 1);
    }

    // ================================================================================================ Sovngarde

    /** A wide golden valley under a ring of mountains; the arena, the chasm, the Hall's heights. No water table. */
    private static void sovngarde(int x, int z, WorldNoise a, WorldNoise b,
                                  WorldNoise c, Column out) {
        double la = a.getValue(x, 0, z);
        double mb = b.getValue(x, 0, z);
        double dc = c.getValue(x, 0, z);
        double h = 84 + la * 7 + mb * 3.5 + dc * 1.2;
        double d = Math.sqrt((double) x * x + (double) z * z);
        double ring = smoothstep(RealmLayout.SG_RING_IN, RealmLayout.SG_RING_IN + 350, d)
                * (1 - smoothstep(RealmLayout.SG_RING_OUT, RealmLayout.SG_RING_OUT + 600, d));
        double ridge = 1 - Math.abs(b.getValue(x * 0.9, 11, z * 0.9));
        h += ring * (48 + 85 * ridge * ridge + 25 * la);
        double outer = smoothstep(RealmLayout.SG_RING_OUT, RealmLayout.SG_RING_OUT + 600, d);
        h += outer * (16 + 10 * la);
        // the landmarks' grounds
        double arena = 1 - smoothstep(RealmLayout.SG_ARENA_R, RealmLayout.SG_ARENA_R + 30, d);
        h = lerp(arena, h, 85 + dc * 0.4);
        double arrival = 1 - smoothstep(20, 44, dist(x, z, 0, RealmLayout.SG_ARRIVAL_Z));
        h = lerp(arrival, h, 85);
        double ax = Math.abs(x);
        if (z > RealmLayout.SG_BRIDGE_NORTH - 40 && z < RealmLayout.SG_ARRIVAL_Z) {
            // the old road: arrival -> arena -> bridge, gently levelled
            double road = (1 - smoothstep(5, 20, ax)) * 0.75;
            h = lerp(road, h, 85.5 + dc * 0.6);
        }
        double hall = 1 - smoothstep(RealmLayout.SG_HALL_R, RealmLayout.SG_HALL_R + 36, dist(x, z, 0, RealmLayout.SG_HALL_Z));
        if (z < RealmLayout.SG_BRIDGE_NORTH + 10 && z > RealmLayout.SG_HALL_Z) {
            // the climb from the bridge's north end up to the Hall
            double t = smoothstep(RealmLayout.SG_BRIDGE_NORTH + 10, RealmLayout.SG_HALL_Z + RealmLayout.SG_HALL_R, z);
            double road = (1 - smoothstep(6, 22, ax)) * 0.85;
            h = lerp(road, h, 86 + 13 * t);
        }
        h = lerp(hall, h, 99 + dc * 0.4);
        // the chasm
        double fadeX = 1 - smoothstep(700, 900, ax);
        if (fadeX > 0) {
            double zc = RealmLayout.sovngardeChasmZ(x);
            double dz = Math.abs(z - zc);
            double w = RealmLayout.SG_CHASM_HALF + 4 * mb;
            double cut = (1 - smoothstep(w, w + 9, dz)) * fadeX;
            if (cut > 0) h = lerp(cut, h, 22 + 6 * dc + 4 * la);
            out.feature = cut;
        } else {
            out.feature = 0;
        }
        out.height = Mth.clamp(h, 6, 250);
        out.jag = Mth.clamp(ring * 0.9 + out.feature * 0.6, 0, 1);
        out.shape = Mth.clamp(ring + outer * 0.5, 0, 1);
    }

    // ================================================================================================ Shivering Isles

    /** One great isle in a calm sea: rolling, cliffed Mania in the north, low boggy Dementia in the south. */
    private static void shiveringIsles(int x, int z, WorldNoise a, WorldNoise b,
                                       WorldNoise c, Column out) {
        double la = a.getValue(x, 0, z);
        double mb = b.getValue(x, 0, z);
        double dc = c.getValue(x, 0, z);
        double d = Math.sqrt((double) x * x + (double) z * z);
        double r = RealmLayout.SI_RADIUS * (1 + 0.12 * a.getValue(x * 0.35, 5, z * 0.35));
        double land = 1 - smoothstep(r - 160, r + 40, d);
        double isles = smoothstep(0.36, 0.55, a.getValue(x * 0.8 + 3000, 0, z * 0.8)) * smoothstep(r, r + 220, d);
        double mask = Math.max(land, isles);
        double mania = smoothstep(80, -140, z);
        double maniaH = 72 + 10 * mb + 16 * Math.max(0, la) + 5 * dc;
        double cliff = smoothstep(0.25, 0.4, mb);
        maniaH = lerp(cliff * 0.8, maniaH, terrace(maniaH + 6, 9, 0.6));
        double dementiaH = 65 + 2.5 * mb + 1.2 * dc;
        if (dc < -0.3) dementiaH = lerp(smoothstep(-0.3, -0.45, dc), dementiaH, 61.5);
        double hl = mania * maniaH + (1 - mania) * dementiaH;
        double sheoth = 1 - smoothstep(RealmLayout.SI_SHEOTH_R, RealmLayout.SI_SHEOTH_R + 70, d);
        hl = lerp(sheoth, hl, 94 + dc * 0.6);
        double fringe = 1 - smoothstep(RealmLayout.SI_FRINGE_R, RealmLayout.SI_FRINGE_R + 50, dist(x, z, RealmLayout.SI_FRINGE_X, 0));
        hl = lerp(fringe, hl, 76 + dc * 0.6);
        double seaFloor = 44 + 6 * mb;
        double h = seaFloor + mask * (hl - seaFloor);
        out.height = Mth.clamp(h, 20, 200);
        out.jag = Mth.clamp(mania * cliff * mask * 0.7 * (1 - sheoth) * (1 - fringe), 0, 1);
        out.shape = mask;
        out.feature = mania;
    }

    // ================================================================================================ Apocrypha

    /** An ink sea (y 52) with towering, terraced stacks of pages; flat reading platforms at every chapter. */
    private static void apocrypha(int x, int z, WorldNoise a, WorldNoise b,
                                  WorldNoise c, Column out) {
        double mb = b.getValue(x, 0, z);
        double dc = c.getValue(x, 0, z);
        double floor = 26 + 5 * mb + 2 * dc;
        double r1 = 1 - Math.abs(a.getValue(x * 1.3, 0, z * 1.3));
        double r2 = 1 - Math.abs(b.getValue(x * 0.9 + 400, 7, z * 0.9));
        double t = smoothstep(0.72, 0.9, r1 + 0.08 * dc) * smoothstep(0.55, 0.85, r2);
        double top = 56 + 22 + 48 * (0.5 + 0.5 * b.getValue(x * 0.5, 9, z * 0.5));
        double h = lerp(smoothstep(0.0, 0.35, t), floor, top);
        if (h > 52) h = terrace(h, 3, 0.85);
        int cx = Math.floorDiv(x + RealmLayout.AP_SPACING / 2, RealmLayout.AP_SPACING);
        int cz = Math.floorDiv(z + RealmLayout.AP_SPACING / 2, RealmLayout.AP_SPACING);
        double dk = dist(x, z, RealmLayout.chapterX(cx), RealmLayout.chapterZ(cz));
        double plat = 1 - smoothstep(26, 50, dk);
        h = lerp(plat, h, 30);
        out.height = Mth.clamp(h, 8, 220);
        out.jag = 0;
        out.shape = Mth.clamp(t * 2, 0, 1);
        out.feature = Mth.clamp((r1 - 0.6) * 2.5, 0, 1);
    }

    // ================================================================================================ Soul Cairn

    /** Ashen violet wastes: soft dunes, terraced mesas, deep rifts. No fluids. */
    private static void soulCairn(int x, int z, WorldNoise a, WorldNoise b,
                                  WorldNoise c, Column out) {
        double la = a.getValue(x, 0, z);
        double mb = b.getValue(x, 0, z);
        double dc = c.getValue(x, 0, z);
        double h = 72 + 5 * la + 3 * mb + 1.2 * dc;
        double du = 1 - Math.abs(c.getValue(x * 0.5 + z * 0.2, 3, z * 0.9));
        h += 3 * du * du;
        double m = smoothstep(0.48, 0.58, b.getValue(x * 0.7 + 500, 0, z * 0.7));
        if (m > 0) h = lerp(m, h, terrace(h + 16 + 6 * la, 5, 0.7));
        double rift = Math.abs(a.getValue(x * 1.8, 33, z * 1.8 + 77));
        double cut = (1 - smoothstep(0.02, 0.065, rift)) * (1 - m);
        double keep = 1 - smoothstep(75, 115, dist(x, z, 0, RealmLayout.SC_KEEP_Z));
        double arrival = 1 - smoothstep(22, 46, dist(x, z, 0, RealmLayout.SC_ARRIVAL_Z));
        double boneyard = 1 - smoothstep(60, 95, dist(x, z, RealmLayout.SC_BONEYARD_X, RealmLayout.SC_BONEYARD_Z));
        double flat = Math.max(keep, Math.max(arrival, boneyard));
        cut *= 1 - flat;
        h = lerp(cut * 0.92, h, 36 + 4 * dc);
        h = lerp(keep, h, 74);
        h = lerp(arrival, h, 72);
        h = lerp(boneyard, h, 70 + dc);
        out.height = Mth.clamp(h, 10, 200);
        out.jag = Mth.clamp(cut * 0.5 + m * 0.2, 0, 1);
        out.shape = Mth.clamp(1 - cut, 0, 1);
        out.feature = m;
    }

    // ================================================================================================ Coldharbour

    /** Broken, burning highlands: crags, terraced mesas, lava lowlands (y 56); the Hollow City's ruined plain. */
    private static void coldharbour(int x, int z, WorldNoise a, WorldNoise b,
                                    WorldNoise c, Column out) {
        double la = a.getValue(x, 0, z);
        double mb = b.getValue(x, 0, z);
        double dc = c.getValue(x, 0, z);
        double h = 76 + 9 * la + 5 * mb + 2.2 * dc;
        double ridge = 1 - Math.abs(b.getValue(x * 1.4, 0, z * 1.4));
        h += 14 * ridge * ridge * ridge * ridge;
        double m = smoothstep(0.32, 0.45, la);
        if (m > 0) h = lerp(m, h, terrace(h + 22, 6, 0.75));
        double low = smoothstep(-0.42, -0.6, la);
        h = lerp(low, h, 50 + 2 * dc);
        double city = 1 - smoothstep(RealmLayout.CH_CITY_R, RealmLayout.CH_CITY_R + 50, dist(x, z, 0, RealmLayout.CH_CITY_Z));
        double arrival = 1 - smoothstep(20, 42, dist(x, z, 0, RealmLayout.CH_ARRIVAL_Z));
        h = lerp(city, h, 78 + dc * 0.8);
        h = lerp(arrival, h, 77);
        out.height = Mth.clamp(h, 10, 220);
        out.jag = Mth.clamp((0.35 + 0.4 * ridge) * (1 - city) * (1 - arrival) * (1 - low), 0, 1);
        out.shape = 1 - low;
        out.feature = Mth.clamp(m + city, 0, 1);
    }
}
