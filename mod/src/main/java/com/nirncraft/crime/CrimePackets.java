package com.nirncraft.crime;

import com.nirncraft.crime.client.CrimeClientHandlers;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import com.nirncraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Network messages of the crime module (registered in this fixed order by {@link CrimeModule#registerPackets()}). */
public final class CrimePackets {
    private static final Map<UUID, Long> LAST_QUERY = new HashMap<>();
    private static final Map<UUID, BlockPos> LAST_QUERY_POS = new HashMap<>();

    private CrimePackets() {}

    static void register() {
        SkyNetwork.register(OpenPickpocket.class, PacketFlow.CLIENTBOUND, OpenPickpocket::encode, OpenPickpocket::decode, OpenPickpocket::handle);
        SkyNetwork.register(ClosePickpocket.class, PacketFlow.CLIENTBOUND, ClosePickpocket::encode, ClosePickpocket::decode, ClosePickpocket::handle);
        SkyNetwork.register(PickpocketTake.class, PacketFlow.SERVERBOUND, PickpocketTake::encode, PickpocketTake::decode, PickpocketTake::handle);
        SkyNetwork.register(OpenLockpick.class, PacketFlow.CLIENTBOUND, OpenLockpick::encode, OpenLockpick::decode, OpenLockpick::handle);
        SkyNetwork.register(LockTurn.class, PacketFlow.SERVERBOUND, LockTurn::encode, LockTurn::decode, LockTurn::handle);
        SkyNetwork.register(LockResult.class, PacketFlow.CLIENTBOUND, LockResult::encode, LockResult::decode, LockResult::handle);
        SkyNetwork.register(LockClose.class, PacketFlow.SERVERBOUND, LockClose::encode, LockClose::decode, LockClose::handle);
        SkyNetwork.register(QueryContainer.class, PacketFlow.SERVERBOUND, QueryContainer::encode, QueryContainer::decode, QueryContainer::handle);
        SkyNetwork.register(ContainerInfo.class, PacketFlow.CLIENTBOUND, ContainerInfo::encode, ContainerInfo::decode, ContainerInfo::handle);
        SkyNetwork.register(JailCover.class, PacketFlow.CLIENTBOUND, JailCover::encode, JailCover::decode, JailCover::handle);
    }

    static void forget(UUID player) {
        LAST_QUERY.remove(player);
        LAST_QUERY_POS.remove(player);
    }

    // ------------------------------------------------------------------ pickpocket

    public record PocketEntry(ItemStack stack, int chance) {}

    /** Shows (or refreshes) the pickpocket screen for an NPC. */
    public record OpenPickpocket(int entityId, Component name, List<PocketEntry> entries) {
        static void encode(OpenPickpocket m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, m.name);
            buf.writeVarInt(m.entries.size());
            for (PocketEntry e : m.entries) {
                net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, e.stack());
                buf.writeVarInt(e.chance());
            }
        }

        static OpenPickpocket decode(RegistryFriendlyByteBuf buf) {
            int id = buf.readVarInt();
            Component name = net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf);
            int n = Math.min(64, buf.readVarInt());
            List<PocketEntry> entries = new ArrayList<>();
            for (int i = 0; i < n; i++) entries.add(new PocketEntry(net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), buf.readVarInt()));
            return new OpenPickpocket(id, name, entries);
        }

        static void handle(OpenPickpocket m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.openPickpocket(m));
        }
    }

    /** Closes the pickpocket screen (caught, or the NPC is gone). */
    public record ClosePickpocket() {
        static void encode(ClosePickpocket m, RegistryFriendlyByteBuf buf) {
        }

        static ClosePickpocket decode(RegistryFriendlyByteBuf buf) {
            return new ClosePickpocket();
        }

        static void handle(ClosePickpocket m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.closePickpocket());
        }
    }

    /** The player tries to steal pocket slot {@code slot} of the NPC. */
    public record PickpocketTake(int entityId, int slot) {
        static void encode(PickpocketTake m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeVarInt(m.slot);
        }

        static PickpocketTake decode(RegistryFriendlyByteBuf buf) {
            return new PickpocketTake(buf.readVarInt(), buf.readVarInt());
        }

        static void handle(PickpocketTake m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) Pickpocket.take(player, m.entityId, m.slot);
        }
    }

    // ------------------------------------------------------------------ lockpicking

    /** Opens the lockpicking minigame. The sweet spot is NOT sent; the server judges every attempt. */
    public record OpenLockpick(BlockPos pos, int tier, int picks) {
        static void encode(OpenLockpick m, RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
            buf.writeVarInt(m.tier);
            buf.writeVarInt(m.picks);
        }

        static OpenLockpick decode(RegistryFriendlyByteBuf buf) {
            return new OpenLockpick(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt());
        }

        static void handle(OpenLockpick m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.openLockpick(m));
        }
    }

    /** Sent every few ticks while the player turns the lock; {@code start} marks the first packet of a turn. */
    public record LockTurn(float angle, boolean start) {
        static void encode(LockTurn m, RegistryFriendlyByteBuf buf) {
            buf.writeFloat(m.angle);
            buf.writeBoolean(m.start);
        }

        static LockTurn decode(RegistryFriendlyByteBuf buf) {
            return new LockTurn(buf.readFloat(), buf.readBoolean());
        }

        static void handle(LockTurn m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) Locks.turn(player, m.angle, m.start);
        }
    }

    /**
     * Server verdict on a turn: {@link Locks#RESULT_PARTIAL} (value = how far the lock turns, 0..1),
     * {@link Locks#RESULT_BROKEN} (value = lockpicks left), {@link Locks#RESULT_UNLOCKED} or {@link Locks#RESULT_CLOSE}.
     */
    public record LockResult(int result, float value) {
        static void encode(LockResult m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.result);
            buf.writeFloat(m.value);
        }

        static LockResult decode(RegistryFriendlyByteBuf buf) {
            return new LockResult(buf.readVarInt(), buf.readFloat());
        }

        static void handle(LockResult m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.lockResult(m));
        }
    }

    /** The player left the lockpicking screen. */
    public record LockClose() {
        static void encode(LockClose m, RegistryFriendlyByteBuf buf) {
        }

        static LockClose decode(RegistryFriendlyByteBuf buf) {
            return new LockClose();
        }

        static void handle(LockClose m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) Locks.close(player);
        }
    }

    // ------------------------------------------------------------------ crosshair hints

    /** The client looks at a container and asks whether it's owned / locked (for the "Steal" and "Locked" hints). */
    public record QueryContainer(BlockPos pos) {
        static void encode(QueryContainer m, RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
        }

        static QueryContainer decode(RegistryFriendlyByteBuf buf) {
            return new QueryContainer(buf.readBlockPos());
        }

        static void handle(QueryContainer m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) answer(player, m.pos);
        }

        private static void answer(ServerPlayer player, BlockPos pos) {
            long now = player.level().getGameTime();
            Long last = LAST_QUERY.get(player.getUUID());
            BlockPos prevPos = LAST_QUERY_POS.get(player.getUUID());
            if (last != null && now - last < 5 && pos.equals(prevPos)) return;
            LAST_QUERY.put(player.getUUID(), now);
            LAST_QUERY_POS.put(player.getUUID(), pos.immutable());
            if (player.distanceToSqr(Vec3.atCenterOf(pos)) > 100 || !player.level().isLoaded(pos)) return;
            var level = player.level();
            var queried = level.getBlockState(pos).getBlock();
            if (queried instanceof net.minecraft.world.level.block.DoorBlock || queried instanceof net.minecraft.world.level.block.TrapDoorBlock) {
                boolean blocked = Locks.blocks(player, level, pos);
                int tier = blocked ? Math.max(0, Locks.lockLevel(level, Locks.normalizePos(level, pos))) : Locks.NOT_LOCKED;
                Component doorLabel = com.nirncraft.tamriel.settlement.SettlementDoors.describeDoor(level, pos);
                SkyNetwork.sendToPlayer(player, new ContainerInfo(pos, false, tier, !blocked, doorLabel != null ? doorLabel : Component.empty()));
                return;
            }
            if (level.getBlockEntity(pos) == null) return;
            boolean owned = !player.isCreative() && Theft.isOwned(level, pos);
            int lock = Locks.lockLevel(level, pos);
            boolean unlocked = lock == Locks.NOT_LOCKED || Locks.isUnlocked(player, level, pos) || player.isCreative();
            SkyNetwork.sendToPlayer(player, new ContainerInfo(pos, owned, lock, unlocked, Component.empty()));
        }
    }

    public record ContainerInfo(BlockPos pos, boolean owned, int lock, boolean unlocked, Component label) {
        public ContainerInfo(BlockPos pos, boolean owned, int lock, boolean unlocked) {
            this(pos, owned, lock, unlocked, Component.empty());
        }

        static void encode(ContainerInfo m, RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
            buf.writeBoolean(m.owned);
            buf.writeVarInt(m.lock + 1);
            buf.writeBoolean(m.unlocked);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, m.label != null ? m.label : Component.empty());
        }

        static ContainerInfo decode(RegistryFriendlyByteBuf buf) {
            return new ContainerInfo(
                    buf.readBlockPos(),
                    buf.readBoolean(),
                    buf.readVarInt() - 1,
                    buf.readBoolean(),
                    net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf)
            );
        }

        static void handle(ContainerInfo m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.containerInfo(m));
        }
    }

    /**
     * The loading cover of an arrest whose jail is being generated (the opening's "valley wakes" screen with another
     * title and subtitle): {@code show} raises or updates it with the chunks ready of needed, else it fades away.
     */
    public record JailCover(boolean show, Component title, Component sub, int ready, int total) {
        static void encode(JailCover m, RegistryFriendlyByteBuf buf) {
            buf.writeBoolean(m.show);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, m.title);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, m.sub);
            buf.writeVarInt(m.ready);
            buf.writeVarInt(m.total);
        }

        static JailCover decode(RegistryFriendlyByteBuf buf) {
            return new JailCover(buf.readBoolean(), net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf), net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf), buf.readVarInt(), buf.readVarInt());
        }

        static void handle(JailCover m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> CrimeClientHandlers.jailCover(m));
        }
    }
}
