package com.skycraft.tamriel.settlement.plan;

import com.skycraft.tamriel.Place;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Plots for the provinces' player homes. The settlement layout only keeps the ground clear and level (a
 * {@link Spec#RESERVED} piece that paints nothing and records the plot's bounds and front door as house anchors); the
 * economy module builds the house itself on the plot ({@code economy.homes.HomeBuilder}), so the homes don't depend on
 * the culture kits.
 *
 * <p>Each plot is looked for around a preferred local spot (local south is the gate side), on dry ground inside the
 * walls, clear of streets, walls and landmarks; ordinary houses, gardens and stalls in the way give up their lot.
 * Waterfront plots (the Imperial City's shack, Vivec's canal house) stand on posts over the water.</p>
 */
public final class HousePlots {
    private HousePlots() {}

    /**
     * A player home plot: {@code style} names the home's architecture for the builder, {@code w} x {@code d} its
     * footprint (front along w), {@code lx}/{@code lz} the preferred local centre.
     */
    public record Plot(String id, String place, int lx, int lz, Direction face, int w, int d, int cost, String style, boolean waterfront) {
    }

    private static final Direction N = Direction.NORTH, S = Direction.SOUTH, E = Direction.EAST, W = Direction.WEST;

    public static final List<Plot> PLOTS = List.of(
            // Cyrodiil
            new Plot("waterfront_shack", "imperial_city", 40, 168, N, 7, 9, 2000, "imperial_shack", true),
            new Plot("talos_plaza_house", "imperial_city", 40, 80, W, 13, 15, 25000, "imperial_manor", false),
            new Plot("benirus_manor", "anvil", -28, 28, E, 13, 13, 5000, "imperial_manor", false),
            new Plot("rosethorn_hall", "skingrad", 28, 28, W, 13, 15, 25000, "imperial_manor", false),
            new Plot("riverbank_cottage", "bravil", -28, 28, E, 9, 11, 4000, "imperial_cottage", false),
            new Plot("arborwatch", "chorrol", 28, 28, W, 11, 13, 20000, "imperial", false),
            new Plot("corbolo_house", "cheydinhal", -28, 28, E, 11, 11, 15000, "imperial", false),
            new Plot("blackwood_villa", "leyawiin", 28, 28, W, 11, 13, 12000, "imperial", false),
            new Plot("jerall_view", "bruma", -28, 28, E, 11, 11, 10000, "colovian", false),
            new Plot("kvatch_hill_house", "kvatch", -28, 28, E, 11, 13, 14000, "colovian", false),
            // Morrowind & Solstheim
            new Plot("balmora_manor", "balmora", 28, 28, W, 11, 13, 12000, "hlaalu", false),
            new Plot("vivec_canalside", "vivec", 30, 122, N, 9, 11, 9000, "hlaalu", true),
            new Plot("uvirith_spire", "sadrith_mora", -28, 28, E, 13, 13, 15000, "telvanni", false),
            new Plot("godsreach_manor", "mournhold", -40, 58, E, 13, 13, 20000, "hlaalu", false),
            new Plot("severin_manor", "raven_rock", 28, 28, W, 13, 13, 15000, "hlaalu", false),
            new Plot("aldruhn_redoran_manor", "aldruhn", -28, 28, E, 13, 15, 16000, "hlaalu", false),
            new Plot("ebonheart_villa", "ebonheart", 28, 40, W, 11, 13, 14000, "imperial", false),
            new Plot("necrom_sanctum", "necrom", -28, 40, E, 11, 13, 16000, "hlaalu", false),
            new Plot("blacklight_manor", "blacklight", 28, 40, W, 13, 15, 18000, "hlaalu", false),
            // High Rock & Orsinium
            new Plot("wayrest_townhouse", "wayrest", 28, 60, W, 11, 13, 15000, "breton", false),
            new Plot("daggerfall_manor", "daggerfall", -28, 28, E, 11, 13, 18000, "breton", false),
            new Plot("camlorn_townhouse", "camlorn", -28, 40, E, 11, 13, 14000, "breton", false),
            new Plot("shornhelm_manor", "shornhelm", 28, 40, W, 11, 13, 14000, "breton", false),
            new Plot("evermor_villa", "evermor", -28, 40, E, 11, 13, 13000, "breton", false),
            new Plot("orsinium_longhouse", "orsinium", 28, 60, W, 9, 15, 10000, "orc", false),
            // Hammerfell
            new Plot("sentinel_villa", "sentinel", 28, 60, W, 13, 13, 20000, "redguard", false),
            new Plot("rihad_townhouse", "rihad", -28, 40, E, 13, 13, 16000, "redguard", false),
            new Plot("taneth_villa", "taneth", 28, 40, W, 13, 13, 15000, "redguard", false),
            new Plot("hegathe_palace_house", "hegathe", -28, 40, E, 13, 15, 18000, "redguard", false),
            new Plot("skaven_courtyard_home", "skaven", 28, 40, W, 11, 13, 13000, "redguard", false),
            new Plot("elinhir_tower_house", "elinhir", -28, 40, E, 11, 13, 14000, "redguard", false),
            new Plot("abahs_landing_villa", "abahs_landing", 28, 40, W, 13, 13, 17000, "redguard", false),
            // Valenwood
            new Plot("elden_root_treehouse", "elden_root", 32, 70, W, 11, 11, 15000, "bosmer", false),
            new Plot("falinesti_bough_house", "falinesti", -28, 48, E, 11, 11, 16000, "bosmer", false),
            new Plot("woodhearth_harbor_home", "woodhearth", 28, 40, W, 11, 13, 13000, "bosmer", false),
            new Plot("silvenar_canopy_lodge", "silvenar", -28, 40, E, 11, 11, 14000, "bosmer", false),
            new Plot("haven_port_lodge", "haven", 28, 40, W, 11, 11, 12000, "bosmer", false),
            // Summerset
            new Plot("alinor_villa", "alinor", 28, 60, W, 13, 13, 30000, "altmer", false),
            new Plot("shimmerene_villa", "shimmerene", -28, 48, E, 13, 13, 24000, "altmer", false),
            new Plot("firsthold_manor", "firsthold", 28, 48, W, 13, 15, 26000, "altmer", false),
            new Plot("lillandril_spire", "lillandril", -28, 48, E, 13, 13, 24000, "altmer", false),
            new Plot("cloudrest_aerie", "cloudrest", 28, 40, W, 13, 13, 25000, "altmer", false),
            new Plot("skywatch_villa", "skywatch", -28, 40, E, 13, 13, 20000, "altmer", false),
            // Black Marsh
            new Plot("lilmoth_stilt_house", "lilmoth", 28, 60, W, 11, 11, 8000, "argonian", false),
            new Plot("stormhold_xanmeer_house", "stormhold", -28, 48, E, 11, 13, 10000, "argonian", false),
            new Plot("gideon_marsh_manor", "gideon", 28, 48, W, 11, 13, 11000, "argonian", false),
            new Plot("helstrom_root_lodge", "helstrom", -28, 40, E, 11, 11, 12000, "argonian", false),
            new Plot("thorn_fen_house", "thorn", 28, 40, W, 11, 11, 9000, "argonian", false),
            new Plot("soulrest_bay_house", "soulrest", -28, 40, E, 11, 11, 9000, "argonian", false),
            // Elsweyr
            new Plot("torval_moon_house", "torval", 28, 60, W, 11, 11, 12000, "khajiit", false),
            new Plot("senchal_port_villa", "senchal", -28, 48, E, 13, 13, 16000, "khajiit", false),
            new Plot("rimmen_palace_house", "rimmen", 28, 48, W, 13, 13, 15000, "khajiit", false),
            new Plot("riverhold_caravanserai_home", "riverhold", -28, 40, E, 11, 13, 12000, "khajiit", false),
            new Plot("dune_oasis_house", "dune", 28, 40, W, 11, 11, 11000, "khajiit", false),
            new Plot("corinthe_bazaar_house", "corinthe", -28, 40, E, 11, 13, 12000, "khajiit", false));

    public static Plot byId(String id) {
        for (Plot p : PLOTS) if (p.id().equals(id)) return p;
        return null;
    }

    public static List<Plot> plotsForPlace(String placeId) {
        if (placeId == null || placeId.isEmpty()) return List.of();
        List<Plot> out = new ArrayList<>();
        for (Plot p : PLOTS) if (p.place().equals(placeId)) out.add(p);
        return List.copyOf(out);
    }

    /** Reserves the plots of a place in its finished layout (before floors are resolved). */
    public static void reserve(Plan plan, Place place) {
        for (Plot p : PLOTS) {
            if (p.place().equals(place.id())) reserve(plan, p);
        }
    }

    static void reserve(Plan plan, Plot p) {
        Spec best = null;
        List<Spec> bestVictims = null;
        int bestCost = Integer.MAX_VALUE;
        // score: three per displaced piece, one per 4-block step from the preferred spot
        for (int pass = 0; pass < 2 && best == null; pass++) {
            for (int ring = 0; ring <= 18 && ring < bestCost; ring++) {
                for (int k = -ring; k <= ring; k++) {
                    for (int side = 0; side < 4; side++) {
                        int ox = switch (side) {
                            case 0 -> k;
                            case 1 -> ring;
                            case 2 -> -k;
                            default -> -ring;
                        };
                        int oz = switch (side) {
                            case 0 -> -ring;
                            case 1 -> k;
                            case 2 -> ring;
                            default -> -k;
                        };
                        if (ring > 0 && Math.abs(ox) != ring && Math.abs(oz) != ring) continue;
                        if (ring == 0 && side > 0) continue;
                        Spec s = plot(plan, p, p.lx() + ox * 4, p.lz() + oz * 4);
                        List<Spec> victims = new ArrayList<>();
                        int cost = cost(plan, p, s, victims, pass == 1);
                        if (cost >= 0) cost = cost * 3 + ring;
                        if (cost >= 0 && cost < bestCost) {
                            best = s;
                            bestVictims = victims;
                            bestCost = cost;
                        }
                    }
                }
            }
        }
        if (best == null) {
            // nowhere clear: the preferred spot, whatever stands there
            best = plot(plan, p, p.lx(), p.lz());
            bestVictims = new ArrayList<>();
            for (Spec o : plan.specs) if (!o.isLine() && removable(o) && Plan.collides(best, o, 1)) bestVictims.add(o);
        }
        plan.specs.removeAll(bestVictims);
        plan.add(best);
    }

    static Spec plot(Plan plan, Plot p, int lx, int lz) {
        Spec s = plan.at(Archetype.HOUSE, lx, lz, p.face(), p.w(), p.d());
        s.flags |= Spec.FOR_SALE | Spec.RESERVED | Spec.UPPER;
        if (p.waterfront()) s.flags |= Spec.WATER | Spec.NO_BEARD;
        s.name = p.id();
        s.margin = 2;
        return s;
    }

    /** -1 when the spot can't be used; else how many removable pieces it displaces. */
    static int cost(Plan plan, Plot p, Spec s, List<Spec> victims, boolean relaxed) {
        long dx = s.x - plan.cx, dz = s.z - plan.cz;
        int limit = p.waterfront() ? plan.radius + 40 : plan.radius - 8;
        if (dx * dx + dz * dz > (long) limit * limit) return -1;
        int hx = Math.max(1, s.halfX() - s.margin), hz = Math.max(1, s.halfZ() - s.margin);
        if (p.waterfront()) {
            // half over the water: posts in the shallows, the door towards the land
            if (plan.terrain.water(s.x, s.z) == Terrain.NO_WATER && !relaxed) return -1;
        } else {
            if (plan.terrain.anyWet(s.x, s.z, hx, hz)) return -1;
            if (plan.terrain.spread(s.x, s.z, hx, hz) > (relaxed ? 14 : 7)) return -1;
        }
        int cost = 0;
        for (Spec o : plan.specs) {
            if (!Plan.collides(s, o, 1)) continue;
            if (o.isLine() || !removable(o)) return -1;
            victims.add(o);
            cost++;
        }
        return cost;
    }

    /** Pieces a home plot may displace: ordinary homes, gardens, stalls, lamps, fields and the like. */
    static boolean removable(Spec o) {
        if (o.has(Spec.FOR_SALE) || o.has(Spec.SEAT) || o.has(Spec.SIGN)) return false;
        return switch (o.kind) {
            case HOUSE, FARMHOUSE, MANOR, GARDEN, STALL, LAMP, FIELD, LUMBER, WELL, TENT, YARD, PEN -> true;
            default -> false;
        };
    }
}
