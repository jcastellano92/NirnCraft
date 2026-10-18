package com.skycraft.tamriel.biome.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import com.skycraft.worldgen.ConfiguredFeature;
import com.skycraft.worldgen.FeaturePlaceContext;
import com.skycraft.worldgen.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

/**
 * A boulder of mixed rock half sunk into the ground: two or three overlapping blobs. Unlike vanilla's forest rock it
 * sits on any ground (ash, sand, snow), so every biome can have its own stones.
 */
public class BoulderFeature extends ConfiguredFeature<BoulderFeature.Config> {
    public record Config(BlockStateProvider state, IntProvider radius) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockStateProvider.DIRECT_CODEC.fieldOf("state").forGetter(Config::state),
                net.minecraft.util.valueproviders.IntProviders.codec(1, 6).fieldOf("radius").forGetter(Config::radius)
        ).apply(i, Config::new));
    }

    public BoulderFeature(Codec<Config> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        RandomSource random = ctx.random();
        GenBox box = GenBox.of(ctx.level(), ctx.origin());
        BlockPos o = ctx.origin();
        int x = o.getX(), z = o.getZ();
        if (!com.skycraft.world.PlacementMask.clear(ctx.level(), x, z, 6)) return false;
        int y = box.surface(x, z);
        if (y == Integer.MIN_VALUE || !box.get(x, y - 1, z).getFluidState().isEmpty()
                || !box.get(x, y, z).getFluidState().isEmpty()) return false;
        if (box.get(x, y - 1, z).isAir()) return false;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int blobs = 2 + random.nextInt(2);
        for (int b = 0; b < blobs; b++) {
            int r = ctx.config().radius().sample(random);
            if (box.reach(x, z) < r + 3) return b > 0;
            int bx = x + (b == 0 ? 0 : random.nextInt(3) - 1);
            int bz = z + (b == 0 ? 0 : random.nextInt(3) - 1);
            int by = y - 1 + (b == 0 ? 0 : random.nextInt(2));
            float rr = r + 0.5f;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        float d = dx * dx + dy * dy * 1.4f + dz * dz;
                        if (d > rr * rr || (d > (rr - 1) * (rr - 1) && random.nextFloat() < 0.3f)) continue;
                        p.set(bx + dx, by + dy, bz + dz);
                        box.set(p.getX(), p.getY(), p.getZ(), ctx.config().state().getState(ctx.level(), random, p));
                    }
                }
            }
            x = Mth.clamp(bx, x - 1, x + 1);
        }
        return true;
    }
}
