package com.nirncraft.tamriel.terrain;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.TamrielMap;
import com.nirncraft.worldplan.PlanGround;
import com.nirncraft.worldplan.RoadPlan;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.*;
import com.nirncraft.worldgen.WorldNoise;
import com.nirncraft.worldgen.WorldDensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;

/**
 * Density function {@code nirncraft:tamriel_height}: the terrain surface height of a column in blocks. The map gives
 * the macro shape; seeded noises add the local detail, scaled by the map's terrain character:
 * <ul>
 *   <li>rolling hills scaled by roughness (gentle tundra, flat marsh),</li>
 *   <li>jagged ridged peaks along mountain ridges,</li>
 *   <li>cliff terraces where the map has crags (the Reach, Craglorn, Anequina),</li>
 *   <li>dunes in the deserts,</li>
 *   <li>river channels and lake basins carved under the map's water level, with banks that hold the water.</li>
 * </ul>
 * 2D: wrap it in {@code minecraft:cache_2d}. Thread-safe; the noises are immutable once wired by the noise router.
 */
public record TamrielHeight(WorldNoise hills, WorldNoise detail,
                            WorldNoise ridges, WorldNoise dunes)
        implements DensityFunction {

    public static final MapCodec<TamrielHeight> DATA_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            WorldNoise.CODEC.fieldOf("hills").forGetter(TamrielHeight::hills),
            WorldNoise.CODEC.fieldOf("detail").forGetter(TamrielHeight::detail),
            WorldNoise.CODEC.fieldOf("ridges").forGetter(TamrielHeight::ridges),
            WorldNoise.CODEC.fieldOf("dunes").forGetter(TamrielHeight::dunes)
    ).apply(i, TamrielHeight::new));
    public static final MapCodec<TamrielHeight> CODEC = DATA_CODEC;

    /** Highest surface the terrain may reach (the noise fills up to y 575; the world top is y 639). */
    public static final double MAX_SURFACE = 568;

    public double compute(int blockX, int blockY, int blockZ) {
        int x = blockX, z = blockZ;
        // per-thread column memo (perf): cache_2d keeps one column, the biome fill and structure probes alternate
        Object owner = hills.noise() != null ? hills.noise() : this;
        com.nirncraft.perf.ColumnCache.Table t = com.nirncraft.perf.ColumnCache.table();
        int slot = t.slot(x, z);
        boolean memo = PlanGround.blending(); // the dev diff harness modes are never memoised
        if (memo && !com.nirncraft.perf.ColumnCache.OFF && t.hit(slot, owner, x, z)) return t.value(slot);
        double v = height(x, z, TamrielMap.sampleScratch(x, z));
        if (memo) t.put(slot, owner, x, z, v);
        return v;
    }

    /** Surface height at (x, z) for an already filled sample of that column. */
    public double height(int x, int z, MapSample s) {
        double h = s.elevation;
        double cd = s.coastDistance;
        double hillsN = hills.getValue(x, 0, z);
        double detailN = detail.getValue(x, 0, z);

        if (cd < 0) {
            // sea floor: a little relief, more in shallow water, plus procedural open-ocean micro-islands
            double floor = Math.min(h + (hillsN * 5 + detailN * 1.5) * (0.4 + 0.6 * MapNoise.smoothstep(-600, 0, cd)), TamrielMap.SEA_LEVEL - 1);
            if (cd < -96) {
                double isle = OceanIsles.surfaceHeight(x, z, cd, hillsN, detailN);
                if (!Double.isNaN(isle)) {
                    double w = MapNoise.smoothstep(-96, -136, cd);
                    return Math.max(floor, floor + (isle - floor) * w);
                }
            }
            return floor;
        }

        // P2: the road plan and the site pads (docs/WORLDGEN-PIPELINE.md section 7): bed and pad weights, applied last
        float wBed = 0f, wPad = 0f, wPlot = 0f;
        PlanGround.Bed pb = null;
        if (PlanGround.enabled()) {
            PlanGround g = PlanGround.getAt(x, z);
            pb = PlanGround.scratch();
            boolean inWater = s.inWater();
            // channel columns (river, stream, lake) are never raised or cut by a road, except by a ford: culverts stay
            // open under their embankment and a road next to a stream does not dam it
            if (g.bed(x, z, pb) && (!inWater || pb.span == RoadPlan.FORD)) wBed = pb.weight;
            boolean tidal = s.elevation < TamrielMap.SEA_LEVEL - 0.5f && cd < 24;
            if (!inWater && !tidal && g.pad(x, z, pb)) wPad = pb.padWeight;
            // P5: the street plan's plots and streets (named settlements) are pads too
            if (!inWater && g.plot(x, z, pb)) wPlot = pb.plotWeight;
        }

        // detail amplitude: calm at the shoreline, along roads and in towns, next to water, on ledges and paths; and
        // fading out over the planned road beds and pads, so the profile never sits far from the natural ground
        double amp = MapNoise.smoothstep(0, 56, cd) * (1 - 0.75 * s.road) * (1 - s.calm)
                * (1 - PlanGround.CALM * Math.max(wBed, Math.max(wPad, wPlot)));
        boolean water = !Float.isNaN(s.waterLevel);
        if (water) {
            // brooks calm the ground over a shorter reach than rivers (every authored river has a half width of 4+)
            double reach = 30 + 40 * MapNoise.clamp((s.riverHalfWidth - 1) / 3.0, 0, 1);
            amp *= Math.min(MapNoise.smoothstep(s.riverHalfWidth + 4, s.riverHalfWidth + reach, s.riverDistance),
                    MapNoise.smoothstep(0, 70, -s.lakeDistance));
        }

        // Erosion-like damping: the fine detail fades on steep macro slopes (run-off smooths the flanks into gullies
        // and talus) and stays on crests, flats and valley floors, like derivative-damped ("eroded") fBm.
        double smooth = 1 / (1 + 6 * s.slope * s.slope);

        // rolling hills
        h += amp * s.roughness * (hillsN * 22 + detailN * 6.5 * smooth);

        // jagged peaks along ridges, more so high up (a sharp peak brings its own crags instead); a ridged
        // multifractal: the fine octave rides on the crests of the coarse one, so the valleys between stay smooth
        double own = Math.min(1.0, s.peak / 8.0);
        if (s.ridge > 0.02f && own < 1) {
            double r1 = 1 - Math.abs(ridges.getValue(x, 0, z));
            double r2 = 1 - Math.abs(detail.getValue(x * 2.1, 37, z * 2.1));
            double high = 1 + 0.6 * MapNoise.smoothstep(150, 300, h);
            h += amp * (1 - own) * StrictMath.pow(s.ridge, 1.3) * high
                    * (r1 * r1 * 36 + r1 * r2 * r2 * 12 * (0.4 + 0.6 * smooth) - 15);
        }
        if (s.peak > 0) {
            // aretes, buttresses and gullies down a horn peak's flanks
            double r1 = 1 - Math.abs(ridges.getValue(x * 1.7, 113, z * 1.7));
            double r2 = 1 - Math.abs(detail.getValue(x * 3.3, 131, z * 3.3));
            h += amp * s.peak * ((r1 * r1 - 0.45) + 0.4 * r1 * (r2 * r2 - 0.45) * (0.4 + 0.6 * smooth));
        }

        // cliff bands: only where the rock is steep (the macro slope), with uneven step heights and the bands broken
        // and offset along their length, not contour lines repeated over every hillside
        if (s.crag > 0.05f) {
            double steep = MapNoise.smoothstep(0.25, 0.85, s.slope);
            double w = s.crag * 0.85 * amp * (0.15 + 0.85 * steep);
            if (w > 0.01) {
                double step = 8 + 4 * hillsN;
                double off = dunes.getValue(x * 0.22, 61, z * 0.22) * 9;
                double t = (h + off) / step;
                double fl = Math.floor(t);
                double terraced = (fl + MapNoise.smoothstep(0.5, 0.9, t - fl)) * step - off;
                h += (terraced - h) * w;
            }
        }

        // dunes: long crests across the prevailing wind
        if (s.dunes > 0.02f) {
            double dn = dunes.getValue(x * 0.8 + z * 0.35, 0, z * 1.7 - x * 0.2);
            double crest = 1 - Math.abs(dn);
            h += amp * s.dunes * (crest * crest * 11 - 2);
        }

        // wetlands: keep the ground hovering around the water table so hollows hold pools
        if (s.wetland > 0.05f && h < TamrielMap.SEA_LEVEL + 10) {
            double wl = TamrielMap.SEA_LEVEL + 0.4 + detailN * 1.8 + hillsN * 0.8 + Math.max(0, hillsN) * 2.5;
            h += (wl - h) * s.wetland * 0.6;
            if (s.wetland > 0.3f) {
                // a maze of meandering channels a few blocks wide between the hummocks (the water feature fills
                // them up to sea level)
                double c1 = Math.abs(ridges.getValue(x * 1.9, 57, z * 1.9));
                double c2 = Math.abs(hills.getValue(x * 3.6 + 400, 23, z * 3.6)) * 1.4;
                double k = 1 - Math.min(c1, c2) / 0.11;
                if (k > 0) {
                    double bed = TamrielMap.SEA_LEVEL - 1.2 - 2.4 * k;
                    double w = MapNoise.smoothstep(0.3, 0.8, s.wetland) * Math.min(1, k * 3) * (1 - 0.8 * s.road);
                    h += (Math.min(bed, h) - h) * w;
                }
            }
        }

        // lakes and rivers above sea level: basins below the water, banks above it
        if (water) {
            double wl = s.waterLevel;
            if (s.lakeDistance > -40) {
                if (s.lakeDistance > 0) {
                    h = Math.min(h, wl - Math.min(12, 1.2 + s.lakeDistance * 0.25));
                } else {
                    h = Math.max(h, wl + 1 - s.lakeDistance * 0.04);
                }
            }
            double hw = s.riverHalfWidth;
            double bankReach = 6 + 10 * MapNoise.clamp((hw - 1) / 3.0, 0, 1);
            if (hw > 0 && s.riverDistance < hw + bankReach) {
                double d = s.riverDistance;
                if (d < hw) {
                    double depth = MapNoise.clamp(hw * 0.3, hw < 4 ? 1.0 + 0.25 * hw : 1.5, 5);
                    double u = d / hw;
                    h = Math.min(h, wl - 0.6 - depth * (1 - u * u));
                } else if (s.lakeDistance < -8) {
                    h = Math.max(h, wl + 1.0 + (d - hw) * 0.05);
                }
            }
        }
        // site pads, then the road bed (profile + shoulders); the bed wins where they meet (roads end on the pad height)
        if (PlanGround.blending()) {
            if (wPad > 0f) h += (pb.padHeight - h) * wPad;
            if (wPlot > 0f) h += (pb.plotHeight - h) * wPlot;
            if (wBed > 0f) h += (pb.height - h) * wBed;
        }
        return Math.min(h, MAX_SURFACE);
    }





    public double minValue() {
        return -64;
    }

    public double maxValue() {
        return MAX_SURFACE;
    }

    @Override public DensitySampler compileSampler(CompileContext context) {
        var bound = new TamrielHeight(hills.bind(context), detail.bind(context), ridges.bind(context), dunes.bind(context));
        return (WorldDensitySampler) (sampling, x, y, z) -> (float) bound.compute(x, y, z);
    }

    @Override public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; }
    @Override public Interval range() { return Interval.of((float) minValue(), (float) maxValue()); }
    @Override public int domainAxes() { return DensityFunction.AXIS_X | DensityFunction.AXIS_Z; }

    @Override
    public MapCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
