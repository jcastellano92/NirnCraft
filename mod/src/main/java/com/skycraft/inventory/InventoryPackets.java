package com.skycraft.inventory;

import com.skycraft.inventory.client.ClientInventoryHandlers;
import com.skycraft.network.SkyNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import com.skycraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Network messages of the inventory module (registered in this fixed order by {@link InventoryModule#registerPackets()}). */
public final class InventoryPackets {
    private InventoryPackets() {}

    static void register() {
        SkyNetwork.register(SyncWeights.class, PacketFlow.CLIENTBOUND, SyncWeights::encode, SyncWeights::decode, SyncWeights::handle);
        SkyNetwork.register(CarryState.class, PacketFlow.CLIENTBOUND, CarryState::encode, CarryState::decode, CarryState::handle);
        SkyNetwork.register(InvAction.class, PacketFlow.SERVERBOUND, InvAction::encode, InvAction::decode, InvAction::handle);
        SkyNetwork.register(UseHeld.class, PacketFlow.CLIENTBOUND, UseHeld::encode, UseHeld::decode, UseHeld::handle);
        SkyNetwork.register(PackSync.class, PacketFlow.CLIENTBOUND, PackSync::encode, PackSync::decode, PackSync::handle);
        SkyNetwork.register(ContainerOverflowSync.class, PacketFlow.CLIENTBOUND, ContainerOverflowSync::encode,
                ContainerOverflowSync::decode, ContainerOverflowSync::handle);
        SkyNetwork.register(MachineAction.class, PacketFlow.SERVERBOUND, MachineAction::encode, MachineAction::decode,
                MachineAction::handle);
    }

    // ------------------------------------------------------------------ S2C

    /** The server's item weight table (item ids and tag ids, tags in priority order). */
    public record SyncWeights(Map<Identifier, Float> items, Map<Identifier, Float> tags) {
        public static SyncWeights current() {
            return new SyncWeights(ItemWeights.itemTable(), ItemWeights.tagTable());
        }

        static void encode(SyncWeights m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.items.size());
            m.items.forEach((k, v) -> {
                buf.writeIdentifier(k);
                buf.writeFloat(v);
            });
            buf.writeVarInt(m.tags.size());
            m.tags.forEach((k, v) -> {
                buf.writeIdentifier(k);
                buf.writeFloat(v);
            });
        }

        static SyncWeights decode(RegistryFriendlyByteBuf buf) {
            int n = buf.readVarInt();
            Map<Identifier, Float> items = new HashMap<>();
            for (int i = 0; i < n; i++) items.put(buf.readIdentifier(), buf.readFloat());
            int t = buf.readVarInt();
            Map<Identifier, Float> tags = new LinkedHashMap<>();
            for (int i = 0; i < t; i++) tags.put(buf.readIdentifier(), buf.readFloat());
            return new SyncWeights(items, tags);
        }

        static void handle(SyncWeights m, IPayloadContext ctx) {
            // common code; on an integrated server the table is shared and identical anyway
            ItemWeights.setTable(m.items, m.tags);
        }
    }

    /** The player's carry weight as the server sees it. */
    public record CarryState(float current, float capacity, boolean enabled, boolean over) {
        static void encode(CarryState m, RegistryFriendlyByteBuf buf) {
            buf.writeFloat(m.current);
            buf.writeFloat(m.capacity);
            buf.writeByte((m.enabled ? 1 : 0) | (m.over ? 2 : 0));
        }

        static CarryState decode(RegistryFriendlyByteBuf buf) {
            float cur = buf.readFloat();
            float cap = buf.readFloat();
            byte flags = buf.readByte();
            return new CarryState(cur, cap, (flags & 1) != 0, (flags & 2) != 0);
        }

        static void handle(CarryState m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> ClientInventoryHandlers.carryState(m.current, m.capacity, m.enabled, m.over));
        }
    }

    /**
     * After a "Read" action moved a book into the main hand, asks the client to use it the vanilla way (so written books,
     * spell tomes, skill books and modded books open their screens / run their logic normally).
     */
    public record UseHeld(Identifier item) {
        static void encode(UseHeld m, RegistryFriendlyByteBuf buf) {
            buf.writeIdentifier(m.item);
        }

        static UseHeld decode(RegistryFriendlyByteBuf buf) {
            return new UseHeld(buf.readIdentifier());
        }

        static void handle(UseHeld m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> ClientInventoryHandlers.useHeld(m.item));
        }
    }

    /**
     * The owner's pack ({@link Pack}), in packets of at most 100 entries: {@code start} is the index of the first row,
     * {@code total} the number of entries; a packet with start 0 begins a new copy. Counts are VarInts (never bytes).
     */
    public record PackSync(int start, int total, List<Row> rows) {
        public record Row(CompoundTag key, int count) {}

        static void encode(PackSync m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.start);
            buf.writeVarInt(m.total);
            buf.writeVarInt(m.rows.size());
            for (Row r : m.rows) {
                buf.writeNbt(r.key);
                buf.writeVarInt(r.count);
            }
        }

        static PackSync decode(RegistryFriendlyByteBuf buf) {
            int start = buf.readVarInt();
            int total = buf.readVarInt();
            int n = Math.min(buf.readVarInt(), Pack.MAX_ENTRIES);
            List<Row> rows = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                CompoundTag key = buf.readNbt();
                rows.add(new Row(key == null ? new CompoundTag() : key, buf.readVarInt()));
            }
            return new PackSync(start, total, rows);
        }

        static void handle(PackSync m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> ClientInventoryHandlers.packSync(m));
        }
    }

    /**
     * The overflow of the container behind the menu {@code containerId} ({@link ContainerOverflow}), in packets of at
     * most 100 rows like {@link PackSync}; a packet with start 0 begins a new copy, total 0 clears it. Rows reuse
     * {@link PackSync.Row}. The client lists them after the container's slot rows; a row's menu slot id for take
     * requests is {@link ContainerOverflow#SLOT_BASE} + its index.
     */
    public record ContainerOverflowSync(int containerId, int start, int total, List<PackSync.Row> rows) {
        static void encode(ContainerOverflowSync m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.containerId);
            buf.writeVarInt(m.start);
            buf.writeVarInt(m.total);
            buf.writeVarInt(m.rows.size());
            for (PackSync.Row r : m.rows) {
                buf.writeNbt(r.key());
                buf.writeVarInt(r.count());
            }
        }

        static ContainerOverflowSync decode(RegistryFriendlyByteBuf buf) {
            int id = buf.readVarInt();
            int start = buf.readVarInt();
            int total = buf.readVarInt();
            int n = Math.min(buf.readVarInt(), ContainerSync.ROWS);
            List<PackSync.Row> rows = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                CompoundTag key = buf.readNbt();
                rows.add(new PackSync.Row(key == null ? new CompoundTag() : key, buf.readVarInt()));
            }
            return new ContainerOverflowSync(id, start, total, rows);
        }

        static void handle(ContainerOverflowSync m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> ClientInventoryHandlers.containerOverflow(m));
        }
    }

    // ------------------------------------------------------------------ C2S

    /**
     * An inventory screen action on one slot ({@link net.minecraft.world.entity.player.Inventory#getItem(int)} index:
     * 0-35 main, 36-39 armor, 40 offhand; {@link Pack#VIRTUAL_BASE} + n names pack entry n). {@code item} must match
     * the slot's current item or the request is ignored.
     */
    public record InvAction(int action, int slot, Identifier item) {
        /** Toggle: equip to the right hand / armor slot, or unequip if already equipped. */
        public static final int EQUIP = 0;
        /** Toggle: equip to / unequip from the left hand (offhand). */
        public static final int EQUIP_LEFT = 1;
        /** Eat / drink (food, potions, milk...). */
        public static final int USE = 2;
        public static final int DROP_ONE = 3;
        public static final int DROP_ALL = 4;
        public static final int FAVORITE = 5;
        /** Books, spell tomes: read directly (or open on client). */
        public static final int READ = 6;
        /** Apply a poison / harming potion to the equipped weapon. */
        public static final int APPLY_POISON = 7;

        static void encode(InvAction m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.action);
            buf.writeVarInt(m.slot);
            buf.writeIdentifier(m.item);
        }

        static InvAction decode(RegistryFriendlyByteBuf buf) {
            return new InvAction(buf.readVarInt(), buf.readVarInt(), buf.readIdentifier());
        }

        static void handle(InvAction m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) InventoryActions.handle(player, m.action, m.slot, m.item);
        }
    }

    /**
     * A move on an open workstation menu (furnace, brewing stand, anvil, crafting grid...) from the Skyrim machine
     * screen ({@code inventory.client.SkyrimMachineScreen}); see {@link MachineActions}. {@code a} is a menu slot id
     * (or {@link Pack#VIRTUAL_BASE} + n for pack entry n), {@code b} a target menu slot or -1.
     */
    public record MachineAction(int containerId, int kind, int a, int b) {
        /** Take machine slot {@code a} (a result or an input going back) into the bags, through the slot's own rules. */
        public static final int TAKE = 0;
        /** Shift-click the player stack {@code a} into the machine (the menu picks the slot). */
        public static final int FEED = 1;
        /** Put the whole player stack {@code a} into machine slot {@code b}. */
        public static final int PLACE_ALL = 2;
        /** Put one item of player stack {@code a} into machine slot {@code b}. */
        public static final int PLACE_ONE = 3;

        static void encode(MachineAction m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.containerId);
            buf.writeByte(m.kind);
            buf.writeVarInt(m.a);
            buf.writeVarInt(m.b + 1);
        }

        static MachineAction decode(RegistryFriendlyByteBuf buf) {
            return new MachineAction(buf.readVarInt(), buf.readByte(), buf.readVarInt(), buf.readVarInt() - 1);
        }

        static void handle(MachineAction m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) MachineActions.handle(player, m.containerId, m.kind, m.a, m.b);
        }
    }
}
