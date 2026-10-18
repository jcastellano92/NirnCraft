package com.skycraft.tamriel.biome.flora;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;


import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code skycraft:flora_scatter}: natural spacing for trees. Replaces {@code count + in_square}: it yields the
 * positions inside the decorated chunk of a world-wide blue-noise point set (a jittered grid of cell size
 * {@code spacing} thinned so that no two kept points are closer than {@code spacing * min_distance}), where each
 * point lives with probability {@code floor + (density - floor) * field}. Points and their survival are pure
 * functions of the world seed and position, so neighbouring chunks agree on them: groves, clearings and forest edges
 * run across chunk borders, trees never crowd, and no grid shows.
 */
public class ScatterPlacement implements PlacementModifier {
    public static final MapCodec<ScatterPlacement> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.intRange(2, 48).fieldOf("spacing").forGetter(p -> p.spacing),
            Codec.floatRange(0f, 1.5f).optionalFieldOf("min_distance", 0.8f).forGetter(p -> p.minDistance),
            Codec.floatRange(0f, 1f).fieldOf("density").forGetter(p -> p.density),
            Codec.floatRange(0f, 1f).optionalFieldOf("floor", 0f).forGetter(p -> p.floor),
            FloraField.CODEC.fieldOf("field").forGetter(p -> p.field)
    ).apply(i, ScatterPlacement::new));

    private final int spacing;
    private final float minDistance, density, floor;
    private final FloraField field;

    public ScatterPlacement(int spacing, float minDistance, float density, float floor, FloraField field) {
        this.spacing = spacing;
        this.minDistance = minDistance;
        this.density = density;
        this.floor = floor;
        this.field = field;
    }

    public Stream<BlockPos> getPositions(PlacementContext ctx, RandomSource random, BlockPos origin) {
        long worldSeed = ctx.getLevel().getSeed();
        long seed = field.seed(worldSeed) ^ 0x7F4A7C15L * spacing;
        int s = spacing;
        int x0 = origin.getX() & ~15, z0 = origin.getZ() & ~15;
        int ci0 = Math.floorDiv(x0, s), ci1 = Math.floorDiv(x0 + 15, s);
        int cj0 = Math.floorDiv(z0, s), cj1 = Math.floorDiv(z0 + 15, s);
        double minD2 = s * minDistance * s * minDistance;
        List<BlockPos> out = new ArrayList<>();
        for (int i = ci0; i <= ci1; i++) {
            for (int j = cj0; j <= cj1; j++) {
                int h = FloraNoise.hash(i, j, seed);
                int px = i * s + (int) (FloraNoise.unit01(h) * s);
                int pz = j * s + (int) (FloraNoise.unit01(h * 0x2C1B3C6D + 0x297A2D39) * s);
                if (px < x0 || px > x0 + 15 || pz < z0 || pz > z0 + 15) continue;
                if (!alive(worldSeed, seed, h, px, pz)) continue;
                boolean keep = true;
                for (int di = -1; di <= 1 && keep; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        if (di == 0 && dj == 0) continue;
                        int hn = FloraNoise.hash(i + di, j + dj, seed);
                        // the higher priority of two close live points wins
                        if (Integer.compareUnsigned(hn * 0x165667B1, h * 0x165667B1) <= 0) continue;
                        int qx = (i + di) * s + (int) (FloraNoise.unit01(hn) * s);
                        int qz = (j + dj) * s + (int) (FloraNoise.unit01(hn * 0x2C1B3C6D + 0x297A2D39) * s);
                        double d2 = (qx - px) * (double) (qx - px) + (qz - pz) * (double) (qz - pz);
                        if (d2 < minD2 && alive(worldSeed, seed, hn, qx, qz)) {
                            keep = false;
                            break;
                        }
                    }
                }
                if (keep) out.add(new BlockPos(px, origin.getY(), pz));
            }
        }
        return out.stream();
    }

    @Override public void modify(PlacementContext context, RandomSource random, BlockPos origin, java.util.function.Consumer<BlockPos> output) {
        getPositions(context, random, origin).forEach(output);
    }

    private boolean alive(long worldSeed, long seed, int h, int x, int z) {
        double p = floor + (density - floor) * field.sample(worldSeed, x, z);
        return FloraNoise.unit01(h * 0x5BD1E995 ^ 0x68E31DA4) < p;
    }

    @Override
    public MapCodec<? extends net.minecraft.world.level.levelgen.placement.PlacementModifier> codec() { return CODEC; }
}
