package com.skycraft.mounts;

import com.skycraft.mounts.client.StableClient;
import com.skycraft.network.SkyNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** The stable master's screen: the state the server sends ({@link State}) and what the player asks of it ({@link Action}). */
public final class StablePackets {
    public static final int MAX_OFFERS = 12;
    public static final int MAX_TRAIN = 8;

    private StablePackets() {}

    static void register() {
        SkyNetwork.register(State.class, NetworkDirection.PLAY_TO_CLIENT, State::encode, State::decode, State::handle);
        SkyNetwork.register(Action.class, NetworkDirection.PLAY_TO_SERVER, Action::encode, Action::decode, Action::handle);
    }

    /** One animal for sale: its view, its price (before the trade-in) and whether your tack fits it. */
    public record Offer(String id, MountView view, int price, boolean saddleFits, boolean bardingFits) {
        void write(FriendlyByteBuf b) {
            b.writeUtf(id, 40);
            view.write(b);
            b.writeVarInt(price);
            b.writeBoolean(saddleFits);
            b.writeBoolean(bardingFits);
        }

        static Offer read(FriendlyByteBuf b) {
            return new Offer(b.readUtf(40), MountView.read(b), b.readVarInt(), b.readBoolean(), b.readBoolean());
        }
    }

    /** One line of the Train tab. {@code state}: 0 can be trained, 1 at its cap, 2 weekly limit used, 3 maxed. */
    public record TrainRow(byte stat, int fee, byte state, byte points, byte cap) {
        void write(FriendlyByteBuf b) {
            b.writeByte(stat);
            b.writeVarInt(fee);
            b.writeByte(state);
            b.writeByte(points);
            b.writeByte(cap);
        }

        static TrainRow read(FriendlyByteBuf b) {
            return new TrainRow(b.readByte(), b.readVarInt(), b.readByte(), b.readByte(), b.readByte());
        }
    }

    /**
     * The whole screen: the player's own mount (wherever it is parked) next to the stock, what it is worth to the
     * stable, the training it can have. Sent to open the screen and again after every transaction.
     */
    public record State(int npcId, Component stableName, boolean open, long gold, Component message, @Nullable MountView own,
                        int tradeIn, boolean canSell, int sessionsLeft, List<Offer> offers, List<TrainRow> train) {
        static void encode(State m, FriendlyByteBuf b) {
            b.writeVarInt(m.npcId);
            b.writeComponent(m.stableName);
            b.writeBoolean(m.open);
            b.writeVarLong(m.gold);
            b.writeComponent(m.message);
            b.writeBoolean(m.own != null);
            if (m.own != null) m.own.write(b);
            b.writeVarInt(m.tradeIn);
            b.writeBoolean(m.canSell);
            b.writeVarInt(m.sessionsLeft);
            int n = Math.min(MAX_OFFERS, m.offers.size());
            b.writeVarInt(n);
            for (int i = 0; i < n; i++) m.offers.get(i).write(b);
            int t = Math.min(MAX_TRAIN, m.train.size());
            b.writeVarInt(t);
            for (int i = 0; i < t; i++) m.train.get(i).write(b);
        }

        static State decode(FriendlyByteBuf b) {
            int npc = b.readVarInt();
            Component name = b.readComponent();
            boolean open = b.readBoolean();
            long gold = b.readVarLong();
            Component msg = b.readComponent();
            MountView own = b.readBoolean() ? MountView.read(b) : null;
            int tradeIn = b.readVarInt();
            boolean canSell = b.readBoolean();
            int sessions = b.readVarInt();
            int n = Math.min(MAX_OFFERS, b.readVarInt());
            List<Offer> offers = new ArrayList<>(n);
            for (int i = 0; i < n; i++) offers.add(Offer.read(b));
            int t = Math.min(MAX_TRAIN, b.readVarInt());
            List<TrainRow> train = new ArrayList<>(t);
            for (int i = 0; i < t; i++) train.add(TrainRow.read(b));
            return new State(npc, name, open, gold, msg, own, tradeIn, canSell, sessions, offers, train);
        }

        static void handle(State m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> StableClient.handle(m)));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final byte BUY = 0, SELL = 1, TRAIN = 2, REFRESH = 3;

    /**
     * C2S: {@code op} BUY (arg = offer id, expected = the price after the trade-in), SELL (expected = what you were
     * shown), TRAIN (arg = stat 0..5, expected = the fee). The server recomputes everything and refuses when the
     * client's figure is stale.
     */
    public record Action(int npcId, byte op, String arg, int expected) {
        static void encode(Action m, FriendlyByteBuf b) {
            b.writeVarInt(m.npcId);
            b.writeByte(m.op);
            b.writeUtf(m.arg, 40);
            b.writeInt(m.expected);
        }

        static Action decode(FriendlyByteBuf b) {
            return new Action(b.readVarInt(), b.readByte(), b.readUtf(40), b.readInt());
        }

        static void handle(Action m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            if (p != null) StableUi.handle(p, m);
            ctx.get().setPacketHandled(true);
        }
    }
}
