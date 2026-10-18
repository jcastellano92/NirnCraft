package com.skycraft.network.client;

import com.skycraft.Skycraft;
import com.skycraft.network.SkyNetwork;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

/** Physical-client registration and sending, kept out of dedicated-server class loading. */
@EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class NetworkClient {
    private NetworkClient() {}

    @SubscribeEvent
    public static void register(RegisterClientPayloadHandlersEvent event) {
        com.skycraft.inventory.ItemData.clientRegistrySupplier(() -> {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            return minecraft.isSameThread() && minecraft.level != null ? minecraft.level.registryAccess() : null;
        });
        SkyNetwork.registerClientHandlers(new SkyNetwork.ClientHandlerRegistrar() {
            @Override
            public <T extends CustomPacketPayload> void register(CustomPacketPayload.Type<T> type, IPayloadHandler<T> handler) {
                event.register(type, handler);
            }
        });
    }

    public static void send(CustomPacketPayload payload) {
        ClientPacketDistributor.sendToServer(payload);
    }
}
