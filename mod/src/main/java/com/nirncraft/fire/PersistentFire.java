package com.nirncraft.fire;

import com.nirncraft.NirnCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which blocks are "persistent fire": NirnCraft's own big fires (burning homes, pyres, rubble) that stay lit for the life
 * of a world. The block tag {@code nirncraft:persistent_fire} lists them; systems that put fire out (the world
 * protection's fire handling) must skip these.
 */
public final class PersistentFire {
    public static final TagKey<Block> TAG = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "persistent_fire"));

    private PersistentFire() {}

    /** True for a block of the big fire family (or anything else tagged {@code nirncraft:persistent_fire}). */
    public static boolean is(BlockState state) {
        return state.getBlock() instanceof BigFireBlock || state.is(TAG);
    }
}
