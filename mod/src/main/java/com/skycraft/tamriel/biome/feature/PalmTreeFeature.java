package com.skycraft.tamriel.biome.feature;

import com.mojang.serialization.Codec;
import com.skycraft.tamriel.biome.BiomeBlocks;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Palm and date trees of the warm coasts (Elsweyr, Hammerfell, Summerset, the southern isles): a slender trunk that
 * curves away from straight, topped by drooping fronds. Date palms are shorter and straighter with a fuller head.
 */
public class PalmTreeFeature extends Feature<NoneFeatureConfiguration> {
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    public PalmTreeFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        RandomSource random = ctx.random();
        GenBox box = GenBox.of(ctx.level(), ctx.origin());
        int x = ctx.origin().getX(), y = ctx.origin().getY(), z = ctx.origin().getZ();
        BlockState ground = box.get(x, y - 1, z);
        if (!ground.is(BlockTags.SAND) && !ground.is(BlockTags.DIRT) && !ground.is(BiomeBlocks.DUNE_SAND.get())) return false;
        if (!GenBox.isSoft(box.get(x, y, z)) || !box.get(x, y, z).getFluidState().isEmpty()) return false;
        if (box.reach(x, z) < 8) return false;
        boolean date = random.nextInt(3) == 0;
        int height = date ? 5 + random.nextInt(3) : 7 + random.nextInt(5);
        double a = random.nextDouble() * Math.PI * 2;
        double bend = date ? 0.15 : 0.35 + random.nextDouble() * 0.35;
        BlockState log = BiomeBlocks.PALM_LOG.get().defaultBlockState();
        BlockState fronds = BiomeBlocks.PALM_FRONDS.get().defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        double sx = x + 0.5, sz = z + 0.5;
        int tx = x, tz = z;
        for (int dy = 0; dy < height; dy++) {
            double t = dy / (double) height;
            sx = x + 0.5 + Math.cos(a) * bend * height * t * t;
            sz = z + 0.5 + Math.sin(a) * bend * height * t * t;
            tx = Mth.floor(sx);
            tz = Mth.floor(sz);
            if (!GenBox.isSoft(box.get(tx, y + dy, tz)) && dy > 0) return dy > 3;
            box.set(tx, y + dy, tz, log);
        }
        int top = y + height;
        box.setSoft(tx, top, tz, fronds);
        box.setSoft(tx, top - 1, tz, log);
        int len = date ? 3 : 4 + random.nextInt(2);
        for (int[] d : DIRS) {
            boolean diag = d[0] != 0 && d[1] != 0;
            int l = diag ? len - 1 : len;
            for (int s = 1; s <= l; s++) {
                int drop = s <= 1 ? 0 : (s - 1 + (date ? 1 : 0)) / 2;
                box.setSoft(tx + d[0] * s, top - drop, tz + d[1] * s, fronds);
                if (s == l) box.setSoft(tx + d[0] * s, top - drop - 1, tz + d[1] * s, fronds);
            }
            if (date) box.setSoft(tx + d[0], top + 1, tz + d[1], fronds);
        }
        return true;
    }
}
