package com.nirncraft.society;

import com.nirncraft.network.SkyNetwork;
import com.nirncraft.society.client.SocietyClientPackets;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import com.nirncraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Network messages of the society module (registered in this fixed order). */
public final class SocietyPackets {
    private SocietyPackets() {}

    public static void register() {
        SkyNetwork.register(Bark.class, PacketFlow.CLIENTBOUND, Bark::encode, Bark::decode, Bark::handle);
        SkyNetwork.register(CosmeticSync.class, PacketFlow.CLIENTBOUND, CosmeticSync::encode, CosmeticSync::decode, CosmeticSync::handle);
        SkyNetwork.register(ChooseCosmetic.class, PacketFlow.SERVERBOUND, ChooseCosmetic::encode, ChooseCosmetic::decode, ChooseCosmetic::handle);
    }

    /** S2C: show {@code text} above entity {@code entityId} for {@code ticks}. */
    public record Bark(int entityId, Component text, int ticks) {
        static void encode(Bark m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, m.text);
            buf.writeVarInt(m.ticks);
        }

        static Bark decode(RegistryFriendlyByteBuf buf) {
            return new Bark(buf.readVarInt(), net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf), buf.readVarInt());
        }

        static void handle(Bark m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> SocietyClientPackets.bark(m.entityId, m.text, m.ticks));
        }
    }

    /** S2C: the faction cosmetic a player wears ("" = none). */
    public record CosmeticSync(UUID player, String cosmetic) {
        static void encode(CosmeticSync m, RegistryFriendlyByteBuf buf) {
            buf.writeUUID(m.player);
            buf.writeUtf(m.cosmetic, 64);
        }

        static CosmeticSync decode(RegistryFriendlyByteBuf buf) {
            return new CosmeticSync(buf.readUUID(), buf.readUtf(64));
        }

        static void handle(CosmeticSync m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> SocietyClientPackets.cosmetic(m.player, m.cosmetic));
        }
    }

    /** C2S: the player picked a cosmetic in the reputation screen ("" = none). */
    public record ChooseCosmetic(String cosmetic) {
        static void encode(ChooseCosmetic m, RegistryFriendlyByteBuf buf) {
            buf.writeUtf(m.cosmetic, 64);
        }

        static ChooseCosmetic decode(RegistryFriendlyByteBuf buf) {
            return new ChooseCosmetic(buf.readUtf(64));
        }

        static void handle(ChooseCosmetic m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) Cosmetics.choose(player, m.cosmetic);
        }
    }
}
