package com.skycraft.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;

/** Native value/registry context for entities created before their world is attached. */
public final class WorldgenData {
    private WorldgenData() {}

    public static void load(BlockEntity entity, CompoundTag tag, HolderLookup.Provider registries) {
        entity.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
    }

    public static void setLootTable(WorldGenLevel level, RandomSource random, BlockPos pos, Identifier id) {
        if (level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity container) {
            container.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, id), random.nextLong());
        }
    }
}
