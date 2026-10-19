package com.nirncraft.landmarks;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-world landmark state: which scenes have had their residents spawned. */
public final class LandmarkWorldData extends SavedData {
    private static final String NAME = "nirncraft_landmarks";
    private final LongSet populated = new LongOpenHashSet();

    public static LandmarkWorldData get(ServerLevel level) {
        return com.nirncraft.platform.SavedDataTypes.get(level.getServer().overworld(), NAME, LandmarkWorldData::load, LandmarkWorldData::new, d -> d.save(new CompoundTag()));
    }

    private static LandmarkWorldData load(CompoundTag tag) {
        LandmarkWorldData d = new LandmarkWorldData();
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
