package com.skycraft.tamriel.terrain;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Density function {@code skycraft:tamriel_height}: the terrain surface height of a column in blocks. The map gives
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
public record TamrielHeight(DensityFunction.NoiseHolder hills, DensityFunction.NoiseHolder detail,
                            DensityFunction.NoiseHolder ridges, DensityFunction.NoiseHolder dunes)
        implements DensityFunction {

    public static final MapCodec<TamrielHeight> DATA_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            NoiseHolder.CODEC.fieldOf("hills").forGetter(TamrielHeight::hills),
            NoiseHolder.CODEC.fieldOf("detail").forGetter(TamrielHeight::detail),
            NoiseHolder.CODEC.fieldOf("ridges").forGetter(TamrielHeight::ridges),
            NoiseHolder.CODEC.fieldOf("dunes").forGetter(TamrielHeight::dunes)
    ).apply(i, TamrielHeight::new));
    public static final KeyDispatchDataCodec<TamrielHeight> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    /** Highest surface the terrain may reach (world top is y 383). */
    public static final double MAX_SURFACE = 378;

    @Override
    public double compute(FunctionContext ctx) {
        return height(ctx.blockX(), ctx.blockZ(), TamrielMap.sampleScratch(ctx.blockX(), ctx.blockZ()));
    }

    /** Surface height at (x, z) for an already filled sample of that column. */
    public double height(int x, int z, MapSample s) {
        double h = s.elevation;
        double cd = s.coastDistance;
        double hillsN = hills.getValue(x, 0, z);
        double detailN = detail.getValue(x, 0, z);

        if (cd < 0) {
            // sea floor: a little relief, more in shallow water
            return Math.min(h + (hillsN * 5 + detailN * 1.5) * (0.4 + 0.6 * MapNoise.smoothstep(-600, 0, cd)), TamrielMap.SEA_LEVEL - 1);
        }

        // detail amplitude: calm at the shoreline, along roads and next to water
        double amp = MapNoise.smoothstep(0, 56, cd) * (1 - 0.75 * s.road);
        boolean water = !Float.isNaN(s.waterLevel);
        if (water) {
            amp *= Math.min(MapNoise.smoothstep(s.riverHalfWidth + 4, s.riverHalfWidth + 70, s.riverDistance),
                    MapNoise.smoothstep(0, 70, -s.lakeDistance));
        }

        // rolling hills
        h += amp * s.roughness * (hillsN * 14 + detailN * 4.5);

        // jagged peaks along ridges
        if (s.ridge > 0.02f) {
            double r1 = 1 - Math.abs(ridges.getValue(x, 0, z));
            double r2 = 1 - Math.abs(detail.getValue(x * 2.1, 37, z * 2.1));
            h += amp * Math.pow(s.ridge, 1.3) * (r1 * r1 * 36 + r2 * r2 * 9 - 13);
        }

        // cliff terraces
        if (s.crag > 0.05f) {
            double step = 7 + 3 * hillsN;
            double t = h / step;
            double fl = Math.floor(t);
            double terraced = (fl + MapNoise.smoothstep(0.55, 0.9, t - fl)) * step;
            h += (terraced - h) * s.crag * 0.85 * amp;
        }

        // dunes: long crests across the prevailing wind
        if (s.dunes > 0.02f) {
            double dn = dunes.getValue(x * 0.8 + z * 0.35, 0, z * 1.7 - x * 0.2);
            double crest = 1 - Math.abs(dn);
            h += amp * s.dunes * (crest * crest * 11 - 2);
        }

        // wetlands: keep the ground hovering around the water table so hollows hold pools
        if (s.wetland > 0.05f && h < TamrielMap.SEA_LEVEL + 10) {
            double wl = TamrielMap.SEA_LEVEL + 0.4 + detailN * 1.8 + hillsN * 0.8;
            h += (wl - h) * s.wetland * 0.6;
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
            if (hw > 0 && s.riverDistance < hw + 16) {
                double d = s.riverDistance;
                if (d < hw) {
                    double depth = MapNoise.clamp(hw * 0.3, 1.5, 5);
                    double u = d / hw;
                    h = Math.min(h, wl - 0.6 - depth * (1 - u * u));
                } else if (s.lakeDistance < -8) {
                    h = Math.max(h, wl + 1.0 + (d - hw) * 0.05);
                }
            }
        }
        return Math.min(h, MAX_SURFACE);
    }

    @Override
    public void fillArray(double[] values, ContextProvider provider) {
        provider.fillAllDirectly(values, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new TamrielHeight(visitor.visitNoise(hills), visitor.visitNoise(detail),
                visitor.visitNoise(ridges), visitor.visitNoise(dunes)));
    }

    @Override
    public double minValue() {
        return -64;
    }

    @Override
    public double maxValue() {
        return MAX_SURFACE;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
