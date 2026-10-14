package com.skycraft.mounts;

import com.skycraft.mounts.client.MountDownedClient;
import com.skycraft.network.SkyNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Packets of the downed state ({@link MountCompanion}): which mount entities lie down, for the renderer and the compass. */
public final class CompanionPackets {
    private CompanionPackets() {}

    public static void register() {
        SkyNetwork.register(Downed.class, NetworkDirection.PLAY_TO_CLIENT, Downed::encode, Downed::decode, Downed::handle);
    }

    /**
     * S2C: a mount went down or got up. {@code mine}: it is the receiving player's own mount (it also gets a compass
     * marker at {@code x, y, z}).
     */
    public record Downed(int entityId, boolean down, boolean mine, double x, double y, double z) {
        static void encode(Downed m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeBoolean(m.down);
            buf.writeBoolean(m.mine);
            buf.writeDouble(m.x);
            buf.writeDouble(m.y);
            buf.writeDouble(m.z);
        }

        static Downed decode(FriendlyByteBuf buf) {
            return new Downed(buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readDouble(), buf.readDouble(), buf.readDouble());
        }

        static void handle(Downed m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MountDownedClient.apply(m)));
            ctx.get().setPacketHandled(true);
        }
    }
}
