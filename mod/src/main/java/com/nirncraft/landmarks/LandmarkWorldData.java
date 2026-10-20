package com.nirncraft.landmarks;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-world landmark state: which scenes have had their residents spawned, and what generation made of the L and XL
 * sites (built or refused) so the far field draws what stands, not what was predicted.
 */
public final class LandmarkWorldData extends SavedData {
    private static final String NAME = "nirncraft_landmarks";
    private final LongSet populated = new LongOpenHashSet();
    /** Site key -> built (true) or refused (false). Written on the server thread, read by the far field's worker. */
    private final Map<Long, Boolean> outcomes = new ConcurrentHashMap<>();

    public static LandmarkWorldData get(ServerLevel level) {
        return com.nirncraft.platform.SavedDataTypes.get(level.getServer().overworld(), NAME, LandmarkWorldData::load, LandmarkWorldData::new, d -> d.save(new CompoundTag()));
    }

    private static LandmarkWorldData load(CompoundTag tag) {
        LandmarkWorldData d = new LandmarkWorldData();
        for (long k : tag.getLongArray("populated").orElseGet(() -> new long[0])) d.populated.add(k);
        for (long k : tag.getLongArray("built").orElseGet(() -> new long[0])) d.outcomes.put(k, Boolean.TRUE);
        for (long k : tag.getLongArray("refused").orElseGet(() -> new long[0])) d.outcomes.put(k, Boolean.FALSE);
        return d;
    }

    public CompoundTag save(CompoundTag tag) {
        tag.putLongArray("populated", populated.toLongArray());
        tag.putLongArray("built", keys(true));
        tag.putLongArray("refused", keys(false));
        return tag;
    }

    private long[] keys(boolean built) {
        return outcomes.entrySet().stream().filter(e -> e.getValue() == built).mapToLong(Map.Entry::getKey).toArray();
    }

    public boolean isPopulated(long key) {
        return populated.contains(key);
    }

    public void markPopulated(long key) {
        if (populated.add(key)) setDirty();
    }

    /**
     * What generation did with the site of this key: true built, false refused (by any check, or the builder failed),
     * null when its chunk has not been decorated since outcomes were recorded. Any thread.
     */
    @Nullable
    public Boolean outcome(long key) {
        return outcomes.get(key);
    }

    /**
     * Records a grid site's outcome from its decoration (any world-generation thread: handed to the server thread,
     * where the saved data lives). Only the L and XL tiers are kept (the far field draws no smaller site).
     */
    static void record(WorldGenLevel level, LandmarkSite site, boolean built) {
        if (site.tier() != Tier.L && site.tier() != Tier.XL) return;
        ServerLevel owner = level.getLevel();
        MinecraftServer server = owner.getServer();
        long key = site.key();
        server.execute(() -> {
            LandmarkWorldData d = get(owner);
            Boolean was = d.outcomes.put(key, built);
            if (was == null || was != built) d.setDirty();
        });
    }
}
