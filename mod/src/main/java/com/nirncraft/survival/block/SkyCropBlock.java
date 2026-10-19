package com.nirncraft.survival.block;

import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.CropBlock;

import java.util.function.Supplier;

/** Cabbage, leek, tomato and garlic: ordinary 8-stage farmland crops (villager farmers harvest them too). */
public class SkyCropBlock extends CropBlock {
    private final Supplier<? extends ItemLike> seed;

    public SkyCropBlock(Supplier<? extends ItemLike> seed, Properties props) {
        super(props);
        this.seed = seed;
    }

    @Override
    protected ItemLike getBaseSeedId() {
        return seed.get();
    }
}
