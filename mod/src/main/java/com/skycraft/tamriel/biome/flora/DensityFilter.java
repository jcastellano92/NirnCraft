package com.skycraft.tamriel.biome.flora;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;


/**
 * {@code skycraft:flora_density}: keeps a position with probability {@code floor + (density - floor) * field(x, z)}.
 * After {@code count + in_square} it turns an even sprinkle of ground cover into drifts, meadows and bare patches
 * that follow a {@link FloraField}.
 */
public class DensityFilter implements PlacementFilter {
    public static final MapCodec<DensityFilter> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.floatRange(0f, 1f).optionalFieldOf("density", 1f).forGetter(f -> f.density),
            Codec.floatRange(0f, 1f).optionalFieldOf("floor", 0f).forGetter(f -> f.floor),
            FloraField.CODEC.fieldOf("field").forGetter(f -> f.field)
    ).apply(i, DensityFilter::new));

    private final float density, floor;
    private final FloraField field;

    public DensityFilter(float density, float floor, FloraField field) {
        this.density = density;
        this.floor = floor;
        this.field = field;
    }

    @Override
    public boolean shouldPlace(PlacementContext ctx, RandomSource random, BlockPos pos) {
        double p = floor + (density - floor) * field.sample(ctx.getLevel().getSeed(), pos.getX(), pos.getZ());
        return random.nextDouble() < p;
    }

    @Override
    public MapCodec<? extends PlacementFilter> codec() { return CODEC; }
}
