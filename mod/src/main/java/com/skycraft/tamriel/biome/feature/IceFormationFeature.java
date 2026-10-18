package com.skycraft.tamriel.biome.feature;

import com.mojang.serialization.Codec;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.skycraft.worldgen.ConfiguredFeature;
import com.skycraft.worldgen.FeaturePlaceContext;
import com.skycraft.worldgen.NoneFeatureConfiguration;

/** Ice caves: icicles of packed and blue ice hanging from the ceiling, and stubby ice stalagmites under them. */
public class IceFormationFeature extends ConfiguredFeature<NoneFeatureConfiguration> {
    public IceFormationFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        RandomSource random = ctx.random();
        GenBox box = GenBox.of(ctx.level(), ctx.origin());
        int x = ctx.origin().getX(), y = ctx.origin().getY(), z = ctx.origin().getZ();
        if (!box.get(x, y, z).isAir() || box.get(x, y - 1, z).isAir()) return false;
        int ceiling = 1;
        while (ceiling < 24 && box.get(x, y + ceiling, z).isAir()) ceiling++;
        if (ceiling >= 24 || ceiling < 4) return false;
        BlockState ice = random.nextInt(4) == 0 ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState();
        int icicle = Math.min(ceiling - 2, 2 + random.nextInt(5));
        for (int d = 1; d <= icicle; d++) box.set(x, y + ceiling - d, z, ice);
        if (icicle > 3) {
            // a thicker root where it meets the ceiling
            box.set(x + 1, y + ceiling - 1, z, ice);
            box.set(x, y + ceiling - 1, z + 1, ice);
        }
        int mite = Math.min(ceiling - icicle - 1, random.nextInt(4));
        for (int d = 0; d < mite; d++) box.set(x, y + d, z, Blocks.PACKED_ICE.defaultBlockState());
        return true;
    }
}
