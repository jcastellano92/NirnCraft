package com.skycraft.tamriel.settlement.grammar;

import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.settlement.Culture;
import com.skycraft.tamriel.settlement.SettlementPlan;
import com.skycraft.tamriel.settlement.plan.Archetype;
import com.skycraft.tamriel.settlement.plan.Layouts;
import com.skycraft.tamriel.settlement.plan.Plan;
import com.skycraft.tamriel.settlement.plan.Spec;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The zoning planner: lays out every generic settlement (and fills around the unique capitals) as a compact,
 * lived-in place. A settlement gets a seeded <i>form</i> chosen from its culture's style sheet and its site (a linear
 * road town, a crossroads, a planned grid, a hilltop town of ring streets, a riverside or harbour town along the
 * shore, a canal town, an organic cluster of alleys, a ring of huts round a green); a street skeleton for that form
 * whose main streets end at the gates where outer roads join; zones (centre square, noble quarter by the keep, craft
 * row by the water, poor quarter by the walls and docks, common quarter, outskirts); required trades placed in
 * their quarter; every street frontage packed with houses two to five blocks apart; backyards with gardens, pens and
 * woodpiles; streets lined with lamps, carts, barrels, benches, signposts and shrines; squares with wells, fountains,
 * statues, notice boards and market stalls; walls only where the culture and size warrant, drawn as an organic
 * polygon round the built area (partial, palisade or stone); and farms, mills, lumber yards, mines and graveyards
 * outside.
 *
 * <p>The skeleton (and so the gates) depends only on the seed, the culture, the size and the map, never on the
 * generated terrain: {@link #gates} predicts where roads must arrive before a chunk exists.</p>
 */
public final class Zoning {
    private Zoning() {}

    public enum Form { LINEAR, CROSSROADS, GRID, HILLTOP, RIVERSIDE, HARBOUR, CANAL, CLUSTER, RING }

    public enum Zone { CENTRE, NOBLE, COMMON, POOR, CRAFT, GATE }

    /** A street of the skeleton (local coordinates); rank 0 main, 1 lane, 2 alley, 3 square edge (no paving). */
    public record Street(int x1, int z1, int x2, int z2, int width, int rank) {
        double len() {
            return Math.hypot(x2 - x1, z2 - z1);
        }
    }

    /** A settlement's street skeleton. */
    public static final class Skeleton {
        public Form form;
        public int radius;
        public final List<Street> streets = new ArrayList<>();
        /** Gate points (local x, z): where a main street meets the edge. The first is the main gate. */
        public final List<int[]> gates = new ArrayList<>();
        /** Squares: local x, z, size. The first is the main square. */
        public final List<int[]> squares = new ArrayList<>();
        /** Keep / seat position (local) or null. */
        public int[] keep;
        /** Local direction of the water, or null. */
        public Direction water;
        public int waterDist;
        public boolean hill;
        /** The canal (local segment) of a canal town, or null. */
        public int[] canal;
    }

    // ------------------------------------------------------------------ sizes

    static int tier(Layouts.Size size) {
        return switch (size) {
            case CITY -> 3;
            case TOWN -> 2;
            case VILLAGE -> 1;
            default -> 0;
        };
    }

    /** Radius of the built-up area: compact, a fraction of the structure's reach (the rest is outskirts). */
    public static int builtRadius(Layouts.Size size, int planRadius) {
        return switch (size) {
            case CITY -> Math.max(52, Math.min(84, (int) (planRadius * 0.62)));
            case TOWN -> Math.max(34, Math.min(54, (int) (planRadius * 0.55)));
            case VILLAGE -> Math.max(24, Math.min(36, (int) (planRadius * 0.5)));
            default -> Math.max(14, Math.min(22, (int) (planRadius * 0.5)));
        };
    }

    // ------------------------------------------------------------------ skeleton (terrain-free)

    /**
     * The street skeleton of a settlement. Depends only on the arguments and the Tamriel map, so road planners can
     * predict the gates before any terrain exists.
     */
    public static Skeleton skeleton(long seed, Culture culture, int style, Layouts.Size size, int cx, int cz, Direction back, int planRadius) {
        RandomSource r = RandomSource.create(seed ^ 0x5EEDFACEL);
        Skeleton sk = new Skeleton();
        int R = builtRadius(size, planRadius);
        sk.radius = R;
        int t = tier(size);
        site(sk, cx, cz, back, R);
        Style st = Styles.of(culture, style);
        sk.form = chooseForm(st, sk, t, r);
        int main = t >= 2 ? 5 : 3, lane = 3, alley = 2;
        boolean keep = t >= 2;
        int keepZ = -(int) (R * 0.72);
        int sq = t == 3 ? 19 : t == 2 ? 15 : t == 1 ? 11 : 7;
        switch (sk.form) {
            case LINEAR -> {
                int j = r.nextInt(9) - 4;
                int mz = r.nextInt(9) - 4;
                sk.streets.add(new Street(0, R, 0, mz, main, 0));
                if (j != 0) sk.streets.add(new Street(0, mz, j, mz, main, 0));
                int north = keep ? keepZ + 12 : -R;
                sk.streets.add(new Street(j, mz, j, north, main, 0));
                sk.squares.add(new int[]{j / 2 + (r.nextBoolean() ? 1 : -1) * (main / 2 + sq / 2 + 1), mz, sq});
                // side lanes are traced through the tensor field at planning time (they follow the contours)
                gate(sk, 0, R);
                if (!keep) gate(sk, j, -R);
            }
            case CROSSROADS -> {
                int zc = r.nextInt(R / 3 + 1) - R / 6;
                int north = keep ? keepZ + 12 : -R;
                sk.streets.add(new Street(0, R, 0, north, main, 0));
                sk.streets.add(new Street(-R, zc, R, zc, main, 0));
                sk.squares.add(new int[]{0, zc, sq});
                gate(sk, 0, R);
                gate(sk, -R, zc);
                gate(sk, R, zc);
                if (!keep) gate(sk, 0, -R);
            }
            case GRID -> {
                int block = t >= 3 ? 26 : 24;
                sk.streets.add(new Street(0, R, 0, keep ? keepZ + 12 : -R, main, 0));
                sk.streets.add(new Street(-R, 0, R, 0, main, 0));
                for (int k = 1; k * block < R - 6; k++) {
                    for (int s = -1; s <= 1; s += 2) {
                        int off = s * k * block;
                        int len = (int) Math.sqrt(Math.max(0, (long) R * R - (long) off * off)) - 4;
                        if (len < 12) continue;
                        sk.streets.add(new Street(off, -len, off, len, lane, 1));
                        if (!(keep && off < 0 && Math.abs(off) > R * 0.55)) sk.streets.add(new Street(-len, off, len, off, lane, 1));
                    }
                }
                sk.squares.add(new int[]{block / 2, block / 2, sq});
                if (t >= 3) sk.squares.add(new int[]{-block - block / 2, block + block / 2, 13});
                gate(sk, 0, R);
                gate(sk, -R, 0);
                gate(sk, R, 0);
            }
            case HILLTOP -> {
                double rot = r.nextDouble() * Math.PI / 4;
                int r1 = (int) (R * 0.42), r2 = (int) (R * 0.78);
                ring(sk, r1, 8, rot, lane, 1);
                if (t >= 1) ring(sk, r2, 10, rot + 0.2, lane, 1);
                // the main street climbs from the gate to the top in two dog-legs
                int a = (int) (R * 0.18) * (r.nextBoolean() ? 1 : -1);
                sk.streets.add(new Street(0, R, 0, r2 + 2, main, 0));
                sk.streets.add(new Street(0, r2 + 2, a, r2 + 2, main, 0));
                sk.streets.add(new Street(a, r2 + 2, a, r1, main, 0));
                sk.squares.add(new int[]{0, r1 / 2 + 2, sq});
                keep = t >= 1;
                keepZ = -r1 / 2 - 4;
                gate(sk, 0, R);
            }
            case RIVERSIDE, HARBOUR, CANAL -> {
                if (sk.form == Form.CANAL || sk.water == null) {
                    int cw = 7;
                    sk.canal = new int[]{0, R - 4, 0, -R + 8};
                    sk.streets.add(new Street(-(cw / 2 + 2), R, -(cw / 2 + 2), -R + 8, lane, 0));
                    sk.streets.add(new Street(cw / 2 + 2, R, cw / 2 + 2, -R + 8, lane, 0));
                    for (int z = -R + 18; z < R - 10; z += 22 + r.nextInt(6)) {
                        sk.streets.add(new Street(cw / 2 + 4, z, (int) (R * 0.75), z, alley + 1, 1));
                        sk.streets.add(new Street(-(cw / 2 + 4), z + 9, -(int) (R * 0.75), z + 9, alley + 1, 1));
                    }
                    sk.squares.add(new int[]{cw / 2 + 4 + sq / 2, 0, sq});
                    gate(sk, -(cw / 2 + 2), R);
                    gate(sk, cw / 2 + 2, R);
                    keep = false;
                } else {
                    // canonical frame: the water lies west; rotate onto its real side
                    int q = quarter(sk.water);
                    int shoreX = -Math.max(8, Math.min(R - 6, sk.waterDist - 10));
                    List<Street> local = new ArrayList<>();
                    local.add(new Street(shoreX, R, shoreX, -R, main, 0));
                    // the lanes inland are traced through the tensor field (along the shore, up the contours)
                    for (Street s : local) sk.streets.add(rot(s, q));
                    int[] sqc = rot(shoreX + main / 2 + 2 + sq / 2, r.nextInt(9) - 4, q);
                    sk.squares.add(new int[]{sqc[0], sqc[1], sq});
                    int[] g1 = rot(shoreX, R, q), g2 = rot(shoreX, -R, q);
                    gate(sk, g1[0], g1[1]);
                    gate(sk, g2[0], g2[1]);
                    int[] inland = rot((int) (R * 0.7), 0, q);
                    if (keep) {
                        int[] k = rot((int) (R * 0.55), (int) (R * 0.45), q);
                        sk.keep = new int[]{k[0], k[1]};
                    }
                    if (t >= 2) gate(sk, inland[0], inland[1]);
                    keep = false;
                }
            }
            case CLUSTER -> {
                int n = 3 + r.nextInt(t >= 2 ? 3 : 2);
                double base = Math.PI * (0.85 + r.nextDouble() * 0.3);
                for (int i = 0; i < n; i++) {
                    double ang = i == 0 ? 0 : base + (i - 1) * (2 * Math.PI - 2 * (base - Math.PI)) / Math.max(1, n - 1) * 0.9 + r.nextDouble() * 0.3 - (Math.PI - base);
                    if (i > 0) ang = Math.PI * 2 * i / n + r.nextDouble() * 0.5 - 0.25;
                    double len = i == 0 ? R : R * (0.6 + r.nextDouble() * 0.4);
                    int x2 = (int) Math.round(Math.sin(ang) * len), z2 = (int) Math.round(Math.cos(ang) * len);
                    int x1 = (int) Math.round(Math.sin(ang) * (sq / 2 + 1)), z1 = (int) Math.round(Math.cos(ang) * (sq / 2 + 1));
                    sk.streets.add(new Street(x1, z1, x2, z2, i == 0 ? main : lane, i == 0 || len > R * 0.85 ? 0 : 1));
                    if (i == 0 || len > R * 0.85) gate(sk, x2, z2);
                }
                sk.squares.add(new int[]{0, 0, sq});
                keep = t >= 2;
                keepZ = -(int) (R * 0.55);
            }
            case RING -> {
                int rg = t >= 2 ? 14 : 10;
                ring(sk, rg + 4, 8 + 2 * t, r.nextDouble(), lane, 1);
                if (t >= 1) ring(sk, rg + 20, 10 + 2 * t, r.nextDouble(), alley + 1, 2);
                sk.streets.add(new Street(0, rg + 6, 0, R, main, 0));
                sk.squares.add(new int[]{0, 0, rg * 2 - 3});
                gate(sk, 0, R);
                keep = false;
            }
        }
        if (keep && sk.keep == null) sk.keep = new int[]{0, keepZ};
        return sk;
    }

    static int quarter(Direction water) {
        return switch (water) {
            case NORTH -> 1;
            case EAST -> 2;
            case SOUTH -> 3;
            default -> 0;
        };
    }

    /** Rotates a canonical (water west) point by q quarter turns clockwise. */
    static int[] rot(int x, int z, int q) {
        for (int i = 0; i < q; i++) {
            int nx = -z, nz = x;
            x = nx;
            z = nz;
        }
        return new int[]{x, z};
    }

    static Street rot(Street s, int q) {
        int[] a = rot(s.x1(), s.z1(), q), b = rot(s.x2(), s.z2(), q);
        return new Street(a[0], a[1], b[0], b[1], s.width(), s.rank());
    }

    static void gate(Skeleton sk, int x, int z) {
        sk.gates.add(new int[]{x, z});
    }

    /** A polygonal ring street of radius {@code rad} with {@code n} sides. */
    static void ring(Skeleton sk, int rad, int n, double rot, int width, int rank) {
        for (int i = 0; i < n; i++) {
            double a0 = rot + 2 * Math.PI * i / n, a1 = rot + 2 * Math.PI * (i + 1) / n;
            sk.streets.add(new Street((int) Math.round(Math.sin(a0) * rad), (int) Math.round(Math.cos(a0) * rad),
                    (int) Math.round(Math.sin(a1) * rad), (int) Math.round(Math.cos(a1) * rad), width, rank));
        }
    }

    /** Reads the site from the map: the nearest water side and whether the centre is a hill. */
    static void site(Skeleton sk, int cx, int cz, Direction back, int R) {
        MapSample m = new MapSample();
        int best = Integer.MAX_VALUE;
        Direction[] locals = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        for (Direction local : locals) {
            Direction world = toWorld(local, back);
            for (int d = 12; d <= R + 24; d += 8) {
                TamrielMap.sample(cx + world.getStepX() * d, cz + world.getStepZ() * d, m);
                if (m.coastDistance < 0 || m.inWater()) {
                    if (d < best) {
                        best = d;
                        sk.water = local;
                    }
                    break;
                }
            }
        }
        sk.waterDist = best;
        TamrielMap.sample(cx, cz, m);
        float c = m.elevation;
        float ring = 0;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            TamrielMap.sample(cx + (int) (Math.sin(a) * R * 0.9), cz + (int) (Math.cos(a) * R * 0.9), m);
            ring += m.elevation;
        }
        sk.hill = c - ring / 8 > 5 || m.crag > 0.4f;
    }

    static Direction toWorld(Direction local, Direction back) {
        return switch (back) {
            case EAST -> local.getClockWise();
            case SOUTH -> local.getOpposite();
            case WEST -> local.getCounterClockWise();
            default -> local;
        };
    }

    static Form chooseForm(Style st, Skeleton sk, int tier, RandomSource r) {
        java.util.Map<String, Integer> w = new java.util.LinkedHashMap<>(st.forms);
        if (w.isEmpty()) {
            w.put("linear", 3);
            w.put("crossroads", 3);
            w.put("cluster", 2);
        }
        if (sk.water == null || sk.waterDist > sk.radius + 10) {
            w.remove("riverside");
            w.remove("harbour");
        } else {
            // a town on the water lives by it
            w.merge("riverside", 3, Integer::sum);
            if (tier >= 2) w.merge("harbour", 2, Integer::sum);
        }
        if (!sk.hill) w.remove("hilltop");
        else w.merge("hilltop", 3, Integer::sum);
        if (tier == 0) {
            w.remove("grid");
            w.remove("canal");
            w.remove("harbour");
        }
        String f = Style.weighted(w, "linear", r);
        try {
            return Form.valueOf(f.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Form.LINEAR;
        }
    }

    // ------------------------------------------------------------------ named places

    /** The settlement size a named place is built at. */
    public static Layouts.Size sizeOf(com.skycraft.tamriel.Place p, int radius) {
        return switch (p.type()) {
            case CAPITAL, CITY -> Layouts.Size.CITY;
            case TOWN -> Layouts.Size.TOWN;
            case FARM -> Layouts.Size.FARM;
            default -> radius < 30 ? Layouts.Size.HAMLET : Layouts.Size.VILLAGE;
        };
    }

    /** Lays out a named place: its unique layout (adopted into the grammar) or the zoning planner. */
    public static void place(Plan plan, com.skycraft.tamriel.Place p) {
        Layouts.Size sz = sizeOf(p, plan.radius);
        plan.place = p;
        if (com.skycraft.tamriel.settlement.plan.Cities.build(plan, p)) {
            if (!plan.zoned) civic(plan);
            adopt(plan, sz);
        } else {
            generic(plan, sz, p.variant(), com.skycraft.tamriel.settlement.plan.Cities.names(p));
            if (!plan.civicDone) civic(plan);
        }
    }

    /** The jail, the faction halls and the player-home plots of a named place (once per planning attempt). */
    static void civic(Plan plan) {
        if (plan.place == null) return;
        com.skycraft.tamriel.settlement.plan.Halls.addTo(plan, plan.place);
        com.skycraft.tamriel.settlement.plan.HousePlots.reserve(plan, plan.place);
        plan.civicDone = true;
    }

    // ------------------------------------------------------------------ the planner

    /** What a lot asks for. */
    record Want(Use use, Wealth wealth, Zone zone, String name) {
    }

    static final class Ctx {
        Plan plan;
        Skeleton sk;
        Style style;
        RandomSource r;
        int tier, R;
        Layouts.Size size;
        boolean named;
        final List<Spec> buildings = new ArrayList<>();
        int[] craft, noble, poor;
    }

    /** Lays out a generic settlement of {@code size}. Special variants (strongholds, mills, inns...) keep their layouts. */
    public static void generic(Plan plan, Layouts.Size size, String variant, Layouts.Names names) {
        if (special(variant)) {
            Layouts.generic(plan, size, variant, names);
            adopt(plan, size);
            return;
        }
        if (size == Layouts.Size.FARM || size == Layouts.Size.INN || size == Layouts.Size.HOMESTEAD) {
            if (size == Layouts.Size.FARM) farmstead(plan);
            else Layouts.generic(plan, size, variant, names);
            adopt(plan, size);
            return;
        }
        // quality gate: plan, measure, and re-plan the interior (never the main streets or gates) if it came out thin
        int base = plan.specs.size();
        List<Spec> best = null;
        double bestScore = -1;
        Metrics bestM = null;
        int R = builtRadius(size, plan.radius);
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) plan.specs.subList(base, plan.specs.size()).clear();
            plan.relaxed = false;
            run(plan, size, variant, names, attempt);
            Metrics m = Metrics.of(plan, base, R + 8);
            double score = m.density() + 0.5 * m.variety() + Math.min(0.2, m.buildings() / 200.0);
            if (score > bestScore) {
                bestScore = score;
                best = new ArrayList<>(plan.specs.subList(base, plan.specs.size()));
                bestM = m;
            }
            if (m.passes(size)) break;
        }
        plan.specs.subList(base, plan.specs.size()).clear();
        plan.specs.addAll(best);
        clearing(plan, base, R + 6);
        plan.zoned = true;
        if (bestM != null) com.skycraft.Skycraft.LOGGER.debug("Zoned {}: {}", plan.key, bestM);
    }

    /** Place variants with a hand-made small layout (inns, mills, estates, strongholds...): the rest are zoned. */
    public static boolean special(String variant) {
        return switch (variant) {
            case "stronghold", "inn", "mill", "homestead", "stables", "estate", "lodge", "embassy", "meadery", "mead_hall", "telvanni" -> true;
            default -> false;
        };
    }

    /** One planning attempt; {@code attempt} reseeds the interior (lanes, lots, clutter). */
    static void run(Plan plan, Layouts.Size size, String variant, Layouts.Names names, int attempt) {
        Ctx x = new Ctx();
        x.plan = plan;
        x.size = size;
        x.tier = tier(size);
        x.named = !plan.key.startsWith("gen:");
        x.style = Styles.of(plan.culture, plan.style);
        x.r = RandomSource.create(plan.seed ^ 0x2B0E5L ^ attempt * 0x9E3779B97F4A7C15L);
        x.sk = skeleton(plan.seed, plan.culture, plan.style, size, plan.cx, plan.cz, plan.back, plan.radius);
        x.R = x.sk.radius;
        zones(x);
        Tensor.lanes(x, attempt);
        // ---- streets, canal, squares
        for (Street s : x.sk.streets) {
            if (s.rank() == 3) continue;
            plan.street(s.x1(), s.z1(), s.x2(), s.z2(), s.width(), s.rank() == 0);
        }
        if (x.sk.canal != null) {
            int[] cn = x.sk.canal;
            plan.put(plan.line(Archetype.CANAL, cn[0], cn[1], cn[2], cn[3], 7));
            for (int z = cn[3] + 10; z < cn[1] - 6; z += 20) {
                Spec br = plan.line(Archetype.BRIDGE, cn[0] - 5, z, cn[0] + 5, z, 3);
                br.flags |= Spec.FIXED;
                br.floor = plan.base + 1;
                br.floor2 = plan.base + 1;
                plan.put(br);
            }
        }
        for (int i = 0; i < x.sk.squares.size(); i++) square(x, x.sk.squares.get(i), i == 0);
        if ((x.sk.form == Form.RIVERSIDE || x.sk.form == Form.HARBOUR) && x.sk.water != null) docks(x);
        // ---- the seat
        if (x.sk.keep != null) seat(x, names);
        // other modules' lots first (jail, guild halls, player-home plots): the houses pack round them
        civic(plan);
        // ---- required trades in their quarters, then homes on every frontage
        List<Want> wants = wants(x, names);
        for (Want w : wants) placeWanted(x, w);
        for (Street s : streetsByRank(x)) {
            frontage(x, s, 1);
            frontage(x, s, -1);
        }
        squareFrontage(x);
        reserved(x);
        infill(x);
        backyards(x);
        lining(x);
        walls(x);
        outskirts(x, variant);
        variety(plan);
    }

    /** Jetties off the waterfront street, every 14-18 blocks (dropped at planning where there is no water). */
    static void docks(Ctx x) {
        Street shore = null;
        for (Street s : x.sk.streets) if (s.rank() == 0) {
            shore = s;
            break;
        }
        if (shore == null) return;
        int[] wv = rot(-1, 0, quarter(x.sk.water));
        double len = shore.len();
        double ux = (shore.x2() - shore.x1()) / len, uz = (shore.z2() - shore.z1()) / len;
        int n = x.sk.form == Form.HARBOUR ? 6 : 3;
        int placed = 0;
        for (double t = 10; t < len - 8 && placed < n; t += 14 + x.r.nextInt(5)) {
            double off = shore.width() / 2.0 + 3 + 7;
            int lx = (int) Math.round(shore.x1() + ux * t + wv[0] * off), lz = (int) Math.round(shore.z1() + uz * t + wv[1] * off);
            Spec d = x.plan.at(Archetype.DOCK, lx, lz, cardinal(-wv[0], -wv[1]), 5, 15);
            d.flags |= Spec.NO_BEARD;
            d.margin = 0;
            if (x.plan.place(d, 1) != null) placed++;
        }
    }

    static List<Street> streetsByRank(Ctx x) {
        List<Street> list = new ArrayList<>(x.sk.streets);
        list.sort(Comparator.comparingInt(Street::rank));
        return list;
    }

    static void zones(Ctx x) {
        int R = x.R;
        Skeleton sk = x.sk;
        x.noble = sk.keep != null ? sk.keep : new int[]{0, -(int) (R * 0.6)};
        if (sk.water != null && sk.waterDist < R + 10) {
            int[] w = rot(-(int) (R * 0.55), 0, quarter(sk.water));
            x.craft = w;
            x.poor = rot(-(int) (R * 0.6), (int) (R * 0.5), quarter(sk.water));
        } else {
            boolean east = x.r.nextBoolean();
            x.craft = new int[]{east ? (int) (R * 0.6) : -(int) (R * 0.6), (int) (R * 0.25)};
            x.poor = new int[]{east ? -(int) (R * 0.55) : (int) (R * 0.55), (int) (R * 0.65)};
        }
    }

    static Zone zoneAt(Ctx x, double lx, double lz) {
        int[] sq = x.sk.squares.isEmpty() ? new int[]{0, 0, 9} : x.sk.squares.get(0);
        double R = x.R;
        if (Math.hypot(lx - sq[0], lz - sq[1]) < sq[2] / 2.0 + 10) return Zone.CENTRE;
        if (x.tier >= 1 && Math.hypot(lx - x.noble[0], lz - x.noble[1]) < R * 0.38) return Zone.NOBLE;
        if (x.tier >= 1 && Math.hypot(lx - x.craft[0], lz - x.craft[1]) < R * 0.32) return Zone.CRAFT;
        if (Math.hypot(lx - x.poor[0], lz - x.poor[1]) < R * 0.35 || Math.hypot(lx, lz) > R * 0.85) return Zone.POOR;
        if (!x.sk.gates.isEmpty() && Math.hypot(lx - x.sk.gates.get(0)[0], lz - x.sk.gates.get(0)[1]) < 14) return Zone.GATE;
        return Zone.COMMON;
    }

    static Wealth wealthOf(Ctx x, Zone z) {
        return switch (z) {
            case NOBLE -> x.tier >= 2 && x.r.nextInt(3) == 0 ? Wealth.NOBLE : Wealth.WEALTHY;
            case CENTRE -> x.tier >= 2 && x.r.nextBoolean() ? Wealth.WEALTHY : Wealth.COMMON;
            case POOR -> Wealth.POOR;
            default -> x.tier >= 3 && x.r.nextInt(4) == 0 ? Wealth.WEALTHY : x.tier == 0 && x.r.nextBoolean() ? Wealth.POOR : Wealth.COMMON;
        };
    }

    static Use homeFor(Ctx x, Wealth w) {
        return switch (w) {
            case POOR -> x.r.nextInt(3) == 0 ? Use.HOVEL : Use.COTTAGE;
            case COMMON -> x.r.nextInt(3) == 0 ? Use.COTTAGE : Use.HOUSE;
            case WEALTHY -> x.r.nextInt(3) == 0 ? Use.HOUSE : Use.TOWNHOUSE;
            case NOBLE -> x.r.nextInt(3) == 0 ? Use.TOWNHOUSE : Use.MANOR;
        };
    }

    /** The trades and halls a settlement of this size must have, each with its quarter. */
    static List<Want> wants(Ctx x, Layouts.Names names) {
        List<Want> w = new ArrayList<>();
        RandomSource r = x.r;
        int t = x.tier;
        boolean water = x.sk.water != null && x.sk.waterDist < x.R + 10;
        if (t >= 1 || r.nextInt(3) == 0) w.add(new Want(Use.INN, Wealth.COMMON, Zone.CENTRE, names.inn()));
        if (t >= 1 || r.nextBoolean()) w.add(new Want(Use.GENERAL_STORE, Wealth.COMMON, Zone.CENTRE, names.store()));
        if (t >= 1 || r.nextInt(3) == 0) w.add(new Want(Use.SMITHY, Wealth.COMMON, Zone.CRAFT, names.smithy()));
        if (t >= 2 || t == 1 && r.nextInt(3) == 0) w.add(new Want(Use.ALCHEMIST, Wealth.COMMON, Zone.CENTRE, ""));
        if (t >= 2) w.add(new Want(Use.TEMPLE, Wealth.WEALTHY, Zone.CENTRE, ""));
        if (t >= 2) w.add(new Want(Use.GUARDHOUSE, Wealth.COMMON, Zone.GATE, ""));
        if (t >= 1 && r.nextBoolean() || t >= 2) w.add(new Want(Use.TANNERY, Wealth.POOR, Zone.CRAFT, ""));
        if (t >= 2 || t == 1 && r.nextInt(3) == 0) w.add(new Want(Use.BUTCHER, Wealth.COMMON, Zone.CRAFT, ""));
        if (t >= 2 && r.nextInt(3) > 0 || t >= 3) w.add(new Want(Use.FLETCHER, Wealth.COMMON, Zone.CRAFT, ""));
        if (t >= 2 && r.nextBoolean() || t >= 3) w.add(new Want(Use.TAILOR, Wealth.COMMON, Zone.CENTRE, ""));
        if (t >= 2 && r.nextInt(3) == 0 || t >= 3) w.add(new Want(Use.JEWELLER, Wealth.WEALTHY, Zone.NOBLE, ""));
        if (t >= 2 && r.nextInt(3) == 0 || t >= 3) w.add(new Want(Use.BOOKSELLER, Wealth.WEALTHY, Zone.CENTRE, ""));
        if (t >= 2) w.add(new Want(Use.FENCE_DEN, Wealth.POOR, Zone.POOR, ""));
        if (water) w.add(new Want(Use.FISHERY, Wealth.POOR, Zone.CRAFT, ""));
        if (water && t >= 2) w.add(new Want(Use.WAREHOUSE, Wealth.COMMON, Zone.CRAFT, ""));
        if (t >= 3) {
            w.add(new Want(Use.INN, Wealth.WEALTHY, Zone.NOBLE, ""));
            w.add(new Want(Use.GENERAL_STORE, Wealth.COMMON, Zone.COMMON, ""));
            w.add(new Want(Use.MAGE_TOWER, Wealth.NOBLE, Zone.NOBLE, ""));
            w.add(new Want(Use.BARRACKS, Wealth.COMMON, Zone.GATE, ""));
            w.add(new Want(Use.HALL_OF_DEAD, Wealth.WEALTHY, Zone.CENTRE, ""));
            w.add(new Want(Use.WAREHOUSE, Wealth.COMMON, Zone.CRAFT, ""));
            w.add(new Want(Use.SMITHY, Wealth.COMMON, Zone.GATE, ""));
        }
        if (t >= 1) {
            int manors = t >= 3 ? 4 : t == 2 ? 2 : 0;
            for (int i = 0; i < manors; i++) w.add(new Want(i % 2 == 0 ? Use.MANOR : Use.TOWNHOUSE, i == 0 ? Wealth.NOBLE : Wealth.WEALTHY, Zone.NOBLE, ""));
        }
        return w;
    }

    static void seat(Ctx x, Layouts.Names names) {
        int[] k = x.sk.keep;
        Plan plan = x.plan;
        boolean hall = plan.culture == Culture.NORD || plan.culture == Culture.ORC || plan.culture == Culture.ATMORAN;
        Use use = x.tier >= 2 ? (hall && x.tier == 2 && x.r.nextBoolean() ? Use.LONGHOUSE : Use.KEEP) : Use.LONGHOUSE;
        int[] sz = use.size(Wealth.NOBLE, x.r);
        if (use == Use.KEEP && x.tier == 2) sz = new int[]{17, 19};
        Spec s = plan.at(use.archetype(), k[0], k[1], Direction.SOUTH).size(sz[0], sz[1]);
        s.use(use, Wealth.NOBLE);
        s.flag(Spec.SEAT | Spec.SIGN);
        s.margin = 2;
        stamp(x, s);
        if (plan.place(s, 2) == null) {
            plan.relaxed = true;
            if (plan.place(s, 1) == null) {
                s.size(13, 15);
                plan.place(s, 1);
            }
            plan.relaxed = false;
        }
        if (plan.specs.contains(s)) x.buildings.add(s);
    }

    static void stamp(Ctx x, Spec s) {
        Plan plan = x.plan;
        if (s.culture < 0) s.culture = plan.culture.ordinal();
        if (s.style < 0) s.style = plan.style;
        s.gseed = (int) mix(plan.seed, s.x, s.z);
        s.slope = plan.terrain.spread(s.x, s.z, Math.max(1, s.halfX() - s.margin), Math.max(1, s.halfZ() - s.margin));
        if (s.wealth >= Wealth.WEALTHY.ordinal() || s.use == Use.MANOR.ordinal()) s.flags |= Spec.RICH;
    }

    static long mix(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return h;
    }

    // ------------------------------------------------------------------ lots

    /** A building lot facing a street: centre position and facing for a footprint w x d at distance t along the street. */
    static Spec lotOn(Ctx x, Street st, int side, double t, Use use, Wealth wealth, int w, int d, int setback) {
        double len = st.len();
        if (len < 1) return null;
        double ux = (st.x2() - st.x1()) / len, uz = (st.z2() - st.z1()) / len;
        double nx = -uz * side, nz = ux * side;
        Direction face = cardinal(-nx, -nz);
        boolean zFace = face.getAxis() == Direction.Axis.Z;
        int ex = zFace ? w : d, ez = zFace ? d : w;
        double alongU = Math.abs(ux) * ex + Math.abs(uz) * ez;
        double alongN = Math.abs(nx) * ex + Math.abs(nz) * ez;
        double px = st.x1() + ux * (t + alongU / 2), pz = st.z1() + uz * (t + alongU / 2);
        double off = st.width() / 2.0 + setback + alongN / 2.0 + 0.5;
        int lx = (int) Math.round(px + nx * off), lz = (int) Math.round(pz + nz * off);
        Spec s = x.plan.at(use.archetype(), lx, lz, face).size(w, d);
        s.use(use, wealth);
        s.margin = 1;
        return s;
    }

    /** Footprint extent of a lot along the street (for the frontage cursor). */
    static double alongStreet(Street st, int side, int w, int d) {
        double len = st.len();
        double ux = (st.x2() - st.x1()) / len, uz = (st.z2() - st.z1()) / len;
        Direction face = cardinal(uz * side, -ux * side);
        boolean zFace = face.getAxis() == Direction.Axis.Z;
        int ex = zFace ? w : d, ez = zFace ? d : w;
        return Math.abs(ux) * ex + Math.abs(uz) * ez;
    }

    static Direction cardinal(double dx, double dz) {
        // local frame: +x east, +z south
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    static int setback(Ctx x, Street st) {
        return st.rank() == 0 ? 1 : st.rank() == 3 ? 1 : x.r.nextInt(3) == 0 ? 1 : 0;
    }

    static boolean tryPlace(Ctx x, Spec s) {
        if (s == null) return false;
        Plan plan = x.plan;
        // keep inside the built area (outskirts come later)
        int lx = localX(plan, s.x, s.z), lz = localZ(plan, s.x, s.z);
        if (Math.hypot(lx, lz) > x.R + 4) return false;
        stamp(x, s);
        if (plan.place(s, 0) != null) {
            x.buildings.add(s);
            return true;
        }
        return false;
    }

    static int localX(Plan p, int wx, int wz) {
        int dx = wx - p.cx, dz = wz - p.cz;
        return switch (p.back) {
            case EAST -> dz;
            case SOUTH -> -dx;
            case WEST -> -dz;
            default -> dx;
        };
    }

    static int localZ(Plan p, int wx, int wz) {
        int dx = wx - p.cx, dz = wz - p.cz;
        return switch (p.back) {
            case EAST -> -dx;
            case SOUTH -> -dz;
            case WEST -> dx;
            default -> dz;
        };
    }

    /** Places a required trade on the frontage nearest its quarter. */
    static void placeWanted(Ctx x, Want want) {
        int[] anchor = switch (want.zone()) {
            case CENTRE -> x.sk.squares.isEmpty() ? new int[]{0, 0} : x.sk.squares.get(0);
            case NOBLE -> x.noble;
            case CRAFT -> x.craft;
            case POOR -> x.poor;
            case GATE -> x.sk.gates.isEmpty() ? new int[]{0, x.R} : new int[]{x.sk.gates.get(0)[0] / 4 * 3, x.sk.gates.get(0)[1] / 4 * 3};
            default -> new int[]{0, 0};
        };
        int[] sz = want.use().size(want.wealth(), x.r);
        List<double[]> cands = new ArrayList<>();
        List<Street> all = new ArrayList<>(x.sk.streets);
        all.addAll(squareEdges(x));
        for (int si = 0; si < all.size(); si++) {
            Street st = all.get(si);
            double len = st.len();
            for (double t = 3; t < len - 3; t += 2) {
                double px = st.x1() + (st.x2() - st.x1()) * t / len, pz = st.z1() + (st.z2() - st.z1()) * t / len;
                double dist = Math.hypot(px - anchor[0], pz - anchor[1]);
                // terrain-adaptive: steep lots cost as much as walking a few blocks further
                int wx = x.plan.wx((int) Math.round(px), (int) Math.round(pz)), wz = x.plan.wz((int) Math.round(px), (int) Math.round(pz));
                dist += 2.5 * x.plan.terrain.spread(wx, wz, 4, 4);
                cands.add(new double[]{dist, si, t, 1});
                cands.add(new double[]{dist + 0.5, si, t, -1});
            }
        }
        cands.sort(Comparator.comparingDouble(a -> a[0]));
        int tries = 0;
        for (double[] cd : cands) {
            if (tries++ > 90) break;
            Street st = all.get((int) cd[1]);
            for (int shrink = 0; shrink <= 2; shrink++) {
                int w = Math.max(want.use().wMin, sz[0] - shrink * 2), d = Math.max(want.use().dMin, sz[1] - shrink * 2);
                Spec s = lotOn(x, st, (int) cd[3], cd[2], want.use(), want.wealth(), w, d, setback(x, st));
                if (s == null) continue;
                if (want.use() == Use.SMITHY || want.use() == Use.STABLES) s.margin = 2;
                if (!want.name().isEmpty()) {
                    s.name = want.name();
                    s.flag(Spec.SIGN);
                }
                if (want.use() == Use.FISHERY || want.use() == Use.WAREHOUSE) s.margin = 1;
                if (tryPlace(x, s)) return;
            }
        }
        if (x.named) {
            // named places: rough ground is no reason to leave a trade out
            x.plan.relaxed = true;
            for (int i = 0; i < Math.min(40, cands.size()); i++) {
                double[] cd = cands.get(i);
                Street st = all.get((int) cd[1]);
                Spec s = lotOn(x, st, (int) cd[3], cd[2], want.use(), want.wealth(), want.use().wMin, want.use().dMin, 1);
                if (s != null && !want.name().isEmpty()) {
                    s.name = want.name();
                    s.flag(Spec.SIGN);
                }
                if (tryPlace(x, s)) break;
            }
            x.plan.relaxed = false;
        }
    }

    /** Packs one side of a street with homes: two to five blocks apart, no holes left unfilled. */
    static void frontage(Ctx x, Street st, int side) {
        double len = st.len();
        if (len < 8) return;
        double t = st.width() / 2.0 + 2;
        int gapMin = x.tier >= 1 ? 1 : 2, gapMax = x.tier >= 2 ? 2 : x.tier == 1 ? 3 : 4;
        int misses = 0;
        while (t < len - 3) {
            double px = st.x1() + (st.x2() - st.x1()) * t / len, pz = st.z1() + (st.z2() - st.z1()) * t / len;
            Zone z = zoneAt(x, px, pz);
            if (z == Zone.CENTRE && x.tier >= 2 && x.r.nextInt(4) == 0) z = Zone.COMMON;
            Wealth wl = wealthOf(x, z);
            Use use = homeFor(x, wl);
            if (z == Zone.CRAFT && x.r.nextInt(5) == 0) use = x.r.nextBoolean() ? Use.WAREHOUSE : Use.TANNERY;
            int[] sz = use.size(wl, x.r);
            boolean placed = false;
            for (int shrink = 0; shrink <= 2 && !placed; shrink++) {
                int w = Math.max(5, sz[0] - shrink * 2), d = Math.max(5, sz[1] - shrink * 2);
                Use u = shrink == 2 && use != Use.HOVEL ? Use.COTTAGE : use;
                Spec s = lotOn(x, st, side, t, u, wl, w, d, setback(x, st));
                if (tryPlace(x, s)) {
                    t += alongStreet(st, side, w, d) + gapMin + x.r.nextInt(gapMax - gapMin + 1);
                    placed = true;
                }
            }
            if (!placed) {
                // something small in the gap keeps the street lived-in
                if (misses++ % 3 == 0) gapFiller(x, st, side, t);
                t += 2;
            } else {
                misses = 0;
            }
        }
    }

    /**
     * Back lots: homes in the holes of the blocks behind the street fronts, each facing its nearest street and joined
     * to it by a footpath, so blocks fill up the way old towns do instead of leaving yards of empty grass.
     */
    static void infill(Ctx x) {
        Plan plan = x.plan;
        List<int[]> pts = new ArrayList<>();
        int step = 5;
        for (int gx = -x.R; gx <= x.R; gx += step) for (int gz = -x.R; gz <= x.R; gz += step) if (Math.hypot(gx, gz) < x.R * 0.86) pts.add(new int[]{gx, gz});
        for (int i = pts.size() - 1; i > 0; i--) {
            int j = x.r.nextInt(i + 1);
            int[] t = pts.get(i);
            pts.set(i, pts.get(j));
            pts.set(j, t);
        }
        for (int[] p : pts) {
            // the nearest street point
            double best = Double.MAX_VALUE, bx = 0, bz = 0;
            for (Street st : x.sk.streets) {
                double vx = st.x2() - st.x1(), vz = st.z2() - st.z1();
                double l2 = vx * vx + vz * vz;
                double t = l2 < 1e-6 ? 0 : Math.max(0, Math.min(1, ((p[0] - st.x1()) * vx + (p[1] - st.z1()) * vz) / l2));
                double qx = st.x1() + vx * t, qz = st.z1() + vz * t;
                double dd = Math.hypot(qx - p[0], qz - p[1]) - st.width() / 2.0;
                if (dd < best) {
                    best = dd;
                    bx = qx;
                    bz = qz;
                }
            }
            if (best < 7 || best > 20) continue;
            Zone z = zoneAt(x, p[0], p[1]);
            Wealth wl = z == Zone.NOBLE ? Wealth.WEALTHY : z == Zone.CENTRE || z == Zone.COMMON ? Wealth.COMMON : Wealth.POOR;
            Use use = wl == Wealth.POOR ? (x.r.nextBoolean() ? Use.HOVEL : Use.COTTAGE) : wl == Wealth.COMMON ? Use.COTTAGE : Use.HOUSE;
            int[] sz = use.size(wl, x.r);
            Direction face = cardinal(bx - p[0], bz - p[1]);
            Spec s = plan.at(use.archetype(), p[0], p[1], face).size(sz[0], sz[1]);
            s.use(use, wl);
            s.margin = 1;
            if (!tryPlace(x, s)) continue;
            // a footpath from the door to the street
            int fx = face.getStepX(), fz = face.getStepZ();
            int half = (face.getAxis() == Direction.Axis.Z ? sz[1] : sz[0]) / 2;
            int sx = p[0] + fx * (half + 2), sz2 = p[1] + fz * (half + 2);
            int ex = (int) Math.round(bx), ez = (int) Math.round(bz);
            Spec path = plan.line(Archetype.STREET, sx, sz2, fx != 0 ? ex : sx, fz != 0 ? ez : sz2, 1);
            if (plan.fits(path, 0)) plan.add(path);
        }
    }

    static void gapFiller(Ctx x, Street st, int side, double t) {
        int kind = switch (x.r.nextInt(6)) {
            case 0 -> Outdoors.WOODPILE;
            case 1 -> Outdoors.FLOWER_BED;
            case 2 -> Outdoors.BARRELS;
            case 3 -> Outdoors.HAY;
            case 4 -> Outdoors.TREE;
            default -> Outdoors.CRATES;
        };
        int w = kind == Outdoors.TREE ? 5 : 3, d = kind == Outdoors.TREE ? 5 : 2;
        double len = st.len();
        double ux = (st.x2() - st.x1()) / len, uz = (st.z2() - st.z1()) / len;
        double nx = -uz * side, nz = ux * side;
        double off = st.width() / 2.0 + 1.5 + d / 2.0;
        int lx = (int) Math.round(st.x1() + ux * t + nx * off), lz = (int) Math.round(st.z1() + uz * t + nz * off);
        if (Math.hypot(lx, lz) > x.R) return;
        yard(x, kind, lx, lz, cardinal(-nx, -nz), w, d, 0);
    }

    /** Places a yard prop (street furniture / clutter) if it fits. */
    static Spec yard(Ctx x, int kind, int lx, int lz, Direction face, int w, int d, int gap) {
        Spec s = x.plan.at(Archetype.YARD, lx, lz, face).size(w, d);
        s.variant = kind;
        s.margin = 0;
        s.h = kind == Outdoors.TREE ? 8 : 5;
        return x.plan.place(s, gap);
    }

    static List<Street> squareEdges(Ctx x) {
        List<Street> out = new ArrayList<>();
        for (int[] sq : x.sk.squares) {
            int h = sq[2] / 2;
            // four edges, walked so that "side +1" lies outside the square
            out.add(new Street(sq[0] - h, sq[1] - h, sq[0] + h, sq[1] - h, 1, 3));
            out.add(new Street(sq[0] + h, sq[1] - h, sq[0] + h, sq[1] + h, 1, 3));
            out.add(new Street(sq[0] + h, sq[1] + h, sq[0] - h, sq[1] + h, 1, 3));
            out.add(new Street(sq[0] - h, sq[1] + h, sq[0] - h, sq[1] - h, 1, 3));
        }
        return out;
    }

    static void squareFrontage(Ctx x) {
        for (Street e : squareEdges(x)) frontage(x, e, -1);
    }

    // ------------------------------------------------------------------ squares and street life

    static void square(Ctx x, int[] sq, boolean main) {
        Plan plan = x.plan;
        int size = sq[2];
        Spec s = plan.at(Archetype.PLAZA, sq[0], sq[1], Direction.SOUTH).size(size, size);
        s.margin = 0;
        Culture c = plan.culture;
        s.variant = x.sk.form == Form.RING ? 2 : x.tier >= 3 && main ? 1 : x.tier >= 2 && x.r.nextBoolean() ? 3 : 0;
        if (c == Culture.BOSMER || c == Culture.ARGONIAN) s.variant = 2;
        plan.put(s);
        int h = size / 2;
        // a fountain in towns and cities instead of the well, a notice board, benches, a tree
        if (x.tier >= 2 && main && s.variant != 2) {
            Spec f = plan.at(Archetype.YARD, sq[0] + (x.r.nextBoolean() ? 4 : -4), sq[1] + 4, Direction.NORTH).size(5, 5);
            f.variant = Outdoors.FOUNTAIN;
            f.margin = 0;
            plan.put(f);
        }
        if (x.tier >= 1) {
            Spec nb = plan.at(Archetype.YARD, sq[0] - h + 2, sq[1] - h + 1, Direction.SOUTH).size(3, 1);
            nb.variant = Outdoors.NOTICE_BOARD;
            nb.margin = 0;
            plan.put(nb);
        }
        // market stalls round the inside of the square
        if (x.tier >= 1 && main) {
            int stalls = x.tier >= 3 ? 8 : x.tier == 2 ? 5 : 2;
            int placed = 0;
            for (int k = 0; k < 16 && placed < stalls; k++) {
                int edge = k % 4, along = -h + 3 + (k / 4) * 4;
                if (along > h - 3) continue;
                int lx = switch (edge) {
                    case 0, 2 -> sq[0] + along;
                    case 1 -> sq[0] + h - 2;
                    default -> sq[0] - h + 2;
                };
                int lz = switch (edge) {
                    case 0 -> sq[1] - h + 2;
                    case 2 -> sq[1] + h - 2;
                    default -> sq[1] + along;
                };
                if (Math.abs(lx - sq[0]) <= 3 && Math.abs(lz - sq[1]) <= 3) continue;
                Direction face = switch (edge) {
                    case 0 -> Direction.SOUTH;
                    case 1 -> Direction.WEST;
                    case 2 -> Direction.NORTH;
                    default -> Direction.EAST;
                };
                Spec st = plan.at(Archetype.STALL, lx, lz, face).size(3, 3);
                st.margin = 0;
                boolean clash = false;
                for (Spec o : plan.specs) if (o.kind == Archetype.STALL || o.kind == Archetype.YARD) if (o.overlaps(st, 1)) clash = true;
                if (clash) continue;
                plan.put(st);
                placed++;
            }
        }
    }

    /** Street furniture: lamps along the main streets, carts and vendors near the market, signposts and shrines at junctions, braziers at the gates. */
    static void lining(Ctx x) {
        Plan plan = x.plan;
        boolean lamps = x.tier >= 1;
        for (Street st : x.sk.streets) {
            double len = st.len();
            if (len < 6) continue;
            double ux = (st.x2() - st.x1()) / len, uz = (st.z2() - st.z1()) / len;
            int every = st.rank() == 0 ? 10 + x.r.nextInt(3) : 14 + x.r.nextInt(4);
            int side = 1;
            for (double t = every / 2.0; t < len - 2; t += every) {
                side = -side;
                double nx = -uz * side, nz = ux * side;
                double off = st.width() / 2.0 + 1;
                int lx = (int) Math.round(st.x1() + ux * t + nx * off), lz = (int) Math.round(st.z1() + uz * t + nz * off);
                int kind;
                if (lamps && (st.rank() <= 1)) kind = x.style.culture.equals("nord") && x.r.nextInt(3) == 0 ? Outdoors.BRAZIER : Outdoors.LAMP_POST;
                else kind = x.r.nextInt(3) == 0 ? Outdoors.BARRELS : x.r.nextBoolean() ? Outdoors.WOODPILE : Outdoors.SIGNPOST;
                if (yard(x, kind, lx, lz, cardinal(-nx, -nz), 1, 1, 0) == null) {
                    // nudge along the street to the next free spot
                    for (int k = 1; k <= 3; k++) {
                        int ax = (int) Math.round(st.x1() + ux * (t + k) + nx * off), az = (int) Math.round(st.z1() + uz * (t + k) + nz * off);
                        if (yard(x, kind, ax, az, cardinal(-nx, -nz), 1, 1, 0) != null) break;
                    }
                }
            }
        }
        // carts and vendors along the main street near the square, benches by the square
        if (!x.sk.squares.isEmpty()) {
            int[] sq = x.sk.squares.get(0);
            int carts = 1 + x.tier;
            for (int i = 0; i < carts * 4 && carts > 0; i++) {
                double a = x.r.nextDouble() * Math.PI * 2;
                double rad = sq[2] / 2.0 + 3 + x.r.nextInt(8);
                int lx = sq[0] + (int) Math.round(Math.sin(a) * rad), lz = sq[1] + (int) Math.round(Math.cos(a) * rad);
                int kind = i % 3 == 0 ? Outdoors.VENDOR : i % 3 == 1 ? Outdoors.CART : Outdoors.BENCH;
                if (yard(x, kind, lx, lz, cardinal(sq[0] - lx, sq[1] - lz), 3, 1, 1) != null) carts--;
            }
        }
        // signposts or wayside shrines where lanes meet the main streets
        for (Street st : x.sk.streets) {
            if (st.rank() != 1 || x.r.nextInt(3) != 0) continue;
            int kind = x.r.nextBoolean() ? Outdoors.SHRINE : Outdoors.SIGNPOST;
            yard(x, kind, st.x1() + (st.z1() == st.z2() ? 0 : 2), st.z1() + (st.z1() == st.z2() ? 2 : 0), Direction.SOUTH, 1, 1, 0);
        }
        // town dogs and cats, coops between houses
        int pets = 2 + x.tier * 2;
        for (int i = 0; i < pets * 3 && pets > 0; i++) {
            Spec b = x.buildings.isEmpty() ? null : x.buildings.get(x.r.nextInt(x.buildings.size()));
            if (b == null) break;
            int lx = localX(plan, b.x, b.z) + (x.r.nextBoolean() ? 1 : -1) * (b.halfX() + 2);
            int lz = localZ(plan, b.x, b.z) + x.r.nextInt(5) - 2;
            int kind = x.r.nextInt(3) == 0 ? Outdoors.COOP : Outdoors.KENNEL;
            if (yard(x, kind, lx, lz, Direction.SOUTH, kind == Outdoors.COOP ? 4 : 2, kind == Outdoors.COOP ? 4 : 2, 0) != null) pets--;
        }
        // near the barracks and guardhouses: a training dummy
        for (Spec b : x.buildings) {
            if (b.use != Use.GUARDHOUSE.ordinal() && b.use != Use.BARRACKS.ordinal()) continue;
            yard(x, Outdoors.DUMMY, localX(plan, b.x, b.z) + b.halfX() + 2, localZ(plan, b.x, b.z), Direction.SOUTH, 2, 2, 0);
        }
    }

    /** Behind every home: a kitchen garden, a pen, a woodpile, a laundry line or flowers. */
    static void backyards(Ctx x) {
        Plan plan = x.plan;
        for (Spec b : new ArrayList<>(x.buildings)) {
            Use u = Use.byOrdinal(b.use);
            if (!u.home() && u != Use.INN && u != Use.TANNERY && u != Use.BUTCHER) continue;
            Direction back = b.facing.getOpposite();
            int depth = (back.getAxis() == Direction.Axis.Z ? b.d : b.w) / 2 + 1;
            Wealth wl = Wealth.byOrdinal(b.wealth);
            int kind;
            int w = 5, d = 5;
            int roll = x.r.nextInt(6);
            if (wl.atLeast(Wealth.WEALTHY)) kind = roll < 3 ? -1 : roll < 5 ? Outdoors.FLOWER_BED : Outdoors.TREE;
            else kind = switch (roll) {
                case 0, 1 -> -1;
                case 2 -> -2;
                case 3 -> Outdoors.LAUNDRY;
                case 4 -> Outdoors.WOODPILE;
                default -> Outdoors.COOP;
            };
            if (kind == Outdoors.LAUNDRY) {
                w = 5;
                d = 1;
            } else if (kind == Outdoors.WOODPILE) {
                w = 4;
                d = 2;
            } else if (kind == Outdoors.COOP) {
                w = 4;
                d = 4;
            }
            int off = depth + 1 + d / 2;
            int bx = b.x + back.getStepX() * off, bz = b.z + back.getStepZ() * off;
            Spec y;
            if (kind == -1) {
                y = Spec.at(Archetype.GARDEN, bx, bz, b.facing);
                y.size(w, d);
                y.variant = cropVariant(x);
            } else if (kind == -2) {
                y = Spec.at(Archetype.PEN, bx, bz, b.facing);
                y.size(7, 5);
                y.variant = x.r.nextInt(8);
            } else {
                y = Spec.at(Archetype.YARD, bx, bz, b.facing);
                y.size(w, d);
                y.variant = kind;
                y.h = 6;
            }
            y.margin = 0;
            plan.place(y, 0);
        }
    }

    static int cropVariant(Ctx x) {
        List<String> crops = x.style.crops();
        if (crops.isEmpty()) return x.r.nextInt(8);
        String c = crops.get(x.r.nextInt(crops.size()));
        return switch (c) {
            case "carrots" -> 1;
            case "potatoes", "ash_yam" -> 2;
            case "beetroots" -> 3;
            case "cabbage" -> 4;
            case "leek" -> 5;
            case "garlic" -> 6;
            case "tomato" -> 7;
            default -> 0;
        };
    }

    // ------------------------------------------------------------------ walls

    enum WallPolicy { NONE, PALISADE_PARTIAL, PALISADE, STONE_PARTIAL, STONE }

    static WallPolicy wallPolicy(Ctx x) {
        Culture c = x.plan.culture;
        RandomSource r = x.r;
        boolean wood = c == Culture.ORC || c == Culture.BOSMER || c == Culture.ARGONIAN || c == Culture.ATMORAN;
        return switch (x.tier) {
            case 3 -> wood ? WallPolicy.PALISADE : r.nextInt(5) == 0 ? WallPolicy.STONE_PARTIAL : WallPolicy.STONE;
            case 2 -> switch (c) {
                case IMPERIAL, BRETON, REDGUARD, ALTMER, YOKUDAN, MAORMER, AKAVIRI -> r.nextBoolean() ? WallPolicy.STONE_PARTIAL : WallPolicy.NONE;
                case DUNMER -> r.nextInt(3) == 0 ? WallPolicy.STONE_PARTIAL : WallPolicy.NONE;
                case NORD -> r.nextInt(5) < 2 ? WallPolicy.PALISADE_PARTIAL : WallPolicy.NONE;
                case ORC -> WallPolicy.PALISADE;
                default -> WallPolicy.NONE;
            };
            case 1 -> c == Culture.NORD && r.nextInt(10) < 3 || c == Culture.ORC ? WallPolicy.PALISADE_PARTIAL : WallPolicy.NONE;
            default -> WallPolicy.NONE;
        };
    }

    /**
     * Walls round the built area as an organic polygon (the outermost buildings in each direction, plus a few blocks,
     * smoothed and jittered), with gatehouses where the main streets leave; partial walls only close the approaches and
     * leave water and empty sides open. Settlements without walls mark their road ends with a signpost (the gate anchor).
     */
    static void walls(Ctx x) {
        Plan plan = x.plan;
        WallPolicy pol = wallPolicy(x);
        if (x.sk.form == Form.CANAL && pol != WallPolicy.NONE) pol = WallPolicy.NONE;
        int n = 9 + x.tier + x.r.nextInt(4);
        double[] rad = new double[n];
        boolean[] built = new boolean[n];
        for (int i = 0; i < n; i++) rad[i] = x.R * 0.55;
        for (Spec s : plan.specs) {
            if (s.isLine() || s.kind == Archetype.YARD || s.kind == Archetype.GARDEN || s.kind == Archetype.PEN) continue;
            int lx = localX(plan, s.x, s.z), lz = localZ(plan, s.x, s.z);
            double a = Math.atan2(lx, lz);
            int k = Math.floorMod((int) Math.round(a / (2 * Math.PI) * n), n);
            double dist = Math.hypot(lx, lz) + Math.max(s.halfX(), s.halfZ()) + 6;
            if (dist > x.R + 14) continue;
            for (int dk = -1; dk <= 1; dk++) {
                int kk = Math.floorMod(k + dk, n);
                rad[kk] = Math.max(rad[kk], dist);
                if (dk == 0) built[kk] = true;
            }
        }
        // smooth and jitter: never a circle
        double[] sm = new double[n];
        for (int i = 0; i < n; i++) sm[i] = (rad[Math.floorMod(i - 1, n)] + 4 * rad[i] + rad[(i + 1) % n]) / 6 + x.r.nextInt(9) - 2;
        for (int i = 0; i < n; i++) sm[i] = Math.max(sm[i], rad[i]);
        // gate angles: road ends
        List<double[]> gates = new ArrayList<>();
        for (int[] g : x.sk.gates) gates.add(new double[]{Math.atan2(g[0], g[1]), Math.hypot(g[0], g[1])});
        if (pol == WallPolicy.NONE) {
            for (int[] g : x.sk.gates) {
                Spec sp = yard(x, Outdoors.SIGNPOST, g[0] + 3, g[1], Direction.SOUTH, 1, 1, 0);
                if (sp == null) {
                    sp = plan.put(plan.at(Archetype.YARD, g[0] + 3, g[1], Direction.SOUTH).size(1, 1));
                    sp.variant = Outdoors.SIGNPOST;
                    sp.margin = 0;
                }
                sp.flags |= Spec.MAJOR;
                Spec br = yard(x, Outdoors.BRAZIER, g[0] - 3, g[1], Direction.SOUTH, 1, 1, 0);
                if (br == null) yard(x, Outdoors.LAMP_POST, g[0] - 3, g[1] + 1, Direction.SOUTH, 1, 1, 0);
            }
            return;
        }
        boolean stone = pol == WallPolicy.STONE || pol == WallPolicy.STONE_PARTIAL;
        boolean partial = pol == WallPolicy.STONE_PARTIAL || pol == WallPolicy.PALISADE_PARTIAL;
        int flags = (stone ? 0 : Spec.PALISADE) | (stone || x.tier >= 3 ? Spec.MAJOR : 0);
        int h = stone ? (x.tier >= 3 ? 10 : 8) : 5;
        // vertices with the gates inserted at their exact road ends
        List<double[]> verts = new ArrayList<>();
        for (int i = 0; i < n; i++) verts.add(new double[]{2 * Math.PI * (i + (x.r.nextDouble() - 0.5) * 0.5) / n, sm[i], 0});
        for (double[] g : gates) {
            double a = g[0] < 0 ? g[0] + 2 * Math.PI : g[0];
            verts.add(new double[]{a, Math.max(g[1], 12), 1});
        }
        verts.sort(Comparator.comparingDouble(v -> v[0]));
        int m = verts.size();
        Direction wl = x.sk.water;
        for (int i = 0; i < m; i++) {
            double[] a = verts.get(i), b = verts.get((i + 1) % m);
            double ax = Math.sin(a[0]) * a[1], az = Math.cos(a[0]) * a[1];
            double bx = Math.sin(b[0]) * b[1], bz = Math.cos(b[0]) * b[1];
            // trim the ends at gates: the gatehouse stands there
            if (a[2] == 1) {
                double len = Math.hypot(bx - ax, bz - az);
                if (len < 8) continue;
                ax += (bx - ax) / len * 6;
                az += (bz - az) / len * 6;
            }
            if (b[2] == 1) {
                double len = Math.hypot(bx - ax, bz - az);
                if (len < 8) continue;
                bx -= (bx - ax) / len * 6;
                bz -= (bz - az) / len * 6;
            }
            double mid = (a[0] + b[0] + (b[0] < a[0] ? 2 * Math.PI : 0)) / 2;
            int sector = Math.floorMod((int) Math.round(mid / (2 * Math.PI) * n), n);
            if (partial) {
                // only the approaches: sectors within 70 degrees of a gate, never the water side, never an empty side
                double near = Math.PI;
                for (double[] g : gates) near = Math.min(near, Math.abs(Math.atan2(Math.sin(mid - g[0]), Math.cos(mid - g[0]))));
                if (near > Math.toRadians(70) || !built[sector] && x.r.nextBoolean()) continue;
            }
            if (wl != null && x.sk.waterDist < x.R + 12) {
                int[] wv = rot(-1, 0, quarter(wl));
                double dot = Math.sin(mid) * wv[0] + Math.cos(mid) * wv[1];
                if (dot > 0.6) continue;
            }
            // terrain: leave the wall out where the ground is wet at its middle
            int wx = plan.wx((int) Math.round((ax + bx) / 2), (int) Math.round((az + bz) / 2)), wz = plan.wz((int) Math.round((ax + bx) / 2), (int) Math.round((az + bz) / 2));
            if (plan.terrain.wet(wx, wz)) continue;
            double segLen = Math.hypot(bx - ax, bz - az);
            int parts = Math.max(1, (int) Math.ceil(segLen / 22.0));
            Direction out = Plan.outward(mid);
            for (int k = 0; k < parts; k++) {
                double t0 = k / (double) parts, t1 = (k + 1) / (double) parts;
                Spec w = plan.line(Archetype.WALL, (int) Math.round(ax + (bx - ax) * t0), (int) Math.round(az + (bz - az) * t0),
                        (int) Math.round(ax + (bx - ax) * t1), (int) Math.round(az + (bz - az) * t1), 3);
                w.h = h;
                w.facing = plan.dir(out);
                w.flags |= flags;
                plan.add(w);
            }
            // a tower at some corners of stone walls
            if (stone && b[2] == 0 && x.r.nextInt(3) > 0) {
                Spec t = plan.at(Archetype.TOWER, (int) Math.round(Math.sin(b[0]) * b[1]), (int) Math.round(Math.cos(b[0]) * b[1]), Plan.outward(b[0])).size(7, 7);
                t.h = h + 12;
                t.margin = 1;
                plan.add(t);
            }
        }
        for (double[] g : gates) {
            double a = g[0];
            int lx = (int) Math.round(Math.sin(a) * g[1]), lz = (int) Math.round(Math.cos(a) * g[1]);
            Spec gate = plan.at(Archetype.GATE, lx, lz, Plan.outward(a)).size(stone ? 11 : 9, 7);
            gate.h = h + (stone ? 5 : 3);
            gate.flags |= flags & Spec.PALISADE;
            gate.margin = 1;
            plan.add(gate);
        }
    }

    // ------------------------------------------------------------------ outskirts

    static void outskirts(Ctx x, String variant) {
        Plan plan = x.plan;
        int R = x.R;
        int t = x.tier;
        // the stables and carriage stop outside the main gate
        if (t >= 2 && !x.sk.gates.isEmpty()) {
            int[] g = x.sk.gates.get(0);
            double a = Math.atan2(g[0], g[1]);
            double ox = Math.sin(a), oz = Math.cos(a);
            for (int side = -1; side <= 1; side += 2) {
                int lx = (int) Math.round(g[0] + ox * 12 + oz * side * 11), lz = (int) Math.round(g[1] + oz * 12 - ox * side * 11);
                Spec s = plan.at(Archetype.BUILDING, lx, lz, cardinal(-oz * side, ox * side)).size(11, 11);
                s.use(Use.STABLES, Wealth.COMMON);
                s.margin = 2;
                stamp(x, s);
                if (plan.place(s, 1) != null) break;
            }
        }
        int fields = t == 0 ? 2 + x.r.nextInt(2) : t == 1 ? 3 + x.r.nextInt(3) : t == 2 ? 4 + x.r.nextInt(3) : 5;
        int farms = t == 0 ? 0 : t;
        if (plan.culture == Culture.BOSMER) {
            // the Green Pact: no fields, no farms; hunters' pens and hives instead
            fields = 0;
            farms = 0;
        }
        for (int i = 0; i < farms; i++) farm(x, R + 12, R + 34);
        for (int i = 0; i < fields; i++) field(x, R + 6, Math.min(plan.radius - 4, R + 38));
        // pens with livestock
        for (int i = 0; i < 1 + t; i++) {
            Spec pen = outside(x, Archetype.PEN, 9 + x.r.nextInt(3) * 2, 7 + x.r.nextInt(2) * 2, R + 4, R + 26);
            if (pen != null) pen.variant = x.r.nextInt(8);
        }
        // a mill on the water, else a windmill on open ground for the farming cultures
        Culture c = plan.culture;
        boolean water = x.sk.water != null && x.sk.waterDist < R + 30;
        if (t >= 1 && (water || c == Culture.IMPERIAL || c == Culture.BRETON || c == Culture.NORD && x.r.nextInt(3) == 0)) {
            Spec mill = water ? outsideToward(x, Use.MILL, x.sk.water, R + 2, R + 30) : outsideUse(x, Use.MILL, R + 8, R + 30);
            if (mill != null) mill.variant = water ? 1 : 0;
        }
        // lumber where the culture fells trees, a mine toward the hills for mining towns
        if (t >= 1 && c != Culture.BOSMER && x.r.nextInt(3) > 0) {
            Spec saw = outsideUse(x, Use.SAWMILL, R + 6, R + 30);
            if (saw != null) {
                int lx = localX(plan, saw.x, saw.z), lz = localZ(plan, saw.x, saw.z);
                Spec yardSpec = plan.at(Archetype.CAMP, lx + (lx > 0 ? 12 : -12), lz, Direction.SOUTH).size(11, 9);
                yardSpec.variant = Outdoors.LOG_YARD;
                yardSpec.margin = 0;
                plan.place(yardSpec, 1);
            }
        }
        if (variant.equals("mining") || t >= 1 && x.sk.hill && x.r.nextBoolean()) {
            Spec mine = outside(x, Archetype.MINE, 7, 14, R + 6, Math.min(plan.radius - 2, R + 32));
            if (mine != null) {
                mine.flag(Spec.NO_BEARD);
                mine.variant = x.r.nextInt(7);
            }
        }
        if (t >= 2) {
            Spec g = outside(x, Archetype.GRAVEYARD, 13, 11, R + 4, R + 22);
            if (g != null) g.margin = 0;
        }
        // farm cultures' specialities: vines, moon sugar, saltrice, hives
        for (String crop : x.style.crops()) {
            int kind = switch (crop) {
                case "vines" -> Outdoors.VINEYARD;
                case "sugar" -> Outdoors.SUGAR;
                case "saltrice" -> Outdoors.PADDY;
                case "honey" -> Outdoors.APIARY;
                case "orchard" -> Outdoors.ORCHARD;
                default -> -1;
            };
            if (kind < 0 || t == 0 && x.r.nextBoolean()) continue;
            Spec camp = outside(x, Archetype.CAMP, 13, 11, R + 8, R + 36);
            if (camp != null) camp.variant = kind;
        }
    }

    static boolean nearGateRay(Ctx x, double lx, double lz) {
        for (int[] g : x.sk.gates) {
            double a = Math.atan2(g[0], g[1]);
            double ux = Math.sin(a), uz = Math.cos(a);
            double along = lx * ux + lz * uz;
            double across = Math.abs(lx * uz - lz * ux);
            if (along > 0 && across < 9) return true;
        }
        return false;
    }

    static Spec outside(Ctx x, Archetype kind, int w, int d, double r0, double r1) {
        for (int tries = 0; tries < 24; tries++) {
            double a = x.r.nextDouble() * Math.PI * 2;
            double rad = r0 + x.r.nextDouble() * Math.max(1, r1 - r0);
            int lx = (int) Math.round(Math.sin(a) * rad), lz = (int) Math.round(Math.cos(a) * rad);
            if (nearGateRay(x, lx, lz)) continue;
            Spec s = x.plan.at(kind, lx, lz, Plan.outward(a + Math.PI), w, d);
            s.margin = kind == Archetype.MINE ? 1 : 1;
            if (x.plan.place(s, 2) != null) return s;
        }
        return null;
    }

    static Spec outsideUse(Ctx x, Use use, double r0, double r1) {
        int[] sz = use.size(Wealth.COMMON, x.r);
        for (int tries = 0; tries < 24; tries++) {
            double a = x.r.nextDouble() * Math.PI * 2;
            double rad = r0 + x.r.nextDouble() * Math.max(1, r1 - r0);
            int lx = (int) Math.round(Math.sin(a) * rad), lz = (int) Math.round(Math.cos(a) * rad);
            if (nearGateRay(x, lx, lz)) continue;
            Spec s = x.plan.at(use.archetype(), lx, lz, Plan.outward(a + Math.PI), sz[0], sz[1]);
            s.use(use, Wealth.COMMON);
            s.margin = 2;
            stamp(x, s);
            if (x.plan.place(s, 2) != null) {
                x.buildings.add(s);
                return s;
            }
        }
        return null;
    }

    static Spec outsideToward(Ctx x, Use use, Direction localDir, double r0, double r1) {
        int[] sz = use.size(Wealth.COMMON, x.r);
        int[] v = rot(-1, 0, quarter(localDir));
        double base = Math.atan2(v[0], v[1]);
        for (int tries = 0; tries < 24; tries++) {
            double a = base + (x.r.nextDouble() - 0.5) * 1.2;
            double rad = Math.min(r1, Math.max(r0, x.sk.waterDist - 4 - sz[1] / 2.0)) + x.r.nextInt(5) - 2;
            int lx = (int) Math.round(Math.sin(a) * rad), lz = (int) Math.round(Math.cos(a) * rad);
            Spec s = x.plan.at(use.archetype(), lx, lz, Plan.outward(a + Math.PI), sz[0], sz[1]);
            s.use(use, Wealth.COMMON);
            s.margin = 4;
            stamp(x, s);
            if (x.plan.place(s, 2) != null) {
                x.buildings.add(s);
                return s;
            }
        }
        return outsideUse(x, use, r0, r1);
    }

    static void field(Ctx x, double r0, double r1) {
        Spec f = outside(x, Archetype.FIELD, 11 + 2 * x.r.nextInt(3), 9 + 2 * x.r.nextInt(3), r0, r1);
        if (f != null) f.variant = cropVariant(x);
    }

    /** A farmhouse with its barn, a field and a pen, out past the houses. */
    static void farm(Ctx x, double r0, double r1) {
        Spec h = outsideUse(x, Use.FARMHOUSE, r0, r1);
        if (h == null) return;
        int lx = localX(x.plan, h.x, h.z), lz = localZ(x.plan, h.x, h.z);
        double a = Math.atan2(lx, lz);
        double tx = Math.cos(a), tz = -Math.sin(a);
        Spec barn = x.plan.at(Archetype.BUILDING, (int) Math.round(lx + tx * 14), (int) Math.round(lz + tz * 14), h.facing == null ? Direction.SOUTH : localFacing(x, h)).size(9, 13);
        barn.use(Use.BARN, Wealth.COMMON);
        barn.margin = 1;
        stamp(x, barn);
        if (x.plan.place(barn, 1) != null) x.buildings.add(barn);
        Spec f = x.plan.at(Archetype.FIELD, (int) Math.round(lx - tx * 15), (int) Math.round(lz - tz * 15), Direction.SOUTH).size(13, 11);
        f.variant = cropVariant(x);
        x.plan.place(f, 1);
    }

    static Direction localFacing(Ctx x, Spec s) {
        Direction f = s.facing;
        return switch (x.plan.back) {
            case EAST -> f.getCounterClockWise();
            case SOUTH -> f.getOpposite();
            case WEST -> f.getClockWise();
            default -> f;
        };
    }

    /** An isolated farmstead (the FARM size): house, barn, fields, pens, a garden and a woodpile. */
    static void farmstead(Plan plan) {
        Ctx x = new Ctx();
        x.plan = plan;
        x.size = Layouts.Size.FARM;
        x.tier = 0;
        x.R = 10;
        x.style = Styles.of(plan.culture, plan.style);
        x.r = RandomSource.create(plan.seed ^ 0xFA4AL);
        x.sk = new Skeleton();
        x.sk.radius = 10;
        Spec h = plan.at(Archetype.FARMHOUSE, 0, 0, Direction.SOUTH).size(9, 9);
        h.use(Use.FARMHOUSE, x.r.nextInt(4) == 0 ? Wealth.WEALTHY : Wealth.COMMON);
        h.margin = 3;
        stamp(x, h);
        plan.put(h);
        x.buildings.add(h);
        Spec barn = plan.at(Archetype.BUILDING, x.r.nextBoolean() ? -15 : 15, 2, x.r.nextBoolean() ? Direction.SOUTH : Direction.EAST).size(9, 13);
        barn.use(Use.BARN, Wealth.COMMON);
        stamp(x, barn);
        plan.place(barn, 2);
        plan.street(0, 6, 0, plan.radius, 3, false);
        x.sk.gates.add(new int[]{0, plan.radius});
        int fields = 2 + x.r.nextInt(2);
        for (int i = 0; i < fields; i++) field(x, 10, plan.radius);
        Spec pen = outside(x, Archetype.PEN, 9, 7, 8, plan.radius - 4);
        if (pen != null) pen.variant = x.r.nextInt(8);
        Spec g = plan.at(Archetype.GARDEN, 9, -5, Direction.SOUTH);
        g.variant = cropVariant(x);
        plan.place(g, 1);
        yard(x, Outdoors.WOODPILE, -6, -6, Direction.SOUTH, 4, 2, 0);
        yard(x, Outdoors.COOP, 7, 6, Direction.SOUTH, 4, 4, 0);
        variety(plan);
    }

    // ------------------------------------------------------------------ reserved lots (other modules)

    static void reserved(Ctx x) {
        Plan plan = x.plan;
        List<SettlementPlan.Slot> slots = SettlementPlan.slots();
        if (slots.isEmpty()) return;
        SettlementPlan.Where where = where(plan, x.size);
        for (SettlementPlan.Slot slot : slots) {
            boolean ok;
            try {
                ok = slot.where().test(where);
            } catch (RuntimeException e) {
                ok = false;
            }
            if (!ok) continue;
            Zone z = switch (slot.quarter()) {
                case CENTRE -> Zone.CENTRE;
                case NOBLE -> Zone.NOBLE;
                case POOR -> Zone.POOR;
                case CRAFT -> Zone.CRAFT;
                case GATE -> Zone.GATE;
                default -> Zone.COMMON;
            };
            Want w = new Want(Use.GUILD_HALL, Wealth.COMMON, z, "");
            int before = plan.specs.size();
            placeReserved(x, slot, w);
            if (plan.specs.size() == before) com.skycraft.Skycraft.LOGGER.debug("No lot for reserved {} in {}", slot.role(), plan.key);
        }
    }

    static void placeReserved(Ctx x, SettlementPlan.Slot slot, Want want) {
        List<Street> all = new ArrayList<>(x.sk.streets);
        int[] anchor = want.zone() == Zone.NOBLE ? x.noble : want.zone() == Zone.CRAFT ? x.craft : want.zone() == Zone.POOR ? x.poor
                : x.sk.squares.isEmpty() ? new int[]{0, 0} : x.sk.squares.get(0);
        List<double[]> cands = new ArrayList<>();
        for (int si = 0; si < all.size(); si++) {
            Street st = all.get(si);
            double len = st.len();
            for (double t = 3; t < len - 3; t += 2) {
                double px = st.x1() + (st.x2() - st.x1()) * t / len, pz = st.z1() + (st.z2() - st.z1()) * t / len;
                double dist = Math.hypot(px - anchor[0], pz - anchor[1]);
                cands.add(new double[]{dist, si, t, 1});
                cands.add(new double[]{dist + 0.5, si, t, -1});
            }
        }
        cands.sort(Comparator.comparingDouble(a -> a[0]));
        x.plan.relaxed = true;
        int tries = 0;
        for (double[] cd : cands) {
            if (tries++ > 120) break;
            Spec s = lotOn(x, all.get((int) cd[1]), (int) cd[3], cd[2], Use.GUILD_HALL, Wealth.WEALTHY, slot.w(), slot.d(), 1);
            if (s == null) continue;
            s.kind = Archetype.RESERVED;
            s.name = slot.role();
            s.flag(Spec.SIGN);
            if (tryPlace(x, s)) break;
        }
        x.plan.relaxed = false;
    }

    public static SettlementPlan.Where where(Plan plan, Layouts.Size size) {
        String region;
        try {
            region = TamrielMap.region(plan.cx, plan.cz).id();
        } catch (RuntimeException e) {
            region = "";
        }
        boolean named = !plan.key.startsWith("gen:");
        boolean capital = named && com.skycraft.tamriel.Places.byId(plan.key).map(p -> p.type() == com.skycraft.tamriel.PlaceType.CAPITAL).orElse(false);
        return new SettlementPlan.Where(plan.key, plan.culture, size.name().toLowerCase(Locale.ROOT), capital, named, region);
    }

    // ------------------------------------------------------------------ adoption of other layouts

    /**
     * Brings a layout drawn by other planners (the unique capitals, special villages) into the grammar: its ordinary
     * buildings become grammar buildings with a wealth tier from their flags and quarter, reserved lots are added on its
     * streets, its streets are lined, and neighbours are kept different.
     */
    public static void adopt(Plan plan, Layouts.Size size) {
        if (plan.zoned) return;
        plan.zoned = true;
        Ctx x = new Ctx();
        x.plan = plan;
        x.size = size;
        x.tier = tier(size);
        x.named = !plan.key.startsWith("gen:");
        x.style = Styles.of(plan.culture, plan.style);
        x.r = RandomSource.create(plan.seed ^ 0xAD09L);
        x.sk = new Skeleton();
        x.R = plan.radius;
        x.sk.radius = plan.radius;
        for (Spec s : plan.specs) {
            if (s.isLine() && s.kind == Archetype.STREET) {
                int ax = localX(plan, s.x, s.z), az = localZ(plan, s.x, s.z), bx = localX(plan, s.x2, s.z2), bz = localZ(plan, s.x2, s.z2);
                x.sk.streets.add(new Street(ax, az, bx, bz, s.w, s.has(Spec.MAJOR) ? 0 : 1));
            }
        }
        x.noble = new int[]{0, -(int) (plan.radius * 0.6)};
        x.craft = new int[]{(int) (plan.radius * 0.5), (int) (plan.radius * 0.3)};
        x.poor = new int[]{-(int) (plan.radius * 0.5), (int) (plan.radius * 0.6)};
        for (Spec s : new ArrayList<>(plan.specs)) {
            if (!s.kind.building || s.grammar() || s.has(Spec.RUINED) || s.has(Spec.RESERVED)) continue;
            if (s.kind == Archetype.PALACE || s.kind == Archetype.TOWER || s.kind == Archetype.TENT || s.kind == Archetype.SHRINE) continue;
            if (s.culture >= 0 && s.culture != plan.culture.ordinal() && s.style >= 0) {
                // a quarter of another culture (Windhelm's Grey Quarter) keeps its kit but still uses the grammar
            }
            int lx = localX(plan, s.x, s.z), lz = localZ(plan, s.x, s.z);
            Wealth w = s.has(Spec.SEAT) || s.kind == Archetype.MANOR && s.has(Spec.RICH) ? Wealth.NOBLE
                    : s.has(Spec.RICH) || s.kind == Archetype.MANOR ? Wealth.WEALTHY : wealthOf(x, zoneAt(x, lx, lz));
            if (s.has(Spec.FOR_SALE)) w = s.kind == Archetype.MANOR ? Wealth.WEALTHY : Wealth.COMMON;
            Use u = Use.of(s.kind, w);
            if (u == null) continue;
            s.use(u, w);
            stamp(x, s);
            x.buildings.add(s);
        }
        if (x.sk.squares.isEmpty()) {
            for (Spec s : plan.specs) if (s.kind == Archetype.PLAZA) x.sk.squares.add(new int[]{localX(plan, s.x, s.z), localZ(plan, s.x, s.z), s.w});
        }
        if (!x.sk.streets.isEmpty() && x.tier >= 1) {
            reserved(x);
            lining(x);
        }
        variety(plan);
        clearing(plan, 0, Math.min(plan.radius, 120));
    }

    /**
     * Clearing tiles over the built area, inserted at {@code at} so they paint before the buildings in every chunk:
     * the forest a town stands in is felled (settlements are placed after the trees have grown).
     */
    static void clearing(Plan plan, int at, int radius) {
        List<Spec> tiles = new ArrayList<>();
        int tw = 31;
        for (int lx = -radius; lx <= radius; lx += tw) {
            for (int lz = -radius; lz <= radius; lz += tw) {
                int cx = lx + tw / 2, cz = lz + tw / 2;
                double near = Math.hypot(Math.max(0, Math.abs(cx) - tw / 2.0), Math.max(0, Math.abs(cz) - tw / 2.0));
                if (near > radius) continue;
                Spec t = plan.at(Archetype.CAMP, cx, cz, Direction.SOUTH).size(tw, tw);
                t.variant = Outdoors.CLEARING;
                t.margin = 0;
                t.h = 28;
                t.flags |= Spec.GROUND | Spec.NO_BEARD;
                t.culture = plan.culture.ordinal();
                t.style = plan.style;
                tiles.add(t);
            }
        }
        plan.specs.addAll(at, tiles);
    }

    // ------------------------------------------------------------------ variety

    /** No two neighbouring grammar buildings share footprint, roof and materials: reseed the later one until they differ. */
    public static void variety(Plan plan) {
        List<Spec> bs = new ArrayList<>();
        for (Spec s : plan.specs) if (s.grammar() && s.kind.building) bs.add(s);
        int[] sig = new int[bs.size()];
        for (int i = 0; i < bs.size(); i++) sig[i] = Blueprint.of(bs.get(i)).signature();
        for (int i = 0; i < bs.size(); i++) {
            Spec a = bs.get(i);
            for (int tries = 0; tries < 12; tries++) {
                boolean clash = false;
                for (int j = 0; j < i; j++) {
                    if (sig[j] != sig[i]) continue;
                    if (neighbours(a, bs.get(j))) {
                        clash = true;
                        break;
                    }
                }
                if (!clash) break;
                a.gseed = a.gseed * 31 + 7919;
                sig[i] = Blueprint.of(a).signature();
            }
        }
    }

    static boolean neighbours(Spec a, Spec b) {
        return Math.abs(a.x - b.x) <= a.halfX() + b.halfX() + 8 && Math.abs(a.z - b.z) <= a.halfZ() + b.halfZ() + 8;
    }

    // ------------------------------------------------------------------ gate prediction

    /** World (x, z) of the gates of a generic plan centred at (cx, cz): main gate first. */
    public static List<int[]> gates(long seed, Culture culture, int style, Layouts.Size size, int cx, int cz, Direction back, int planRadius) {
        Skeleton sk = skeleton(seed, culture, style, size, cx, cz, back, planRadius);
        List<int[]> out = new ArrayList<>();
        for (int[] g : sk.gates) {
            int lx = g[0], lz = g[1];
            int wx = cx + switch (back) {
                case EAST -> -lz;
                case SOUTH -> -lx;
                case WEST -> lz;
                default -> lx;
            };
            int wz = cz + switch (back) {
                case EAST -> lx;
                case SOUTH -> -lz;
                case WEST -> -lx;
                default -> lz;
            };
            out.add(new int[]{wx, wz});
        }
        return out;
    }
}
