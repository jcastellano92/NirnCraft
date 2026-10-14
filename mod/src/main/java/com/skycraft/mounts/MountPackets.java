package com.skycraft.mounts;

import com.skycraft.mounts.client.MountCardOverlay;
import com.skycraft.mounts.client.MountHudOverlay;
import com.skycraft.network.SkyNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Packets of the mount system: the rider's gallop key, and the ridden mount's health for the HUD. */
public final class MountPackets {
    private MountPackets() {}

    public static void register() {
        SkyNetwork.register(Gallop.class, NetworkDirection.PLAY_TO_SERVER, Gallop::encode, Gallop::decode, Gallop::handle);
        SkyNetwork.register(MountHud.class, NetworkDirection.PLAY_TO_CLIENT, MountHud::encode, MountHud::decode, MountHud::handle);
        CompanionPackets.register();
        SkyNetwork.register(Inspect.class, NetworkDirection.PLAY_TO_SERVER, Inspect::encode, Inspect::decode, Inspect::handle);
        SkyNetwork.register(Card.class, NetworkDirection.PLAY_TO_CLIENT, Card::encode, Card::decode, Card::handle);
        SkyNetwork.register(Sheet.class, NetworkDirection.PLAY_TO_CLIENT, Sheet::encode, Sheet::decode, Sheet::handle);
        SkyNetwork.register(Spotted.class, NetworkDirection.PLAY_TO_CLIENT, Spotted::encode, Spotted::decode, Spotted::handle);
        StablePackets.register();
    }

    /** One wild mount a rider with Wild Eye can make out: where, what, and how good (-1 when Horse Sense does not tell). */
    public record Spot(double x, double y, double z, String kindId, int grade) {}

    /** S2C: the wild mounts near a rider with Wild Eye, for the compass (replaces the previous list). */
    public record Spotted(java.util.List<Spot> spots) {
        static void encode(Spotted m, FriendlyByteBuf buf) {
            int n = Math.min(12, m.spots.size());
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                Spot s = m.spots.get(i);
                buf.writeDouble(s.x);
                buf.writeDouble(s.y);
                buf.writeDouble(s.z);
                buf.writeUtf(s.kindId, 40);
                buf.writeByte(s.grade);
            }
        }

        static Spotted decode(FriendlyByteBuf buf) {
            int n = Math.min(12, buf.readVarInt());
            java.util.List<Spot> out = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(new Spot(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readUtf(40), buf.readByte()));
            return new Spotted(out);
        }

        static void handle(Spotted m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MountCardOverlay.spotted(m)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** C2S: "what is that animal?" (the entity under the crosshair, at most a few times a second). */
    public record Inspect(int entityId) {
        static void encode(Inspect m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
        }

        static Inspect decode(FriendlyByteBuf buf) {
            return new Inspect(buf.readVarInt());
        }

        static void handle(Inspect m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player != null) MountInspect.handleRequest(player, m.entityId);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** S2C: the answer to {@link Inspect}: what this player may know of that animal. */
    public record Card(int entityId, MountView view) {
        static void encode(Card m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            m.view.write(buf);
        }

        static Card decode(FriendlyByteBuf buf) {
            return new Card(buf.readVarInt(), MountView.read(buf));
        }

        static void handle(Card m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MountCardOverlay.update(m)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** S2C: the full sheet of your own mount, sent ahead of its menu ({@code open}: the screen starts on it). */
    public record Sheet(MountView view, boolean open) {
        static void encode(Sheet m, FriendlyByteBuf buf) {
            m.view.write(buf);
            buf.writeBoolean(m.open);
        }

        static Sheet decode(FriendlyByteBuf buf) {
            return new Sheet(MountView.read(buf), buf.readBoolean());
        }

        static void handle(Sheet m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MountCardOverlay.sheet(m)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** C2S: the rider pressed or released sprint (gallop). */
    public record Gallop(boolean on) {
        static void encode(Gallop m, FriendlyByteBuf buf) {
            buf.writeBoolean(m.on);
        }

        static Gallop decode(FriendlyByteBuf buf) {
            return new Gallop(buf.readBoolean());
        }

        static void handle(Gallop m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player != null) MountStamina.setGallop(player, m.on && player.isPassenger());
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * S2C: the ridden mount's health (its stamina is the player's own bar while riding, {@link MountStamina}, and needs
     * no packet of its own).
     */
    public record MountHud(float health, float maxHealth) {
        static void encode(MountHud m, FriendlyByteBuf buf) {
            buf.writeFloat(m.health);
            buf.writeFloat(m.maxHealth);
        }

        static MountHud decode(FriendlyByteBuf buf) {
            return new MountHud(buf.readFloat(), buf.readFloat());
        }

        static void handle(MountHud m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MountHudOverlay.update(m)));
            ctx.get().setPacketHandled(true);
        }
    }
}
