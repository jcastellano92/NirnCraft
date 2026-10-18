package com.skycraft.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * compile stub: the signatures of mod/src/main/java/com/skycraft/network/SkyNetwork.java (26.3 conversion) that the
 * aether glue uses. Not moved into the mod; the real class replaces it.
 */
public final class SkyNetwork {
    private SkyNetwork() {}

    public static <T> void register(Class<T> type, PacketFlow direction, BiConsumer<T, RegistryFriendlyByteBuf> encoder,
                                    Function<RegistryFriendlyByteBuf, T> decoder, BiConsumer<T, IPayloadContext> handler) {
        throw new UnsupportedOperationException("stub");
    }

    public static ServerPlayer sender(IPayloadContext context) {
        throw new UnsupportedOperationException("stub");
    }

    public static void sendToPlayer(ServerPlayer player, Object msg) {
        throw new UnsupportedOperationException("stub");
    }

    public static void sendToServer(Object msg) {
        throw new UnsupportedOperationException("stub");
    }
}
