package com.skycraft.survival.fishing;

import com.skycraft.survival.SurvivalConfig;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * How many fish are left in each 16x16 stretch of water (per dimension). Every catch takes one; one swims back every
 * {@code fishing.stockRegenTicks}. Fished-out water bites slowly, so moving along the shore pays. Only areas that were
 * fished are stored (a packed long per chunk: stock in the top byte, the game time it was last counted below).
 */
public final class FishStock extends SavedData {
    public static final String NAME = "skycraft_fish_stock";
    public static final int MAX = 10;

    private final Long2LongOpenHashMap entries = new Long2LongOpenHashMap();

    public static FishStock get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FishStock::load, FishStock::new, NAME);
    }

    /** Fish left around {@code pos} right now (0..{@value #MAX}). */
    public int stock(ServerLevel level, BlockPos pos) {
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        if (!entries.containsKey(key)) return MAX;
        long v = entries.get(key);
        int stock = (int) (v >>> 56);
        long since = v & 0x00FFFFFFFFFFFFFFL;
        long regen = Math.max(20, SurvivalConfig.FISH_STOCK_REGEN_TICKS.get());
        long now = level.getGameTime() & 0x00FFFFFFFFFFFFFFL;
        int back = (int) Math.min(MAX, Math.max(0, (now - since) / regen));
        return Math.min(MAX, stock + back);
    }

    /** One fish was caught here. */
    public void take(ServerLevel level, BlockPos pos) {
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        int left = Math.max(0, stock(level, pos) - 1);
        if (left >= MAX) {
            entries.remove(key);
        } else {
            entries.put(key, ((long) left << 56) | (level.getGameTime() & 0x00FFFFFFFFFFFFFFL));
        }
        // forget fully regrown areas now and then
        if (entries.size() > 4096) entries.long2LongEntrySet().removeIf(e -> e.getLongValue() >>> 56 >= MAX);
        setDirty();
    }

    public static FishStock load(CompoundTag tag) {
        FishStock s = new FishStock();
        ListTag list = tag.getList("stock", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            s.entries.put(t.getLong("k"), t.getLong("v"));
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        entries.long2LongEntrySet().forEach(e -> {
            CompoundTag t = new CompoundTag();
            t.putLong("k", e.getLongKey());
            t.putLong("v", e.getLongValue());
            list.add(t);
        });
        tag.put("stock", list);
        return tag;
    }
}
