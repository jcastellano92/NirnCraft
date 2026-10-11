package com.skycraft.tamriel.biome.flora;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skycraft.tamriel.biome.feature.GenBox;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

import java.util.List;

/**
 * {@code skycraft:ground_cover}: one tuft of ground cover (grass, ferns, flowers, berries, moss, mushrooms, reeds,
 * alchemy plants). Which plant grows is picked by a slow noise per entry, sharpened by {@code patchiness}: at 0 the
 * entries are mixed at random, near 1 each plant keeps to its own drifts (a sweep of lavender, a bank of ferns, a ring
 * of mushrooms). A tuft is {@code cluster} plants within {@code spread} blocks of the same kind. Placed by
 * {@code count + in_square + skycraft:flora_density}, which decides where the drifts and the bare ground are. With
 * {@code near_water} the plants keep to the shore.
 */
public class GroundCoverFeature extends Feature<GroundCoverFeature.Config> {
    public record Entry(BlockState state, int weight) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockState.CODEC.fieldOf("state").forGetter(Entry::state),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("weight", 1).forGetter(Entry::weight)
        ).apply(i, Entry::new));
    }

    public record Config(List<Entry> entries, float scale, float patchiness, IntProvider cluster, int spread, int salt,
                         boolean nearWater) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                Entry.CODEC.listOf().fieldOf("entries").forGetter(Config::entries),
                Codec.floatRange(2f, 1024f).optionalFieldOf("scale", 24f).forGetter(Config::scale),
                Codec.floatRange(0f, 1f).optionalFieldOf("patchiness", 0.6f).forGetter(Config::patchiness),
                IntProvider.codec(1, 32).optionalFieldOf("cluster", net.minecraft.util.valueproviders.ConstantInt.of(1))
                        .forGetter(Config::cluster),
                Codec.intRange(0, 6).optionalFieldOf("spread", 2).forGetter(Config::spread),
                Codec.INT.optionalFieldOf("salt", 0).forGetter(Config::salt),
                Codec.BOOL.optionalFieldOf("near_water", false).forGetter(Config::nearWater)
        ).apply(i, Config::new));
    }

    public GroundCoverFeature(Codec<Config> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config cfg = ctx.config();
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos o = ctx.origin();
        GenBox box = GenBox.of(level, o);
        BlockState state = pick(cfg, level.getSeed(), o.getX(), o.getZ(), random.nextDouble());
        if (state == null) return false;
        int n = cfg.cluster().sample(random);
        int placed = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int k = 0; k < n; k++) {
            int x = o.getX(), z = o.getZ();
            if (k > 0 && cfg.spread() > 0) {
                x += Math.round((random.nextFloat() - random.nextFloat()) * cfg.spread() * 1.3f);
                z += Math.round((random.nextFloat() - random.nextFloat()) * cfg.spread() * 1.3f);
            }
            // stay in the decorated chunk: its heightmaps are the only ones ready
            if ((x >> 4) != box.center.x || (z >> 4) != box.center.z) continue;
            int y = k == 0 ? o.getY() : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            p.set(x, y, z);
            if (!level.getBlockState(p).isAir()) continue;
            if (cfg.nearWater() && !nearWater(box, x, y - 1, z)) continue;
            if (!state.canSurvive(level, p)) continue;
            if (state.getBlock() instanceof DoublePlantBlock && state.hasProperty(DoublePlantBlock.HALF)) {
                if (!level.getBlockState(p.above()).isAir()) continue;
                DoublePlantBlock.placeAt(level, state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER), p, 2);
            } else {
                level.setBlock(p, state, 2);
            }
            placed++;
        }
        return placed > 0;
    }

    /** Water within two blocks of (x, y, z) at the same level (reeds, cane and rushes keep to the shore). */
    private static boolean nearWater(GenBox box, int x, int y, int z) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 3 || (dx == 0 && dz == 0)) continue;
                if (box.get(x + dx, y, z + dz).getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return true;
            }
        }
        return false;
    }

    private static BlockState pick(Config cfg, long seed, int x, int z, double roll) {
        List<Entry> list = cfg.entries();
        if (list.isEmpty()) return null;
        if (list.size() == 1) return list.get(0).state();
        double[] w = new double[list.size()];
        double total = 0;
        long base = seed + cfg.salt() * 0x2545F4914F6CDD1DL;
        for (int i = 0; i < w.length; i++) {
            double nz = cfg.patchiness() > 0 ? FloraNoise.fbm(x / (double) cfg.scale(), z / (double) cfg.scale(), base + 7919L * (i + 1), 2) : 0;
            w[i] = list.get(i).weight() * Math.exp(cfg.patchiness() * 7.0 * nz);
            total += w[i];
        }
        double t = roll * total;
        for (int i = 0; i < w.length; i++) {
            t -= w[i];
            if (t <= 0) return list.get(i).state();
        }
        return list.get(w.length - 1).state();
    }
}
