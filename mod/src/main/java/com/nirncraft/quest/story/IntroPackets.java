package com.nirncraft.quest.story;

import com.nirncraft.network.SkyNetwork;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import com.nirncraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Packets of the opening. The server owns the state machine; the client shows the loading screen, runs the cutscene
 * (a client-only dragon, particles and sounds nobody else sees) and hides the players that are still in the cottage.
 */
public final class IntroPackets {
    private IntroPackets() {}

    public static void register() {
        SkyNetwork.register(State.class, PacketFlow.CLIENTBOUND, State::encode, State::decode, State::handle);
        SkyNetwork.register(Cutscene.class, PacketFlow.CLIENTBOUND, Cutscene::encode, Cutscene::decode, Cutscene::handle);
        SkyNetwork.register(Hidden.class, PacketFlow.CLIENTBOUND, Hidden::encode, Hidden::decode, Hidden::handle);
        SkyNetwork.register(Loaded.class, PacketFlow.SERVERBOUND, Loaded::encode, Loaded::decode, Loaded::handle);
    }

    /**
     * The player's own opening state. While LOADING it also carries what the client must have loaded before it says
     * so: the chunks within {@code chunkRadius} of the player and the town's chunks ({@code townCx/townCz/townRadius}),
     * and the server's preparation progress (0..1).
     */
    public record State(int state, float progress, int townCx, int townCz, int townRadius, int chunkRadius, int timeoutSeconds) {
        static void encode(State m, RegistryFriendlyByteBuf b) {
            b.writeVarInt(m.state);
            b.writeFloat(m.progress);
            b.writeInt(m.townCx);
            b.writeInt(m.townCz);
            b.writeVarInt(m.townRadius);
            b.writeVarInt(m.chunkRadius);
            b.writeVarInt(m.timeoutSeconds);
        }

        static State decode(RegistryFriendlyByteBuf b) {
            return new State(b.readVarInt(), b.readFloat(), b.readInt(), b.readInt(), b.readVarInt(), b.readVarInt(), b.readVarInt());
        }

        static void handle(State m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> com.nirncraft.quest.client.IntroClient.onState(m));
        }
    }

    /**
     * Starts ({@code start}) or ends the cutscene on this client. {@code burn} holds x, y, z triples: the points of the
     * town that burn (chosen on the server from the real ground so every client agrees on the ground height).
     */
    public record Cutscene(boolean start, int ticks, double ex, double ey, double ez, float yaw, float pitch, double tx, double ty, double tz,
                           int townRadius, long seed, double[] burn) {
        static void encode(Cutscene m, RegistryFriendlyByteBuf b) {
            b.writeBoolean(m.start);
            b.writeVarInt(m.ticks);
            b.writeDouble(m.ex);
            b.writeDouble(m.ey);
            b.writeDouble(m.ez);
            b.writeFloat(m.yaw);
            b.writeFloat(m.pitch);
            b.writeDouble(m.tx);
            b.writeDouble(m.ty);
            b.writeDouble(m.tz);
            b.writeVarInt(m.townRadius);
            b.writeLong(m.seed);
            b.writeVarInt(m.burn.length);
            for (double v : m.burn) b.writeDouble(v);
        }

        static Cutscene decode(RegistryFriendlyByteBuf b) {
            boolean start = b.readBoolean();
            int ticks = b.readVarInt();
            double ex = b.readDouble(), ey = b.readDouble(), ez = b.readDouble();
            float yaw = b.readFloat(), pitch = b.readFloat();
            double tx = b.readDouble(), ty = b.readDouble(), tz = b.readDouble();
            int r = b.readVarInt();
            long seed = b.readLong();
            int n = Math.min(b.readVarInt(), 192);
            double[] burn = new double[n];
            for (int i = 0; i < n; i++) burn[i] = b.readDouble();
            return new Cutscene(start, ticks, ex, ey, ez, yaw, pitch, tx, ty, tz, r, seed, burn);
        }

        static void handle(Cutscene m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> com.nirncraft.quest.client.IntroClient.onCutscene(m));
        }
    }

    /** Every player still in the opening: clients do not draw them (nor their name tags) until they leave the cottage. */
    public record Hidden(List<UUID> ids) {
        static void encode(Hidden m, RegistryFriendlyByteBuf b) {
            b.writeVarInt(m.ids.size());
            for (UUID u : m.ids) b.writeUUID(u);
        }

        static Hidden decode(RegistryFriendlyByteBuf b) {
            int n = Math.min(b.readVarInt(), 4096);
            List<UUID> ids = new ArrayList<>(n);
            for (int i = 0; i < n; i++) ids.add(b.readUUID());
            return new Hidden(ids);
        }

        static void handle(Hidden m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> com.nirncraft.quest.client.IntroClient.onHidden(m.ids));
        }
    }

    /** C2S: the loading screen is satisfied (the chunks it asked for are in, or its own time ran out). */
    public record Loaded() {
        static void encode(Loaded m, RegistryFriendlyByteBuf b) {
        }

        static Loaded decode(RegistryFriendlyByteBuf b) {
            return new Loaded();
        }

        static void handle(Loaded m, IPayloadContext ctx) {
            ServerPlayer p = SkyNetwork.sender(ctx);
            if (p != null) IntroFlow.onClientLoaded(p);
        }
    }
}
