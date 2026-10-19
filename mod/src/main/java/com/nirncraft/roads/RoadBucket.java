package com.nirncraft.roads;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The road polylines that start in one region ({@link #SHIFT} blocks square), saved as their own data file so a new
 * road only rewrites its region instead of the whole network.
 */
final class RoadBucket extends SavedData {
    /** Region size: 1024 blocks (64x64 chunks). */
    static final int SHIFT = 10;

    final Int2ObjectLinkedOpenHashMap<Road> roads = new Int2ObjectLinkedOpenHashMap<>();

    static long keyOf(Road road) {
        return ChunkPos.pack(road.xs[0] >> SHIFT, road.zs[0] >> SHIFT);
    }

    static String name(long key) {
        return RoadsData.NAME + "_r_" + ChunkPos.getX(key) + "_" + ChunkPos.getZ(key);
    }

    public CompoundTag save(CompoundTag tag) {
        ListTag r = new ListTag();
        for (Road road : roads.values()) r.add(road.save());
        tag.put("roads", r);
        return tag;
    }

    static RoadBucket load(CompoundTag tag) {
        RoadBucket b = new RoadBucket();
        ListTag r = tag.getListOrEmpty("roads");
        for (int i = 0; i < r.size(); i++) {
            Road road = Road.load(r.getCompoundOrEmpty(i));
            if (road != null) b.roads.put(road.id, road);
        }
        return b;
    }
}
