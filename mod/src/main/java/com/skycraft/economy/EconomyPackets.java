package com.skycraft.economy;

import com.skycraft.economy.client.EconomyClient;
import com.skycraft.network.SkyNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Network messages of the economy module. Registration order is fixed (see {@link #register()}). */
public final class EconomyPackets {
    private static final int MAX_ENTRIES = 512;

    private EconomyPackets() {}

    public static void register() {
        SkyNetwork.register(SyncValues.class, NetworkDirection.PLAY_TO_CLIENT, SyncValues::encode, SyncValues::decode, SyncValues::handle);
        SkyNetwork.register(BarterState.class, NetworkDirection.PLAY_TO_CLIENT, BarterState::encode, BarterState::decode, BarterState::handle);
        SkyNetwork.register(BarterAction.class, NetworkDirection.PLAY_TO_SERVER, BarterAction::encode, BarterAction::decode, BarterAction::handle);
    }

    // ------------------------------------------------------------------ S2C

    /** The server's item value table (item ids and tag ids) so clients can show values. */
    public record SyncValues(Map<ResourceLocation, Integer> items, Map<ResourceLocation, Integer> tags) {
        public static SyncValues current() {
            return new SyncValues(ItemValues.itemTable(), ItemValues.tagTable());
        }

        static void encode(SyncValues m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.items.size());
            m.items.forEach((k, v) -> {
                buf.writeResourceLocation(k);
                buf.writeVarInt(v);
            });
            buf.writeVarInt(m.tags.size());
            m.tags.forEach((k, v) -> {
                buf.writeResourceLocation(k);
                buf.writeVarInt(v);
            });
        }

        static SyncValues decode(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            Map<ResourceLocation, Integer> items = new HashMap<>();
            for (int i = 0; i < n; i++) items.put(buf.readResourceLocation(), buf.readVarInt());
            int t = buf.readVarInt();
            Map<ResourceLocation, Integer> tags = new LinkedHashMap<>();
            for (int i = 0; i < t; i++) tags.put(buf.readResourceLocation(), buf.readVarInt());
            return new SyncValues(items, tags);
        }

        static void handle(SyncValues m, Supplier<NetworkEvent.Context> ctx) {
            // safe on both sides (common code); on an integrated server the table is shared and identical anyway
            ItemValues.setTable(m.items, m.tags);
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * One row of the barter menu.
     *
     * @param index    stock index (merchant side), inventory slot or {@code Pack.VIRTUAL_BASE + n} (player side), or
     *                 buy-back list index
     * @param price    price per item in gold, computed by the server
     * @param category {@link ItemCategory} ordinal
     * @param status   {@link Barter#STATUS_OK}, {@link Barter#STATUS_NOT_DEALT} or {@link Barter#STATUS_STOLEN}
     * @param equip    0 not equipped, 1 right hand, 2 left hand, 3 worn (see InventoryActions.EQ_*)
     */
    public record Entry(int index, ItemStack stack, int price, byte category, byte status, byte equip) {
        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(index);
            // the count is a VarInt of its own: writeItem's count is one byte and rows can carry hundreds (the pack)
            ItemStack one = stack.copy();
            one.setCount(1);
            buf.writeItem(one);
            buf.writeVarInt(stack.getCount());
            buf.writeVarInt(price);
            buf.writeByte(category);
            buf.writeByte(status);
            buf.writeByte(equip);
        }

        static Entry read(FriendlyByteBuf buf) {
            int index = buf.readVarInt();
            ItemStack stack = buf.readItem();
            int count = buf.readVarInt();
            if (!stack.isEmpty()) stack.setCount(Math.max(1, count));
            return new Entry(index, stack, buf.readVarInt(), buf.readByte(), buf.readByte(), buf.readByte());
        }
    }

    /** Full barter state: opens the barter screen ({@code open}) or refreshes it after a trade. */
    public record BarterState(int entityId, Component merchantName, boolean open, long playerGold, int merchantGold,
                              Component message, List<Entry> merchant, List<Entry> player, List<Entry> buyback) {
        static void encode(BarterState m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeComponent(m.merchantName);
            buf.writeBoolean(m.open);
            buf.writeVarLong(m.playerGold);
            buf.writeVarInt(m.merchantGold);
            buf.writeComponent(m.message);
            writeEntries(buf, m.merchant);
            writeEntries(buf, m.player);
            writeEntries(buf, m.buyback);
        }

        private static void writeEntries(FriendlyByteBuf buf, List<Entry> entries) {
            int n = Math.min(MAX_ENTRIES, entries.size());
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) entries.get(i).write(buf);
        }

        private static List<Entry> readEntries(FriendlyByteBuf buf) {
            int n = Math.min(MAX_ENTRIES, buf.readVarInt());
            List<Entry> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(Entry.read(buf));
            return out;
        }

        static BarterState decode(FriendlyByteBuf buf) {
            int id = buf.readVarInt();
            Component name = buf.readComponent();
            boolean open = buf.readBoolean();
            long gold = buf.readVarLong();
            int merchantGold = buf.readVarInt();
            Component msg = buf.readComponent();
            List<Entry> merchant = readEntries(buf);
            List<Entry> player = readEntries(buf);
            List<Entry> buyback = readEntries(buf);
            return new BarterState(id, name, open, gold, merchantGold, msg, merchant, player, buyback);
        }

        static void handle(BarterState m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> EconomyClient.handleBarterState(m));
            ctx.get().setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ C2S

    /**
     * Buy ({@link Barter#OP_BUY}) one item / the whole stack ({@code all}) of stock entry {@code index}, sell
     * ({@link Barter#OP_SELL}) from inventory slot / pack entry {@code index}, or buy back
     * ({@link Barter#OP_BUYBACK}) list entry {@code index}. {@code item} is the id the client saw there, so a stale
     * click never trades the wrong thing. {@code confirm} is the second click that sells an equipped item.
     */
    public record BarterAction(int entityId, int op, int index, boolean all, ResourceLocation item, boolean confirm) {
        static void encode(BarterAction m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeVarInt(m.op);
            buf.writeVarInt(m.index);
            buf.writeBoolean(m.all);
            buf.writeResourceLocation(m.item);
            buf.writeBoolean(m.confirm);
        }

        static BarterAction decode(FriendlyByteBuf buf) {
            return new BarterAction(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readResourceLocation(),
                    buf.readBoolean());
        }

        static void handle(BarterAction m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) Barter.handleAction(player, m.entityId, m.op, m.index, m.all, m.item, m.confirm);
            ctx.get().setPacketHandled(true);
        }
    }
}
