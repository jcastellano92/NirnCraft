package com.skycraft.network;

import com.skycraft.Skycraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The single Skycraft network channel. Every feature module registers its packets through
 * {@link #register(Class, NetworkDirection, BiConsumer, Function, BiConsumer)} from its {@code registerPackets()}
 * method, which {@link #init()} calls in a fixed order (ids must match on both sides).
 *
 * <p>Handlers registered here run on the main thread (server thread for C2S, client thread for S2C).
 * S2C handlers must delegate client-only code through {@code DistExecutor}.</p>
 */
public final class SkyNetwork {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Skycraft.MODID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static int nextId = 0;

    private SkyNetwork() {}

    public static <T> void register(Class<T> type, NetworkDirection direction, BiConsumer<T, FriendlyByteBuf> encoder,
                                    Function<FriendlyByteBuf, T> decoder, BiConsumer<T, Supplier<NetworkEvent.Context>> handler) {
        CHANNEL.messageBuilder(type, nextId++, direction)
                .encoder(encoder)
                .decoder(decoder)
                .consumerMainThread(handler)
                .add();
    }

    public static void init() {
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
    }

    public static void sendToPlayer(ServerPlayer player, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    public static void sendToServer(Object msg) {
        CHANNEL.sendToServer(msg);
    }

    public static void sendToTracking(Entity entity, Object msg) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> entity), msg);
    }

    public static void sendToAll(Object msg) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), msg);
    }
}
