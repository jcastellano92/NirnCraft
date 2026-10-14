package com.skycraft.crime;

import com.skycraft.crime.client.CrimeClientHandlers;
import com.skycraft.network.SkyNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Network messages of the crime module (registered in this fixed order by {@link CrimeModule#registerPackets()}). */
public final class CrimePackets {
    private static final Map<UUID, Long> LAST_QUERY = new HashMap<>();

    private CrimePackets() {}

    static void register() {
        SkyNetwork.register(OpenPickpocket.class, NetworkDirection.PLAY_TO_CLIENT, OpenPickpocket::encode, OpenPickpocket::decode, OpenPickpocket::handle);
        SkyNetwork.register(ClosePickpocket.class, NetworkDirection.PLAY_TO_CLIENT, ClosePickpocket::encode, ClosePickpocket::decode, ClosePickpocket::handle);
        SkyNetwork.register(PickpocketTake.class, NetworkDirection.PLAY_TO_SERVER, PickpocketTake::encode, PickpocketTake::decode, PickpocketTake::handle);
        SkyNetwork.register(OpenLockpick.class, NetworkDirection.PLAY_TO_CLIENT, OpenLockpick::encode, OpenLockpick::decode, OpenLockpick::handle);
        SkyNetwork.register(LockTurn.class, NetworkDirection.PLAY_TO_SERVER, LockTurn::encode, LockTurn::decode, LockTurn::handle);
        SkyNetwork.register(LockResult.class, NetworkDirection.PLAY_TO_CLIENT, LockResult::encode, LockResult::decode, LockResult::handle);
        SkyNetwork.register(LockClose.class, NetworkDirection.PLAY_TO_SERVER, LockClose::encode, LockClose::decode, LockClose::handle);
        SkyNetwork.register(QueryContainer.class, NetworkDirection.PLAY_TO_SERVER, QueryContainer::encode, QueryContainer::decode, QueryContainer::handle);
        SkyNetwork.register(ContainerInfo.class, NetworkDirection.PLAY_TO_CLIENT, ContainerInfo::encode, ContainerInfo::decode, ContainerInfo::handle);
        SkyNetwork.register(JailCover.class, NetworkDirection.PLAY_TO_CLIENT, JailCover::encode, JailCover::decode, JailCover::handle);
    }

    static void forget(UUID player) {
        LAST_QUERY.remove(player);
    }

    // ------------------------------------------------------------------ pickpocket

    public record PocketEntry(ItemStack stack, int chance) {}

    /** Shows (or refreshes) the pickpocket screen for an NPC. */
    public record OpenPickpocket(int entityId, Component name, List<PocketEntry> entries) {
        static void encode(OpenPickpocket m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeComponent(m.name);
            buf.writeVarInt(m.entries.size());
            for (PocketEntry e : m.entries) {
                buf.writeItem(e.stack());
                buf.writeVarInt(e.chance());
            }
        }

        static OpenPickpocket decode(FriendlyByteBuf buf) {
            int id = buf.readVarInt();
            Component name = buf.readComponent();
            int n = Math.min(64, buf.readVarInt());
            List<PocketEntry> entries = new ArrayList<>();
            for (int i = 0; i < n; i++) entries.add(new PocketEntry(buf.readItem(), buf.readVarInt()));
            return new OpenPickpocket(id, name, entries);
        }

        static void handle(OpenPickpocket m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.openPickpocket(m));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Closes the pickpocket screen (caught, or the NPC is gone). */
    public record ClosePickpocket() {
        static void encode(ClosePickpocket m, FriendlyByteBuf buf) {
        }

        static ClosePickpocket decode(FriendlyByteBuf buf) {
            return new ClosePickpocket();
        }

        static void handle(ClosePickpocket m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.closePickpocket());
            ctx.get().setPacketHandled(true);
        }
    }

    /** The player tries to steal pocket slot {@code slot} of the NPC. */
    public record PickpocketTake(int entityId, int slot) {
        static void encode(PickpocketTake m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeVarInt(m.slot);
        }

        static PickpocketTake decode(FriendlyByteBuf buf) {
            return new PickpocketTake(buf.readVarInt(), buf.readVarInt());
        }

        static void handle(PickpocketTake m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) Pickpocket.take(player, m.entityId, m.slot);
            ctx.get().setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ lockpicking

    /** Opens the lockpicking minigame. The sweet spot is NOT sent; the server judges every attempt. */
    public record OpenLockpick(BlockPos pos, int tier, int picks) {
        static void encode(OpenLockpick m, FriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
            buf.writeVarInt(m.tier);
            buf.writeVarInt(m.picks);
        }

        static OpenLockpick decode(FriendlyByteBuf buf) {
            return new OpenLockpick(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt());
        }

        static void handle(OpenLockpick m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.openLockpick(m));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Sent every few ticks while the player turns the lock; {@code start} marks the first packet of a turn. */
    public record LockTurn(float angle, boolean start) {
        static void encode(LockTurn m, FriendlyByteBuf buf) {
            buf.writeFloat(m.angle);
            buf.writeBoolean(m.start);
        }

        static LockTurn decode(FriendlyByteBuf buf) {
            return new LockTurn(buf.readFloat(), buf.readBoolean());
        }

        static void handle(LockTurn m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) Locks.turn(player, m.angle, m.start);
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * Server verdict on a turn: {@link Locks#RESULT_PARTIAL} (value = how far the lock turns, 0..1),
     * {@link Locks#RESULT_BROKEN} (value = lockpicks left), {@link Locks#RESULT_UNLOCKED} or {@link Locks#RESULT_CLOSE}.
     */
    public record LockResult(int result, float value) {
        static void encode(LockResult m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.result);
            buf.writeFloat(m.value);
        }

        static LockResult decode(FriendlyByteBuf buf) {
            return new LockResult(buf.readVarInt(), buf.readFloat());
        }

        static void handle(LockResult m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.lockResult(m));
            ctx.get().setPacketHandled(true);
        }
    }

    /** The player left the lockpicking screen. */
    public record LockClose() {
        static void encode(LockClose m, FriendlyByteBuf buf) {
        }

        static LockClose decode(FriendlyByteBuf buf) {
            return new LockClose();
        }

        static void handle(LockClose m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) Locks.close(player);
            ctx.get().setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ crosshair hints

    /** The client looks at a container and asks whether it's owned / locked (for the "Steal" and "Locked" hints). */
    public record QueryContainer(BlockPos pos) {
        static void encode(QueryContainer m, FriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
        }

        static QueryContainer decode(FriendlyByteBuf buf) {
            return new QueryContainer(buf.readBlockPos());
        }

        static void handle(QueryContainer m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) answer(player, m.pos);
            ctx.get().setPacketHandled(true);
        }

        private static void answer(ServerPlayer player, BlockPos pos) {
            long now = player.level().getGameTime();
            Long last = LAST_QUERY.get(player.getUUID());
            if (last != null && now - last < 5) return;
            LAST_QUERY.put(player.getUUID(), now);
            if (player.distanceToSqr(Vec3.atCenterOf(pos)) > 100 || !player.level().isLoaded(pos)) return;
            var level = player.serverLevel();
            var queried = level.getBlockState(pos).getBlock();
            if (queried instanceof net.minecraft.world.level.block.DoorBlock || queried instanceof net.minecraft.world.level.block.TrapDoorBlock) {
                // Doors have no block entity, so they used to get no answer at all: the client then predicted the door
                // opening and the server snapped it shut again. Answer with the real lock decision (inn rooms, owned
                // doors, picked locks included) so the client can refuse the click before the door moves.
                boolean blocked = Locks.blocks(player, level, pos);
                // looking at a door never decides anything: only a blocked door has a tier to report
                int tier = blocked ? Math.max(0, Locks.lockLevel(level, Locks.normalizePos(level, pos))) : Locks.NOT_LOCKED;
                SkyNetwork.sendToPlayer(player, new ContainerInfo(pos, false, tier, !blocked));
                return;
            }
            if (level.getBlockEntity(pos) == null) return;
            boolean owned = !player.isCreative() && Theft.isOwned(level, pos);
            int lock = Locks.lockLevel(level, pos);
            boolean unlocked = lock == Locks.NOT_LOCKED || Locks.isUnlocked(player, level, pos) || player.isCreative();
            SkyNetwork.sendToPlayer(player, new ContainerInfo(pos, owned, lock, unlocked));
        }
    }

    public record ContainerInfo(BlockPos pos, boolean owned, int lock, boolean unlocked) {
        static void encode(ContainerInfo m, FriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
            buf.writeBoolean(m.owned);
            buf.writeVarInt(m.lock + 1);
            buf.writeBoolean(m.unlocked);
        }

        static ContainerInfo decode(FriendlyByteBuf buf) {
            return new ContainerInfo(buf.readBlockPos(), buf.readBoolean(), buf.readVarInt() - 1, buf.readBoolean());
        }

        static void handle(ContainerInfo m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.containerInfo(m));
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * The loading cover of an arrest whose jail is being generated (the opening's "valley wakes" screen with another
     * title and subtitle): {@code show} raises or updates it with the chunks ready of needed, else it fades away.
     */
    public record JailCover(boolean show, Component title, Component sub, int ready, int total) {
        static void encode(JailCover m, FriendlyByteBuf buf) {
            buf.writeBoolean(m.show);
            buf.writeComponent(m.title);
            buf.writeComponent(m.sub);
            buf.writeVarInt(m.ready);
            buf.writeVarInt(m.total);
        }

        static JailCover decode(FriendlyByteBuf buf) {
            return new JailCover(buf.readBoolean(), buf.readComponent(), buf.readComponent(), buf.readVarInt(), buf.readVarInt());
        }

        static void handle(JailCover m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.jailCover(m));
            ctx.get().setPacketHandled(true);
        }
    }
}
