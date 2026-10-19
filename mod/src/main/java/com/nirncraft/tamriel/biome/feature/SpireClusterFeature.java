package com.nirncraft.tamriel.biome.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.block.state.BlockState;
import com.nirncraft.worldgen.ConfiguredFeature;
import com.nirncraft.worldgen.FeaturePlaceContext;
import com.nirncraft.worldgen.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

/**
 * A cluster of leaning spires growing from the ground: Summerset crystal, Thras coral, basalt pillars in the ash,
 * ice pinnacles in Atmora. Tall spires are two blocks thick at the foot; each ends in a tip block.
 */
public class SpireClusterFeature extends ConfiguredFeature<SpireClusterFeature.Config> {
    public record Config(BlockStateProvider body, BlockStateProvider tip, IntProvider count, IntProvider height,
                         int spread, float lean) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockStateProvider.DIRECT_CODEC.fieldOf("body").forGetter(Config::body),
                BlockStateProvider.DIRECT_CODEC.fieldOf("tip").forGetter(Config::tip),
                net.minecraft.util.valueproviders.IntProviders.codec(1, 32).fieldOf("count").forGetter(Config::count),
                net.minecraft.util.valueproviders.IntProviders.codec(1, 48).fieldOf("height").forGetter(Config::height),
                Codec.intRange(0, 12).fieldOf("spread").forGetter(Config::spread),
                Codec.floatRange(0f, 1f).fieldOf("lean").forGetter(Config::lean)
        ).apply(i, Config::new));
    }

    public SpireClusterFeature(Codec<Config> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config cfg = ctx.config();
        RandomSource random = ctx.random();
        GenBox box = GenBox.of(ctx.level(), ctx.origin());
        int n = cfg.count().sample(random);
        boolean any = false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < n; i++) {
            int x = ctx.origin().getX() + (cfg.spread() > 0 ? random.nextInt(cfg.spread() * 2 + 1) - cfg.spread() : 0);
            int z = ctx.origin().getZ() + (cfg.spread() > 0 ? random.nextInt(cfg.spread() * 2 + 1) - cfg.spread() : 0);
            int y = box.surface(x, z);
            if (y == Integer.MIN_VALUE) continue;
            BlockState below = box.get(x, y - 1, z);
            if (!below.isFaceSturdy(ctx.level(), pos.set(x, y - 1, z), net.minecraft.core.Direction.UP)
                    || !below.getFluidState().isEmpty() || !box.get(x, y, z).getFluidState().isEmpty()) continue;
            int h = cfg.height().sample(random);
            double a = random.nextDouble() * Math.PI * 2;
            double lean = cfg.lean() * random.nextDouble();
            double sx = x + 0.5, sz = z + 0.5;
            int lastX = x, lastZ = z, lastY = y;
            for (int dy = -1; dy < h; dy++) {
                int bx = Mth.floor(sx), bz = Mth.floor(sz);
                pos.set(bx, y + dy, bz);
                box.set(bx, y + dy, bz, cfg.body().getState(ctx.level(), random, pos));
                if (h >= 6 && dy < h / 3) {
                    box.set(bx + 1, y + dy, bz, cfg.body().getState(ctx.level(), random, pos));
                    box.set(bx, y + dy, bz + 1, cfg.body().getState(ctx.level(), random, pos));
                }
                lastX = bx;
                lastZ = bz;
                lastY = y + dy;
                sx += Math.cos(a) * lean;
                sz += Math.sin(a) * lean;
            }
            pos.set(lastX, lastY + 1, lastZ);
            box.setSoft(lastX, lastY + 1, lastZ, cfg.tip().getState(ctx.level(), random, pos));
            any = true;
        }
        return any;
    }
}
