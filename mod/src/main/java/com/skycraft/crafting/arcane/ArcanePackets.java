package com.skycraft.crafting.arcane;

import com.skycraft.crafting.arcane.alchemy.Alchemy;
import com.skycraft.crafting.arcane.client.ArcaneClientHandlers;
import com.skycraft.crafting.arcane.enchant.Enchanting;
import com.skycraft.network.SkyNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import com.skycraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;


/** Arcane packets: the server opens station screens; the client asks for (validated) station actions. */
public final class ArcanePackets {
    private ArcanePackets() {}

    public static void register() {
        SkyNetwork.register(OpenStation.class, PacketFlow.CLIENTBOUND, OpenStation::encode, OpenStation::decode, OpenStation::handle);
        SkyNetwork.register(Disenchant.class, PacketFlow.SERVERBOUND, Disenchant::encode, Disenchant::decode, Disenchant::handle);
        SkyNetwork.register(Enchant.class, PacketFlow.SERVERBOUND, Enchant::encode, Enchant::decode, Enchant::handle);
        SkyNetwork.register(Combine.class, PacketFlow.SERVERBOUND, Combine::encode, Combine::decode, Combine::handle);
        SkyNetwork.register(Taste.class, PacketFlow.SERVERBOUND, Taste::encode, Taste::decode, Taste::handle);
    }

    // ------------------------------------------------------------------ S2C

    /** Opens the Arcane Enchanter ({@code kind} 0) or Alchemy Lab (1) screen for the station at {@code pos}. */
    public record OpenStation(int kind, BlockPos pos) {
        static void encode(OpenStation m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.kind);
            buf.writeBlockPos(m.pos);
        }

        static OpenStation decode(RegistryFriendlyByteBuf buf) {
            return new OpenStation(buf.readVarInt(), buf.readBlockPos());
        }

        static void handle(OpenStation m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> ArcaneClientHandlers.openStation(m.kind, m.pos));
        }
    }

    // ------------------------------------------------------------------ C2S

    /** Destroy the item in an inventory slot and learn its enchantments. */
    public record Disenchant(BlockPos pos, int slot) {
        static void encode(Disenchant m, RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
            buf.writeVarInt(m.slot);
        }

        static Disenchant decode(RegistryFriendlyByteBuf buf) {
            return new Disenchant(buf.readBlockPos(), buf.readVarInt());
        }

        static void handle(Disenchant m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) Enchanting.disenchant(player, m.pos, m.slot);
        }
    }

    /** Enchant the item in {@code itemSlot} with a known enchantment, powered by the soul gem in {@code gemSlot}. */
    public record Enchant(BlockPos pos, int itemSlot, String enchantment, int gemSlot) {
        static void encode(Enchant m, RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
            buf.writeVarInt(m.itemSlot);
            buf.writeUtf(m.enchantment, 256);
            buf.writeVarInt(m.gemSlot);
        }

        static Enchant decode(RegistryFriendlyByteBuf buf) {
            return new Enchant(buf.readBlockPos(), buf.readVarInt(), buf.readUtf(256), buf.readVarInt());
        }

        static void handle(Enchant m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) Enchanting.enchant(player, m.pos, m.itemSlot, m.enchantment, m.gemSlot);
        }
    }

    /** Combine the ingredients in 2-3 inventory slots into a potion or poison. */
    public record Combine(BlockPos pos, int[] slots) {
        static void encode(Combine m, RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
            buf.writeVarInt(m.slots.length);
            for (int s : m.slots) buf.writeVarInt(s);
        }

        static Combine decode(RegistryFriendlyByteBuf buf) {
            BlockPos pos = buf.readBlockPos();
            int n = Math.max(0, Math.min(8, buf.readVarInt()));
            int[] slots = new int[n];
            for (int i = 0; i < n; i++) slots[i] = buf.readVarInt();
            return new Combine(pos, slots);
        }

        static void handle(Combine m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) Alchemy.combine(player, m.pos, m.slots);
        }
    }

    /** Taste (eat) one ingredient to learn its first unknown effect(s). */
    public record Taste(BlockPos pos, int slot) {
        static void encode(Taste m, RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
            buf.writeVarInt(m.slot);
        }

        static Taste decode(RegistryFriendlyByteBuf buf) {
            return new Taste(buf.readBlockPos(), buf.readVarInt());
        }

        static void handle(Taste m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) Alchemy.taste(player, m.pos, m.slot);
        }
    }
}
