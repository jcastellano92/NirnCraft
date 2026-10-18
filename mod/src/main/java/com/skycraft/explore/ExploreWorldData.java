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
        return com.skycraft.platform.SavedDataTypes.get(level.getServer().overworld(), NAME, ExploreWorldData::load, ExploreWorldData::new, value263 -> value263.save(new net.minecraft.nbt.CompoundTag()));
    }

    private static ExploreWorldData load(CompoundTag tag) {
        ExploreWorldData d = new ExploreWorldData();
        for (long k : tag.getLongArray("populated").orElseGet(() -> new long[0])) d.populated.add(k);
        return d;
    }

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
