package com.skycraft.mounts;

import com.skycraft.mounts.client.MountDownedClient;
import com.skycraft.network.SkyNetwork;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;
import com.skycraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;


/** Packets of the downed state ({@link MountCompanion}): which mount entities lie down, for the renderer and the compass. */
public final class CompanionPackets {
    private CompanionPackets() {}

    public static void register() {
        SkyNetwork.register(Downed.class, PacketFlow.CLIENTBOUND, Downed::encode, Downed::decode, Downed::handle);
    }

    /**
     * S2C: a mount went down or got up. {@code mine}: it is the receiving player's own mount (it also gets a compass
     * marker at {@code x, y, z}).
     */
    public record Downed(int entityId, boolean down, boolean mine, double x, double y, double z) {
        static void encode(Downed m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeBoolean(m.down);
            buf.writeBoolean(m.mine);
            buf.writeDouble(m.x);
            buf.writeDouble(m.y);
            buf.writeDouble(m.z);
        }

        static Downed decode(RegistryFriendlyByteBuf buf) {
            return new Downed(buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readDouble(), buf.readDouble(), buf.readDouble());
        }

        static void handle(Downed m, IPayloadContext ctx) {
            ctx.enqueueWork(() -> Sides.runOn(Dist.CLIENT, () -> () -> MountDownedClient.apply(m)));
        }
    }
}
