package com.skycraft.tamriel.settlement.plan;

import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.settlement.Culture;
import com.skycraft.tamriel.settlement.build.Landmarks;
import com.skycraft.tamriel.settlement.kit.Kits;
import net.minecraft.core.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The unique layouts: the nine hold capitals of Skyrim, Helgen, the College of Winterhold, Dragon Bridge and Raven Rock,
 * and the great provincial capitals (the Imperial City, Mournhold, Vivec, Alinor, Sentinel, Wayrest, Orsinium, Elden
 * Root, Lilmoth, Torval). Layouts use the plan's local frame: local north is the palace side, local south the gate.
 */
public final class Cities {
    private Cities() {}

    private static final Direction N = Direction.NORTH, S = Direction.SOUTH, E = Direction.EAST, W = Direction.WEST;

    /** Where each capital's house for sale stands in its layout (local frame), shared with the economy's house list. */
    public record HouseSpot(String id, String place, int lx, int lz, Direction face, int w, int d, int cost) {
    }

    public static final java.util.List<HouseSpot> HOUSE_SPOTS = java.util.List.of(
            new HouseSpot("breezehome", "whiterun", -18, 54, E, 9, 11, 5000),
            new HouseSpot("proudspire_manor", "solitude", -46, -12, E, 11, 13, 25000),
            new HouseSpot("hjerim", "windhelm", -34, -32, E, 11, 13, 12000),
            new HouseSpot("honeyside", "riften", 26, 48, W, 9, 11, 8000),
            new HouseSpot("vlindrel_hall", "markarth", 36, -54, W, 11, 11, 8000));

    static HouseSpot spot(String id) {
        for (HouseSpot h : HOUSE_SPOTS) if (h.id().equals(id)) return h;
        throw new IllegalArgumentException(id);
    }

    /** A house spot as a spec of {@code kind} in the plan. */
    static Spec saleHouse(Plan plan, String id, Archetype kind) {
        HouseSpot h = spot(id);
        Spec s = b(plan, kind, h.lx(), h.lz(), h.face(), h.w(), h.d());
        s.flags |= Spec.FOR_SALE | Spec.UPPER;
        s.name = id;
        return s;
    }

    /** World x/z of a local point of a place's layout. */
    public static int[] world(Place p, int lx, int lz) {
        int dx = switch (back(p)) {
            case EAST -> -lz;
            case SOUTH -> -lx;
            case WEST -> lz;
            default -> lx;
        };
        int dz = switch (back(p)) {
            case EAST -> lx;
            case SOUTH -> -lz;
            case WEST -> -lx;
            default -> lz;
        };
        return new int[]{p.x() + dx, p.z() + dz};
    }

    // ------------------------------------------------------------------ per-place parameters

    /** Fixed per place (layouts don't change with the world seed). */
    public static long seed(Place p) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < p.id().length(); i++) {
            h ^= p.id().charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** How far the layout reaches from the place anchor (bridges, arches and outworks included). */
    public static int reach(Place p) {
        return switch (key(p)) {
            case "solitude" -> 215;
            case "windhelm" -> 175;
            case "imperial_city" -> 225;
            case "vivec" -> 150;
            case "mournhold", "elden_root" -> 135;
            case "college_of_winterhold" -> 112;
            default -> Math.min(p.radius(), layoutRadius(p));
        };
    }

    /** Radius used for building placement. */
    public static int layoutRadius(Place p) {
        return switch (key(p)) {
            case "imperial_city" -> 160;
            case "solitude" -> 135;
            case "vivec" -> 140;
            case "mournhold", "elden_root" -> 125;
            default -> Math.min(p.radius(), 110);
        };
    }

    static String key(Place p) {
        return p.variant().isEmpty() ? p.id() : p.variant();
    }

    /** World direction of the palace side (local north). */
    public static Direction back(Place p) {
        return switch (key(p)) {
            case "solitude" -> E;
            case "riften" -> S;
            case "markarth" -> W;
            default -> N;
        };
    }

    public static int style(Place p, Culture c) {
        String k = key(p);
        return switch (k) {
            case "whiterun" -> Kits.WHITERUN;
            case "solitude", "katlas_farm" -> Kits.SOLITUDE;
            case "windhelm" -> Kits.WINDHELM;
            case "riften" -> Kits.RIFTEN;
            case "markarth" -> Kits.MARKARTH;
            case "winterhold", "college_of_winterhold" -> Kits.WINTERHOLD;
            case "falkreath" -> Kits.FOREST;
            case "morthal" -> Kits.MARSH;
            case "dawnstar", "helgen" -> Kits.STONE;
            case "imperial_city" -> Kits.IMPERIAL_CITY;
            default -> c == Culture.NORD ? Layouts.nordStyle(p.regionId())
                    : c == Culture.DUNMER ? Culture.dunmerStyle(p.id() + " " + p.regionId() + " " + p.variant()) : 0;
        };
    }

    /** Names of the key shops of named towns (signs and inn names). */
    public static Layouts.Names names(Place p) {
        return switch (p.id()) {
            case "riverwood" -> new Layouts.Names("sleeping_giant_inn", "riverwood_trader", "alvors_smithy");
            case "rorikstead" -> new Layouts.Names("frostfruit_inn", "", "");
            case "ivarstead" -> new Layouts.Names("vilemyr_inn", "", "");
            case "dragon_bridge" -> new Layouts.Names("four_shields_tavern", "", "");
            case "kynesgrove" -> new Layouts.Names("braidwood_inn", "", "");
            case "old_hroldan" -> new Layouts.Names("old_hroldan_inn", "", "");
            case "nightgate_inn" -> new Layouts.Names("nightgate_inn", "", "");
            case "raven_rock" -> new Layouts.Names("the_retching_netch", "milores_ingredients", "glover_mallorys_smithy");
            default -> Layouts.Names.NONE;
        };
    }

    /** The inn-name index ({@code survival.skycraft.inn.N}) of a named place's inn, or -1. */
    public static int innIndex(String placeId) {
        return switch (placeId) {
            case "whiterun" -> 0;
            case "riverwood" -> 1;
            case "windhelm" -> 2;
            case "solitude" -> 3;
            case "markarth" -> 4;
            case "winterhold" -> 5;
            case "riften" -> 6;
            case "morthal" -> 7;
            case "dawnstar" -> 8;
            case "falkreath" -> 9;
            case "kynesgrove" -> 10;
            case "nightgate_inn" -> 11;
            case "rorikstead" -> 12;
            case "old_hroldan" -> 13;
            case "raven_rock" -> 14;
            case "dragon_bridge" -> 15;
            default -> -1;
        };
    }

    // ------------------------------------------------------------------ dispatch

    /** Builds the unique layout of the place, if it has one. */
    public static boolean build(Plan plan, Place p) {
        switch (key(p)) {
            case "whiterun" -> whiterun(plan);
            case "solitude" -> solitude(plan);
            case "windhelm" -> windhelm(plan);
            case "riften" -> riften(plan);
            case "markarth" -> markarth(plan);
            case "winterhold" -> winterhold(plan);
            case "falkreath" -> falkreath(plan);
            case "morthal" -> morthal(plan);
            case "dawnstar" -> dawnstar(plan);
            case "college_of_winterhold" -> college(plan);
            case "helgen" -> helgen(plan);
            case "dragon_bridge" -> {
                Layouts.generic(plan, Layouts.Size.TOWN, "", names(p));
                Spec b = plan.line(Archetype.BRIDGE, -plan.radius - 30, 0, -plan.radius + 4, 0, 7);
                b.variant = 1;
                plan.put(b);
            }
            case "imperial_city" -> imperialCity(plan);
            case "mournhold" -> mournhold(plan);
            case "vivec" -> vivec(plan);
            case "alinor" -> domedCapital(plan, Landmarks.CRYSTAL_TOWER, 19, 110);
            case "sentinel" -> domedCapital(plan, Landmarks.DOME_TEMPLE, 23, 30);
            case "torval" -> domedCapital(plan, Landmarks.DOME_TEMPLE, 23, 30);
            case "wayrest" -> castleCapital(plan, 31);
            case "orsinium" -> castleCapital(plan, 35);
            case "elden_root" -> eldenRoot(plan);
            case "lilmoth" -> lilmoth(plan);
            case "raven_rock" -> ravenRock(plan, p);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ helpers

    static Spec b(Plan plan, Archetype kind, int lx, int lz, Direction face, int w, int d) {
        return plan.put(plan.at(kind, lx, lz, face, w, d));
    }

    static Spec named(Spec s, String name) {
        s.name = name;
        s.flag(Spec.SIGN);
        return s;
    }

    static Spec raise(Spec s, int minFloor) {
        s.minFloor = minFloor;
        return s;
    }

    /** Covers a local rectangle with terrace tiles (at most 31 x 31 each) whose floor is {@code level} over the base. */
    static void terraces(Plan plan, int x0, int z0, int x1, int z1, int level, int parapet) {
        int tw = 31;
        for (int x = x0; x < x1; x += tw) {
            for (int z = z0; z < z1; z += tw) {
                int w = Math.min(tw, x1 - x), d = Math.min(tw, z1 - z);
                if (w < 5 || d < 5) continue;
                if (w % 2 == 0) w--;
                if (d % 2 == 0) d--;
                Spec t = plan.at(Archetype.TERRACE, x + w / 2, z + d / 2, S, w, d);
                t.minFloor = level;
                t.variant = parapet;
                t.margin = 0;
                t.flags |= Spec.GROUND;
                plan.put(t);
            }
        }
    }

    /** Fills lots along local streets {@code {x1, z1, x2, z2, width}} with {@code n} houses on terrace {@code level}. */
    static void houses(Plan plan, List<int[]> streets, int n, int flags, int level, Archetype kind) {
        Deque<Spec> q = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            Spec h = new Spec(kind);
            h.flags = flags;
            if ((flags & Spec.UPPER) != 0 && i % 3 == 2) h.flags &= ~Spec.UPPER;
            if (i % 4 == 1) h.size(9, 9);
            h.minFloor = level == 0 ? Spec.UNSET : level;
            q.add(h);
        }
        Layouts.fillStreets(plan, streets, q, 2);
    }

    static int[] st(Plan plan, int x1, int z1, int x2, int z2, int width, boolean major, int level) {
        Spec s = plan.street(x1, z1, x2, z2, width, major);
        if (level != 0) s.minFloor = level;
        return new int[]{x1, z1, x2, z2, width};
    }

    static void stalls(Plan plan, int cx, int cz, int n, int level) {
        int placed = 0;
        for (int k = 0; k < n * 3 && placed < n; k++) {
            int lx = cx + (k % 2 == 0 ? -1 : 1) * (10 + (k / 4) * 4), lz = cz - 8 + (k / 2 % 2) * 16;
            Spec s = plan.at(Archetype.STALL, lx, lz, k / 2 % 2 == 0 ? S : N, 3, 3);
            raise(s, level);
            if (plan.place(s, 1) != null) placed++;
        }
    }

    static Spec plaza(Plan plan, int lx, int lz, int size, int variant, int level) {
        Spec s = plan.at(Archetype.PLAZA, lx, lz, S, size, size);
        s.variant = variant;
        s.margin = 0;
        if (level != 0) s.minFloor = level;
        return plan.put(s);
    }

    /** Marks every spec added since {@code from} with a minimum floor. */
    static void raiseFrom(Plan plan, int from, int level) {
        for (int i = from; i < plan.specs.size(); i++) plan.specs.get(i).minFloor = level;
    }

    // ------------------------------------------------------------------ Whiterun

    /**
     * Whiterun on its hill: the Plains District inside the gate with the market, the Wind District terrace with the
     * Gildergreen, Jorrvaskr and the Temple of Kynareth, the Skyforge above Jorrvaskr, and the Cloud District crowned by
     * Dragonsreach. Outer wall with its gate and drawbridge to the south.
     */
    static void whiterun(Plan plan) {
        int wind = 7, cloud = 15;
        terraces(plan, -76, -54, 76, 2, wind, 3);
        terraces(plan, -38, -104, 38, -52, cloud, 3);
        Spec forgeT = plan.at(Archetype.TERRACE, -52, -66, S, 21, 21);
        forgeT.minFloor = cloud - 3;
        forgeT.margin = 0;
        forgeT.flags |= Spec.GROUND;
        plan.put(forgeT);
        // walls and the gate
        plan.wallRing(108, 16, 9, new double[]{0}, Spec.MAJOR, true);
        Spec draw = plan.line(Archetype.BRIDGE, 0, 112, 0, 128, 5);
        plan.put(draw);
        // landmarks
        Spec dr = b(plan, Archetype.PALACE, 0, -80, S, 27, 45);
        dr.flags |= Spec.UPPER | Spec.SEAT;
        dr.name = "dragonsreach";
        dr.h = 30;
        dr.margin = 2;
        raise(dr, cloud);
        // Dragonsreach's front towers
        for (int side = -1; side <= 1; side += 2) {
            Spec t = b(plan, Archetype.TOWER, side * 19, -62, S, 7, 7);
            t.h = 30;
            t.margin = 1;
            raise(t, cloud);
        }
        Spec gg = b(plan, Archetype.LANDMARK, 0, -30, S, 15, 15);
        gg.variant = Landmarks.GILDERGREEN;
        gg.h = 18;
        raise(gg, wind);
        Spec sf = b(plan, Archetype.LANDMARK, -52, -66, S, 17, 17);
        sf.variant = Landmarks.SKYFORGE;
        sf.h = 18;
        raise(sf, cloud - 3);
        Spec jv = named(b(plan, Archetype.LONGHOUSE, -40, -30, E, 13, 25), "jorrvaskr");
        jv.flags |= Spec.SEAT;
        raise(jv, wind);
        Spec tk = named(b(plan, Archetype.TEMPLE, 40, -30, W, 13, 19), "temple_of_kynareth");
        tk.variant = 4;
        raise(tk, wind);
        Spec hd = b(plan, Archetype.HALL_OF_DEAD, 20, -28, S, 9, 11);
        raise(hd, wind);
        Spec talos = b(plan, Archetype.LANDMARK, 16, -46, S, 7, 7);
        talos.variant = Landmarks.TALOS;
        talos.h = 16;
        raise(talos, wind);
        // streets: main road from the gate, the market square, stairs up through the Wind District
        List<int[]> plains = new ArrayList<>();
        plains.add(st(plan, 0, 104, 0, 44, 5, true, 0));
        plains.add(st(plan, 0, 26, 0, 0, 5, true, 0));
        plains.add(st(plan, -62, 35, -9, 35, 3, false, 0));
        plains.add(st(plan, 9, 35, 62, 35, 3, false, 0));
        plains.add(st(plan, -45, 70, 45, 70, 3, false, 0));
        List<int[]> windSt = new ArrayList<>();
        windSt.add(st(plan, -66, -12, 66, -12, 3, false, wind));
        st(plan, 0, -14, 0, -22, 3, false, wind);
        st(plan, 0, -38, 0, -50, 3, false, wind);
        plaza(plan, 0, 35, 15, 0, 0);
        stalls(plan, 0, 35, 4, 0);
        // the Plains District
        named(b(plan, Archetype.INN, -20, 16, E, 13, 17), "bannered_mare").flags |= Spec.UPPER;
        named(b(plan, Archetype.STORE, 18, 52, W, 9, 11), "belethors_general_goods");
        named(b(plan, Archetype.ALCHEMIST, 18, 16, W, 9, 11), "arcadias_cauldron");
        named(b(plan, Archetype.SMITHY, 22, 86, W, 11, 9), "warmaidens");
        named(b(plan, Archetype.STORE, -18, 86, E, 9, 11), "drunken_huntsman");
        saleHouse(plan, "breezehome", Archetype.HOUSE);
        b(plan, Archetype.BARRACKS, 44, 84, W, 9, 15);
        houses(plan, plains, 14, 0, 0, Archetype.HOUSE);
        // the Wind District: the clan houses and more homes
        named(raise(b(plan, Archetype.MANOR, -62, -34, E, 11, 13), wind), "house_gray_mane").flags |= Spec.UPPER | Spec.RICH;
        named(raise(b(plan, Archetype.MANOR, 62, -36, W, 11, 13), wind), "house_battle_born").flags |= Spec.UPPER | Spec.RICH;
        houses(plan, windSt, 6, Spec.UPPER, wind, Archetype.HOUSE);
        // the Cloud District's stair
        Spec cs = plan.line(Archetype.STREET, 0, -52, 0, -56, 5);
        cs.minFloor = cloud;
        plan.put(cs);
    }

    // ------------------------------------------------------------------ Solitude

    /**
     * Solitude on its great rock over the Karth estuary: the city on a raised plateau, the Blue Palace at the far end,
     * the natural arch running on beyond it, Castle Dour, the Temple of the Divines, the Bards College, Proudspire Manor.
     */
    static void solitude(Plan plan) {
        int lv = 16;
        // the rock under the city
        for (int x = -90; x <= 90; x += 45) {
            for (int z = -125; z <= 115; z += 45) {
                if (x * x + z * z > 130 * 130) continue;
                Spec r = b(plan, Archetype.LANDMARK, x, z, S, 61, 61);
                r.variant = Landmarks.ROCK;
                r.h = 6;
                r.margin = 2;
                r.flags |= Spec.NO_BEARD | Spec.GROUND;
                raise(r, lv);
            }
        }
        // the arch: on past the palace end, over the estuary
        int sx = 0, sz = -125, ex = 0, ez = -215;
        int segs = 4;
        for (int i = 0; i < segs; i++) {
            int cz = sz + (ez - sz) * (2 * i + 1) / (2 * segs);
            Spec a = b(plan, Archetype.LANDMARK, sx, cz, S, 49, 25);
            a.variant = Landmarks.ARCH;
            a.flags |= Spec.NO_BEARD | Spec.FIXED | Spec.GROUND;
            a.floor = plan.base;
            a.h = lv * 2 + 4;
            a.margin = 2;
            a.name = plan.wx(sx, sz) + "," + plan.wz(sx, sz) + "," + plan.wx(ex, ez) + "," + plan.wz(ex, ez) + "," + lv + ",40";
        }
        int walls = plan.specs.size();
        plan.wallRing(122, 16, 8, new double[]{0}, Spec.MAJOR, true);
        raiseFrom(plan, walls, lv);
        // a ramp from the gate down to the plain
        Spec ramp = plan.line(Archetype.BRIDGE, 0, 128, 0, 180, 7);
        ramp.variant = 1;
        ramp.flags |= Spec.FIXED;
        ramp.floor = plan.base + lv;
        ramp.floor2 = plan.terrain.land(plan.wx(0, 180), plan.wz(0, 180)) + 1;
        plan.put(ramp);
        // palaces and halls
        Spec bp = b(plan, Archetype.LANDMARK, 0, -95, S, 23, 29);
        bp.variant = Landmarks.BLUE_PALACE;
        bp.h = 26;
        bp.margin = 8;
        raise(bp, lv);
        Spec cd = b(plan, Archetype.LANDMARK, -48, -48, E, 25, 25);
        cd.variant = Landmarks.CASTLE;
        cd.culture = Culture.IMPERIAL.ordinal();
        cd.style = 0;
        cd.h = 28;
        cd.margin = 5;
        raise(cd, lv);
        named(raise(b(plan, Archetype.TEMPLE, 46, -55, W, 13, 21), lv), "temple_of_the_divines").variant = 0;
        named(raise(b(plan, Archetype.MANOR, 46, -18, W, 13, 15), lv), "bards_college").flags |= Spec.UPPER | Spec.RICH;
        raise(saleHouse(plan, "proudspire_manor", Archetype.MANOR), lv).flags |= Spec.RICH;
        named(raise(b(plan, Archetype.INN, -26, 26, E, 13, 17), lv), "winking_skeever").flags |= Spec.UPPER;
        named(raise(b(plan, Archetype.STORE, 26, 22, W, 9, 11), lv), "radiant_raiment");
        named(raise(b(plan, Archetype.ALCHEMIST, 26, 44, W, 9, 11), lv), "angelines_aromatics");
        named(raise(b(plan, Archetype.STORE, -26, 52, E, 9, 11), lv), "bits_and_pieces");
        named(raise(b(plan, Archetype.SMITHY, 28, 86, W, 11, 9), lv), "solitude_blacksmith");
        raise(b(plan, Archetype.HALL_OF_DEAD, -50, 14, E, 9, 11), lv);
        raise(b(plan, Archetype.BARRACKS, -48, -78, E, 9, 15), lv);
        List<int[]> streets = new ArrayList<>();
        streets.add(st(plan, 0, 120, 0, -78, 5, true, lv));
        streets.add(st(plan, -64, 38, 64, 38, 3, false, lv));
        streets.add(st(plan, -60, 0, 60, 0, 3, false, lv));
        streets.add(st(plan, -50, 75, 50, 75, 3, false, lv));
        plaza(plan, 0, 38, 15, 0, lv);
        stalls(plan, 0, 38, 4, lv);
        houses(plan, streets, 16, Spec.UPPER, lv, Archetype.HOUSE);
        // docks of the East Empire Company on the estuary below the arch
        for (int i = 0; i < 3; i++) {
            Spec d = plan.at(Archetype.DOCK, 32 + i * 12, -175, E, 5, 15);
            d.flags |= Spec.NO_BEARD;
            plan.put(d);
        }
    }

    // ------------------------------------------------------------------ Windhelm

    /** Windhelm: dark stone, the Palace of the Kings, the Grey Quarter, Hjerim, the long bridge and the docks. */
    static void windhelm(Plan plan) {
        plan.wallRing(95, 16, 11, new double[]{0}, Spec.MAJOR, true);
        Spec bridge = plan.line(Archetype.BRIDGE, 0, 101, 0, 172, 7);
        bridge.variant = 1;
        bridge.flags |= Spec.FIXED;
        bridge.floor = plan.base + 4;
        bridge.floor2 = plan.terrain.land(plan.wx(0, 172), plan.wz(0, 172)) + 1;
        plan.put(bridge);
        Spec pk = named(b(plan, Archetype.PALACE, 0, -68, S, 23, 35), "palace_of_the_kings");
        pk.flags |= Spec.UPPER | Spec.SEAT;
        pk.h = 28;
        named(b(plan, Archetype.INN, -24, 12, E, 13, 17), "candlehearth_hall").flags |= Spec.UPPER;
        named(b(plan, Archetype.TEMPLE, 30, -34, W, 13, 19), "temple_of_talos").variant = 7;
        saleHouse(plan, "hjerim", Archetype.MANOR).flags |= Spec.RICH;
        named(b(plan, Archetype.ALCHEMIST, 22, 14, W, 9, 11), "the_white_phial");
        named(b(plan, Archetype.SMITHY, 24, 62, W, 11, 9), "windhelm_blacksmith");
        b(plan, Archetype.HALL_OF_DEAD, -40, -62, E, 9, 11);
        b(plan, Archetype.BARRACKS, -40, 64, E, 9, 15);
        List<int[]> streets = new ArrayList<>();
        streets.add(st(plan, 0, 90, 0, -48, 5, true, 0));
        streets.add(st(plan, -60, 36, 40, 36, 3, false, 0));
        streets.add(st(plan, -60, -10, 40, -10, 3, false, 0));
        plaza(plan, 0, 36, 13, 0, 0);
        stalls(plan, 0, 36, 3, 0);
        houses(plan, streets, 12, Spec.UPPER, 0, Archetype.HOUSE);
        // the Grey Quarter: Dunmer houses round their own street and the New Gnisis Cornerclub
        List<int[]> grey = new ArrayList<>();
        grey.add(st(plan, 62, -40, 62, 60, 3, false, 0));
        Spec cornerclub = named(b(plan, Archetype.INN, 78, 10, W, 11, 13), "new_gnisis_cornerclub");
        cornerclub.culture = Culture.DUNMER.ordinal();
        cornerclub.style = Kits.VELOTHI;
        Deque<Spec> q = new ArrayDeque<>();
        for (int i = 0; i < 7; i++) {
            Spec h = new Spec(Archetype.HOUSE);
            h.culture = Culture.DUNMER.ordinal();
            h.style = Kits.VELOTHI;
            q.add(h);
        }
        Layouts.fillStreets(plan, grey, q, 2);
        // docks on the river beyond the palace
        for (int i = 0; i < 3; i++) {
            Spec d = plan.at(Archetype.DOCK, 60 + i * 12, -112, N, 5, 15);
            d.flags |= Spec.NO_BEARD;
            plan.put(d);
        }
    }

    // ------------------------------------------------------------------ Riften

    /** Riften: a timber town on Lake Honrich round its canal, with walkways, Mistveil Keep, Honeyside and the Ratway. */
    static void riften(Plan plan) {
        plan.wallRing(76, 14, 9, new double[]{0}, Spec.MAJOR, true);
        Spec canal = plan.line(Archetype.CANAL, 0, -46, 0, 66, 9);
        plan.put(canal);
        for (int z : new int[]{-18, 26, 56}) {
            Spec br = plan.line(Archetype.BRIDGE, -6, z, 6, z, 3);
            br.flags |= Spec.FIXED;
            br.floor = plan.base + 1;
            br.floor2 = plan.base + 1;
            plan.put(br);
        }
        Spec keep = named(b(plan, Archetype.PALACE, 0, -60, S, 19, 25), "mistveil_keep");
        keep.flags |= Spec.UPPER | Spec.SEAT;
        List<int[]> streets = new ArrayList<>();
        streets.add(st(plan, -9, -46, -9, 70, 3, true, 0));
        streets.add(st(plan, 9, -46, 9, 70, 3, true, 0));
        streets.add(st(plan, -60, 0, -11, 0, 3, false, 0));
        streets.add(st(plan, 11, 40, 60, 40, 3, false, 0));
        named(b(plan, Archetype.INN, -24, 14, E, 13, 17), "bee_and_barb").flags |= Spec.UPPER;
        saleHouse(plan, "honeyside", Archetype.HOUSE);
        named(b(plan, Archetype.TEMPLE, -36, -30, E, 13, 19), "temple_of_mara").variant = 5;
        named(b(plan, Archetype.ALCHEMIST, 22, 14, W, 9, 11), "elgrims_elixirs");
        named(b(plan, Archetype.STORE, -22, 42, E, 9, 11), "pawned_prawn");
        named(b(plan, Archetype.SMITHY, 24, 66, W, 11, 9), "scorched_hammer");
        named(b(plan, Archetype.STORE, -26, 62, E, 11, 13), "black_briar_meadery");
        b(plan, Archetype.HALL_OF_DEAD, 34, -30, W, 9, 11);
        Spec rat = b(plan, Archetype.LANDMARK, -15, 30, E, 5, 7);
        rat.variant = Landmarks.RATWAY;
        rat.flags |= Spec.NO_BEARD;
        plaza(plan, 28, -6, 11, 0, 0);
        stalls(plan, 28, -6, 3, 0);
        houses(plan, streets, 12, Spec.UPPER, 0, Archetype.HOUSE);
        // jetties on Lake Honrich (local west)
        for (int i = 0; i < 4; i++) {
            Spec d = plan.at(Archetype.DOCK, -92, -30 + i * 20, W, 5, 15);
            d.flags |= Spec.NO_BEARD;
            plan.put(d);
        }
    }

    // ------------------------------------------------------------------ Markarth

    /** Markarth: the Dwemer city carved into the cliff, Understone Keep in the rock face, waterfalls, terraces climbing up. */
    static void markarth(Plan plan) {
        int mid = 4, up = 9;
        for (int i = -1; i <= 1; i++) {
            Spec cl = b(plan, Archetype.LANDMARK, i * 55, -92, S, 55, 25);
            cl.variant = Landmarks.CLIFF;
            cl.h = 46;
            cl.margin = 5;
            if (i == 0) cl.name = "keep";
        }
        terraces(plan, -80, -78, 80, -40, up, 1);
        terraces(plan, -80, -40, 80, -8, mid, 1);
        // the city is walled by its cliffs; only the gate side has a wall
        Spec gate = plan.at(Archetype.GATE, 0, 84, S, 11, 7);
        gate.h = 15;
        gate.margin = 2;
        plan.put(gate);
        for (int side = -1; side <= 1; side += 2) {
            Spec w = plan.line(Archetype.WALL, side * 7, 84, side * 34, 80, 3);
            w.h = 9;
            w.facing = plan.dir(S);
            w.flags |= Spec.MAJOR;
            plan.put(w);
            Spec w2 = plan.line(Archetype.WALL, side * 34, 80, side * 60, 66, 3);
            w2.h = 9;
            w2.facing = plan.dir(side < 0 ? W : E);
            w2.flags |= Spec.MAJOR;
            plan.put(w2);
            Spec t = plan.at(Archetype.TOWER, side * 34, 80, S, 7, 7);
            t.h = 22;
            t.margin = 2;
            plan.put(t);
        }
        named(b(plan, Archetype.INN, -24, 22, E, 13, 17), "silver_blood_inn").flags |= Spec.UPPER;
        raise(saleHouse(plan, "vlindrel_hall", Archetype.HOUSE), up);
        named(raise(b(plan, Archetype.TEMPLE, -36, -56, E, 13, 19), up), "temple_of_dibella").variant = 2;
        named(b(plan, Archetype.STORE, 24, 26, W, 9, 11), "arnleif_and_sons");
        named(raise(b(plan, Archetype.ALCHEMIST, 26, -20, W, 9, 11), mid), "hags_cure");
        named(b(plan, Archetype.SMITHY, 24, 62, W, 11, 9), "markarth_smithy");
        raise(b(plan, Archetype.HALL_OF_DEAD, -26, -22, E, 9, 11), mid);
        Spec mine = raise(b(plan, Archetype.MINE, -66, -72, S, 7, 14), up);
        mine.flags |= Spec.NO_BEARD;
        mine.variant = 2;
        mine.name = "cidhna_mine";
        List<int[]> low = new ArrayList<>();
        low.add(st(plan, 0, 80, 0, 0, 5, true, 0));
        low.add(st(plan, -60, 44, 60, 44, 3, false, 0));
        List<int[]> midSt = new ArrayList<>();
        midSt.add(st(plan, -64, -32, 64, -32, 3, false, mid));
        List<int[]> upSt = new ArrayList<>();
        upSt.add(st(plan, -58, -70, 58, -70, 3, false, up));
        plaza(plan, 0, 44, 13, 0, 0);
        stalls(plan, 0, 44, 3, 0);
        houses(plan, low, 8, 0, 0, Archetype.HOUSE);
        houses(plan, midSt, 5, Spec.UPPER, mid, Archetype.HOUSE);
        houses(plan, upSt, 3, Spec.UPPER, up, Archetype.MANOR);
    }

    // ------------------------------------------------------------------ the small capitals

    static void winterhold(Plan plan) {
        named(b(plan, Archetype.LONGHOUSE, 0, -36, S, 13, 21), "jarls_longhouse").flags |= Spec.SEAT;
        named(b(plan, Archetype.INN, -18, 6, E, 11, 15), "the_frozen_hearth");
        named(b(plan, Archetype.STORE, 18, 6, W, 9, 9), "birnas_oddments");
        List<int[]> streets = new ArrayList<>();
        streets.add(st(plan, 0, 66, 0, -22, 3, true, 0));
        streets.add(st(plan, -50, 24, 50, 24, 3, false, 0));
        houses(plan, streets, 5, 0, 0, Archetype.HOUSE);
        houses(plan, streets, 6, Spec.RUINED, 0, Archetype.HOUSE);
        Spec t = plan.at(Archetype.TOWER, 40, -30, W, 7, 7);
        t.flags |= Spec.RUINED;
        t.h = 14;
        plan.place(t, 2);
    }

    static void falkreath(Plan plan) {
        named(b(plan, Archetype.LONGHOUSE, 0, -52, S, 13, 21), "dengeirs_hall").flags |= Spec.SEAT;
        named(b(plan, Archetype.INN, -22, 2, E, 13, 17), "dead_mans_drink").flags |= Spec.UPPER;
        named(b(plan, Archetype.SMITHY, 22, 0, W, 11, 9), "falkreath_smithy");
        named(b(plan, Archetype.STORE, 22, 26, W, 9, 11), "gray_pine_goods");
        b(plan, Archetype.HALL_OF_DEAD, 34, -34, W, 9, 11);
        for (int i = 0; i < 2; i++) {
            Spec g = b(plan, Archetype.GRAVEYARD, 58, -12 + i * 20, W, 19, 17);
            g.margin = 0;
        }
        List<int[]> streets = new ArrayList<>();
        streets.add(st(plan, 0, 82, 0, -38, 3, true, 0));
        streets.add(st(plan, -64, 12, 40, 12, 3, false, 0));
        houses(plan, streets, 10, 0, 0, Archetype.HOUSE);
        Layouts.outskirts(plan, Archetype.LUMBER, 2, 7, 5, 40, 80);
        Layouts.outskirts(plan, Archetype.FIELD, 1, 13, 11, 45, 80);
    }

    static void morthal(Plan plan) {
        named(b(plan, Archetype.PALACE, 0, -46, S, 17, 25), "highmoon_hall").flags |= Spec.SEAT;
        named(b(plan, Archetype.INN, -22, 6, E, 13, 15), "moorside_inn");
        named(b(plan, Archetype.ALCHEMIST, 22, 10, W, 9, 11), "thaumaturgists_hut");
        Spec mill = b(plan, Archetype.MILL, 44, 34, S, 9, 11);
        mill.margin = 4;
        List<int[]> streets = new ArrayList<>();
        streets.add(st(plan, 0, 72, 0, -32, 3, true, 0));
        streets.add(st(plan, -56, 26, 34, 26, 3, false, 0));
        houses(plan, streets, 9, 0, 0, Archetype.HOUSE);
        Layouts.outskirts(plan, Archetype.LUMBER, 1, 7, 5, 30, 70);
    }

    static void dawnstar(Plan plan) {
        named(b(plan, Archetype.PALACE, 0, -48, S, 17, 25), "white_hall").flags |= Spec.SEAT;
        named(b(plan, Archetype.INN, -22, 4, E, 13, 17), "windpeak_inn").flags |= Spec.UPPER;
        named(b(plan, Archetype.ALCHEMIST, 22, 4, W, 9, 11), "the_mortar_and_pestle");
        named(b(plan, Archetype.SMITHY, 22, 28, W, 11, 9), "dawnstar_smithy");
        Spec q = b(plan, Archetype.MINE, -56, 44, S, 7, 14);
        q.flags |= Spec.NO_BEARD;
        q.variant = 4;
        q.name = "quicksilver_mine";
        Spec ib = b(plan, Archetype.MINE, 56, 44, S, 7, 14);
        ib.flags |= Spec.NO_BEARD;
        ib.variant = 5;
        ib.name = "iron_breaker_mine";
        List<int[]> streets = new ArrayList<>();
        streets.add(st(plan, 0, 78, 0, -34, 3, true, 0));
        streets.add(st(plan, -62, 22, 62, 22, 3, false, 0));
        houses(plan, streets, 9, 0, 0, Archetype.HOUSE);
        for (int i = 0; i < 2; i++) {
            Spec d = plan.at(Archetype.DOCK, -20 + i * 40, -82, N, 5, 15);
            d.flags |= Spec.NO_BEARD;
            plan.put(d);
        }
    }

    /** The College of Winterhold on its sea stack, with the long bridge back towards the town. */
    static void college(Plan plan) {
        int lv = 22;
        Spec rock = b(plan, Archetype.LANDMARK, 0, 0, S, 57, 57);
        rock.variant = Landmarks.SEA_STACK;
        rock.flags |= Spec.NO_BEARD | Spec.GROUND;
        rock.h = 4;
        rock.margin = 2;
        raise(rock, lv);
        Spec col = b(plan, Archetype.LANDMARK, 0, 0, S, 51, 51);
        col.variant = Landmarks.COLLEGE;
        col.h = 60;
        col.flags |= Spec.NO_BEARD;
        raise(col, lv);
        // the bridge, in two spans, towards Winterhold (south-west of the College)
        double dx = -0.63, dz = 0.77;
        int x0 = 0, z0 = 27;
        int x1 = (int) Math.round(x0 + dx * 42), z1 = (int) Math.round(z0 + dz * 42);
        int x2 = (int) Math.round(x0 + dx * 84), z2 = (int) Math.round(z0 + dz * 84);
        int top = plan.base + lv;
        int end = plan.terrain.land(plan.wx(x2, z2), plan.wz(x2, z2)) + 1;
        Spec s1 = plan.line(Archetype.BRIDGE, x0, z0, x1, z1, 5);
        s1.variant = 1;
        s1.flags |= Spec.FIXED;
        s1.floor = top;
        s1.floor2 = (top + end) / 2 + 4;
        plan.put(s1);
        Spec s2 = plan.line(Archetype.BRIDGE, x1, z1, x2, z2, 5);
        s2.variant = 1;
        s2.flags |= Spec.FIXED;
        s2.floor = s1.floor2;
        s2.floor2 = end;
        plan.put(s2);
    }

    /** Helgen: an Imperial-style walled town with its keep, its tower and the square where the headsman waits. */
    static void helgen(Plan plan) {
        plan.wallRing(56, 12, 8, new double[]{0, Math.PI}, Spec.MAJOR, true);
        Spec keep = b(plan, Archetype.LANDMARK, 22, -22, W, 23, 23);
        keep.variant = Landmarks.CASTLE;
        keep.culture = Culture.IMPERIAL.ordinal();
        keep.style = 0;
        keep.h = 26;
        keep.margin = 5;
        Spec tower = b(plan, Archetype.TOWER, -26, -26, E, 7, 7);
        tower.h = 22;
        List<int[]> streets = new ArrayList<>();
        streets.add(st(plan, 0, 52, 0, -52, 3, true, 0));
        streets.add(st(plan, -44, 14, 44, 14, 3, false, 0));
        plaza(plan, -12, 0, 9, 3, 0);
        Spec barracks = b(plan, Archetype.BARRACKS, 30, 30, W, 9, 15);
        barracks.culture = Culture.IMPERIAL.ordinal();
        barracks.style = 0;
        b(plan, Archetype.SHRINE, -10, -36, S, 5, 5).variant = 1;
        Deque<Spec> q = new ArrayDeque<>();
        q.add(new Spec(Archetype.STORE));
        q.add(new Spec(Archetype.INN).size(11, 13));
        for (int i = 0; i < 6; i++) q.add(new Spec(Archetype.HOUSE));
        Layouts.fillStreets(plan, streets, q, 2);
    }

    // ------------------------------------------------------------------ Morrowind

    static void ravenRock(Plan plan, Place p) {
        Layouts.generic(plan, Layouts.Size.TOWN, "", names(p));
        // the Bulwark: a stone wall round the landward side
        int from = plan.specs.size();
        plan.wallRing(Math.min(plan.radius - 4, 70), 12, 9, new double[]{0, Math.PI}, Spec.MAJOR, true);
        for (int i = from; i < plan.specs.size(); i++) {
            plan.specs.get(i).style = Kits.REDORAN;
        }
    }

    static void mournhold(Plan plan) {
        int R = plan.radius;
        Spec palace = b(plan, Archetype.LANDMARK, 0, -84, S, 27, 27);
        palace.variant = Landmarks.DOME_PALACE;
        palace.h = 32;
        palace.margin = 2;
        Spec temple = b(plan, Archetype.LANDMARK, 0, 6, S, 25, 25);
        temple.variant = Landmarks.DOME_TEMPLE;
        temple.name = "akatosh";
        temple.h = 32;
        plaza(plan, 62, 36, 17, 0, 0);
        stalls(plan, 62, 36, 8, 0);
        plan.wallRing(R - 5, 16, 11, new double[]{0, Math.PI / 2, -Math.PI / 2}, Spec.MAJOR, true);
        List<int[]> streets = new ArrayList<>();
        streets.add(st(plan, 0, R - 8, 0, 22, 5, true, 0));
        streets.add(st(plan, 0, -10, 0, -68, 5, true, 0));
        streets.add(st(plan, -R + 10, 36, R - 10, 36, 5, true, 0));
        streets.add(st(plan, -80, -40, 80, -40, 3, false, 0));
        streets.add(st(plan, -80, 80, 80, 80, 3, false, 0));
        Deque<Spec> q = new ArrayDeque<>();
        q.add(new Spec(Archetype.INN).size(13, 17).flag(Spec.UPPER));
        q.add(new Spec(Archetype.SMITHY).size(11, 9));
        q.add(new Spec(Archetype.STORE));
        q.add(new Spec(Archetype.STORE));
        q.add(new Spec(Archetype.ALCHEMIST));
        q.add(new Spec(Archetype.BARRACKS));
        for (int i = 0; i < 26; i++) {
            Spec h = new Spec(i % 3 == 0 ? Archetype.MANOR : Archetype.HOUSE);
            if (i % 2 == 0) h.flag(Spec.UPPER);
            if (i % 4 == 1) h.style = Kits.HLAALU;
            q.add(h);
        }
        Layouts.fillStreets(plan, streets, q, 2);
    }

    /** Vivec: cantons standing in the water round the Palace of Vivec, linked by bridges. */
    static void vivec(Plan plan) {
        int lv = 2;
        int[][] cantons = {{0, -95}, {-70, -45}, {70, -45}, {-70, 45}, {70, 45}, {0, 95}};
        Spec palace = b(plan, Archetype.LANDMARK, 0, 0, S, 37, 37);
        palace.variant = Landmarks.CANTON;
        palace.h = 42;
        palace.flags |= Spec.FIXED | Spec.NO_BEARD;
        palace.floor = plan.base + lv;
        for (int[] cc : cantons) {
            Spec c = b(plan, Archetype.LANDMARK, cc[0], cc[1], S, 31, 31);
            c.variant = Landmarks.CANTON;
            c.h = 30;
            c.flags |= Spec.FIXED | Spec.NO_BEARD;
            c.floor = plan.base + lv;
            int bx0 = (int) Math.round(cc[0] * 0.2), bz0 = (int) Math.round(cc[1] * 0.2);
            int bx1 = (int) Math.round(cc[0] * 0.82), bz1 = (int) Math.round(cc[1] * 0.82);
            Spec br = plan.line(Archetype.BRIDGE, bx0, bz0, bx1, bz1, 5);
            br.variant = 1;
            br.flags |= Spec.FIXED;
            br.floor = plan.base + lv + 1;
            br.floor2 = plan.base + lv + 1;
            plan.put(br);
        }
        Spec gate = plan.line(Archetype.BRIDGE, 0, 112, 0, 140, 7);
        gate.variant = 1;
        plan.put(gate);
    }

    // ------------------------------------------------------------------ the other provinces

    /** A domed capital (Sentinel, Torval) or a crystal-spired one (Alinor): signature landmark, domed palace, market, walls. */
    static void domedCapital(Plan plan, int landmark, int size, int height) {
        int R = plan.radius;
        Spec palace = b(plan, Archetype.LANDMARK, 0, -R + 30, S, 27, 27);
        palace.variant = Landmarks.DOME_PALACE;
        palace.h = 32;
        palace.margin = 2;
        Spec sig = b(plan, Archetype.LANDMARK, -46, 4, E, size, size);
        sig.variant = landmark;
        sig.h = height;
        sig.margin = 2;
        plaza(plan, 0, 22, 17, 0, 0);
        stalls(plan, 0, 22, 6, 0);
        plan.wallRing(R - 5, 16, 10, new double[]{0, Math.PI / 2, -Math.PI / 2}, Spec.MAJOR, true);
        genericCityStreets(plan, 24);
        for (int i = 0; i < 3; i++) {
            Spec d = plan.at(Archetype.DOCK, -20 + i * 20, R + 6, S, 5, 15);
            d.flags |= Spec.NO_BEARD;
            plan.put(d);
        }
    }

    /** A castle capital (Wayrest, Orsinium): the castle at the back, market, houses and walls. */
    static void castleCapital(Plan plan, int size) {
        int R = plan.radius;
        Spec castle = b(plan, Archetype.LANDMARK, 0, -R + 30, S, size, size);
        castle.variant = Landmarks.CASTLE;
        castle.h = 32;
        castle.margin = 5;
        plaza(plan, 0, 22, 17, 1, 0);
        stalls(plan, 0, 22, 6, 0);
        int flags = plan.culture == Culture.ORC ? Spec.MAJOR | Spec.PALISADE : Spec.MAJOR;
        plan.wallRing(R - 5, 16, 11, new double[]{0, Math.PI / 2, -Math.PI / 2}, flags, true);
        genericCityStreets(plan, 24);
    }

    /** Elden Root: the city tree at the centre, tree houses round it. */
    static void eldenRoot(Plan plan) {
        Spec tree = b(plan, Archetype.LANDMARK, 0, 0, S, 61, 61);
        tree.variant = Landmarks.GIANT_TREE;
        tree.h = 110;
        tree.flags |= Spec.NO_BEARD;
        genericCityStreets(plan, 40);
    }

    /** Lilmoth: the great xanmeer, stilt huts and canals on the delta. */
    static void lilmoth(Plan plan) {
        int R = plan.radius;
        Spec x = b(plan, Archetype.LANDMARK, 0, -R + 30, S, 33, 33);
        x.variant = Landmarks.XANMEER;
        x.h = 26;
        plan.put(plan.line(Archetype.CANAL, -R + 15, 20, R - 15, 20, 9));
        genericCityStreets(plan, 30);
        for (int i = 0; i < 4; i++) {
            Spec d = plan.at(Archetype.DOCK, -30 + i * 20, R + 6, S, 5, 15);
            d.flags |= Spec.NO_BEARD;
            plan.put(d);
        }
    }

    /** The Imperial City: the White-Gold Tower at the heart of a ringed island city, the Arena, the temple, one bridge. */
    static void imperialCity(Plan plan) {
        int R = 150;
        Spec tower = b(plan, Archetype.LANDMARK, 0, 0, S, 27, 27);
        tower.variant = Landmarks.WHITE_GOLD_TOWER;
        tower.h = 210;
        tower.margin = 4;
        // the Green Emperor Way: a garden ring round the tower
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            Spec g = plan.at(Archetype.GARDEN, (int) Math.round(Math.sin(a) * 24), (int) Math.round(Math.cos(a) * 24), S, 7, 7);
            g.variant = i;
            plan.place(g, 1);
        }
        Spec arena = b(plan, Archetype.LANDMARK, -80, 0, E, 37, 37);
        arena.variant = Landmarks.ARENA;
        arena.h = 16;
        named(b(plan, Archetype.TEMPLE, 0, -100, S, 15, 23), "temple_of_the_one").variant = 0;
        Spec talos = b(plan, Archetype.LANDMARK, 14, 80, S, 7, 7);
        talos.variant = Landmarks.TALOS;
        talos.h = 16;
        plan.wallRing(R, 20, 14, new double[]{0}, Spec.MAJOR, true);
        Spec bridge = plan.line(Archetype.BRIDGE, 0, R + 6, 0, R + 70, 7);
        bridge.variant = 1;
        plan.put(bridge);
        List<int[]> streets = new ArrayList<>();
        streets.add(st(plan, 0, 34, 0, R - 4, 5, true, 0));
        streets.add(st(plan, 0, -34, 0, -86, 5, true, 0));
        streets.add(st(plan, 34, 0, R - 8, 0, 5, true, 0));
        streets.add(st(plan, -34, 0, -58, 0, 5, true, 0));
        for (int r : new int[]{60, 100}) {
            streets.add(st(plan, -r, -r, r, -r, 3, false, 0));
            streets.add(st(plan, -r, r, r, r, 3, false, 0));
            streets.add(st(plan, -r, -r, -r, r, 3, false, 0));
            streets.add(st(plan, r, -r, r, r, 3, false, 0));
        }
        Deque<Spec> q = new ArrayDeque<>();
        q.add(new Spec(Archetype.INN).size(13, 17).flag(Spec.UPPER));
        q.add(new Spec(Archetype.INN).size(13, 17).flag(Spec.UPPER));
        q.add(new Spec(Archetype.SMITHY).size(11, 9));
        for (int i = 0; i < 4; i++) q.add(new Spec(Archetype.STORE));
        q.add(new Spec(Archetype.ALCHEMIST));
        q.add(new Spec(Archetype.BARRACKS));
        q.add(new Spec(Archetype.BARRACKS));
        q.add(new Spec(Archetype.HALL_OF_DEAD));
        for (int i = 0; i < 40; i++) {
            Spec h = new Spec(i % 4 == 0 ? Archetype.MANOR : Archetype.HOUSE);
            h.flag(Spec.UPPER);
            if (i % 4 == 0) h.flag(Spec.RICH);
            q.add(h);
        }
        Layouts.fillStreets(plan, streets, q, 2);
        plaza(plan, 80, 30, 15, 1, 0);
        stalls(plan, 80, 30, 6, 0);
    }

    /** A cross of main streets with two side streets, filled with the culture's shops and homes. */
    static void genericCityStreets(Plan plan, int clear) {
        int R = plan.radius;
        int main = R - 8;
        List<int[]> streets = new ArrayList<>();
        streets.add(st(plan, 0, main, 0, clear, 5, true, 0));
        streets.add(st(plan, -main + 6, clear / 2 + 10, main - 6, clear / 2 + 10, 5, true, 0));
        streets.add(st(plan, -R / 2, -R + 40, -R / 2, R - 30, 3, false, 0));
        streets.add(st(plan, R / 2, -R + 40, R / 2, R - 30, 3, false, 0));
        Deque<Spec> q = new ArrayDeque<>();
        q.add(new Spec(Archetype.INN).size(13, 17).flag(Spec.UPPER));
        q.add(new Spec(Archetype.SMITHY).size(11, 9));
        q.add(new Spec(Archetype.STORE));
        q.add(new Spec(Archetype.STORE));
        q.add(new Spec(Archetype.ALCHEMIST));
        q.add(new Spec(Archetype.TEMPLE).size(11, 17));
        q.add(new Spec(Archetype.BARRACKS));
        for (int i = 0; i < 24; i++) {
            Spec h = new Spec(i % 5 == 0 ? Archetype.MANOR : Archetype.HOUSE);
            if (i % 2 == 0) h.flag(Spec.UPPER);
            q.add(h);
        }
        Layouts.fillStreets(plan, streets, q, 2);
    }
}
