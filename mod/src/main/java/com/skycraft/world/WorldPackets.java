package com.skycraft.world;

import com.skycraft.network.SkyNetwork;
import com.skycraft.world.client.WorldClient;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import com.skycraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;


/** Packets of the world module. Registration order is fixed (ids must match on both sides). */
public final class WorldPackets {
    private WorldPackets() {}

    static void register() {
        SkyNetwork.register(Cue.class, PacketFlow.CLIENTBOUND, Cue::encode, Cue::decode, Cue::handle);
        SkyNetwork.register(RestFade.class, PacketFlow.CLIENTBOUND, RestFade::encode, RestFade::decode, RestFade::handle);
        SkyNetwork.register(RestState.class, PacketFlow.CLIENTBOUND, RestState::encode, RestState::decode, RestState::handle);
        SkyNetwork.register(RestHint.class, PacketFlow.CLIENTBOUND, RestHint::encode, RestHint::decode, RestHint::handle);
        SkyNetwork.register(Rest.class, PacketFlow.SERVERBOUND, Rest::encode, Rest::decode, Rest::handle);
        SkyNetwork.register(FastTravel.class, PacketFlow.SERVERBOUND, FastTravel::encode, FastTravel::decode, FastTravel::handle);
    }

    // ------------------------------------------------------------------ S2C

    /** A musical cue on the client (discovery sting). */
    public record Cue(int cue) {
        public static final int DISCOVERY = 0;

        static void encode(Cue m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.cue);
        }

        static Cue decode(RegistryFriendlyByteBuf buf) {
            return new Cue(buf.readVarInt());
        }

        static void handle(Cue m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> WorldClient.cue(m.cue));
        }
    }

    /** Fade to black after waiting, sleeping or fast travelling ("8 hours later..."). */
    public record RestFade(int hours, int kind) {
        public static final int WAIT = 0;
        public static final int SLEEP = 1;
        public static final int TRAVEL = 2;

        static void encode(RestFade m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.hours);
            buf.writeVarInt(m.kind);
        }

        static RestFade decode(RegistryFriendlyByteBuf buf) {
            return new RestFade(buf.readVarInt(), buf.readVarInt());
        }

        static void handle(RestFade m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> WorldClient.fade(m.hours, m.kind));
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

        static void encode(RestState m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.phase);
            buf.writeVarInt(m.mode);
            buf.writeVarInt(m.flags);
            buf.writeVarInt(m.hours);
            buf.writeVarInt(m.progress);
            buf.writeVarInt(m.resting);
            buf.writeVarInt(m.required);
            buf.writeUtf(m.initiator, 64);
        }

        static RestState decode(RegistryFriendlyByteBuf buf) {
            return new RestState(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readUtf(64));
        }

        static void handle(RestState m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> WorldClient.restState(m));
        }
    }

    /** "Name is resting. Press [key] to rest and join (n/m)" for a player who is not resting; {@code required} 0 clears it. */
    public record RestHint(String who, int resting, int required) {
        static void encode(RestHint m, RegistryFriendlyByteBuf buf) {
            buf.writeUtf(m.who, 64);
            buf.writeVarInt(m.resting);
            buf.writeVarInt(m.required);
        }

        static RestHint decode(RegistryFriendlyByteBuf buf) {
            return new RestHint(buf.readUtf(64), buf.readVarInt(), buf.readVarInt());
        }

        static void handle(RestHint m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> WorldClient.restHint(m));
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

        static void encode(Rest m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.action);
            buf.writeVarInt(m.mode);
            buf.writeVarInt(m.hours);
        }

        static Rest decode(RegistryFriendlyByteBuf buf) {
            return new Rest(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }

        static void handle(Rest m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) RestManager.handle(player, m.action, m.mode, m.hours);
        }
    }

    /** Fast travel to a discovered location by id. */
    public record FastTravel(String id) {
        static void encode(FastTravel m, RegistryFriendlyByteBuf buf) {
            buf.writeUtf(m.id, 512);
        }

        static FastTravel decode(RegistryFriendlyByteBuf buf) {
            return new FastTravel(buf.readUtf(512));
        }

        static void handle(FastTravel m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) FastTravelHandler.travel(player, m.id);
        }
    }
}
