package com.skycraft.fire;

import com.skycraft.Skycraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which blocks are "persistent fire": Skycraft's own big fires (burning homes, pyres, rubble) that stay lit for the life
 * of a world. The block tag {@code skycraft:persistent_fire} lists them; systems that put fire out (the world
 * protection's fire handling) must skip these.
 */
public final class PersistentFire {
    public static final TagKey<Block> TAG = TagKey.create(Registries.BLOCK, new ResourceLocation(Skycraft.MODID, "persistent_fire"));

    private PersistentFire() {}

    /** True for a block of the big fire family (or anything else tagged {@code skycraft:persistent_fire}). */
    public static boolean is(BlockState state) {
        return state.getBlock() instanceof BigFireBlock || state.is(TAG);
    }
}
