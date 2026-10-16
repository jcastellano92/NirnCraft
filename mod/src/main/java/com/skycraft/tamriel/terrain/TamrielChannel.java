package com.skycraft.tamriel.terrain;

import com.mojang.serialization.MapCodec;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.Locale;

/**
 * Density function {@code skycraft:tamriel_channel}: one channel of the Tamriel map at the column, e.g.
 * {@code {"type": "skycraft:tamriel_channel", "channel": "continentalness"}}. 2D, so wrap it in
 * {@code minecraft:cache_2d} or {@code minecraft:flat_cache}. Thread-safe and allocation-free.
 *
 * <p>The climate channels are scaled like vanilla's so the biome source can read them from the
 * {@code Climate.Sampler}: continentalness from the coast distance (below -0.19 is sea, -0.19..-0.11 the coast,
 * up to 1 far inland), erosion from roughness and ridges (-0.75 for jagged mountains, 0.75 for flat land),
 * ridges = mountain ridge strength 0..1.</p>
 */
public record TamrielChannel(Channel channel) implements DensityFunction.SimpleFunction {
    public enum Channel implements StringRepresentable {
        ELEVATION(-64, 640), CONTINENTALNESS(-1.2, 1), EROSION(-1, 1), RIDGES(0, 1), RIVER(0, 1), LAKE(0, 1),
        ROUGHNESS(0, 1), CRAG(0, 1), DUNES(0, 1), WETLAND(0, 1), WATER_LEVEL(-64, 640), COAST(-100000, 100000),
        ROAD(0, 1), OVERHANG(0, 1);

        public static final StringRepresentable.EnumCodec<Channel> CODEC = StringRepresentable.fromEnum(Channel::values);
        final double min, max;

        Channel(double min, double max) {
            this.min = min;
            this.max = max;
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final MapCodec<TamrielChannel> DATA_CODEC =
            Channel.CODEC.fieldOf("channel").xmap(TamrielChannel::new, TamrielChannel::channel);
    public static final KeyDispatchDataCodec<TamrielChannel> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    @Override
    public double compute(FunctionContext ctx) {
        double v = value(channel, TamrielMap.sampleScratch(ctx.blockX(), ctx.blockZ()));
        if ((channel == Channel.ROAD || channel == Channel.OVERHANG) && com.skycraft.worldplan.PlanGround.blending()) {
            // P2: the planned road beds and site pads also keep caves and overhangs off the surface
            double w = com.skycraft.worldplan.PlanGround.influence(ctx.blockX(), ctx.blockZ());
            v = channel == Channel.ROAD ? Math.max(v, w) : v * (1 - w);
        }
        return v;
    }

    public static double value(Channel channel, MapSample s) {
        return switch (channel) {
            case ELEVATION -> s.elevation;
            case CONTINENTALNESS -> continentalness(s.coastDistance);
            case EROSION -> 0.75 - 1.5 * MapNoise.clamp(s.roughness * 0.55 + s.ridge * 0.65 + s.crag * 0.15, 0, 1);
            case RIDGES -> s.ridge;
            case RIVER -> s.river;
            case LAKE -> s.lake;
            case ROUGHNESS -> s.roughness;
            case CRAG -> s.crag;
            case DUNES -> s.dunes;
            case WETLAND -> s.wetland;
            case WATER_LEVEL -> Float.isNaN(s.waterLevel) ? -64 : s.waterLevel;
            case COAST -> s.coastDistance;
            case ROAD -> Math.max(s.road, s.calm);  // road corridors, settlement pads, ledges and climbing paths
            case OVERHANG -> MapNoise.smoothstep(0.3, 0.8, s.ridge) * (0.35 + 0.65 * s.crag)
                    * MapNoise.smoothstep(30, 120, s.coastDistance) * (1 - s.calm) * (1 - 0.8 * s.road);
        };
    }

    /** Vanilla-scaled continentalness from a coast distance in blocks. */
    public static double continentalness(double cd) {
        if (cd < 0) return Math.max(-1.2, -0.19 + cd / 1500.0);
        if (cd < 64) return -0.19 + 0.08 * cd / 64.0;
        return -0.11 + 1.11 * Math.min(1.0, (cd - 64) / 2500.0);
    }

    @Override
    public double minValue() {
        return channel.min;
    }

    @Override
    public double maxValue() {
        return channel.max;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
