package com.skycraft.mounts;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The world's stables as storage ({@code SavedData} "skycraft_mount_stock"): what players have sold to a stable
 * ({@link #consign}: the token of the very animal, kept until someone buys it), the pool of animals that went back to no
 * particular stable, and what is to become of an animal whose entity was out of reach when it was sold or let go (its
 * {@link Fate}, applied when it loads).
 *
 * <p>A token is in exactly one place: removing it from here and handing it out is one call on the server thread
 * ({@link #take}), selling removes it from the seller's record in the same call chain ({@link Mounts#retire}), so a
 * double click or two buyers can never get the same animal twice.</p>
 */
public final class MountStable extends SavedData {
    /** Animals a stable keeps of those sold to it; the oldest leave for the pool. */
    public static final int PER_STABLE = 12;
    /** Animals sold on: bounded, the oldest are let go. */
    public static final int POOL = 64;
    private static final int FATES = 512;
    private static final String NAME = "skycraft_mount_stock";

    /** What becomes of an animal that is still standing in an unloaded chunk. */
    public enum Fate { DELETE, RELEASE }

    private final CompoundTag stock;
    private final ListTag pool;
    private final CompoundTag fates;

    private MountStable(CompoundTag stock, ListTag pool, CompoundTag fates) {
        this.stock = stock;
        this.pool = pool;
        this.fates = fates;
    }

    public static MountStable get(MinecraftServer server) {
        return com.skycraft.platform.SavedDataTypes.get(server.overworld(), NAME, MountStable::load, () -> new MountStable(new CompoundTag(), new ListTag(), new CompoundTag()), value263 -> value263.save(new net.minecraft.nbt.CompoundTag()));
    }

    private static MountStable load(CompoundTag t) {
        return new MountStable(t.getCompoundOrEmpty("stock"), t.getListOrEmpty("pool"), t.getCompoundOrEmpty("fates"));
    }

    public CompoundTag save(CompoundTag out) {
        out.put("stock", stock);
        out.put("pool", pool);
        out.put("fates", fates);
        return out;
    }

    // ------------------------------------------------------------------ a stable's own stock

    /** The tokens a stable holds, oldest first (copies: nothing here is changed by editing them). */
    public List<CompoundTag> stockOf(String stableKey) {
        List<CompoundTag> out = new ArrayList<>();
        for (Tag t : stock.getListOrEmpty(stableKey)) out.add(((CompoundTag) t).copy());
        return out;
    }

    /** A player's animal joins a stable's stock; when it is over {@value #PER_STABLE} the oldest goes to the pool. */
    public void consign(String stableKey, CompoundTag token) {
        ListTag list = stock.getListOrEmpty(stableKey);
        UUID id = MountToken.id(token);
        for (int i = list.size() - 1; i >= 0; i--) if (MountToken.id(list.getCompoundOrEmpty(i)).equals(id)) list.remove(i);
        list.add(token.copy());
        while (list.size() > PER_STABLE) {
            CompoundTag oldest = list.getCompoundOrEmpty(0);
            list.remove(0);
            toPool(oldest);
        }
        stock.put(stableKey, list);
        setDirty();
    }

    /** Removes and returns the animal with this id from a stable's stock, or null (already bought). */
    @Nullable
    public CompoundTag take(String stableKey, UUID id) {
        ListTag list = stock.getListOrEmpty(stableKey);
        for (int i = 0; i < list.size(); i++) {
            if (MountToken.id(list.getCompoundOrEmpty(i)).equals(id)) {
                CompoundTag token = list.getCompoundOrEmpty(i).copy();
                list.remove(i);
                stock.put(stableKey, list);
                setDirty();
                return token;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the pool

    /** An animal that went back to no stable in particular. */
    public void toPool(CompoundTag token) {
        UUID id = MountToken.id(token);
        for (int i = pool.size() - 1; i >= 0; i--) if (MountToken.id(pool.getCompoundOrEmpty(i)).equals(id)) pool.remove(i);
        pool.add(token.copy());
        while (pool.size() > POOL) pool.remove(0);
        setDirty();
    }

    public List<CompoundTag> pool() {
        List<CompoundTag> out = new ArrayList<>();
        for (Tag t : pool) out.add(((CompoundTag) t).copy());
        return out;
    }

    @Nullable
    public CompoundTag takeFromPool(UUID id) {
        for (int i = 0; i < pool.size(); i++) {
            if (MountToken.id(pool.getCompoundOrEmpty(i)).equals(id)) {
                CompoundTag token = pool.getCompoundOrEmpty(i).copy();
                pool.remove(i);
                setDirty();
                return token;
            }
        }
        return null;
    }

    /** Whether a token with this id is held anywhere here (selftests, duplicate checks). */
    public boolean holds(UUID id) {
        for (Tag t : pool) if (MountToken.id((CompoundTag) t).equals(id)) return true;
        for (String key : stock.keySet()) {
            for (Tag t : stock.getListOrEmpty(key)) if (MountToken.id((CompoundTag) t).equals(id)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ animals out of reach

    /** Marks an entity that could not be reached (unloaded) to be removed or let go when it loads. */
    public void fate(UUID id, Fate fate) {
        if (fates.size() >= FATES) {
            // bounded: the oldest are forgotten (a stray animal then simply stays what it was)
            String first = fates.keySet().iterator().next();
            fates.remove(first);
        }
        fates.putString(id.toString(), fate.name());
        setDirty();
    }

    /** The fate waiting for an animal (by its animal id or its entity UUID), consumed; null when none. */
    @Nullable
    public Fate takeFate(UUID... ids) {
        Fate found = null;
        for (UUID id : ids) {
            String key = id.toString();
            if (fates.contains(key)) {
                found = Fate.valueOf(fates.getStringOr(key, ""));
                fates.remove(key);
                setDirty();
            }
        }
        return found;
    }
}
