package com.skycraft.worldplan.street;

import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.terrain.TamrielHeight;
import com.skycraft.worldplan.PlanGround;
import com.skycraft.worldplan.RoadPlan;

/**
 * The plan terrain named settlements are laid out on (docs/WORLDGEN-PIPELINE.md P5): {@code TamrielHeight} without the
 * seeded detail noise, i.e. the macro elevation, the seed-independent hydrology (lake basins, river channels, banks),
 * the site pads and the road beds of the {@link RoadPlan} (P2). Inside a settlement's pad this is exactly the terrain
 * the world generates; outside it the street plan's plot and street pads pull the generated terrain to it
 * ({@link StreetPlan#pad}). It never reads the world seed, so a layout made on it is the same in every world.
 * Thread-safe (per-thread scratch), allocation-free.
 */
public final class StreetTerrain {
    private static final ThreadLocal<MapSample> SAMPLE = ThreadLocal.withInitial(MapSample::new);
    private static final ThreadLocal<PlanGround.Bed> BED = ThreadLocal.withInitial(PlanGround.Bed::new);
    private static final ThreadLocal<RoadPlan.Hit> HIT = ThreadLocal.withInitial(RoadPlan.Hit::new);
    /** Verge kept free of plots beside a planned road's bed (blocks). */
    static final float ROAD_KEEP_OUT = 2.5f;

    private StreetTerrain() {}

    /** Surface height (fractional) of the plan terrain at a column. */
    public static double height(int x, int z) {
        MapSample s = SAMPLE.get();
        TamrielMap.sample(x, z, s);
        double h = s.elevation;
        if (s.coastDistance < 0) return Math.min(h, TamrielMap.SEA_LEVEL - 1);
        boolean water = !Float.isNaN(s.waterLevel);
        if (water) {
            // TamrielHeight's lakes and rivers above sea level: basins below the water, banks above it
            double wl = s.waterLevel;
            if (s.lakeDistance > -40) {
                if (s.lakeDistance > 0) h = Math.min(h, wl - Math.min(12, 1.2 + s.lakeDistance * 0.25));
                else h = Math.max(h, wl + 1 - s.lakeDistance * 0.04);
            }
            double hw = s.riverHalfWidth;
            double bankReach = 6 + 10 * Math.max(0, Math.min(1, (hw - 1) / 3.0));
            if (hw > 0 && s.riverDistance < hw + bankReach) {
                double d = s.riverDistance;
                if (d < hw) {
                    double depth = Math.max(hw < 4 ? 1.0 + 0.25 * hw : 1.5, Math.min(5, hw * 0.3));
                    double u = d / hw;
                    h = Math.min(h, wl - 0.6 - depth * (1 - u * u));
                } else if (s.lakeDistance < -8) {
                    h = Math.max(h, wl + 1.0 + (d - hw) * 0.05);
                }
            }
        }
        PlanGround g = PlanGround.get();
        PlanGround.Bed b = BED.get();
        boolean inWater = s.inWater();
        float wBed = 0f, bedH = 0f;
        if (g.bed(x, z, b) && (!inWater || b.span == RoadPlan.FORD)) {
            wBed = b.weight;
            bedH = b.height;
        }
        boolean tidal = s.elevation < TamrielMap.SEA_LEVEL - 0.5f && s.coastDistance < 24;
        if (!inWater && !tidal && g.pad(x, z, b) && b.padWeight > 0f) h += (b.padHeight - h) * b.padWeight;
        if (wBed > 0f) h += (bedH - h) * wBed;
        return Math.min(h, TamrielHeight.MAX_SURFACE);
    }

    /**
     * One column for the walk grid: the y of the topmost solid block, and the y of the first air block over open water
     * (a plank walk's deck) or {@link Integer#MIN_VALUE} on dry land. Returns the land; {@code deck[0]} gets the deck.
     */
    public static int column(int x, int z, int[] deck) {
        int land = (int) Math.floor(height(x, z));
        MapSample s = SAMPLE.get(); // filled by height()
        deck[0] = Integer.MIN_VALUE;
        if (s.coastDistance < 0) deck[0] = TamrielMap.SEA_LEVEL;
        else if (s.inWater()) deck[0] = (int) Math.ceil(s.waterLevel);
        if (deck[0] <= land) deck[0] = Integer.MIN_VALUE;
        return land;
    }

    /** y of the topmost solid block of the plan terrain (what {@code Terrain.land} reads from the generator). */
    public static int land(int x, int z) {
        return (int) Math.floor(height(x, z));
    }

    /** True within a planned road's bed plus its verge: plots keep off it (farm fields, yards, fences, houses). */
    public static boolean roadBed(int x, int z) {
        RoadPlan.Hit h = HIT.get();
        return RoadPlan.get().at(x + 0.5, z + 0.5, h) && h.distance <= h.halfWidth + ROAD_KEEP_OUT;
    }
}
