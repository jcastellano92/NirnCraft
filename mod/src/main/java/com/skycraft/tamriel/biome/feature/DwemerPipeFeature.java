package com.skycraft.tamriel.biome.feature;

import com.mojang.serialization.Codec;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Old Dwemer pipework in the caverns: bronze pipes running along the cave floor with junction collars, rising into
 * the rock where they meet a wall, sometimes climbing to the ceiling.
 */
public class DwemerPipeFeature extends Feature<NoneFeatureConfiguration> {
    public DwemerPipeFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        RandomSource random = ctx.random();
        GenBox box = GenBox.of(ctx.level(), ctx.origin());
        int x = ctx.origin().getX(), y = ctx.origin().getY(), z = ctx.origin().getZ();
        if (!box.get(x, y, z).isAir() || box.get(x, y - 1, z).isAir()) return false;
        Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        BlockState pipe = Blocks.LIGHTNING_ROD.defaultBlockState().setValue(LightningRodBlock.FACING, dir);
        BlockState collar = random.nextBoolean() ? Blocks.WAXED_CUT_COPPER.defaultBlockState()
                : Blocks.WAXED_EXPOSED_CUT_COPPER.defaultBlockState();
        int len = 6 + random.nextInt(12);
        int placed = 0;
        for (int s = 0; s < len; s++) {
            int px = x + dir.getStepX() * s, pz = z + dir.getStepZ() * s;
            if (box.reach(px, pz) < 1) break;
            BlockState here = box.get(px, y, pz);
            if (!here.isAir()) {
                // the pipe enters the rock: finish with a collar
                if (placed > 2) box.set(px - dir.getStepX(), y, pz - dir.getStepZ(), collar);
                break;
            }
            box.set(px, y, pz, s % 5 == 0 ? collar : pipe);
            placed++;
        }
        // a riser to the ceiling
        if (placed > 3 && random.nextInt(3) == 0) {
            int rx = x + dir.getStepX() * (placed / 2), rz = z + dir.getStepZ() * (placed / 2);
            BlockState riser = Blocks.LIGHTNING_ROD.defaultBlockState().setValue(LightningRodBlock.FACING, Direction.UP);
            for (int dy = 1; dy < 16; dy++) {
                if (!box.get(rx, y + dy, rz).isAir()) break;
                box.set(rx, y + dy, rz, riser);
            }
            box.set(rx, y, rz, collar);
        }
        return placed > 0;
    }
}
