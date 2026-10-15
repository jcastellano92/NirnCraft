package com.skycraft.worldplan.tile;

import com.skycraft.Skycraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * World-save persistence for planned 4,096 x 4,096 block Endless Lands tiles
 * (pipeline stage P7, see {@code docs/WORLDGEN-PIPELINE.md} section 7).
 *
 * <p>Stores compressed binary {@link TilePlan#write()} payloads keyed by {@link TilePlan#key(int, int)} in the
 * overworld's {@code data/skycraft_tile_plans.dat} so planned tiles survive world reloads with byte-identical
 * road, street, and scene plans.</p>
 */
public final class TilePlanData extends SavedData {
    public static final String NAME = "skycraft_tile_plans";

    private final Map<Long, byte[]> tileBytes = new ConcurrentHashMap<>();

    public TilePlanData() {
    }

    /** Attaches this world's {@link TilePlanData} to {@link TilePlanner} for automatic load/save. */
    public static TilePlanData attach(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        long seed = overworld.getSeed();
        TilePlanner.setWorldSeed(seed);
        TilePlanData data = overworld.getDataStorage().computeIfAbsent(TilePlanData::load, TilePlanData::new, NAME);
        TilePlanner.setPersistence(
                (worldSeed, tx, tz) -> data.readTile(worldSeed, tx, tz),
                (key, plan) -> data.putTile(plan)
        );
        return data;
    }

    public void putTile(TilePlan plan) {
        try {
            byte[] bytes = plan.write();
            tileBytes.put(TilePlan.key(plan.tileX(), plan.tileZ()), bytes);
            setDirty();
        } catch (IOException e) {
            Skycraft.LOGGER.warn("[tileplan] failed to serialise tile ({}, {})", plan.tileX(), plan.tileZ(), e);
        }
    }

    public TilePlan readTile(long expectedSeed, int tileX, int tileZ) {
        byte[] bytes = tileBytes.get(TilePlan.key(tileX, tileZ));
        if (bytes == null) return null;
        try {
            TilePlan plan = TilePlan.read(bytes);
            if (plan.worldSeed() != expectedSeed) {
                return null;
            }
            return plan;
        } catch (IOException e) {
            Skycraft.LOGGER.warn("[tileplan] corrupt saved tile ({}, {}), recomputing", tileX, tileZ, e);
            return null;
        }
    }

    public int size() {
        return tileBytes.size();
    }

    public static TilePlanData load(CompoundTag tag) {
        TilePlanData d = new TilePlanData();
        ListTag list = tag.getList("Tiles", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            long key = entry.getLong("Key");
            byte[] payload = entry.getByteArray("Bytes");
            if (payload.length > 0) {
                d.tileBytes.put(key, payload);
            }
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<Long, byte[]> e : tileBytes.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Key", e.getKey());
            entry.putByteArray("Bytes", e.getValue());
            list.add(entry);
        }
        tag.put("Tiles", list);
        return tag;
    }
}
