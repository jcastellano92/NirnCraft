package com.skycraft.mounts;

import com.skycraft.mounts.client.MountCardOverlay;
import com.skycraft.mounts.client.MountHudOverlay;
import com.skycraft.network.SkyNetwork;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import com.skycraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;


/** Packets of the mount system: the rider's gallop key, and the ridden mount's health for the HUD. */
public final class MountPackets {
    private MountPackets() {}

    public static void register() {
        SkyNetwork.register(Gallop.class, PacketFlow.SERVERBOUND, Gallop::encode, Gallop::decode, Gallop::handle);
        SkyNetwork.register(MountHud.class, PacketFlow.CLIENTBOUND, MountHud::encode, MountHud::decode, MountHud::handle);
        CompanionPackets.register();
        SkyNetwork.register(Inspect.class, PacketFlow.SERVERBOUND, Inspect::encode, Inspect::decode, Inspect::handle);
        SkyNetwork.register(Card.class, PacketFlow.CLIENTBOUND, Card::encode, Card::decode, Card::handle);
        SkyNetwork.register(Sheet.class, PacketFlow.CLIENTBOUND, Sheet::encode, Sheet::decode, Sheet::handle);
        SkyNetwork.register(Spotted.class, PacketFlow.CLIENTBOUND, Spotted::encode, Spotted::decode, Spotted::handle);
        StablePackets.register();
    }

    /** One wild mount a rider with Wild Eye can make out: where, what, and how good (-1 when Horse Sense does not tell). */
    public record Spot(double x, double y, double z, String kindId, int grade) {}

    /** S2C: the wild mounts near a rider with Wild Eye, for the compass (replaces the previous list). */
    public record Spotted(java.util.List<Spot> spots) {
        static void encode(Spotted m, RegistryFriendlyByteBuf buf) {
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

        static Spotted decode(RegistryFriendlyByteBuf buf) {
            int n = Math.min(12, buf.readVarInt());
            java.util.List<Spot> out = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(new Spot(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readUtf(40), buf.readByte()));
            return new Spotted(out);
        }

        static void handle(Spotted m, IPayloadContext ctx) {
            ctx.enqueueWork(() -> Sides.runOn(Dist.CLIENT, () -> () -> MountCardOverlay.spotted(m)));
        }
    }

    /** C2S: "what is that animal?" (the entity under the crosshair, at most a few times a second). */
    public record Inspect(int entityId) {
        static void encode(Inspect m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
        }

        static Inspect decode(RegistryFriendlyByteBuf buf) {
            return new Inspect(buf.readVarInt());
        }

        static void handle(Inspect m, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = SkyNetwork.sender(ctx);
                if (player != null) MountInspect.handleRequest(player, m.entityId);
            });
        }
    }

    /** S2C: the answer to {@link Inspect}: what this player may know of that animal. */
    public record Card(int entityId, MountView view) {
        static void encode(Card m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            m.view.write(buf);
        }

        static Card decode(RegistryFriendlyByteBuf buf) {
            return new Card(buf.readVarInt(), MountView.read(buf));
        }

        static void handle(Card m, IPayloadContext ctx) {
            ctx.enqueueWork(() -> Sides.runOn(Dist.CLIENT, () -> () -> MountCardOverlay.update(m)));
        }
    }

    /** S2C: the full sheet of your own mount, sent ahead of its menu ({@code open}: the screen starts on it). */
    public record Sheet(MountView view, boolean open) {
        static void encode(Sheet m, RegistryFriendlyByteBuf buf) {
            m.view.write(buf);
            buf.writeBoolean(m.open);
        }

        static Sheet decode(RegistryFriendlyByteBuf buf) {
            return new Sheet(MountView.read(buf), buf.readBoolean());
        }

        static void handle(Sheet m, IPayloadContext ctx) {
            ctx.enqueueWork(() -> Sides.runOn(Dist.CLIENT, () -> () -> MountCardOverlay.sheet(m)));
        }
    }

    /** C2S: the rider pressed or released sprint (gallop). */
    public record Gallop(boolean on) {
        static void encode(Gallop m, RegistryFriendlyByteBuf buf) {
            buf.writeBoolean(m.on);
        }

        static Gallop decode(RegistryFriendlyByteBuf buf) {
            return new Gallop(buf.readBoolean());
        }

        static void handle(Gallop m, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                ServerPlayer player = SkyNetwork.sender(ctx);
                if (player != null) MountStamina.setGallop(player, m.on && player.isPassenger());
            });
        }
    }

    /**
     * S2C: the ridden mount's health (its stamina is the player's own bar while riding, {@link MountStamina}, and needs
     * no packet of its own).
     */
    public record MountHud(float health, float maxHealth) {
        static void encode(MountHud m, RegistryFriendlyByteBuf buf) {
            buf.writeFloat(m.health);
            buf.writeFloat(m.maxHealth);
        }

        static MountHud decode(RegistryFriendlyByteBuf buf) {
            return new MountHud(buf.readFloat(), buf.readFloat());
        }

        static void handle(MountHud m, IPayloadContext ctx) {
            ctx.enqueueWork(() -> Sides.runOn(Dist.CLIENT, () -> () -> MountHudOverlay.update(m)));
        }
    }
}
