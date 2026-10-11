package com.skycraft.tamriel.settlement.plan;

import com.skycraft.tamriel.settlement.Culture;
import com.skycraft.tamriel.settlement.build.Landmarks;
import com.skycraft.tamriel.settlement.kit.Kits;
import com.skycraft.tamriel.settlement.kit.Shell;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Procedural layouts: streets first, then lots along them filled from a queue of required buildings and houses,
 * then fields, walls and the odd extra. Works for every culture; the kit decides what the buildings look like.
 */
public final class Layouts {
    private Layouts() {}

    /** Settlement sizes (structure JSON {@code size}). */
    public enum Size {
        CITY, TOWN, VILLAGE, HAMLET, FARM, INN, HOMESTEAD;

        public static Size of(String s) {
            try {
                return valueOf(s.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return VILLAGE;
            }
        }

        public int radius() {
            return switch (this) {
                case CITY -> 100;
                case TOWN -> 55;
                case VILLAGE -> 40;
                case HAMLET -> 26;
                case FARM -> 24;
                case INN -> 18;
                case HOMESTEAD -> 16;
            };
        }
    }

    /** Lays out a settlement of the given size; {@code variant} is a place variant (mining, mill, stronghold...). */
    public static void generic(Plan plan, Size size, String variant, Names names) {
        RandomSource r = plan.rnd;
        switch (variant) {
            case "stronghold" -> {
                stronghold(plan, names);
                return;
            }
            case "inn" -> {
                roadsideInn(plan, names);
                return;
            }
            case "mill" -> {
                mill(plan, names);
                return;
            }
            case "homestead" -> {
                homestead(plan);
                return;
            }
            case "stables" -> {
                stables(plan);
                return;
            }
            case "estate", "lodge", "embassy" -> {
                estate(plan, variant, names);
                return;
            }
            case "meadery" -> {
                meadery(plan, names);
                return;
            }
            case "mead_hall" -> {
                meadHall(plan, names);
                return;
            }
            case "telvanni" -> {
                telvanni(plan, names);
                return;
            }
            default -> {
            }
        }
        switch (size) {
            case FARM -> farm(plan, r);
            case INN -> roadsideInn(plan, names);
            case HOMESTEAD -> homestead(plan);
            case HAMLET -> village(plan, 1, variant, names);
            case VILLAGE -> village(plan, 2, variant, names);
            case TOWN -> village(plan, 3, variant, names);
            case CITY -> city(plan, variant, names);
        }
    }

    /** Names of a named place's key buildings (inn, store, smithy). Empty strings: unnamed. */
    public record Names(String inn, String store, String smithy) {
        public static final Names NONE = new Names("", "", "");
    }

    // ------------------------------------------------------------------ villages and towns

    /**
     * A street village. {@code tier} 1 hamlet, 2 village, 3 town. Main street through the middle, a cross street in
     * towns, a square with a well (or market), lots along the streets, fields around.
     */
    static void village(Plan plan, int tier, String variant, Names names) {
        RandomSource r = plan.rnd;
        int R = plan.radius;
        int main = (int) (R * 0.95);
        plan.street(0, -main, 0, main, tier >= 3 ? 5 : 3, tier >= 3);
        List<int[]> streets = new ArrayList<>();
        streets.add(new int[]{0, -main, 0, main, tier >= 3 ? 5 : 3});
        if (tier >= 3) {
            int cross = (int) (R * 0.75);
            int off = r.nextInt(11) - 5;
            plan.street(-cross, off, cross, off, 3, false);
            streets.add(new int[]{-cross, off, cross, off, 3});
        }
        if (tier >= 2) {
            // a square in the middle
            Spec sq = plan.at(Archetype.PLAZA, tier >= 3 ? 9 : 7, 0, Direction.WEST).size(tier >= 3 ? 11 : 7, tier >= 3 ? 11 : 7);
            sq.variant = tier >= 3 ? (r.nextInt(3) == 0 ? 1 : 0) : 0;
            sq.margin = 0;
            plan.place(sq, 0);
        }
        Deque<Spec> queue = new ArrayDeque<>();
        boolean mining = variant.equals("mining");
        if (tier >= 2 || r.nextInt(3) == 0) queue.add(named(new Spec(Archetype.INN).size(11, 15), names.inn()));
        if (tier >= 2) queue.add(named(new Spec(Archetype.SMITHY).size(11, 9), names.smithy()));
        if (tier >= 2) queue.add(named(new Spec(Archetype.STORE), names.store()));
        if (tier >= 3) queue.add(new Spec(Archetype.ALCHEMIST));
        if (tier >= 3 && r.nextBoolean()) queue.add(new Spec(Archetype.TEMPLE).size(9, 13).variant(r.nextInt(9)));
        else if (tier >= 2) queue.add(new Spec(Archetype.SHRINE).variant(r.nextInt(9)));
        if (tier >= 3) queue.add(new Spec(Archetype.LONGHOUSE).flag(Spec.SEAT));
        int houses = tier == 1 ? 2 + r.nextInt(3) : tier == 2 ? 5 + r.nextInt(4) : 9 + r.nextInt(6);
        for (int i = 0; i < houses; i++) {
            Spec h = new Spec(i % 4 == 3 && tier >= 2 ? Archetype.FARMHOUSE : Archetype.HOUSE);
            if (tier >= 3 && r.nextInt(4) == 0) h.flag(Spec.UPPER).size(7, 9);
            if (r.nextInt(3) == 0) h.size(9, 9);
            queue.add(h);
        }
        fillStreets(plan, streets, queue, 2);
        // outskirts: fields, a graveyard, a mine or lumber yard
        int fields = tier == 1 ? 1 + r.nextInt(2) : tier == 2 ? 2 + r.nextInt(2) : 3 + r.nextInt(3);
        outskirts(plan, Archetype.FIELD, fields, 13, 11, R * 0.55, R * 0.95);
        if (mining) outskirts(plan, Archetype.MINE, 1, 7, 14, R * 0.6, R * 0.95);
        if (tier >= 3) outskirts(plan, Archetype.GRAVEYARD, 1, 11, 9, R * 0.5, R * 0.9);
        if (tier >= 2 && r.nextBoolean()) outskirts(plan, Archetype.LUMBER, 1, 7, 5, R * 0.4, R * 0.9);
        if (tier >= 3) lamps(plan, 0, -main, 0, main, 14);
        {
            var p = plan.palette();
            if (p.shell == Shell.TENT || plan.culture == Culture.KHAJIIT && tier == 1) {
                // caravan camp
                for (int i = 0; i < 3; i++) {
                    Spec t = plan.at(Archetype.TENT, -10 + i * 9, -10, Direction.SOUTH);
                    plan.place(t, 2);
                }
            }
        }
    }

    static Spec named(Spec s, String name) {
        if (!name.isEmpty()) {
            s.name = name;
            s.flag(Spec.SIGN);
        }
        return s;
    }

    /**
     * Fills lots along the given local streets ({@code {x1, z1, x2, z2}}, axis aligned) with the queued buildings, each
     * facing its street. Buildings that fit nowhere are dropped.
     */
    public static void fillStreets(Plan plan, List<int[]> streets, Deque<Spec> queue, int gap) {
        RandomSource r = plan.rnd;
        // lots: positions along both sides of every street
        List<int[]> lots = new ArrayList<>();
        for (int[] st : streets) {
            boolean alongZ = st[0] == st[2];
            int a0 = alongZ ? Math.min(st[1], st[3]) : Math.min(st[0], st[2]);
            int a1 = alongZ ? Math.max(st[1], st[3]) : Math.max(st[0], st[2]);
            for (int a = a0 + 4; a <= a1 - 4; a += 3) {
                for (int side = -1; side <= 1; side += 2) {
                    lots.add(new int[]{alongZ ? st[0] : a, alongZ ? a : st[1], alongZ ? 1 : 0, side, st.length > 4 ? st[4] / 2 : 2});
                }
            }
        }
        // shuffle lots lightly so buildings mix along the streets (deterministic)
        for (int i = lots.size() - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int[] t = lots.get(i);
            lots.set(i, lots.get(j));
            lots.set(j, t);
        }
        // required buildings prefer lots near the centre
        lots.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
        int idx = 0;
        while (!queue.isEmpty()) {
            Spec s = queue.poll();
            boolean placed = false;
            for (int k = 0; k < lots.size() && !placed; k++) {
                int[] lot = lots.get((idx + k) % lots.size());
                placed = tryLot(plan, s, lot, gap);
                if (placed) idx = (idx + k + 1) % lots.size();
            }
            if (!placed && !plan.key.startsWith("gen:")) {
                // named places: rough ground is no reason to leave a building out
                plan.relaxed = true;
                for (int k = 0; k < lots.size() && !placed; k++) placed = tryLot(plan, s, lots.get(k), gap);
                plan.relaxed = false;
            }
            if (!placed && s.kind == Archetype.HOUSE) {
                // smaller house as a last try
                s.size(5, 7);
                for (int k = 0; k < lots.size() && !placed; k++) placed = tryLot(plan, s, lots.get(k), gap);
            }
        }
    }

    static boolean tryLot(Plan plan, Spec proto, int[] lot, int gap) {
        boolean alongZ = lot[2] == 1;
        int side = lot[3];
        int depth = proto.d, width = proto.w;
        int margin = proto.kind == Archetype.MILL ? 4 : plan.palette().stilts ? 5 : Math.max(1, proto.margin);
        int setback = lot[4] + 1 + margin + depth / 2;
        int lx = alongZ ? lot[0] + side * setback : lot[0];
        int lz = alongZ ? lot[1] : lot[1] + side * setback;
        // the front faces the street
        Direction face = alongZ ? (side > 0 ? Direction.WEST : Direction.EAST) : (side > 0 ? Direction.NORTH : Direction.SOUTH);
        Spec s = plan.at(proto.kind, lx, lz, face).size(width, depth, proto.h);
        s.flags = proto.flags;
        s.variant = proto.variant;
        s.name = proto.name;
        s.margin = margin;
        s.culture = proto.culture;
        s.style = proto.style;
        s.minFloor = proto.minFloor;
        return plan.place(s, gap) != null;
    }

    /** Scatters {@code n} works of {@code kind} in the ring between {@code r0} and {@code r1}. */
    static void outskirts(Plan plan, Archetype kind, int n, int w, int d, double r0, double r1) {
        RandomSource r = plan.rnd;
        int placed = 0;
        for (int tries = 0; tries < n * 16 && placed < n; tries++) {
            double ang = r.nextDouble() * Math.PI * 2;
            double rad = r0 + r.nextDouble() * (r1 - r0);
            int lx = (int) Math.round(Math.sin(ang) * rad), lz = (int) Math.round(Math.cos(ang) * rad);
            Direction face = Plan.outward(ang + Math.PI);
            Spec s = plan.at(kind, lx, lz, face, w, d);
            s.variant = r.nextInt(8);
            if (kind == Archetype.MINE) s.flag(Spec.NO_BEARD);
            if (plan.place(s, 3) != null) placed++;
        }
    }

    static void lamps(Plan plan, int x1, int z1, int x2, int z2, int every) {
        int len = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        for (int i = every / 2; i < len; i += every) {
            double t = i / (double) len;
            int lx = (int) Math.round(x1 + (x2 - x1) * t), lz = (int) Math.round(z1 + (z2 - z1) * t);
            int off = x1 == x2 ? 3 : 0, offz = z1 == z2 ? 3 : 0;
            Spec l = plan.at(Archetype.LAMP, lx + off, lz + offz, Direction.NORTH).size(1, 1);
            l.margin = 0;
            plan.place(l, 0);
        }
    }

    // ------------------------------------------------------------------ cities

    static void city(Plan plan, String variant, Names names) {
        RandomSource r = plan.rnd;
        int R = plan.radius;
        int main = R - 6;
        plan.street(0, -R + 40, 0, main, 5, true);
        plan.street(-main + 10, 0, main - 10, 0, 5, true);
        List<int[]> streets = new ArrayList<>();
        streets.add(new int[]{0, -R + 40, 0, main, 5});
        streets.add(new int[]{-main + 10, 0, main - 10, 0, 5});
        for (int off = -R / 2; off <= R / 2; off += R / 2) {
            if (off == 0) continue;
            int len = (int) Math.sqrt(Math.max(0, (long) main * main - (long) off * off)) - 6;
            plan.street(off, -len, off, len, 3, false);
            streets.add(new int[]{off, -len, off, len, 3});
            plan.street(-len, off, len, off, 3, false);
            streets.add(new int[]{-len, off, len, off, 3});
        }
        Spec market = plan.at(Archetype.PLAZA, 0, 0, Direction.SOUTH).size(17, 17);
        market.variant = 1;
        market.margin = 0;
        plan.put(market);
        for (int i = 0; i < 6; i++) {
            int lx = -12 + (i % 3) * 12, lz = i < 3 ? -12 : 12;
            plan.place(plan.at(Archetype.STALL, lx, lz, Direction.SOUTH).size(3, 3), 1);
        }
        // the palace at the back
        Spec palace = plan.at(Archetype.PALACE, 0, -R + 24, Direction.SOUTH).size(21, 27);
        palace.flags |= Spec.SEAT | Spec.UPPER;
        palace.margin = 2;
        plan.put(palace);
        Deque<Spec> q = new ArrayDeque<>();
        q.add(named(new Spec(Archetype.INN).size(13, 17).flag(Spec.UPPER), names.inn()));
        q.add(new Spec(Archetype.INN).size(11, 15));
        q.add(named(new Spec(Archetype.SMITHY).size(11, 9), names.smithy()));
        q.add(named(new Spec(Archetype.STORE), names.store()));
        q.add(new Spec(Archetype.STORE));
        q.add(new Spec(Archetype.ALCHEMIST));
        q.add(new Spec(Archetype.TEMPLE).size(13, 19).variant(r.nextInt(9)));
        q.add(new Spec(Archetype.HALL_OF_DEAD));
        q.add(new Spec(Archetype.BARRACKS));
        q.add(new Spec(Archetype.BARRACKS));
        for (int i = 0; i < 4; i++) q.add(new Spec(Archetype.MANOR).flag(Spec.UPPER | Spec.RICH));
        for (int i = 0; i < 30; i++) {
            Spec h = new Spec(Archetype.HOUSE);
            if (r.nextInt(3) > 0) h.flag(Spec.UPPER);
            q.add(h);
        }
        fillStreets(plan, streets, q, 2);
        int flags = plan.culture == Culture.ORC || plan.culture == Culture.BOSMER ? Spec.PALISADE | Spec.MAJOR : Spec.MAJOR;
        plan.wallRing(R - 4, 12, 10, new double[]{0, Math.PI / 2, -Math.PI / 2}, flags, true);
        culturalCentrepiece(plan);
    }

    /** A culture's signature building in a generic city (a xanmeer in Black Marsh, a dome in Hammerfell...). */
    static void culturalCentrepiece(Plan plan) {
        int lv = switch (plan.culture) {
            case ARGONIAN -> Landmarks.XANMEER;
            case REDGUARD, YOKUDAN, KHAJIIT -> Landmarks.DOME_TEMPLE;
            case ALTMER, MAORMER -> Landmarks.CRYSTAL_TOWER;
            case BRETON, ORC -> Landmarks.CASTLE;
            case NORD, ATMORAN -> Landmarks.TALOS;
            default -> 0;
        };
        if (lv == 0) return;
        int size = lv == Landmarks.TALOS ? 7 : lv == Landmarks.CRYSTAL_TOWER ? 15 : 23;
        for (int k = 0; k < 8; k++) {
            int lx = (k % 2 == 0 ? 1 : -1) * (22 + k * 4), lz = -20 - k * 3;
            Spec s = plan.at(Archetype.LANDMARK, lx, lz, Direction.SOUTH).size(size, size);
            s.variant = lv;
            s.h = lv == Landmarks.CRYSTAL_TOWER ? 70 : 30;
            if (plan.place(s, 2) != null) return;
        }
    }

    // ------------------------------------------------------------------ small sites

    static void farm(Plan plan, RandomSource r) {
        plan.put(plan.at(Archetype.FARMHOUSE, 0, 0, Direction.SOUTH).size(9, 9));
        Spec barn = plan.at(Archetype.STABLES, -14, 2, Direction.SOUTH).size(11, 9);
        plan.place(barn, 2);
        plan.street(0, 5, 0, plan.radius, 3, false);
        outskirts(plan, Archetype.FIELD, 2 + r.nextInt(2), 13, 11, 10, plan.radius);
        Spec garden = plan.at(Archetype.GARDEN, 9, -4, Direction.SOUTH);
        garden.variant = r.nextInt(8);
        plan.place(garden, 1);
    }

    static void roadsideInn(Plan plan, Names names) {
        Spec inn = plan.at(Archetype.INN, 0, -2, Direction.SOUTH).size(13, 17).flag(Spec.UPPER);
        named(inn, names.inn().isEmpty() ? "" : names.inn());
        plan.put(inn);
        plan.street(-plan.radius, 9, plan.radius, 9, 3, false);
        plan.place(plan.at(Archetype.STABLES, 15, -2, Direction.SOUTH).size(9, 11), 2);
        plan.place(plan.at(Archetype.WELL, -10, 6, Direction.SOUTH).size(3, 3), 1);
        plan.place(plan.at(Archetype.GARDEN, -12, -6, Direction.EAST), 1);
        lamps(plan, -12, 9, 12, 9, 12);
    }

    static void mill(Plan plan, Names names) {
        Spec m = plan.at(Archetype.MILL, 0, 0, Direction.SOUTH).size(9, 11);
        m.margin = 4;
        plan.put(m);
        plan.place(plan.at(Archetype.LUMBER, -12, 4, Direction.SOUTH), 2);
        plan.place(plan.at(Archetype.LUMBER, -12, -6, Direction.SOUTH), 2);
        plan.place(plan.at(Archetype.HOUSE, 12, -8, Direction.WEST), 2);
        plan.place(plan.at(Archetype.HOUSE, 12, 6, Direction.WEST), 2);
        plan.street(0, 7, 0, plan.radius, 3, false);
    }

    static void homestead(Plan plan) {
        Spec plot = plan.at(Archetype.HOMESTEAD_PLOT, 0, 0, Direction.SOUTH).size(23, 23);
        plot.name = plan.key;
        plot.margin = 1;
        plan.put(plot);
    }

    static void stables(Plan plan) {
        Spec st = plan.at(Archetype.STABLES, 0, 0, Direction.SOUTH).size(13, 11);
        st.margin = 3;
        plan.put(st);
        plan.place(plan.at(Archetype.FARMHOUSE, -14, -2, Direction.SOUTH), 2);
        plan.place(plan.at(Archetype.FIELD, 2, -16, Direction.SOUTH).size(13, 9), 2);
        plan.street(-plan.radius, 9, plan.radius, 9, 3, false);
    }

    static void estate(Plan plan, String variant, Names names) {
        Spec manor = plan.at(Archetype.MANOR, 0, -2, Direction.SOUTH).size(15, 15).flag(Spec.UPPER | Spec.RICH);
        manor.name = plan.key;
        manor.flag(Spec.SIGN);
        plan.put(manor);
        plan.street(0, 6, 0, plan.radius, 3, false);
        if (variant.equals("embassy")) {
            plan.place(plan.at(Archetype.BARRACKS, -16, 0, Direction.EAST), 2);
            plan.place(plan.at(Archetype.GARDEN, 14, 4, Direction.WEST), 1);
            plan.wallRing(plan.radius - 4, 8, 6, new double[]{0}, Spec.MAJOR, false);
        } else if (variant.equals("estate")) {
            for (int i = 0; i < 3; i++) plan.place(plan.at(Archetype.GARDEN, -14 + i * 7, 12, Direction.SOUTH).variant(i), 1);
            plan.place(plan.at(Archetype.HOUSE, 15, 0, Direction.WEST), 2);
            plan.place(plan.at(Archetype.DOCK, 0, plan.radius - 4, Direction.SOUTH).size(5, 11), 0);
        } else {
            plan.place(plan.at(Archetype.STABLES, 15, 0, Direction.WEST).size(9, 9), 2);
        }
    }

    static void meadery(Plan plan, Names names) {
        Spec hall = plan.at(Archetype.STORE, 0, -2, Direction.SOUTH).size(13, 17);
        hall.name = plan.key;
        hall.flag(Spec.SIGN | Spec.UPPER);
        plan.put(hall);
        plan.street(0, 7, 0, plan.radius, 3, false);
        plan.place(plan.at(Archetype.HOUSE, 13, 0, Direction.WEST), 2);
        outskirts(plan, Archetype.FIELD, 2, 13, 11, 12, plan.radius);
        plan.place(plan.at(Archetype.LUMBER, -13, 4, Direction.EAST), 2);
    }

    static void meadHall(Plan plan, Names names) {
        Spec hall = plan.at(Archetype.LONGHOUSE, 0, -2, Direction.SOUTH).size(13, 23).flag(Spec.SEAT);
        hall.name = plan.key;
        hall.flag(Spec.SIGN);
        plan.put(hall);
        plan.place(plan.at(Archetype.HOUSE, 13, 4, Direction.WEST), 2);
        plan.place(plan.at(Archetype.HOUSE, -13, 4, Direction.EAST), 2);
        plan.street(0, 10, 0, plan.radius, 3, false);
        plan.wallRing(plan.radius - 3, 10, 5, new double[]{0}, Spec.PALISADE, false);
    }

    static void telvanni(Plan plan, Names names) {
        Spec main = plan.at(Archetype.TOWER, 0, -4, Direction.SOUTH).size(15, 15);
        main.flag(Spec.UPPER | Spec.SEAT);
        main.margin = 5;
        plan.put(main);
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2 + 0.6;
            Spec h = plan.at(Archetype.HOUSE, (int) (Math.sin(a) * 20), (int) (Math.cos(a) * 20), Plan.outward(a)).size(9, 9);
            h.margin = 4;
            plan.place(h, 2);
        }
        plan.street(0, 4, 0, plan.radius, 3, false);
    }

    /** An Orc stronghold: a palisade ring, the chief's longhouse, a forge, a mine and a few longhouses. */
    static void stronghold(Plan plan, Names names) {
        int R = plan.radius;
        Spec chief = plan.at(Archetype.LONGHOUSE, 0, -R / 3, Direction.SOUTH).size(13, 21).flag(Spec.SEAT);
        chief.name = "chief_longhouse";
        plan.put(chief);
        plan.street(0, -R / 3 + 12, 0, R, 5, true);
        Spec yard = plan.at(Archetype.PLAZA, 0, 6, Direction.SOUTH).size(9, 9);
        yard.variant = 3;
        yard.margin = 0;
        plan.place(yard, 0);
        plan.place(plan.at(Archetype.SMITHY, 14, 6, Direction.WEST).size(11, 9), 2);
        plan.place(plan.at(Archetype.LONGHOUSE, -15, 4, Direction.EAST).size(9, 15), 2);
        plan.place(plan.at(Archetype.HOUSE, 13, -10, Direction.WEST).size(9, 11), 2);
        plan.place(plan.at(Archetype.HOUSE, -13, -12, Direction.EAST).size(9, 11), 2);
        Spec mine = plan.at(Archetype.MINE, R / 2, -R / 2, Direction.SOUTH).size(7, 14);
        mine.flag(Spec.NO_BEARD);
        mine.variant = 5;
        plan.place(mine, 2);
        plan.wallRing(R - 3, 10, 7, new double[]{0}, Spec.PALISADE, false);
    }

    // ------------------------------------------------------------------ helpers for unique cities

    /** Kit style for a generic Nord settlement in a Skyrim hold. */
    public static int nordStyle(String region) {
        if (region == null) return Kits.NORD_VILLAGE;
        if (region.endsWith("whiterun")) return Kits.WHITERUN;
        if (region.endsWith("haafingar")) return Kits.SOLITUDE;
        if (region.endsWith("hjaalmarch")) return Kits.MARSH;
        if (region.endsWith("the_pale")) return Kits.STONE;
        if (region.endsWith("winterhold")) return Kits.WINTERHOLD;
        if (region.endsWith("falkreath")) return Kits.FOREST;
        if (region.endsWith("the_rift")) return Kits.RIFTEN;
        if (region.endsWith("the_reach")) return Kits.STONE;
        return Kits.NORD_VILLAGE;
    }
}
