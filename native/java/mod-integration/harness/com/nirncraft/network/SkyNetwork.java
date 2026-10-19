package com.nirncraft.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/** harness stand-in for the mod's SkyNetwork: what the client sends goes to `toServer`, played back by the harness. */
public final class SkyNetwork {
    private SkyNetwork() {}

    public static volatile Consumer<Object> toServer = m -> {};

    public static <T> void register(Class<T> type, PacketFlow direction, BiConsumer<T, RegistryFriendlyByteBuf> encoder,
                                    Function<RegistryFriendlyByteBuf, T> decoder, BiConsumer<T, IPayloadContext> handler) {}

    public static ServerPlayer sender(IPayloadContext context) {
        return null;
    }

    public static void sendToPlayer(ServerPlayer player, Object msg) {}

    public static void sendToServer(Object msg) {
        toServer.accept(msg);
    }
}
