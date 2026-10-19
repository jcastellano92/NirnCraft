package com.nirncraft.tamriel.settlement.plan;

import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.PlaceType;
import com.nirncraft.tamriel.settlement.build.Landmarks;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Guild halls and jails added to named settlements after their layout (unique or generic) is done: every hold
 * capital and city gets a jail beside its keep or palace (Castle Dour's dungeon in Solitude, Cidhna Mine in
 * Markarth...), and the faction halls of the lore live in their cities (the Fighters Guild in Chorrol, Redoran in
 * Ald'ruhn, Telvanni in Sadrith Mora...). A hall goes into a free lot inside the town; a few stand outside the walls
 * by design (Cloud Ruler Temple, the Priory of the Nine) or when the town has no room left.
 *
 * <p>Pieces are only appended, so the rest of an existing layout keeps its indices and positions. The hall table is
 * also read by the quest module (faction halls in the journal, hall-bound quest NPCs).</p>
 */
public final class Halls {
    private Halls() {}

    /** A faction hall in a place; {@code outside} halls stand beyond the walls. */
    public record Hall(String faction, boolean outside) {
    }

    private static final Map<String, List<Hall>> BY_PLACE = new LinkedHashMap<>();

    private static void in(String place, String... factions) {
        List<Hall> list = BY_PLACE.computeIfAbsent(place, k -> new ArrayList<>());
        for (String f : factions) list.add(new Hall(f, false));
    }

    private static void outside(String place, String faction) {
        BY_PLACE.computeIfAbsent(place, k -> new ArrayList<>()).add(new Hall(faction, true));
    }

    static {
        // Cyrodiil
        in("imperial_city", "mages_guild", "arena", "imperial_legion", "thieves_guild", "undaunted");
        in("chorrol", "fighters_guild", "mages_guild");
        in("anvil", "fighters_guild", "mages_guild", "east_empire_company");
        in("bravil", "fighters_guild", "mages_guild");
        in("bruma", "fighters_guild", "mages_guild");
        outside("bruma", "blades");
        in("cheydinhal", "fighters_guild", "mages_guild", "dark_brotherhood");
        in("leyawiin", "fighters_guild", "mages_guild");
        in("skingrad", "fighters_guild", "mages_guild");
        outside("kvatch", "knights_of_the_nine");
        // Morrowind
        in("balmora", "house_hlaalu", "fighters_guild", "mages_guild", "tribunal_temple", "morag_tong", "camonna_tong");
        in("aldruhn", "house_redoran", "fighters_guild", "mages_guild", "tribunal_temple", "morag_tong");
        outside("aldruhn", "ashlanders");
        in("sadrith_mora", "house_telvanni", "mages_guild", "morag_tong");
        in("vivec", "morag_tong", "house_hlaalu");
        in("mournhold", "tribunal_temple", "undaunted");
        in("necrom", "tribunal_temple");
        in("ebonheart", "east_empire_company", "undaunted");
        in("raven_rock", "house_redoran", "east_empire_company");
        // High Rock and Orsinium
        in("wayrest", "knights_of_the_rose", "fighters_guild", "mages_guild", "undaunted");
        in("daggerfall", "knights_of_the_dragon", "fighters_guild", "mages_guild", "undaunted");
        in("orsinium", "orc_strongholds");
        // Hammerfell
        in("sentinel", "crowns", "fighters_guild", "mages_guild");
        in("skaven", "crowns");
        in("taneth", "forebears");
        in("rihad", "forebears");
        // Black Marsh, Elsweyr, Valenwood, Summerset
        outside("stormhold", "shadowscales");
        in("lilmoth", "undaunted");
        in("torval", "moon_priests");
        in("senchal", "moon_priests", "east_empire_company");
        in("rimmen", "undaunted");
        outside("rimmen", "renrijra_krin");
        in("elden_root", "green_pact", "undaunted");
        in("falinesti", "green_pact");
        in("alinor", "thalmor", "aldmeri_dominion");
        in("firsthold", "aldmeri_dominion", "undaunted");
        // Skyrim
        in("riften", "undaunted");
    }

    /** The halls a place gets (empty for most). */
    public static List<Hall> at(String placeId) {
        return BY_PLACE.getOrDefault(placeId, Collections.emptyList());
    }

    /** Place ids where a faction keeps a hall, in table order. */
    public static List<String> placesOf(String faction) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<Hall>> e : BY_PLACE.entrySet()) {
            for (Hall h : e.getValue()) if (h.faction().equals(faction)) out.add(e.getKey());
        }
        return out;
    }

    // ------------------------------------------------------------------ jails

    /**
     * Every hold capital and city keeps a jail (Skyrim's hold capitals are all capitals), and so does every major town
     * (the watch rule of {@code society.GuardWatch}); a minor town keeps a guardhouse, see {@link #guardhouse}.
     */
    public static boolean hasJail(Place p) {
        if (p.type() == PlaceType.CAPITAL || p.type() == PlaceType.CITY) return true;
        return p.type() == PlaceType.TOWN && com.nirncraft.society.GuardWatch.of(p) != com.nirncraft.society.GuardWatch.Watch.NONE;
    }

    /** A minor town's jail is a guardhouse: one low building with one to three cells above ground (build.Jails). */
    public static boolean guardhouse(Place p) {
        return hasJail(p) && p.type() == PlaceType.TOWN && com.nirncraft.society.GuardWatch.of(p) == com.nirncraft.society.GuardWatch.Watch.MINOR;
    }

    /** The jail's name (its lang key {@code settlement.nirncraft.name.<name>}); generic towns get {@code jail}. */
    public static String jailName(String placeId) {
        return switch (placeId) {
            case "whiterun" -> "dragonsreach_dungeon";
            case "solitude" -> "castle_dour_dungeon";
            case "windhelm" -> "palace_of_the_kings_dungeon";
            case "riften" -> "mistveil_keep_jail";
            case "markarth" -> "cidhna_mine";
            case "winterhold" -> "winterhold_jail";
            case "falkreath" -> "falkreath_jail";
            case "morthal" -> "morthal_jail";
            case "dawnstar" -> "dawnstar_jail";
            case "imperial_city" -> "imperial_prison";
            case "mournhold" -> "mournhold_dungeon";
            case "vivec" -> "vivec_hall_of_justice";
            case "wayrest" -> "wayrest_dungeon";
            case "sentinel" -> "sentinel_dungeon";
            case "orsinium" -> "orsinium_cells";
            default -> "jail";
        };
    }

    // ------------------------------------------------------------------ placing

    /** Adds the place's jail and halls to its plan (before {@link Plan#resolve}). */
    public static void addTo(Plan plan, Place p) {
        if (plan.key.startsWith("gen:")) return;
        if (hasJail(p)) {
            Spec jail = new Spec(Archetype.JAIL);
            jail.name = jailName(p.id());
            jail.variant = p.id().equals("markarth") ? 1 : 0;
            jail.margin = 3;
            if (guardhouse(p)) {
                jail.variant = com.nirncraft.tamriel.settlement.build.Jails.GUARDHOUSE;
                jail.size(com.nirncraft.tamriel.settlement.build.Jails.GW, com.nirncraft.tamriel.settlement.build.Jails.GD, 6);
                jail.margin = 2;
            }
            jail.flags |= Spec.SIGN;
            Spec ref = keep(plan, p.id());
            boolean ok = ref != null && around(plan, jail, ref);
            if (!ok) ok = alongStreet(plan, jail);
            if (!ok) ok = ring(plan, jail, 18, plan.radius - 6);
            if (!ok) outside(plan, jail);
        }
        for (Hall h : at(p.id())) {
            Spec hall = new Spec(Archetype.GUILD_HALL);
            hall.name = h.faction();
            hall.flags |= Spec.UPPER | Spec.SIGN;
            hall.margin = 2;
            boolean ok = !h.outside() && (alongStreet(plan, hall) || ring(plan, hall, 16, plan.radius - 6));
            if (!ok) outside(plan, hall);
        }
    }

    /** Logs where the jail and halls of a resolved plan stand (and which halls found no room). */
    public static void report(Plan plan, Place p) {
        if (plan.key.startsWith("gen:") || !hasJail(p) && at(p.id()).isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        int halls = 0;
        for (Spec s : plan.specs) {
            if (s.kind != Archetype.JAIL && s.kind != Archetype.GUILD_HALL) continue;
            if (s.kind == Archetype.GUILD_HALL) halls++;
            sb.append(' ').append(s.name).append('@').append(s.x).append(',').append(s.floor).append(',').append(s.z)
                    .append('/').append(s.facing.getName());
        }
        int missing = at(p.id()).size() - halls;
        com.nirncraft.NirnCraft.LOGGER.info("Halls of {}:{}{}", p.id(), sb, missing > 0 ? " (" + missing + " hall(s) found no room)" : "");
    }

    /** The building a jail belongs next to: the keep, castle or palace (Cidhna Mine's mouth in Markarth). */
    static Spec keep(Plan plan, String placeId) {
        Spec seat = null, castle = null;
        for (Spec s : plan.specs) {
            if (placeId.equals("markarth") && "cidhna_mine".equals(s.name)) return s;
            if (s.kind == Archetype.LANDMARK && s.variant == Landmarks.CASTLE && castle == null) castle = s;
            if (seat == null && (s.has(Spec.SEAT) || s.kind == Archetype.PALACE)) seat = s;
            if (seat == null && s.kind == Archetype.LANDMARK && (s.variant == Landmarks.DOME_PALACE || s.variant == Landmarks.BLUE_PALACE)) seat = s;
        }
        if (placeId.equals("solitude") && castle != null) return castle;
        return seat != null ? seat : castle;
    }

    /** Tries spots on all sides of {@code ref}, front facing it, on its terrace level. */
    static boolean around(Plan plan, Spec s, Spec ref) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, -1}, {0, 1}, {1, -1}, {-1, -1}, {1, 1}, {-1, 1}};
        for (int pass = 0; pass < 2; pass++) {
            plan.relaxed = pass == 1;
            for (int extra = 3; extra <= 27; extra += 6) {
                for (int[] d : dirs) {
                    Direction face = Math.abs(d[0]) >= Math.abs(d[1])
                            ? (d[0] > 0 ? Direction.WEST : Direction.EAST)
                            : (d[1] > 0 ? Direction.NORTH : Direction.SOUTH);
                    s.facing = face;
                    s.x = ref.centerX() + d[0] * (ref.halfX() + s.halfX() + extra);
                    s.z = ref.centerZ() + d[1] * (ref.halfZ() + s.halfZ() + extra);
                    s.minFloor = ref.minFloor;
                    if (plan.place(s, 2) != null) {
                        plan.relaxed = false;
                        return true;
                    }
                }
            }
        }
        plan.relaxed = false;
        s.minFloor = Spec.UNSET;
        return false;
    }

    /** Places {@code s} on a free lot along an existing street, facing the road. */
    static boolean alongStreet(Plan plan, Spec s) {
        List<Spec> streets = new ArrayList<>();
        for (Spec o : plan.specs) {
            if (o.kind == Archetype.STREET) streets.add(o);
        }
        if (streets.isEmpty()) return false;
        streets.sort((a, b) -> Boolean.compare(b.has(Spec.MAJOR), a.has(Spec.MAJOR)));
        int setback = s.d / 2 + Math.max(1, s.margin) + 2;
        for (int pass = 0; pass < 2; pass++) {
            plan.relaxed = pass == 1;
            for (Spec st : streets) {
                double dx = st.x2 - st.x, dz = st.z2 - st.z;
                double len = Math.hypot(dx, dz);
                if (len < s.w + 4) continue;
                double ux = dx / len, uz = dz / len;
                double nx = -uz, nz = ux;
                int dist = st.w / 2 + setback;
                int steps = Math.max(1, (int) ((len - s.w) / 6));
                for (int k = 0; k <= steps; k++) {
                    double t = s.w / 2.0 + (len - s.w) * (k / (double) Math.max(1, steps));
                    for (int side : new int[]{1, -1}) {
                        int wx = (int) Math.round(st.x + ux * t + nx * side * dist);
                        int wz = (int) Math.round(st.z + uz * t + nz * side * dist);
                        if (Math.hypot(wx - plan.cx, wz - plan.cz) > plan.radius - 4) continue;
                        Direction face = Math.abs(nx) >= Math.abs(nz)
                                ? (-nx * side > 0 ? Direction.EAST : Direction.WEST)
                                : (-nz * side > 0 ? Direction.SOUTH : Direction.NORTH);
                        Spec c = Spec.at(s.kind, wx, wz, face).size(s.w, s.d, s.h);
                        copy(s, c);
                        if (plan.place(c, 2) != null) {
                            plan.relaxed = false;
                            return true;
                        }
                    }
                }
            }
        }
        plan.relaxed = false;
        return false;
    }

    /** Tries spots on rings round the centre between {@code r0} and {@code r1}, front facing the centre. */
    static boolean ring(Plan plan, Spec s, int r0, int r1) {
        long salt = plan.seed ^ s.name.hashCode();
        for (int pass = 0; pass < 2; pass++) {
            plan.relaxed = pass == 1;
            for (int r = r0; r <= r1; r += 6) {
                int steps = Math.max(8, (int) (2 * Math.PI * r / 10));
                int start = (int) Math.floorMod(salt, steps);
                for (int k = 0; k < steps; k++) {
                    double a = 2 * Math.PI * ((start + k) % steps) / steps;
                    int lx = (int) Math.round(Math.sin(a) * r), lz = (int) Math.round(Math.cos(a) * r);
                    Spec c = plan.at(s.kind, lx, lz, Plan.outward(a + Math.PI)).size(s.w, s.d, s.h);
                    copy(s, c);
                    if (plan.place(c, 2) != null) {
                        plan.relaxed = false;
                        return true;
                    }
                }
            }
        }
        plan.relaxed = false;
        return false;
    }

    /** Beside the town, beyond its walls but within reach of its structure start (Cloud Ruler Temple...). */
    static boolean outside(Plan plan, Spec s) {
        int r0 = plan.radius + 12, r1 = Math.max(r0, Math.min(118, plan.radius + 48));
        long salt = plan.seed ^ s.name.hashCode() * 31L;
        for (int r = r0; r <= r1; r += 6) {
            int steps = Math.max(12, (int) (2 * Math.PI * r / 12));
            int start = (int) Math.floorMod(salt, steps);
            for (int k = 0; k < steps; k++) {
                double a = 2 * Math.PI * ((start + k) % steps) / steps;
                int lx = (int) Math.round(Math.sin(a) * r), lz = (int) Math.round(Math.cos(a) * r);
                Spec c = plan.at(s.kind, lx, lz, Plan.outward(a + Math.PI)).size(s.w, s.d, s.h);
                copy(s, c);
                if (fitsOutside(plan, c)) {
                    plan.put(c);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean fitsOutside(Plan plan, Spec s) {
        int hx = Math.max(1, s.halfX() - s.margin), hz = Math.max(1, s.halfZ() - s.margin);
        if (plan.terrain.anyWet(s.x, s.z, hx, hz)) return false;
        if (plan.terrain.spread(s.x, s.z, hx, hz) > 12) return false;
        for (Spec o : plan.specs) if (Plan.collides(s, o, 3)) return false;
        return true;
    }

    private static void copy(Spec from, Spec to) {
        to.name = from.name;
        to.flags = from.flags;
        to.variant = from.variant;
        to.margin = from.margin;
        to.minFloor = from.minFloor;
    }
}
