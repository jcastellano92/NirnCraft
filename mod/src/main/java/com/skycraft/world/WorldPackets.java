package com.skycraft.world;

import com.skycraft.network.SkyNetwork;
import com.skycraft.world.client.WorldClient;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Packets of the world module. Registration order is fixed (ids must match on both sides). */
public final class WorldPackets {
    private WorldPackets() {}

    static void register() {
        SkyNetwork.register(Cue.class, NetworkDirection.PLAY_TO_CLIENT, Cue::encode, Cue::decode, Cue::handle);
        SkyNetwork.register(RestFade.class, NetworkDirection.PLAY_TO_CLIENT, RestFade::encode, RestFade::decode, RestFade::handle);
        SkyNetwork.register(RestState.class, NetworkDirection.PLAY_TO_CLIENT, RestState::encode, RestState::decode, RestState::handle);
        SkyNetwork.register(RestHint.class, NetworkDirection.PLAY_TO_CLIENT, RestHint::encode, RestHint::decode, RestHint::handle);
        SkyNetwork.register(RestPose.class, NetworkDirection.PLAY_TO_CLIENT, RestPose::encode, RestPose::decode, RestPose::handle);
        SkyNetwork.register(Rest.class, NetworkDirection.PLAY_TO_SERVER, Rest::encode, Rest::decode, Rest::handle);
        SkyNetwork.register(FastTravel.class, NetworkDirection.PLAY_TO_SERVER, FastTravel::encode, FastTravel::decode, FastTravel::handle);
    }

    // ------------------------------------------------------------------ S2C

    /** A musical cue on the client (discovery sting). */
    public record Cue(int cue) {
        public static final int DISCOVERY = 0;

        static void encode(Cue m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.cue);
        }

        static Cue decode(FriendlyByteBuf buf) {
            return new Cue(buf.readVarInt());
        }

        static void handle(Cue m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> WorldClient.cue(m.cue));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Fade to black after waiting, sleeping or fast travelling ("8 hours later..."). */
    public record RestFade(int hours, int kind) {
        public static final int WAIT = 0;
        public static final int SLEEP = 1;
        public static final int TRAVEL = 2;

        static void encode(RestFade m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.hours);
            buf.writeVarInt(m.kind);
        }

        static RestFade decode(FriendlyByteBuf buf) {
            return new RestFade(buf.readVarInt(), buf.readVarInt());
        }

        static void handle(RestFade m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> WorldClient.fade(m.hours, m.kind));
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * The resting player's own state: {@code phase} 0 none (stand up, give the camera back), 1 resting (time menu),
     * 2 fast-forward; {@code progress} is per mille of the skip; {@code initiator} the name of who picks the hours.
     */
    public record RestState(int phase, int mode, int flags, int hours, int progress, int resting, int required, String initiator) {
        public static final int F_INITIATOR = 1;
        public static final int F_CAN_START = 2;
        /** Time cannot pass (a config switch or a jail sleep without hours). */
        public static final int F_SKIP_OFF = 4;
        /** The skip is personal: needs only, the world clock does not move (multiplayer sync off). */
        public static final int F_PERSONAL = 8;
        /** The player can still be cancelled out of (always); reserved. */
        public static final int F_JAIL = 16;

        static void encode(RestState m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.phase);
            buf.writeVarInt(m.mode);
            buf.writeVarInt(m.flags);
            buf.writeVarInt(m.hours);
            buf.writeVarInt(m.progress);
            buf.writeVarInt(m.resting);
            buf.writeVarInt(m.required);
            buf.writeUtf(m.initiator, 64);
        }

        static RestState decode(FriendlyByteBuf buf) {
            return new RestState(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readUtf(64));
        }

        static void handle(RestState m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> WorldClient.restState(m));
            ctx.get().setPacketHandled(true);
        }
    }

    /** "Name is resting. Press [key] to rest and join (n/m)" for a player who is not resting; {@code required} 0 clears it. */
    public record RestHint(String who, int resting, int required) {
        static void encode(RestHint m, FriendlyByteBuf buf) {
            buf.writeUtf(m.who, 64);
            buf.writeVarInt(m.resting);
            buf.writeVarInt(m.required);
        }

        static RestHint decode(FriendlyByteBuf buf) {
            return new RestHint(buf.readUtf(64), buf.readVarInt(), buf.readVarInt());
        }

        static void handle(RestHint m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> WorldClient.restHint(m));
            ctx.get().setPacketHandled(true);
        }
    }

    /** A player lies down (head pointing along {@code yaw}, as the sleeping pose's rotation) or gets up. */
    public record RestPose(int entityId, boolean lying, float yaw) {
        static void encode(RestPose m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeBoolean(m.lying);
            buf.writeFloat(m.yaw);
        }

        static RestPose decode(FriendlyByteBuf buf) {
            return new RestPose(buf.readVarInt(), buf.readBoolean(), buf.readFloat());
        }

        static void handle(RestPose m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> WorldClient.restPose(m));
            ctx.get().setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ C2S

    /**
     * The rest system's one client request: ENTER a rest {@code mode} ({@link RestLogic#SLEEP} next to a bed or
     * {@link RestLogic#WAIT} sitting; the server finds the bed itself), START the time skip for {@code hours} (1-24,
     * the initiator only) or CANCEL (stand up / stop the skip).
     */
    public record Rest(int action, int mode, int hours) {
        public static final int ENTER = 0;
        public static final int START = 1;
        public static final int CANCEL = 2;

        static void encode(Rest m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.action);
            buf.writeVarInt(m.mode);
            buf.writeVarInt(m.hours);
        }

        static Rest decode(FriendlyByteBuf buf) {
            return new Rest(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }

        static void handle(Rest m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) RestManager.handle(player, m.action, m.mode, m.hours);
            ctx.get().setPacketHandled(true);
        }
    }

    /** Fast travel to a discovered location by id. */
    public record FastTravel(String id) {
        static void encode(FastTravel m, FriendlyByteBuf buf) {
            buf.writeUtf(m.id, 512);
        }

        static FastTravel decode(FriendlyByteBuf buf) {
            return new FastTravel(buf.readUtf(512));
        }

        static void handle(FastTravel m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) FastTravelHandler.travel(player, m.id);
            ctx.get().setPacketHandled(true);
        }
    }
}
