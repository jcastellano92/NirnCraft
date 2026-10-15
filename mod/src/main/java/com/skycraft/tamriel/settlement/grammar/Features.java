package com.skycraft.tamriel.settlement.grammar;

import com.google.gson.JsonObject;
import com.skycraft.tamriel.settlement.Culture;
import com.skycraft.tamriel.settlement.plan.Archetype;
import com.skycraft.tamriel.settlement.plan.CityPlan;
import com.skycraft.tamriel.settlement.plan.Nets;
import com.skycraft.tamriel.settlement.plan.Plan;
import com.skycraft.tamriel.settlement.plan.Spec;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Civic features: the trees, fountains, ponds, pools, shrines, statues, memorials, bell towers, banners, markets,
 * gardens, orchards, aqueducts, waterwheels and harbour basins that make a settlement itself. City plans list their
 * signature features ({@code "features"}: {@code {"type": "great_tree", "at": "@gildergreen", "theme": "oak"}}); every
 * other settlement draws a seeded set from the culture's catalogue ({@link #auto}), sized by tier (hamlet 1-2, village
 * 2-4, town 4-8, city 8-15) and different from its neighbours'. Features are YARD pieces placed in free space (a spiral
 * search from their anchor), so they never overlap buildings and never change a settlement's gates or streets.
 *
 * <p>Feature types: {@code great_tree, tree, tree_row, fountain, well, pond, pool, basin, aqueduct, waterwheel, statue,
 * shrine, memorial, bell_tower, banners, lamps, garden, orchard, market, flowers, bench, gatehouse}.</p>
 */
final class Features {
    private Features() {}

    /** The feature types a catalogue entry can name. */
    static final String[] TYPES = {"great_tree", "tree", "tree_row", "fountain", "well", "pond", "pool", "basin", "aqueduct", "waterwheel",
            "statue", "shrine", "memorial", "bell_tower", "banners", "lamps", "garden", "orchard", "market", "flowers", "bench", "gatehouse"};

    // ------------------------------------------------------------------ authored features

    /** Places the plan's listed features; returns how many were placed. */
    static int apply(Zoning.Ctx x, CityPlan cp) {
        int placed = 0, wanted = 0;
        for (JsonObject o : cp.features) {
            String type = Nets.str(o, "type", "tree");
            int n = (int) Nets.num(o, "count", 1);
            wanted += n;
            double[] at = o.has("at") ? Nets.pt(cp, o.get("at")) : null;
            String where = Nets.str(o, "where", at != null ? "at" : "free");
            String theme = Nets.str(o, "theme", "");
            for (int i = 0; i < n; i++) {
                double[] a = anchor(x, where, at, Nets.str(o, "tag", ""), i, n);
                if (make(x, type, theme, a[0], a[1], (int) Nets.num(o, "reach", where.equals("at") ? 14 : 36), i, o)) placed++;
                else com.skycraft.Skycraft.LOGGER.info("City plan {}: no room for feature {} near ({}, {})", cp.id, type, (int) a[0], (int) a[1]);
            }
        }
        if (placed < wanted) com.skycraft.Skycraft.LOGGER.info("City plan {}: {} of {} features placed", cp.id, placed, wanted);
        return placed;
    }

    /** The anchor point of a feature: an explicit point, the square, a street, a gate, the edge of town or just anywhere. */
    private static double[] anchor(Zoning.Ctx x, String where, double[] at, String tag, int i, int n) {
        RandomSource r = x.r;
        switch (where) {
            case "at" -> {
                if (at != null) return new double[]{at[0] + (n > 1 ? (i - (n - 1) / 2.0) * 7 : 0), at[1]};
            }
            case "square" -> {
                int[] sq = x.sk.squares.isEmpty() ? new int[]{0, 0, 13} : x.sk.squares.get(0);
                double a = r.nextDouble() * Math.PI * 2, rad = sq[2] / 2.0 + 3 + r.nextInt(6);
                return new double[]{sq[0] + Math.sin(a) * rad, sq[1] + Math.cos(a) * rad};
            }
            case "street" -> {
                Plan.Chain pick = null;
                int seen = 0;
                for (Plan.Chain c : x.plan.chains) {
                    if (!tag.isEmpty() && !tag.equals(c.tag())) continue;
                    if (tag.isEmpty() && c.rank() > 1) continue;
                    if (r.nextInt(++seen) == 0) pick = c;
                }
                if (pick != null) {
                    double s = pick.length() * (0.15 + 0.7 * ((i + 0.5) / Math.max(1, n)));
                    double[] p = pick.at(s);
                    double[] p2 = pick.at(Math.min(pick.length(), s + 2.0));
                    double dx = p2[0] - p[0], dz = p2[1] - p[1], l = Math.hypot(dx, dz);
                    double side = (r.nextBoolean() ? 1 : -1) * (pick.width() / 2.0 + 3.0);
                    if (l > 1e-4) return new double[]{p[0] - dz / l * side, p[1] + dx / l * side};
                    return new double[]{p[0] + side, p[1]};
                }
            }
            case "gate" -> {
                if (!x.sk.gates.isEmpty()) {
                    int[] g = x.sk.gates.get(Math.min(x.sk.gates.size() - 1, i % Math.max(1, x.sk.gates.size())));
                    double len = Math.max(1, Math.hypot(g[0], g[1]));
                    return new double[]{g[0] - g[0] / len * 14, g[1] - g[1] / len * 14};
                }
            }
            case "edge" -> {
                double a = r.nextDouble() * Math.PI * 2, rad = x.R * (0.55 + r.nextDouble() * 0.3);
                return new double[]{Math.sin(a) * rad, Math.cos(a) * rad};
            }
            default -> {
            }
        }
        double a = r.nextDouble() * Math.PI * 2, rad = x.R * (0.1 + r.nextDouble() * 0.7);
        return new double[]{Math.sin(a) * rad, Math.cos(a) * rad};
    }

    // ------------------------------------------------------------------ placement

    /** Spiral search for a free spot of the footprint around (ax, az). */
    private static Spec spot(Zoning.Ctx x, Archetype arch, int variant, String name, int w, int d, int h, double ax, double az, int reach, boolean force) {
        Plan plan = x.plan;
        for (int rad = 0; rad <= reach; rad += 3) {
            int steps = rad == 0 ? 1 : Math.max(8, (int) (rad * 0.9));
            double rot = x.r.nextDouble() * 6.28;
            for (int k = 0; k < steps; k++) {
                double a = rot + k * Math.PI * 2 / steps;
                int lx = (int) Math.round(ax + Math.sin(a) * rad), lz = (int) Math.round(az + Math.cos(a) * rad);
                if (Math.hypot(lx, lz) > plan.radius - 3) continue;
                Direction face = Zoning.cardinal(ax - lx, az - lz);
                if (rad == 0) face = Direction.SOUTH;
                Spec s = plan.at(arch, lx, lz, face).size(w, d);
                s.h = h;
                s.margin = 0;
                s.variant = variant;
                s.name = name;
                if (force) return plan.put(s);
                Spec p = plan.place(s, 1);
                if (p != null) return p;
            }
        }
        return null;
    }

    private static String speciesFor(Culture c, RandomSource r) {
        return switch (c) {
            case NORD, ATMORAN -> r.nextBoolean() ? "spruce" : "oak";
            case IMPERIAL -> r.nextBoolean() ? "oak" : "birch";
            case BRETON -> r.nextBoolean() ? "oak" : "birch";
            case DUNMER -> "dark_oak";
            case ALTMER -> r.nextBoolean() ? "cherry" : "azalea";
            case BOSMER -> "graht";
            case ARGONIAN -> r.nextBoolean() ? "willow" : "mangrove";
            case KHAJIIT, REDGUARD, YOKUDAN -> "acacia";
            case ORC -> "dark_oak";
            default -> "oak";
        };
    }

    private static String statueTheme(Culture c, RandomSource r, String requested) {
        if (!requested.isEmpty()) return requested.contains(":") || requested.equals("hist") || requested.equals("pact") || requested.equals("emperor")
                ? requested : "divine:" + requested;
        return switch (c) {
            case DUNMER -> r.nextInt(3) == 0 ? "daedric:azura" : "tribunal:" + new String[]{"vivec", "almalexia", "sotha_sil"}[r.nextInt(3)];
            case ARGONIAN -> "hist";
            case BOSMER -> "pact";
            case IMPERIAL -> r.nextBoolean() ? "emperor" : "divine:" + Divines.pick(r);
            case NORD, ATMORAN -> r.nextInt(3) == 0 ? "divine:talos" : "divine:" + Divines.pick(r);
            default -> "divine:" + Divines.pick(r);
        };
    }

    private static final class Divines {
        static final String[] ALL = {"akatosh", "arkay", "dibella", "julianos", "kynareth", "mara", "stendarr", "talos", "zenithar"};

        static String pick(RandomSource r) {
            return ALL[r.nextInt(ALL.length)];
        }
    }

    /** Builds one feature of {@code type} near (ax, az). Returns true if it found room. */
    static boolean make(Zoning.Ctx x, String type, String theme, double ax, double az, int reach, int index, JsonObject o) {
        Plan plan = x.plan;
        RandomSource r = x.r;
        Culture c = plan.culture;
        int w = o != null ? (int) Nets.num(o, "size", 0) : 0;
        switch (type.toLowerCase(Locale.ROOT)) {
            case "great_tree" -> {
                String sp = theme.isEmpty() ? speciesFor(c, r) : theme;
                int sz = w > 0 ? w : 11;
                return spot(x, Archetype.YARD, Outdoors.GREAT_TREE, "species:" + sp, sz, sz, 22, ax, az, reach, false) != null;
            }
            case "tree" -> {
                return spot(x, Archetype.YARD, Outdoors.TREE, "", 5, 5, 9, ax, az, reach, false) != null;
            }
            case "tree_row" -> {
                // an avenue: five trees in a line, 8 apart, along the cardinal nearer the anchor's street
                int placed = 0;
                boolean alongX = r.nextBoolean();
                for (int k = 0; k < 5; k++) {
                    double tx = ax + (alongX ? (k - 2) * 8 : 0), tz = az + (alongX ? 0 : (k - 2) * 8);
                    if (spot(x, Archetype.YARD, Outdoors.TREE, "", 5, 5, 9, tx, tz, 4, false) != null) placed++;
                }
                return placed >= 2;
            }
            case "fountain" -> {
                return spot(x, Archetype.YARD, Outdoors.FOUNTAIN, "", 7, 7, 6, ax, az, reach, false) != null;
            }
            case "well" -> {
                return spot(x, Archetype.YARD, Outdoors.WELL, "", 5, 5, 6, ax, az, reach, false) != null;
            }
            case "pond" -> {
                return spot(x, Archetype.YARD, Outdoors.POND, "", 11, 9, 4, ax, az, reach, false) != null;
            }
            case "pool" -> {
                return spot(x, Archetype.YARD, Outdoors.REFLECTING_POOL, "", 7, 17, 5, ax, az, reach, false) != null;
            }
            case "basin" -> {
                return spot(x, Archetype.YARD, Outdoors.BASIN, "", 15, 15, 4, ax, az, reach, false) != null;
            }
            case "aqueduct" -> {
                int len = w > 0 ? w : 17 + 4 * r.nextInt(3);
                return spot(x, Archetype.YARD, Outdoors.AQUEDUCT, "", 5, len, 12, ax, az, reach, false) != null;
            }
            case "waterwheel" -> {
                return spot(x, Archetype.YARD, Outdoors.WATERWHEEL, "", 9, 5, 12, ax, az, reach, false) != null;
            }
            case "statue" -> {
                return spot(x, Archetype.YARD, Outdoors.STATUE_THEMED, statueTheme(c, r, theme), 5, 5, 12, ax, az, reach, false) != null;
            }
            case "shrine" -> {
                String t = theme.isEmpty() ? "divine:" + Divines.pick(r) : theme.contains(":") ? theme : "divine:" + theme;
                return spot(x, Archetype.YARD, Outdoors.SHRINE, t, 1, 1, 5, ax, az, reach, false) != null;
            }
            case "memorial" -> {
                return spot(x, Archetype.YARD, Outdoors.MEMORIAL, "", 5, 5, 11, ax, az, reach, false) != null;
            }
            case "bell_tower" -> {
                return spot(x, Archetype.YARD, Outdoors.BELL_TOWER, "", 5, 5, 20, ax, az, reach, false) != null;
            }
            case "banners" -> {
                int placed = 0;
                int n = w > 0 ? w : 4;
                for (int k = 0; k < n; k++) {
                    double a = k * Math.PI * 2 / n + r.nextDouble();
                    if (spot(x, Archetype.YARD, Outdoors.BANNER, "", 3, 1, 10, ax + Math.sin(a) * 10, az + Math.cos(a) * 10, 5, false) != null) placed++;
                }
                return placed > 0;
            }
            case "lamps" -> {
                int placed = 0;
                for (int k = 0; k < 6; k++) {
                    double a = k * Math.PI / 3 + r.nextDouble() * 0.4;
                    if (spot(x, Archetype.YARD, Outdoors.LAMP_POST, "", 1, 1, 5, ax + Math.sin(a) * 9, az + Math.cos(a) * 9, 4, false) != null) placed++;
                }
                return placed > 0;
            }
            case "garden" -> {
                // a small park: lawn bed, flower beds, two trees and a bench
                Spec g = spot(x, Archetype.YARD, Outdoors.FLOWER_BED, "", 7, 5, 3, ax, az, reach, false);
                if (g == null) return false;
                spot(x, Archetype.YARD, Outdoors.TREE, "", 5, 5, 9, g.x - 7, g.z, 4, false);
                spot(x, Archetype.YARD, Outdoors.FLOWER_BED, "", 7, 5, 3, g.x + 7, g.z, 4, false);
                spot(x, Archetype.YARD, Outdoors.BENCH, "", 3, 1, 3, g.x, g.z + 5, 3, false);
                return true;
            }
            case "orchard" -> {
                Spec s = spot(x, Archetype.CAMP, Outdoors.ORCHARD, "", 13, 13, 8, ax, az, reach, false);
                return s != null;
            }
            case "market" -> {
                int size = w > 0 ? w : 15;
                Spec pz = spot(x, Archetype.PLAZA, 0, "", size, size, 6, ax, az, reach, false);
                if (pz == null) return false;
                int stalls = 0;
                for (int k = 0; k < 14 && stalls < 6; k++) {
                    int sx = pz.x + (k % 2 == 0 ? -1 : 1) * (4 + (k / 4) * 4), sz = pz.z + ((k / 2) % 2 == 0 ? -1 : 1) * (size / 2 - 2);
                    Spec st = plan.at(Archetype.STALL, sx, sz, Direction.SOUTH).size(3, 3);
                    st.margin = 0;
                    boolean clash = false;
                    for (Spec q : plan.specs) if (q != pz && q.overlaps(st, 1) && !q.has(Spec.GROUND) && q.kind != Archetype.STREET) clash = true;
                    if (!clash && Math.abs(sx - pz.x) < size / 2 && Math.abs(sz - pz.z) < size / 2) {
                        plan.put(st);
                        stalls++;
                    }
                }
                return true;
            }
            case "flowers" -> {
                return spot(x, Archetype.YARD, Outdoors.FLOWER_BED, "", 7, 5, 3, ax, az, reach, false) != null;
            }
            case "bench" -> {
                return spot(x, Archetype.YARD, Outdoors.BENCH, "", 3, 1, 3, ax, az, reach, false) != null;
            }
            case "gatehouse" -> {
                // two small towers flanking the main gate
                if (x.sk.gates.isEmpty()) return false;
                int[] g = x.sk.gates.get(0);
                int placed = 0;
                for (int side = -1; side <= 1; side += 2) {
                    double len = Math.max(1, Math.hypot(g[0], g[1]));
                    double px = -g[1] / len * side * 13, pz = g[0] / len * side * 13;
                    Spec t = spot(x, Archetype.TOWER, 0, "", 7, 7, 16, g[0] + px, g[1] + pz, 5, false);
                    if (t != null) placed++;
                }
                return placed > 0;
            }
            default -> {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ seeded catalogue

    /** One catalogue entry: the type, its culture weights (0 = never), and where it likes to stand. */
    private record Entry(String type, String where, int nord, int imperial, int breton, int redguard, int dunmer, int altmer,
                         int bosmer, int argonian, int khajiit, int orc, boolean needsWater) {
    }

    private static Entry e(String type, String where, int nord, int imperial, int breton, int redguard, int dunmer, int altmer,
                           int bosmer, int argonian, int khajiit, int orc, boolean water) {
        return new Entry(type, where, nord, imperial, breton, redguard, dunmer, altmer, bosmer, argonian, khajiit, orc, water);
    }

    private static final Entry[] CATALOGUE = {
            //        type            where     nor imp bre red dun alt bos arg kha orc  water
            e("great_tree",  "square", 3, 3, 3, 1, 1, 3, 5, 3, 1, 1, false),
            e("tree_row",    "street", 2, 4, 3, 2, 1, 4, 3, 1, 2, 1, false),
            e("fountain",    "square", 1, 4, 3, 4, 1, 4, 1, 1, 3, 0, false),
            e("well",        "free",   4, 3, 4, 3, 3, 2, 2, 2, 3, 3, false),
            e("pond",        "free",   2, 2, 3, 1, 2, 3, 4, 5, 1, 1, false),
            e("pool",        "square", 0, 3, 2, 3, 1, 5, 0, 0, 3, 0, false),
            e("basin",       "edge",   1, 2, 2, 2, 1, 2, 0, 2, 2, 0, true),
            e("aqueduct",    "edge",   0, 3, 1, 4, 0, 3, 0, 0, 3, 0, false),
            e("waterwheel",  "edge",   3, 2, 3, 1, 1, 0, 1, 0, 0, 2, true),
            e("statue",      "square", 3, 4, 3, 3, 4, 3, 3, 3, 3, 2, false),
            e("shrine",      "street", 4, 4, 4, 3, 4, 3, 3, 4, 3, 3, false),
            e("memorial",    "square", 3, 4, 3, 3, 2, 2, 1, 1, 2, 3, false),
            e("bell_tower",  "square", 2, 4, 4, 2, 0, 1, 0, 0, 1, 0, false),
            e("banners",     "square", 4, 4, 4, 3, 2, 4, 2, 2, 4, 4, false),
            e("lamps",       "street", 3, 4, 4, 4, 3, 5, 2, 2, 4, 3, false),
            e("garden",      "free",   2, 3, 4, 3, 2, 5, 4, 2, 3, 1, false),
            e("orchard",     "edge",   2, 3, 4, 2, 1, 3, 3, 1, 2, 2, false),
            e("market",      "free",   3, 4, 3, 4, 3, 3, 1, 2, 5, 2, false),
            e("flowers",     "street", 2, 3, 4, 3, 2, 5, 4, 2, 3, 1, false),
            e("bench",       "square", 2, 3, 3, 3, 2, 3, 2, 2, 3, 2, false),
            e("tree",        "free",   3, 3, 3, 2, 2, 3, 5, 3, 2, 2, false),
    };

    private static int weight(Entry en, Culture c) {
        return switch (c) {
            case NORD, ATMORAN -> en.nord;
            case IMPERIAL -> en.imperial;
            case BRETON -> en.breton;
            case REDGUARD, YOKUDAN -> en.redguard;
            case DUNMER -> en.dunmer;
            case ALTMER, MAORMER -> en.altmer;
            case BOSMER -> en.bosmer;
            case ARGONIAN -> en.argonian;
            case KHAJIIT -> en.khajiit;
            case ORC -> en.orc;
            default -> en.imperial;
        };
    }

    /** How many features a settlement of this tier carries (hamlet 1-2, village 2-4, town 4-8, city 8-15). */
    static int countFor(int tier, RandomSource r) {
        return switch (tier) {
            case 0 -> 1 + r.nextInt(2);
            case 1 -> 2 + r.nextInt(3);
            case 2 -> 4 + r.nextInt(5);
            default -> 8 + r.nextInt(8);
        };
    }

    /** The catalogue picks of a settlement: weighted by culture, seeded by the plan, without repeats. */
    static List<String> pick(Culture c, long seed, int tier, boolean water) {
        RandomSource r = RandomSource.create(seed ^ 0xFEA7L);
        int want = countFor(tier, r);
        List<Entry> pool = new ArrayList<>();
        for (Entry en : CATALOGUE) if (weight(en, c) > 0 && (!en.needsWater || water)) pool.add(en);
        List<String> out = new ArrayList<>();
        while (out.size() < want && !pool.isEmpty()) {
            int total = 0;
            for (Entry en : pool) total += weight(en, c);
            int roll = r.nextInt(Math.max(1, total));
            for (int i = 0; i < pool.size(); i++) {
                roll -= weight(pool.get(i), c);
                if (roll < 0) {
                    out.add(pool.get(i).type + "@" + pool.get(i).where);
                    pool.remove(i);
                    break;
                }
            }
        }
        return out;
    }

    /** Places a seeded catalogue set; returns the picks that found room. */
    static List<String> auto(Zoning.Ctx x) {
        boolean water = x.sk.water != null && x.sk.waterDist < x.R + 20;
        List<String> picks = pick(x.plan.culture, x.plan.seed, x.tier, water);
        List<String> done = new ArrayList<>();
        int i = 0;
        for (String p : picks) {
            String[] parts = p.split("@");
            double[] a = anchor(x, parts[1], null, "", i++, picks.size());
            if (make(x, parts[0], "", a[0], a[1], 30, 0, null)) done.add(parts[0]);
        }
        return done;
    }
}
