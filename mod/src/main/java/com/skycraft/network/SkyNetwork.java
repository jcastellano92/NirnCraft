package com.skycraft.network;

import com.skycraft.Skycraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import com.skycraft.platform.Sides;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Skycraft's required, versioned play payloads. Module records keep their existing validation and wire order;
 * each registration receives its own NeoForge payload type and registry-aware stream codec.
 * Handlers run on the appropriate game thread. Client handler registration lives in the client package.
 */
public final class SkyNetwork {
    private static final boolean CODEC_DIAGNOSTICS = System.getProperty("skycraft.dev") != null || Boolean.getBoolean("skycraft.autotest");
    private static final List<Registration<?>> REGISTRATIONS = new ArrayList<>();
    private static final Map<Class<?>, Registration<?>> BY_CLASS = new HashMap<>();
    private static final StringBuilder PACKET_LIST = new StringBuilder();
    private static String protocol;
    private static boolean initialized;

    private SkyNetwork() {}

    private static synchronized String protocol() {
        if (protocol != null) return protocol;
        String version = ModList.get() == null ? "?"
                : ModList.get().getModContainerById(Skycraft.MODID)
                        .map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        protocol = WireProtocol.identity(version, WireProtocol.SCHEMA, PACKET_LIST.toString(), REGISTRATIONS.size());
        return protocol;
    }

    public static <T> void register(Class<T> type, PacketFlow direction,
                                    BiConsumer<T, RegistryFriendlyByteBuf> encoder,
                                    Function<RegistryFriendlyByteBuf, T> decoder,
                                    BiConsumer<T, IPayloadContext> handler) {
        if (initialized) throw new IllegalStateException("Cannot register packet after SkyNetwork.init()");
        int id = REGISTRATIONS.size();
        Registration<T> registration = new Registration<>(id, type, direction, encoder, decoder, handler);
        if (BY_CLASS.putIfAbsent(type, registration) != null) {
            throw new IllegalStateException("Duplicate packet registration: " + type.getName());
        }
        REGISTRATIONS.add(registration);
        PACKET_LIST.append(id).append(':').append(type.getName()).append(':').append(direction).append(';');
    }

    public static synchronized void init(IEventBus modBus) {
        if (initialized) return;
        CorePackets.register();
        com.skycraft.dialogue.DialoguePackets.register();
        com.skycraft.magic.MagicModule.registerPackets();
        com.skycraft.creatures.CreaturesModule.registerPackets();
        com.skycraft.economy.EconomyModule.registerPackets();
        com.skycraft.crime.CrimeModule.registerPackets();
        com.skycraft.quest.QuestModule.registerPackets();
        com.skycraft.crafting.CraftingModule.registerPackets();
        com.skycraft.world.WorldModule.registerPackets();
        com.skycraft.roads.RoadsModule.registerPackets();
        com.skycraft.inventory.InventoryModule.registerPackets();
        com.skycraft.survival.SurvivalModule.registerPackets();
        com.skycraft.lore.LoreModule.registerPackets();
        com.skycraft.fauna.FaunaModule.registerPackets();
        com.skycraft.dungeons.DungeonsModule.registerPackets();
        com.skycraft.society.SocietyModule.registerPackets();
        com.skycraft.arsenal.ArsenalModule.registerPackets();
        com.skycraft.atmosphere.AtmosphereModule.registerPackets();
        com.skycraft.curse.CurseModule.registerPackets();
        com.skycraft.sailing.SailingModule.registerPackets();
        com.skycraft.fire.FireModule.registerPackets();
        com.skycraft.protect.ProtectModule.registerPackets();
        com.skycraft.combat.Threats.registerPackets();
        com.skycraft.aether.AetherNet.register();

        initialized = true;
        modBus.addListener(SkyNetwork::registerPayloads);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(protocol());
        for (Registration<?> registration : REGISTRATIONS) registration.register(registrar);
    }

    /** Avoids referencing any client event class from the common networking implementation. */
    public interface ClientHandlerRegistrar {
        <T extends CustomPacketPayload> void register(CustomPacketPayload.Type<T> type, IPayloadHandler<T> handler);
    }

    public static void registerClientHandlers(ClientHandlerRegistrar registrar) {
        for (Registration<?> registration : REGISTRATIONS) {
            if (registration.direction == PacketFlow.CLIENTBOUND) registration.registerClient(registrar);
        }
    }

    /** The sender is available only for server-bound play payloads. */
    public static ServerPlayer sender(IPayloadContext context) {
        return context.flow() == PacketFlow.SERVERBOUND && context.player() instanceof ServerPlayer player ? player : null;
    }

    public static void sendToPlayer(ServerPlayer player, Object msg) {
        PacketDistributor.sendToPlayer(player, payload(msg, PacketFlow.CLIENTBOUND));
    }

    public static void sendToServer(Object msg) {
        CustomPacketPayload payload = payload(msg, PacketFlow.SERVERBOUND);
        Sides.runOn(Dist.CLIENT, () -> () -> com.skycraft.network.client.NetworkClient.send(payload));
    }

    public static void sendToTracking(Entity entity, Object msg) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, payload(msg, PacketFlow.CLIENTBOUND));
    }

    public static void sendToTrackingOnly(Entity entity, Object msg) {
        PacketDistributor.sendToPlayersTrackingEntity(entity, payload(msg, PacketFlow.CLIENTBOUND));
    }

    public static void sendToTrackingChunk(ServerLevel level, ChunkPos pos, Object msg) {
        PacketDistributor.sendToPlayersTrackingChunk(level, pos, payload(msg, PacketFlow.CLIENTBOUND));
    }

    public static void sendNear(ServerLevel level, double x, double y, double z, double radius, Object msg) {
        PacketDistributor.sendToPlayersNear(level, null, x, y, z, radius, payload(msg, PacketFlow.CLIENTBOUND));
    }

    public static void sendToAll(Object msg) {
        PacketDistributor.sendToAllPlayers(payload(msg, PacketFlow.CLIENTBOUND));
    }

    /** Actual codec invocations, not send requests or negotiated-type coverage. Development only. */
    public static com.google.gson.JsonObject diagnostics() {
        var result = new com.google.gson.JsonObject();
        result.addProperty("enabled", CODEC_DIAGNOSTICS);
        result.addProperty("scope", "cumulative for this process; actual StreamCodec invocations");
        result.addProperty("registrationComplete", initialized);
        result.addProperty("registeredPayloads", REGISTRATIONS.size());
        result.addProperty("wireProtocol", initialized ? protocol() : "not initialized");
        result.addProperty("endpoint", net.neoforged.fml.loading.FMLEnvironment.getDist().name());
        long encodes = 0, decodes = 0, encodeFailures = 0, decodeFailures = 0;
        int encodedDistinct = 0, decodedDistinct = 0;
        var exercised = new com.google.gson.JsonArray();
        if (CODEC_DIAGNOSTICS) for (var registration : REGISTRATIONS) {
            long encoded = registration.encoded.sum(), decoded = registration.decoded.sum();
            long encodeFailed = registration.encodeFailed.sum(), decodeFailed = registration.decodeFailed.sum();
            encodes += encoded; decodes += decoded; encodeFailures += encodeFailed; decodeFailures += decodeFailed;
            if (encoded > 0) encodedDistinct++;
            if (decoded > 0) decodedDistinct++;
            if (encoded + decoded + encodeFailed + decodeFailed == 0) continue;
            var row = new com.google.gson.JsonObject();
            row.addProperty("type", registration.payloadType.id().toString());
            row.addProperty("messageClass", registration.messageClass.getName());
            row.addProperty("direction", registration.direction.name());
            row.addProperty("encodes", encoded); row.addProperty("decodes", decoded);
            row.addProperty("encodeFailures", encodeFailed); row.addProperty("decodeFailures", decodeFailed);
            exercised.add(row);
        }
        result.addProperty("encodes", encodes); result.addProperty("decodes", decodes);
        result.addProperty("encodedDistinct", encodedDistinct); result.addProperty("decodedDistinct", decodedDistinct);
        result.addProperty("encodeFailures", encodeFailures); result.addProperty("decodeFailures", decodeFailures);
        result.add("exercisedPayloads", exercised);
        return result;
    }

    private static CustomPacketPayload payload(Object msg, PacketFlow direction) {
        Objects.requireNonNull(msg, "Cannot send null packet");
        Registration<?> registration = BY_CLASS.get(msg.getClass());
        if (registration == null) throw new IllegalArgumentException("Unregistered packet: " + msg.getClass().getName());
        if (registration.direction != direction) throw new IllegalArgumentException("Wrong direction for " + msg.getClass().getName());
        return registration.wrap(msg);
    }

    private record Payload<T>(Registration<T> registration, T message) implements CustomPacketPayload {
        @Override
        public Type<Payload<T>> type() {
            return registration.payloadType;
        }
    }

    private static final class Registration<T> {
        private final Class<T> messageClass;
        private final PacketFlow direction;
        private final CustomPacketPayload.Type<Payload<T>> payloadType;
        private final StreamCodec<RegistryFriendlyByteBuf, Payload<T>> codec;
        private final BiConsumer<T, IPayloadContext> handler;
        private final java.util.concurrent.atomic.LongAdder encoded = CODEC_DIAGNOSTICS ? new java.util.concurrent.atomic.LongAdder() : null;
        private final java.util.concurrent.atomic.LongAdder decoded = CODEC_DIAGNOSTICS ? new java.util.concurrent.atomic.LongAdder() : null;
        private final java.util.concurrent.atomic.LongAdder encodeFailed = CODEC_DIAGNOSTICS ? new java.util.concurrent.atomic.LongAdder() : null;
        private final java.util.concurrent.atomic.LongAdder decodeFailed = CODEC_DIAGNOSTICS ? new java.util.concurrent.atomic.LongAdder() : null;

        private Registration(int id, Class<T> messageClass, PacketFlow direction,
                             BiConsumer<T, RegistryFriendlyByteBuf> encoder,
                             Function<RegistryFriendlyByteBuf, T> decoder, BiConsumer<T, IPayloadContext> handler) {
            this.messageClass = messageClass;
            this.direction = direction;
            this.handler = handler;
            this.payloadType = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Skycraft.MODID, "packet_" + id));
            this.codec = StreamCodec.of((buf, payload) -> {
                try {
                    encoder.accept(payload.message(), buf);
                    if (CODEC_DIAGNOSTICS) encoded.increment();
                } catch (RuntimeException | Error failure) {
                    if (CODEC_DIAGNOSTICS) encodeFailed.increment();
                    throw failure;
                }
            }, buf -> {
                try {
                    var payload = new Payload<>(this, decoder.apply(buf));
                    if (CODEC_DIAGNOSTICS) decoded.increment();
                    return payload;
                } catch (RuntimeException | Error failure) {
                    if (CODEC_DIAGNOSTICS) decodeFailed.increment();
                    throw failure;
                }
            });
        }

        private void register(PayloadRegistrar registrar) {
            if (direction == PacketFlow.CLIENTBOUND) registrar.playToClient(payloadType, codec);
            else registrar.playToServer(payloadType, codec, this::handle);
        }

        private void registerClient(ClientHandlerRegistrar registrar) {
            registrar.register(payloadType, this::handle);
        }

        private void handle(Payload<T> payload, IPayloadContext context) {
            if (context.flow() != direction) throw new IllegalStateException("Unexpected direction for " + payloadType.id());
            handler.accept(payload.message(), context);
        }

        private Payload<T> wrap(Object message) {
            return new Payload<>(this, messageClass.cast(message));
        }
    }
}
