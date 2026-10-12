package com.skycraft.tamriel.biome.flora;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skycraft.world.PlacementMask;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

/**
 * {@code skycraft:placement_mask}: drops a position where a road, street, plot, pad or site will be (see
 * {@link PlacementMask}). For vegetation placed by vanilla features (patches, vanilla trees) that cannot ask the
 * mask themselves; {@code radius} is the footprint of what follows (a tree's crown radius).
 */
public class MaskFilter extends PlacementFilter {
    public static final Codec<MaskFilter> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(0, 32).optionalFieldOf("radius", 0).forGetter(f -> f.radius)
    ).apply(i, MaskFilter::new));

    private final int radius;

    public MaskFilter(int radius) {
        this.radius = radius;
    }

    @Override
    protected boolean shouldPlace(PlacementContext ctx, RandomSource random, BlockPos pos) {
        return PlacementMask.clear(ctx.getLevel(), pos.getX(), pos.getZ(), radius);
    }

    @Override
    public PlacementModifierType<?> type() {
        return Flora.MASK.get();
    }
}
