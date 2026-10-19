package com.nirncraft.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** Retains NirnCraft's authored random patches after native random_patch removal. */
public final class RandomPatchFeature extends ConfiguredFeature<RandomPatchFeature.Options> {
    public record Options(int tries, int xzSpread, int ySpread, Holder<PlacedFeature> feature) {
        public static final Codec<Options> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(0, 4096).fieldOf("tries").orElse(128).forGetter(Options::tries),
                Codec.intRange(0, 1024).fieldOf("xz_spread").orElse(7).forGetter(Options::xzSpread),
                Codec.intRange(0, 1024).fieldOf("y_spread").orElse(3).forGetter(Options::ySpread),
                PlacedFeature.CODEC.fieldOf("feature").forGetter(Options::feature)
        ).apply(i, Options::new));
    }

    public RandomPatchFeature() { super(Options.CODEC); }

    @Override protected java.util.stream.Stream<Holder<Feature>> getSubFeatures(Options config) {
        return config.feature().value().getFeatures();
    }

    @Override public boolean place(FeaturePlaceContext<Options> context) {
        Options options = context.config();
        var random = context.random();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int xz = options.xzSpread() + 1, y = options.ySpread() + 1;
        int successful = 0;
        for (int i = 0; i < options.tries(); i++) {
            pos.setWithOffset(context.origin(), random.nextInt(xz) - random.nextInt(xz),
                    random.nextInt(y) - random.nextInt(y), random.nextInt(xz) - random.nextInt(xz));
            if (options.feature().value().place(context.level(), context.chunkGenerator(), random, pos)) successful++;
        }
        return successful > 0;
    }
}
