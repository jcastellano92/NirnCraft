package com.nirncraft.tamriel.settlement.grammar;

import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.PlaceType;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.TamrielMap;
import com.nirncraft.tamriel.settlement.Culture;
import com.nirncraft.tamriel.settlement.plan.Archetype;
import com.nirncraft.tamriel.settlement.plan.Plan;
import com.nirncraft.tamriel.settlement.plan.Spec;
import com.nirncraft.tamriel.settlement.plan.Terrain;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outlying working sites around and between settlements: logging camps with felled forest (and Skyrim's lumber mills
 * on a stream), mining camps (NirnCraft ores; kwama egg mines in Morrowind; Dwemer salvage camps in the Reach and
 * Morrowind), quarries, isolated farmsteads, fishing and hunting camps, apiaries, vineyards (West Weald, Auridon),
 * moon sugar plantations (Elsweyr), saltrice paddies (Morrowind), shepherds' huts, road watchtowers and waystations.
 * A site only appears within reach of a settlement or on a road corridor of the map (wild minor locations belong to
 * the wilderness generator), its kind chosen from the region's culture, province and the ground. Workers get anchors
 * (and workplaces); chests are owned, so taking from them is theft.
 */
public final class Sites {
    private Sites() {}

    public enum Kind {LOGGING, LUMBER_MILL, MINING, KWAMA, SALVAGE, QUARRY, FARMSTEAD, FISHING, HUNTING, APIARY, VINEYARD, PLANTATION, PADDY, SHEPHERD, WATCHTOWER, WAYSTATION}

    /** What the site generator decided for a chunk, or null for no site. */
    public record Choice(Kind kind, Culture culture, int style, Direction back) {
    }

    /**
     * Picks the site for a start chunk centre from the map and the noise terrain only (worldgen-safe), or null.
     */
    @Nullable
    public static Choice choose(long seed, int x, int z, Terrain t) {
        Region region = TamrielMap.region(x, z);
        if ("sea".equals(region.province()) || "sea".equals(region.kind()) || "none".equals(region.culture())) return null;
        if (!Places.overlapping(x - 90, z - 90, x + 90, z + 90).isEmpty()) return null;
        MapSample m = new MapSample();
        TamrielMap.sample(x, z, m);
        int nearest = Integer.MAX_VALUE;
        for (PlaceType type : new PlaceType[]{PlaceType.CAPITAL, PlaceType.CITY, PlaceType.TOWN, PlaceType.VILLAGE, PlaceType.FARM}) {
            Place p = Places.nearest(type, x, z, 900).orElse(null);
            if (p != null) nearest = (int) Math.min(nearest, Math.sqrt(p.distSqr(x, z)));
        }
        boolean road = m.road > 0.3f;
        boolean nearTown = nearest < 800;
        if (!road && !nearTown) return null;
        int h = t.land(x, z);
        if (h <= t.sea || t.wet(x, z)) return null;
        int spread = t.spread(x, z, 12, 12);
        boolean water = t.anyWet(x, z, 22, 22);
        boolean hilly = spread >= 7 || m.ridge > 0.3f || m.crag > 0.3f;
        if (spread > 16) return null;
        Culture c = Culture.of(region.culture());
        String rid = region.id(), prov = region.province();
        RandomSource r = RandomSource.create(seed ^ 0x517E5L);
        Map<Kind, Integer> w = new LinkedHashMap<>();
        boolean woods = c == Culture.NORD || c == Culture.IMPERIAL || c == Culture.BRETON || c == Culture.ORC || c == Culture.ATMORAN || c == Culture.ARGONIAN;
        if (woods && !prov.equals("hammerfell") && !prov.equals("elsweyr")) w.put(water ? Kind.LUMBER_MILL : Kind.LOGGING, 4);
        if (hilly) {
            if (c == Culture.DUNMER) w.put(Kind.KWAMA, 4);
            else w.put(Kind.MINING, 4);
            if (rid.endsWith("the_reach") || prov.equals("morrowind") || rid.endsWith("craglorn")) w.put(Kind.SALVAGE, 2);
            w.put(Kind.QUARRY, 2);
            w.put(Kind.SHEPHERD, 2);
        }
        if (!hilly && c != Culture.BOSMER) w.put(Kind.FARMSTEAD, 4);
        if (water) w.put(Kind.FISHING, 4);
        w.put(Kind.HUNTING, c == Culture.BOSMER ? 5 : 2);
        if (!hilly && (c == Culture.IMPERIAL || c == Culture.BRETON || c == Culture.ALTMER || c == Culture.NORD || c == Culture.BOSMER)) w.put(Kind.APIARY, 2);
        if (!hilly && (rid.endsWith("west_weald") || rid.endsWith("auridon") || rid.endsWith("summerset_isle") || rid.endsWith("wayrest") || rid.endsWith("gold_coast"))) w.put(Kind.VINEYARD, 6);
        if (!hilly && prov.equals("elsweyr")) w.put(Kind.PLANTATION, 6);
        if (!hilly && (prov.equals("morrowind") || prov.equals("akavir") || prov.equals("black_marsh"))) w.put(Kind.PADDY, 5);
        if (road) {
            w.put(Kind.WATCHTOWER, 3);
            if (nearest > 250) w.put(Kind.WAYSTATION, 4);
        }
        int total = 0;
        for (int v : w.values()) total += v;
        if (total == 0) return null;
        int k = r.nextInt(total);
        Kind kind = Kind.HUNTING;
        for (Map.Entry<Kind, Integer> e : w.entrySet()) {
            k -= e.getValue();
            if (k < 0) {
                kind = e.getKey();
                break;
            }
        }
        int style = c == Culture.NORD ? com.nirncraft.tamriel.settlement.plan.Layouts.nordStyle(rid) : c == Culture.DUNMER ? Culture.dunmerStyle(rid) : 0;
        Direction back = Direction.from2DDataValue(r.nextInt(4));
        // water sites face their water: the back points away from it
        return new Choice(kind, c, style, back);
    }

    /** Lays out a site in the plan's local frame. */
    public static void layout(Plan plan, Kind kind) {
        RandomSource r = RandomSource.create(plan.seed ^ 0x51735L);
        switch (kind) {
            case LOGGING -> {
                building(plan, Use.SHACK, Wealth.POOR, 0, -8, Direction.SOUTH);
                camp(plan, Outdoors.FELLED, 0, 10, 17, 13);
                camp(plan, Outdoors.LOG_YARD, 14, -4, 9, 9);
                camp(plan, Outdoors.CAMPFIRE, -11, 2, 7, 7);
            }
            case LUMBER_MILL -> {
                Spec mill = building(plan, Use.SAWMILL, Wealth.COMMON, 0, -4, Direction.SOUTH);
                if (mill != null) mill.variant = 1;
                building(plan, Use.SHACK, Wealth.COMMON, -14, -6, Direction.EAST);
                camp(plan, Outdoors.LOG_YARD, 0, 12, 13, 9);
                camp(plan, Outdoors.FELLED, -14, 12, 11, 11);
            }
            case MINING -> {
                Spec mine = plan.place(plan.at(Archetype.MINE, 0, -10, Direction.SOUTH, 7, 14), 2);
                if (mine != null) {
                    mine.flag(Spec.NO_BEARD);
                    mine.variant = r.nextInt(7);
                }
                building(plan, Use.SHACK, Wealth.POOR, -12, 6, Direction.EAST);
                building(plan, Use.SHACK, Wealth.POOR, 12, 6, Direction.WEST);
                building(plan, Use.SMELTER, Wealth.COMMON, 0, 14, Direction.NORTH);
                yard(plan, Outdoors.CRATES, -4, 4, 3, 2);
                yard(plan, Outdoors.CART, 4, 3, 3, 1);
            }
            case KWAMA -> {
                Spec mine = plan.place(plan.at(Archetype.MINE, 0, -10, Direction.SOUTH, 7, 14), 2);
                if (mine != null) {
                    mine.flag(Spec.NO_BEARD);
                    mine.variant = 6;
                    mine.name = "kwama_mine";
                }
                camp(plan, Outdoors.KWAMA, 0, 8, 11, 7);
                building(plan, Use.SHACK, Wealth.POOR, -13, 4, Direction.EAST);
                building(plan, Use.SHACK, Wealth.POOR, 13, 4, Direction.WEST);
            }
            case SALVAGE -> {
                camp(plan, Outdoors.SALVAGE, 0, 0, 13, 11);
                plan.place(plan.at(Archetype.TENT, -10, -8, Direction.SOUTH, 5, 7), 2);
                plan.place(plan.at(Archetype.TENT, 10, -8, Direction.SOUTH, 5, 7), 2);
                building(plan, Use.SHACK, Wealth.POOR, 0, 12, Direction.NORTH);
            }
            case QUARRY -> {
                Spec q = plan.place(plan.at(Archetype.QUARRY, 0, -6, Direction.SOUTH, 17, 17), 2);
                if (q != null) {
                    q.h = 9;
                    q.flag(Spec.NO_BEARD);
                }
                building(plan, Use.SHACK, Wealth.POOR, -16, 10, Direction.EAST);
                yard(plan, Outdoors.CRATES, 10, 12, 3, 2);
                yard(plan, Outdoors.CART, 4, 12, 3, 1);
            }
            case FARMSTEAD -> Zoning.farmstead(plan);
            case FISHING -> {
                building(plan, Use.FISHERY, Wealth.POOR, 0, -6, Direction.SOUTH);
                camp(plan, Outdoors.DRYING, 0, 8, 11, 7);
                for (int i = -1; i <= 1; i += 2) {
                    Spec d = plan.at(Archetype.DOCK, i * 10, 20, Direction.SOUTH, 5, 13);
                    d.flags |= Spec.NO_BEARD;
                    plan.place(d, 1);
                }
                building(plan, Use.SHACK, Wealth.POOR, -13, -6, Direction.EAST);
            }
            case HUNTING -> {
                camp(plan, Outdoors.HUNTING, 0, 0, 11, 9);
                plan.place(plan.at(Archetype.TENT, -9, -7, Direction.SOUTH, 5, 7), 2);
                plan.place(plan.at(Archetype.TENT, 9, -7, Direction.SOUTH, 5, 7), 2);
                yard(plan, Outdoors.WOODPILE, 0, -9, 4, 2);
            }
            case APIARY -> {
                building(plan, Use.COTTAGE, Wealth.COMMON, 0, -8, Direction.SOUTH);
                camp(plan, Outdoors.APIARY, -8, 10, 11, 9);
                camp(plan, Outdoors.APIARY, 8, 10, 11, 9);
                yard(plan, Outdoors.FLOWER_BED, 10, -6, 5, 3);
            }
            case VINEYARD -> {
                building(plan, Use.FARMHOUSE, Wealth.WEALTHY, 0, -10, Direction.SOUTH);
                building(plan, Use.WAREHOUSE, Wealth.COMMON, 16, -10, Direction.WEST);
                for (int i = 0; i < 3; i++) camp(plan, Outdoors.VINEYARD, -14 + i * 14, 10, 13, 13);
            }
            case PLANTATION -> {
                building(plan, Use.FARMHOUSE, Wealth.COMMON, 0, -10, Direction.SOUTH);
                building(plan, Use.WAREHOUSE, Wealth.COMMON, 16, -8, Direction.WEST);
                camp(plan, Outdoors.SUGAR, -8, 10, 13, 11);
                camp(plan, Outdoors.SUGAR, 8, 10, 13, 11);
            }
            case PADDY -> {
                building(plan, Use.COTTAGE, Wealth.POOR, 0, -10, Direction.SOUTH);
                camp(plan, Outdoors.PADDY, -9, 8, 13, 13);
                camp(plan, Outdoors.PADDY, 9, 8, 13, 13);
            }
            case SHEPHERD -> {
                building(plan, Use.SHACK, Wealth.POOR, 0, -8, Direction.SOUTH);
                for (int i = -1; i <= 1; i += 2) {
                    Spec pen = plan.place(plan.at(Archetype.PEN, i * 9, 8, Direction.SOUTH, 13, 11), 1);
                    if (pen != null) pen.variant = 1;
                }
                yard(plan, Outdoors.HAY, 0, 3, 3, 2);
            }
            case WATCHTOWER -> {
                building(plan, Use.WATCHTOWER, Wealth.COMMON, 0, 0, Direction.SOUTH);
                yard(plan, Outdoors.BRAZIER, 6, 4, 1, 1);
                yard(plan, Outdoors.WOODPILE, -6, 4, 4, 2);
                yard(plan, Outdoors.DUMMY, 6, -4, 2, 2);
            }
            case WAYSTATION -> {
                plan.street(-plan.radius, 10, plan.radius, 10, 3, false);
                Spec inn = building(plan, Use.INN, Wealth.COMMON, 0, -4, Direction.SOUTH);
                if (inn != null) inn.size(13, 15);
                building(plan, Use.STABLES, Wealth.COMMON, 18, -2, Direction.SOUTH);
                yard(plan, Outdoors.SHRINE, -12, 6, 2, 1);
                yard(plan, Outdoors.WELL, -16, -4, 3, 3);
                yard(plan, Outdoors.LAMP_POST, -6, 8, 1, 1);
                yard(plan, Outdoors.LAMP_POST, 8, 8, 1, 1);
                yard(plan, Outdoors.SIGNPOST, 0, 12, 1, 1);
            }
        }
        Zoning.variety(plan);
    }

    static Spec building(Plan plan, Use use, Wealth w, int lx, int lz, Direction face) {
        RandomSource r = RandomSource.create(plan.seed ^ lx * 31L ^ lz * 17L);
        int[] sz = use.size(w, r);
        Spec s = plan.at(use.archetype(), lx, lz, face, sz[0], sz[1]);
        s.use(use, w);
        s.margin = use == Use.SAWMILL ? 4 : 2;
        s.culture = plan.culture.ordinal();
        s.style = plan.style;
        s.gseed = (int) Zoning.mix(plan.seed, s.x, s.z);
        s.slope = plan.terrain.spread(s.x, s.z, Math.max(1, s.halfX() - s.margin), Math.max(1, s.halfZ() - s.margin));
        Spec out = plan.place(s, 2);
        if (out == null) {
            plan.relaxed = true;
            out = plan.place(s, 1);
            plan.relaxed = false;
        }
        return out;
    }

    static Spec camp(Plan plan, int kind, int lx, int lz, int w, int d) {
        Spec s = plan.at(Archetype.CAMP, lx, lz, Direction.SOUTH, w, d);
        s.variant = kind;
        s.margin = 0;
        return plan.place(s, 1);
    }

    static Spec yard(Plan plan, int kind, int lx, int lz, int w, int d) {
        Spec s = plan.at(Archetype.YARD, lx, lz, Direction.SOUTH, w, d);
        s.variant = kind;
        s.margin = 0;
        s.h = 6;
        return plan.place(s, 0);
    }
}
