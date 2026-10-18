package com.skycraft.fauna;

import com.skycraft.fauna.client.FaunaClientEvents;
import com.skycraft.network.SkyNetwork;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import com.skycraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;


/**
 * Network packets for the fauna module: horse calling, passenger ride requests, and taming balance mini-game.
 */
public final class FaunaPackets {
    private FaunaPackets() {}

    public static void register() {
        SkyNetwork.register(CallHorse.class, PacketFlow.SERVERBOUND, CallHorse::encode, CallHorse::decode, CallHorse::handle);
        SkyNetwork.register(SteerBalance.class, PacketFlow.SERVERBOUND, SteerBalance::encode, SteerBalance::decode, SteerBalance::handle);
        SkyNetwork.register(TameState.class, PacketFlow.CLIENTBOUND, TameState::encode, TameState::decode, TameState::handle);
    }

    // ------------------------------------------------------------------ C2S Call Horse (H)
    public record CallHorse() {
        static void encode(CallHorse m, RegistryFriendlyByteBuf buf) {}
        static CallHorse decode(RegistryFriendlyByteBuf buf) { return new CallHorse(); }
        static void handle(CallHorse m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) {
                HorseManager.callHorse(player);
            }
        }
    }

    // ------------------------------------------------------------------ C2S Steer Balance
    public record SteerBalance(int direction) {
        static void encode(SteerBalance m, RegistryFriendlyByteBuf buf) { buf.writeInt(m.direction); }
        static SteerBalance decode(RegistryFriendlyByteBuf buf) { return new SteerBalance(buf.readInt()); }
        static void handle(SteerBalance m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) {
                WildHorseTaming.onClientSteer(player, m.direction);
            }
        }
    }

    // ------------------------------------------------------------------ S2C Tame State
    /**
     * The breaking's balance state, a few times a second ({@link TameBalance}): the marker (-100..100), the safe
     * zone's half-width, progress, the bucking's strength and the marker's velocity (all percent), and how much of the
     * grace at the edge is used up. {@code active} false ends the minigame on the client.
     */
    public record TameState(boolean active, int marker, int zone, int progress, int amp, int vel, int edge) {
        static void encode(TameState m, RegistryFriendlyByteBuf buf) {
            buf.writeBoolean(m.active);
            buf.writeByte(m.marker);
            buf.writeByte(m.zone);
            buf.writeByte(m.progress);
            buf.writeByte(m.amp);
            buf.writeByte(m.vel);
            buf.writeByte(m.edge);
        }
        static TameState decode(RegistryFriendlyByteBuf buf) {
            return new TameState(buf.readBoolean(), buf.readByte(), buf.readByte(), buf.readByte(), buf.readByte(), buf.readByte(), buf.readByte());
        }
        static void handle(TameState m, IPayloadContext ctx) {
            ctx.enqueueWork(() -> Sides.runOn(Dist.CLIENT, () -> () -> FaunaClientEvents.handleTameState(m)));
        }
    }
}

