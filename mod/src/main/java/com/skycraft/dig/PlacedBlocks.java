package com.skycraft.dig;

import com.skycraft.Skycraft;
import com.skycraft.network.CorePackets;
import com.skycraft.network.SkyNetwork;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Remembers which blocks in a chunk were placed by players, so builders can always remove their own blocks even
 * while terrain digging is still locked behind Mining perks.
 *
 * <p>The server owns the data (a chunk capability). Clients get each chunk's set when they start watching it and
 * add/remove deltas afterwards, so client-side checks (digging, locks, ownership, theft hints) agree with the
 * server.</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class PlacedBlocks {
    public static final Capability<Store> CAP = CapabilityManager.get(new CapabilityToken<>() {});
    /** Positions per sync packet (8 bytes each). */
    private static final int MAX_PER_PACKET = 32768;

    /** Client copy: chunk pos (long) -> placed block positions. Only touched on the client thread. */
    private static final Long2ObjectOpenHashMap<LongOpenHashSet> CLIENT = new Long2ObjectOpenHashMap<>();

    private PlacedBlocks() {}

    public static boolean isPlayerPlaced(Level level, BlockPos pos) {
        if (level.isClientSide) {
            LongOpenHashSet set = CLIENT.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
            return set != null && set.contains(pos.asLong());
        }
        LevelChunk chunk = level.getChunkAt(pos);
        return chunk.getCapability(CAP).map(s -> s.positions.contains(pos.asLong())).orElse(false);
    }

    public static void mark(Level level, BlockPos pos, boolean placed) {
        if (level.isClientSide) return;
        LevelChunk chunk = level.getChunkAt(pos);
        chunk.getCapability(CAP).ifPresent(s -> {
            boolean changed = placed ? s.positions.add(pos.asLong()) : s.positions.remove(pos.asLong());
            if (changed) {
                chunk.setUnsaved(true);
                long[] one = {pos.asLong()};
                long[] none = new long[0];
                SkyNetwork.CHANNEL.send(PacketDistributor.TRACKING_CHUNK.with(() -> chunk),
                        new CorePackets.PlacedBlocksSync(chunk.getPos().toLong(), false, placed ? one : none, placed ? none : one));
            }
        });
    }

    @SubscribeEvent
    public static void attach(AttachCapabilitiesEvent<LevelChunk> event) {
        event.addCapability(new ResourceLocation(Skycraft.MODID, "placed_blocks"), new Store());
    }

    // ------------------------------------------------------------------ client sync

    /** A player starts seeing a chunk (sent right after the chunk itself): give them its placed blocks. */
    @SubscribeEvent
    public static void onWatch(ChunkWatchEvent.Watch event) {
        LevelChunk chunk = event.getChunk();
        chunk.getCapability(CAP).ifPresent(s -> {
            if (s.positions.isEmpty()) return;
            long[] all = s.positions.toLongArray();
            long key = chunk.getPos().toLong();
            for (int from = 0; from < all.length; from += MAX_PER_PACKET) {
                long[] part = Arrays.copyOfRange(all, from, Math.min(all.length, from + MAX_PER_PACKET));
                SkyNetwork.sendToPlayer(event.getPlayer(), new CorePackets.PlacedBlocksSync(key, from == 0, part, new long[0]));
            }
        });
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
            CLIENT.remove(event.getChunk().getPos().toLong());
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) CLIENT.clear();
    }

    public static class Store implements ICapabilitySerializable<Tag> {
        final LongOpenHashSet positions = new LongOpenHashSet();
        private final LazyOptional<Store> optional = LazyOptional.of(() -> this);

        @Override
        public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
            return CAP.orEmpty(cap, optional);
        }

        @Override
        public Tag serializeNBT() {
            return new LongArrayTag(positions.toLongArray());
        }

        @Override
        public void deserializeNBT(Tag nbt) {
            positions.clear();
            if (nbt instanceof LongArrayTag arr) {
                for (long l : arr.getAsLongArray()) positions.add(l);
            }
        }
    }
}
