package com.skycraft.mounts;

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
