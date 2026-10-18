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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
    private record Publication(Object token, long key, byte[] bytes) {}
    private record Owner(BooleanSupplier isThread, Consumer<Runnable> execute) {}
    private volatile Owner owner;
    private final ConcurrentLinkedQueue<Publication> publications = new ConcurrentLinkedQueue<>();

    public TilePlanData() {
    }

    /** Attaches this world's {@link TilePlanData} to {@link TilePlanner} for automatic load/save. */
    public static TilePlanData attach(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        long seed = overworld.getSeed();
        TilePlanData data = com.skycraft.platform.SavedDataTypes.get(overworld, NAME, TilePlanData::load, TilePlanData::new, d -> d.save(new CompoundTag()));
        var server = overworld.getServer();
        data.bind(seed, server::isSameThread, server::execute);
        return data;
    }

    /** Thread/scheduler binding is separate from Minecraft construction so queued publication can be tested exactly. */
    void bind(long seed, BooleanSupplier isOwnerThread, Consumer<Runnable> execute) {
        if (!isOwnerThread.getAsBoolean()) throw new IllegalStateException("Tile save must attach on its server thread");
        owner = new Owner(isOwnerThread, execute);
        TilePlanner.attachQueued(seed, this,
                (worldSeed, tx, tz) -> readTile(worldSeed, tx, tz),
                (token, key, plan) -> queueTile(token, key, plan), this::flush, isOwnerThread);
    }

    /** Workers may serialize, but never mutate the save map or SavedData's dirty flag. */
    public void putTile(TilePlan plan) {
        Object token = TilePlanner.tokenFor(this);
        if (token == null) throw new IllegalStateException("Tile save is not attached");
        queueTile(token, TilePlan.key(plan.tileX(), plan.tileZ()), plan);
    }

    private void queueTile(Object token, long key, TilePlan plan) {
        try {
            enqueueSerialized(token, key, plan.write());
        } catch (IOException e) {
            Skycraft.LOGGER.warn("[tileplan] failed to serialise tile ({}, {})", plan.tileX(), plan.tileZ(), e);
        }
    }

    boolean enqueueSerialized(Object token, long key, byte[] bytes) {
        Owner target = owner;
        if (target == null) return false;
        boolean accepted = TilePlanner.enqueuePublication(token, () -> publications.add(new Publication(token, key, bytes)));
        if (accepted) target.execute.accept(this::flush);
        return accepted;
    }

    /** Also called before normal autosave and before orderly detach; never joins worker futures. */
    void flush() {
        Owner target = owner;
        if (target == null) return;
        if (!target.isThread.getAsBoolean()) throw new IllegalStateException("Tile save publication must run on its server thread");
        Publication ready;
        while ((ready = publications.poll()) != null) {
            Publication publication = ready;
            TilePlanner.publish(publication.token, () -> {
                tileBytes.put(publication.key, publication.bytes);
                setDirty();
            });
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
        ListTag list = tag.getListOrEmpty("Tiles");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompoundOrEmpty(i);
            long key = entry.getLongOr("Key", 0L);
            byte[] payload = entry.getByteArray("Bytes").orElseGet(() -> new byte[0]);
            if (payload.length > 0) {
                d.tileBytes.put(key, payload);
            }
        }
        return d;
    }

    public CompoundTag save(CompoundTag tag) {
        flush();
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
