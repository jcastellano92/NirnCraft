package com.skycraft.crafting;

import com.skycraft.crafting.menu.StationMenu;
import com.skycraft.network.SkyNetwork;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;


/** Smithing packets (all client -> server; the server validates the open station menu, perks and materials). */
public final class CraftingPackets {
    private CraftingPackets() {}

    public static void register() {
        SkyNetwork.register(Craft.class, PacketFlow.SERVERBOUND, Craft::encode, Craft::decode, Craft::handle);
        SkyNetwork.register(Temper.class, PacketFlow.SERVERBOUND, Temper::encode, Temper::decode, Temper::handle);
    }

    /** Make {@code times} of a station recipe. */
    public record Craft(String recipeId, int times) {
        static void encode(Craft m, RegistryFriendlyByteBuf buf) {
            buf.writeUtf(m.recipeId, 128);
            buf.writeVarInt(m.times);
        }

        static Craft decode(RegistryFriendlyByteBuf buf) {
            return new Craft(buf.readUtf(128), buf.readVarInt());
        }

        static void handle(Craft m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null && player.containerMenu instanceof StationMenu menu && menu.stillValid(player)) {
                StationCrafting.craft(player, menu.type, m.recipeId, m.times);
            }
        }
    }

    /** Improve the item in player inventory slot {@code slot} at the open grindstone / armor workbench. */
    public record Temper(int slot) {
        static void encode(Temper m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.slot);
        }

        static Temper decode(RegistryFriendlyByteBuf buf) {
            return new Temper(buf.readVarInt());
        }

        static void handle(Temper m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null && player.containerMenu instanceof StationMenu menu && menu.stillValid(player) && menu.type.tempering()) {
                Tempering.improve(player, menu.type, m.slot);
                menu.broadcastChanges();
            }
        }
    }
}
