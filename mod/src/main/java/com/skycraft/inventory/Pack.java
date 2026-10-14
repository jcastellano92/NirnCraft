package com.skycraft.inventory;

import com.skycraft.Skycraft;
import com.skycraft.core.Currency;
import com.skycraft.core.SkyData;
import com.skycraft.network.SkyNetwork;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The pack: slot-independent carrying capacity (docs/INVENTORY.md).
 *
 * <p><b>Design.</b> Vanilla's {@code Inventory} has 36 storage slots and cannot be enlarged without a mixin or an access
 * transformer (both banned), and a stack's count is a byte on disk. Weight is the only limit on what a Skyrim character
 * carries, so everything the 36 slots cannot take overflows into this pack: a list of {@code (item+NBT, int count)}
 * entries stored in the player's {@link SkyData} module {@code "pack"}. It is saved with the player, copied on respawn
 * (SkyData clone), counted by {@link CarryWeight#current}, mirrored to the owning client by {@link
 * InventoryPackets.PackSync} (the Skyrim inventory, container and barter screens list pack entries in the same rows as
 * slot items) and drained back into free slots on the server tick.</p>
 *
 * <p>The entry list itself (merge by item+NBT, int counts, caps, add/take/iterate, NBT rows) is {@link BulkStore}, the
 * same class that backs the overflow of containers ({@link ContainerOverflow}) and corpses; the pack is a view of it
 * over the list in the player's SkyData, plus what only a player has: weight, sync, refill, virtual slots. Gameplay
 * code reads and spends what a player carries through {@link Inv}, never through slots alone.</p>
 *
 * <p>Entries are identified by their index (a "virtual slot", {@link #VIRTUAL_BASE} + index, in client requests). To
 * act on one (equip, use, read, craft with it) the server <em>materialises</em> it: pulls one stack into a free slot,
 * pushing the least useful stack back into the pack when all slots are full ({@link #materialize}). That keeps every
 * existing slot-based rule (bound curses, equip, use, enchant...) working unchanged.</p>
 *
 * <p>Limits: weight is the only gameplay limit; the pack never refuses an item for weight (an over-encumbered
 * character just cannot run). The engineering cap is {@link #MAX_ENTRIES} distinct item+NBT entries and
 * {@link #MAX_PER_ENTRY} items per entry; beyond it {@link #add} returns what it could not take and callers drop the
 * rest at the player's feet.</p>
 *
 * <p>Server only mutates; the client copy is replaced wholesale by sync packets. The accessors work on both sides.</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Pack {
    public static final String MODULE = "pack";
    private static final String LIST = "e";
    private static final String STACK = "s";
    private static final String COUNT = "n";

    /** Engineering cap: distinct item+NBT entries (not items). 2000 entries is ~110 KB of NBT. */
    public static final int MAX_ENTRIES = 2000;
    /** Engineering cap: items per entry (counts are ints, never bytes). */
    public static final int MAX_PER_ENTRY = 1_000_000;
    /** Client requests name pack entries as {@code VIRTUAL_BASE + index} where a slot index is expected. */
    public static final int VIRTUAL_BASE = 1000;

    private static final int SYNC_ROWS = 100;
    private static final int SYNC_INTERVAL = 5;
    private static final int REFILL_INTERVAL = 10;

    private static final Set<UUID> DIRTY = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Integer> LAST_SYNC = new ConcurrentHashMap<>();
    /** Client side: rows of a multi-packet sync being assembled. */
    private static ListTag staging;

    private Pack() {}

    // ------------------------------------------------------------------ storage

    private static CompoundTag module(Player player) {
        return SkyData.get(player).module(MODULE);
    }

    private static ListTag list(Player player) {
        CompoundTag m = module(player);
        if (!m.contains(LIST, Tag.TAG_LIST)) m.put(LIST, new ListTag());
        return m.getList(LIST, Tag.TAG_COMPOUND);
    }

    /**
     * The pack's entry list as a {@link BulkStore} view (the NBT in the player's SkyData module is the storage; the
     * view is cheap and only valid for the call). Mutations go through {@link #changed}.
     */
    private static BulkStore store(Player player) {
        return new BulkStore(list(player), MAX_ENTRIES, MAX_PER_ENTRY, () -> changed(player));
    }

    private static ItemStack stackOf(CompoundTag entry) {
        ItemStack s = ItemStack.of(entry.getCompound(STACK));
        if (!s.isEmpty()) s.setCount(Math.max(1, entry.getInt(COUNT)));
        return s;
    }

    private static void changed(Player player) {
        module(player).remove("w");
        if (!player.level().isClientSide) {
            DIRTY.add(player.getUUID());
            CarryWeight.invalidate(player);
        }
    }

    // ------------------------------------------------------------------ reading (both sides)

    /** Number of entries (distinct item+NBT). */
    public static int size(Player player) {
        return list(player).size();
    }

    /** Copy of entry {@code index} with its full (int) count; EMPTY when out of range or the item no longer exists. */
    public static ItemStack get(Player player, int index) {
        return store(player).get(index);
    }

    /** The stack at a real inventory slot or, for a virtual slot id, a copy of the pack entry. */
    public static ItemStack stackAt(Player player, int slot) {
        return slot >= VIRTUAL_BASE ? get(player, slot - VIRTUAL_BASE) : player.getInventory().getItem(slot);
    }

    /** All entries as copies, index-aligned with the virtual slots (unreadable entries are EMPTY). */
    public static List<ItemStack> entries(Player player) {
        return store(player).entries();
    }

    /** Total number of items in the pack. */
    public static long totalItems(Player player) {
        return store(player).totalItems();
    }

    /** Weight of the whole pack (cached until the pack or the weight table changes). */
    public static float weight(Player player) {
        ListTag l = list(player);
        if (l.isEmpty()) return 0f;
        CompoundTag m = module(player);
        long version = ItemWeights.version();
        if (m.contains("w", Tag.TAG_FLOAT) && m.getLong("wv") == version) return m.getFloat("w");
        float sum = store(player).weight();
        m.putFloat("w", sum);
        m.putLong("wv", version);
        return sum;
    }

    /** Items in the pack matching {@code filter}. */
    public static int count(Player player, Predicate<ItemStack> filter) {
        return store(player).count(filter);
    }

    /** Items of one type in the pack (cheap: compares the item id only). */
    public static int count(Player player, Item item) {
        return store(player).count(item);
    }

    public static boolean has(Player player, Predicate<ItemStack> filter) {
        return store(player).has(filter);
    }

    /** Index of the first entry matching, or -1. */
    public static int find(Player player, Predicate<ItemStack> filter) {
        return store(player).find(filter);
    }

    // ------------------------------------------------------------------ writing (server)

    /**
     * Adds {@code stack} to the pack, merging with an equal entry. Shrinks {@code stack} by what was taken and returns
     * that number; only the engineering caps can make it less than the whole stack.
     */
    public static int add(Player player, ItemStack stack) {
        if (stack.isEmpty()) return 0;
        // conjured weapons only exist where they tick (slots): they never rest in the pack
        if (com.skycraft.magic.bound.BoundWeapons.isBound(stack)) return 0;
        return store(player).add(stack);
    }

    /** Removes up to {@code max} items of entry {@code index} and returns them as one stack (may exceed the max stack size). */
    public static ItemStack take(Player player, int index, int max) {
        return store(player).take(index, max);
    }

    /** Removes up to {@code max} items matching {@code filter} (first entries first); returns how many were removed. */
    public static int remove(Player player, Predicate<ItemStack> filter, int max) {
        return store(player).remove(filter, max);
    }

    /** Removes every item equal to {@code template} (item and NBT) and returns the stack (count = all of them). */
    public static ItemStack takeAll(Player player, ItemStack template) {
        return store(player).takeAll(template);
    }

    /** Index of the entry equal to {@code template} (item and NBT), or -1. */
    public static int indexOf(Player player, ItemStack template) {
        return store(player).indexOf(template);
    }

    /** Sets or clears the favorite mark on the entry equal to {@code template} (merging with an equal entry). */
    static void setFavorite(Player player, ItemStack template, boolean fav) {
        ItemStack all = takeAll(player, template);
        if (all.isEmpty()) return;
        InventoryActions.setFavorite(all, fav);
        add(player, all);
    }

    /** Empties the pack (tests, jail confiscation of everything). */
    public static void clear(Player player) {
        list(player).clear();
        changed(player);
    }

    /** Coins that ended up in the pack (taken from a container with slots full) become gold; returns the value. */
    static long extractCoins(Player player) {
        ListTag l = list(player);
        if (l.isEmpty()) return 0;
        String septim = String.valueOf(BuiltInRegistries.ITEM.getKey(com.skycraft.registry.ModItems.SEPTIM.get()));
        String purse = String.valueOf(BuiltInRegistries.ITEM.getKey(com.skycraft.registry.ModItems.COIN_PURSE.get()));
        long total = 0;
        for (int i = l.size() - 1; i >= 0; i--) {
            String id = l.getCompound(i).getCompound(STACK).getString("id");
            if (!id.equals(septim) && !id.equals(purse)) continue; // cheap pre-check: this runs every 10 ticks
            ItemStack s = stackOf(l.getCompound(i));
            long v = s.isEmpty() ? 0 : Currency.valueOf(s);
            if (v > 0) {
                total += v;
                l.remove(i);
            }
        }
        if (total > 0) changed(player);
        return total;
    }

    // ------------------------------------------------------------------ giving

    /**
     * Gives a stack to the player the way a pick-up does: merge into existing slot stacks, then free slots (never the
     * held slot), then the pack. Consumes {@code stack}; returns what could not be taken (empty unless the
     * engineering cap was hit).
     */
    public static ItemStack give(Player player, ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        com.skycraft.vitals.ActionHandler.addToBags(player, stack);
        return stack;
    }

    /** {@link #give} and drop anything left at the player's feet. */
    public static void giveOrDrop(Player player, ItemStack stack) {
        ItemStack rest = give(player, stack);
        if (rest.isEmpty()) return;
        if (player instanceof ServerPlayer sp) {
            com.skycraft.core.Notifier.message(sp, net.minecraft.network.chat.Component.translatable("inventory.skycraft.pack.cap"));
        }
        player.drop(rest, false);
    }

    // ------------------------------------------------------------------ virtual slots

    public static boolean isVirtual(int slot) {
        return slot >= VIRTUAL_BASE;
    }

    /** Real storage slot of a free-or-victim choice; see {@link #materialize}. */
    private static int freeStorageSlot(Inventory inv) {
        for (int i = Inventory.getSelectionSize(); i < Inventory.INVENTORY_SIZE; i++) if (inv.getItem(i).isEmpty()) return i;
        for (int i = 0; i < Inventory.getSelectionSize(); i++) if (inv.getItem(i).isEmpty()) return i;
        return -1;
    }

    /**
     * Pulls up to one stack of entry {@code index} into a real inventory slot and returns that slot, or -1 (unknown
     * entry, wrong item, or the pack cannot take the displaced stack). With all 36 slots full the stack in the last
     * unequipped storage slot (not in {@code keep}) moves into the pack instead.
     */
    static int materialize(ServerPlayer player, int index, ResourceLocation expected, int... keep) {
        Inventory inv = player.getInventory();
        ListTag l = list(player);
        if (index < 0 || index >= l.size()) return -1;
        ItemStack probe = stackOf(l.getCompound(index));
        if (probe.isEmpty()) return -1;
        if (expected != null && !BuiltInRegistries.ITEM.getKey(probe.getItem()).equals(expected)) return -1;
        int dest = freeStorageSlot(inv);
        if (dest < 0) {
            int victim = -1;
            for (int i = Inventory.INVENTORY_SIZE - 1; i >= 0 && victim < 0; i--) {
                ItemStack candidate = inv.getItem(i);
                if (i == inv.selected || contains(keep, i) || candidate.isEmpty()
                        || com.skycraft.magic.bound.BoundWeapons.isBound(candidate)) continue;
                victim = i;
            }
            if (victim < 0) return -1;
            ItemStack v = inv.getItem(victim);
            ItemStack moving = v.copy();
            add(player, moving);
            if (!moving.isEmpty()) {
                inv.setItem(victim, moving);
                return -1;
            }
            inv.setItem(victim, ItemStack.EMPTY);
            dest = victim;
        }
        ItemStack pulled = take(player, index, probe.getMaxStackSize());
        if (pulled.isEmpty()) return -1;
        inv.setItem(dest, pulled);
        return dest;
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    /**
     * Server: a slot id from a client request to a real inventory slot. Real ids pass through unchanged (the caller
     * validates them); virtual ids are materialised. -1 when the request is stale.
     */
    public static int resolveSlot(ServerPlayer player, int slot, ResourceLocation expected, int... keep) {
        if (slot < VIRTUAL_BASE) return slot;
        return materialize(player, slot - VIRTUAL_BASE, expected, keep);
    }

    /**
     * {@link #resolveSlot} for several slots of one request (alchemy picks up to three): virtual ids are pulled highest
     * index first (so the others stay valid) and no real slot of the request is displaced. Null if any is stale.
     */
    public static int[] resolveAll(ServerPlayer player, int[] slots) {
        int[] out = slots.clone();
        List<Integer> virtual = new ArrayList<>();
        for (int i = 0; i < out.length; i++) if (out[i] >= VIRTUAL_BASE) virtual.add(i);
        virtual.sort((a, b) -> Integer.compare(out[b], out[a]));
        for (int pos : virtual) {
            List<Integer> keepList = new ArrayList<>();
            for (int v : out) if (v >= 0 && v < VIRTUAL_BASE) keepList.add(v);
            int[] keep = keepList.stream().mapToInt(Integer::intValue).toArray();
            int r = materialize(player, out[pos] - VIRTUAL_BASE, null, keep);
            if (r < 0) return null;
            out[pos] = r;
        }
        return out;
    }

    // ------------------------------------------------------------------ drain back into slots

    /** Moves pack entries into free storage slots (9-35, not the hotbar). Returns true if anything moved. */
    static boolean refill(ServerPlayer player) {
        ListTag l = list(player);
        if (l.isEmpty()) return false;
        Inventory inv = player.getInventory();
        boolean moved = false;
        while (!l.isEmpty()) {
            int dest = -1;
            for (int i = Inventory.getSelectionSize(); i < Inventory.INVENTORY_SIZE && dest < 0; i++) if (inv.getItem(i).isEmpty()) dest = i;
            if (dest < 0) break; // the hotbar is the player's own arrangement: never rearranged in the background
            int pick = -1;
            for (int i = 0; i < l.size() && pick < 0; i++) if (!stackOf(l.getCompound(i)).isEmpty()) pick = i;
            if (pick < 0) break;
            ItemStack s = stackOf(l.getCompound(pick));
            ItemStack pulled = take(player, pick, s.getMaxStackSize());
            if (pulled.isEmpty()) break;
            inv.setItem(dest, pulled);
            moved = true;
        }
        return moved;
    }

    // ------------------------------------------------------------------ sync

    /** The packets that make the owner's client copy equal to this pack (chunked so every packet stays small). */
    static List<InventoryPackets.PackSync> syncPackets(Player player) {
        ListTag l = list(player);
        int total = l.size();
        List<InventoryPackets.PackSync> out = new ArrayList<>();
        if (total == 0) {
            out.add(new InventoryPackets.PackSync(0, 0, List.of()));
            return out;
        }
        for (int start = 0; start < total; start += SYNC_ROWS) {
            List<InventoryPackets.PackSync.Row> rows = new ArrayList<>();
            for (int i = start; i < Math.min(total, start + SYNC_ROWS); i++) {
                CompoundTag e = l.getCompound(i);
                rows.add(new InventoryPackets.PackSync.Row(e.getCompound(STACK).copy(), e.getInt(COUNT)));
            }
            out.add(new InventoryPackets.PackSync(start, total, rows));
        }
        return out;
    }

    /** Sends the whole pack to its owner. */
    public static void sync(ServerPlayer player) {
        DIRTY.remove(player.getUUID());
        LAST_SYNC.put(player.getUUID(), player.tickCount);
        for (InventoryPackets.PackSync p : syncPackets(player)) SkyNetwork.sendToPlayer(player, p);
    }

    /** Client: applies one packet of a sync; the module is replaced when the last row arrived. */
    public static void applyClientSync(Player player, int start, int total, List<InventoryPackets.PackSync.Row> rows) {
        if (start == 0) staging = new ListTag();
        if (staging == null) return;
        for (InventoryPackets.PackSync.Row r : rows) {
            CompoundTag e = new CompoundTag();
            e.put(STACK, r.key());
            e.putInt(COUNT, r.count());
            staging.add(e);
        }
        if (staging.size() >= total) {
            CompoundTag m = module(player);
            for (String k : new ArrayList<>(m.getAllKeys())) m.remove(k);
            m.put(LIST, staging);
            staging = null;
        }
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        if (player.tickCount % REFILL_INTERVAL == 0 && player.isAlive()) refill(player);
        if (DIRTY.contains(player.getUUID())) {
            Integer last = LAST_SYNC.get(player.getUUID());
            if (last == null || player.tickCount - last >= SYNC_INTERVAL || player.tickCount < last) sync(player);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player);
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        DIRTY.remove(event.getEntity().getUUID());
        LAST_SYNC.remove(event.getEntity().getUUID());
    }
}
