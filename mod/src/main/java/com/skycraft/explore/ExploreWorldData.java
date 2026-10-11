package com.skycraft.explore;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-world exploration state: which minor sites have had their resident (hermit, hunter) spawned. */
public final class ExploreWorldData extends SavedData {
    private static final String NAME = "skycraft_explore";
    private final LongSet populated = new LongOpenHashSet();

    public static ExploreWorldData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(ExploreWorldData::load, ExploreWorldData::new, NAME);
    }

    private static ExploreWorldData load(CompoundTag tag) {
        ExploreWorldData d = new ExploreWorldData();
        for (long k : tag.getLongArray("populated")) d.populated.add(k);
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLongArray("populated", populated.toLongArray());
        return tag;
    }

    public boolean isPopulated(long key) {
        return populated.contains(key);
    }

    public void markPopulated(long key) {
        if (populated.add(key)) setDirty();
    }
}
