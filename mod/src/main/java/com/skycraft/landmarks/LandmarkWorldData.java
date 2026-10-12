package com.skycraft.landmarks;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-world landmark state: which scenes have had their residents spawned. */
public final class LandmarkWorldData extends SavedData {
    private static final String NAME = "skycraft_landmarks";
    private final LongSet populated = new LongOpenHashSet();

    public static LandmarkWorldData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(LandmarkWorldData::load, LandmarkWorldData::new, NAME);
    }

    private static LandmarkWorldData load(CompoundTag tag) {
        LandmarkWorldData d = new LandmarkWorldData();
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
