package com.nirncraft.dig;

import com.nirncraft.NirnCraft;
import com.nirncraft.network.CorePackets;
import com.nirncraft.network.SkyNetwork;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.Arrays;
import java.util.function.Supplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Remembers which blocks in a chunk were placed by players, so builders can always remove their own blocks even
 * while terrain digging is still locked behind Mining perks.
 *
 * <p>The server owns the data (a persistent chunk attachment). Clients get each chunk's set when they start watching it and
 * add/remove deltas afterwards, so client-side checks (digging, locks, ownership, theft hints) agree with the
 * server.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class PlacedBlocks {
    private static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, NirnCraft.MODID);
    public static final Supplier<AttachmentType<Store>> DATA = TYPES.register("placed_blocks", () ->
            AttachmentType.builder(Store::new).serialize(com.mojang.serialization.Codec.LONG_STREAM
                    .fieldOf("positions").xmap(values -> {
                        Store store = new Store();
                        values.forEach(store.positions::add);
                        return store;
                    }, store -> Arrays.stream(store.positions.toLongArray()))).build());
    /** Positions per sync packet (8 bytes each). */
    private static final int MAX_PER_PACKET = 32768;

    /** Client copy: chunk pos (long) -> placed block positions. Only touched on the client thread. */
    private static final Long2ObjectOpenHashMap<LongOpenHashSet> CLIENT = new Long2ObjectOpenHashMap<>();

    private PlacedBlocks() {}

    public static void init(IEventBus modBus) {
        TYPES.register(modBus);
    }

    public static boolean isPlayerPlaced(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            LongOpenHashSet set = CLIENT.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
            return set != null && set.contains(pos.asLong());
        }
        LevelChunk chunk = level.getChunkAt(pos);
        Store store = chunk.getExistingDataOrNull(DATA.get());
        return store != null && store.positions.contains(pos.asLong());
    }

    public static void mark(Level level, BlockPos pos, boolean placed) {
        if (level.isClientSide()) return;
        LevelChunk chunk = level.getChunkAt(pos);
        Store s = chunk.getData(DATA.get());
        {
            boolean changed = placed ? s.positions.add(pos.asLong()) : s.positions.remove(pos.asLong());
            if (changed) {
                chunk.markUnsaved();
                long[] one = {pos.asLong()};
                long[] none = new long[0];
                SkyNetwork.sendToTrackingChunk((ServerLevel) level, chunk.getPos(),
                        new CorePackets.PlacedBlocksSync(chunk.getPos().pack(), false, placed ? one : none, placed ? none : one));
            }
        }
    }

    // ------------------------------------------------------------------ client sync

    /** A player starts seeing a chunk (sent right after the chunk itself): give them its placed blocks. */
    @SubscribeEvent
    public static void onWatch(ChunkWatchEvent.Sent event) {
        LevelChunk chunk = event.getChunk();
        Store s = chunk.getExistingDataOrNull(DATA.get());
        if (s != null) {
            if (s.positions.isEmpty()) return;
            long[] all = s.positions.toLongArray();
            long key = chunk.getPos().pack();
            for (int from = 0; from < all.length; from += MAX_PER_PACKET) {
                long[] part = Arrays.copyOfRange(all, from, Math.min(all.length, from + MAX_PER_PACKET));
                SkyNetwork.sendToPlayer(event.getPlayer(), new CorePackets.PlacedBlocksSync(key, from == 0, part, new long[0]));
            }
        }
    }

    /** Client side (packet handler): applies a full chunk set or a delta. */
    public static void applyClientSync(long chunk, boolean full, long[] added, long[] removed) {
        LongOpenHashSet set = full ? null : CLIENT.get(chunk);
        if (set == null) set = new LongOpenHashSet();
        for (long l : added) set.add(l);
        for (long l : removed) set.remove(l);
        if (set.isEmpty()) CLIENT.remove(chunk);
        else CLIENT.put(chunk, set);
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            CLIENT.remove(event.getChunk().getPos().pack());
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) CLIENT.clear();
    }

    public static class Store {
        final LongOpenHashSet positions = new LongOpenHashSet();
    }
}
