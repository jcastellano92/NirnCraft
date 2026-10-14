package com.skycraft.inventory;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The one door gameplay code uses to look at, spend and hand out what a player carries (docs/INVENTORY.md "The facade").
 *
 * <p>"What a player carries" is the 36 storage slots, the offhand and the overflow {@link Pack}: a rule that looks only
 * at {@code getInventory().items} misses pack items (the pack holds exactly what the slots could not), so every
 * gameplay decision (a quest wants five ingots, a recipe costs two, a lock needs a pick, a bow needs an arrow) goes
 * through this class and counts the pack immediately, with no waiting for the pack to refill a slot. Armor slots are
 * worn gear, not carried stuff, and are never counted or consumed here.</p>
 *
 * <p>Reads work on both sides (the client mirrors the pack); {@link #take}, {@link #give} and the rest of the mutators
 * are server only. Pack stacks are returned as copies whose count may exceed the max stack size.</p>
 *
 * <p>What stays outside on purpose: vanilla and third-party code that reads {@code Inventory} itself, the vanilla
 * crafting grid and anvil, and containers (hoppers, brewing stands, furnaces) that are not the player's. The lint
 * {@code tools/qa/data_checks.py} flags direct slot reads in gameplay packages.</p>
 */
public final class Inv {
    private Inv() {}

    /** Slots a rule may read or spend: storage and hotbar (0-35) and the offhand; never the worn armor. */
    private static int[] carriedSlots(Inventory inv) {
        int[] out = new int[Inventory.INVENTORY_SIZE + 1];
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) out[i] = i;
        out[Inventory.INVENTORY_SIZE] = Inventory.SLOT_OFFHAND;
        return out;
    }

    // ------------------------------------------------------------------ reading

    /** Items matching {@code filter}: slots, offhand and pack. */
    public static int count(Player player, Predicate<ItemStack> filter) {
        long n = 0;
        Inventory inv = player.getInventory();
        for (int slot : carriedSlots(inv)) {
            if (slot >= inv.getContainerSize()) continue;
            ItemStack s = inv.getItem(slot);
            if (!s.isEmpty() && filter.test(s)) n += s.getCount();
        }
        n += Pack.count(player, filter);
        return (int) Math.min(Integer.MAX_VALUE, n);
    }

    /** Items of one type: slots, offhand and pack. */
    public static int count(Player player, Item item) {
        long n = 0;
        Inventory inv = player.getInventory();
        for (int slot : carriedSlots(inv)) {
            if (slot >= inv.getContainerSize()) continue;
            ItemStack s = inv.getItem(slot);
            if (!s.isEmpty() && s.is(item)) n += s.getCount();
        }
        n += Pack.count(player, item);
        return (int) Math.min(Integer.MAX_VALUE, n);
    }

    public static boolean has(Player player, Predicate<ItemStack> filter) {
        Inventory inv = player.getInventory();
        for (int slot : carriedSlots(inv)) {
            if (slot >= inv.getContainerSize()) continue;
            ItemStack s = inv.getItem(slot);
            if (!s.isEmpty() && filter.test(s)) return true;
        }
        return Pack.has(player, filter);
    }

    public static boolean has(Player player, Item item) {
        return has(player, s -> s.is(item));
    }

    /** True when the player carries at least {@code n} of the item. */
    public static boolean has(Player player, Item item, int n) {
        return count(player, item) >= n;
    }

    /** True when the player carries at least {@code n} items matching {@code filter}. */
    public static boolean has(Player player, Predicate<ItemStack> filter, int n) {
        return count(player, filter) >= n;
    }

    /**
     * First matching stack: a live reference for a slot (mutating it changes the inventory), a copy for a pack entry
     * (full int count). EMPTY when there is none. Slots are searched before the pack.
     */
    public static ItemStack find(Player player, Predicate<ItemStack> filter) {
        Inventory inv = player.getInventory();
        for (int slot : carriedSlots(inv)) {
            if (slot >= inv.getContainerSize()) continue;
            ItemStack s = inv.getItem(slot);
            if (!s.isEmpty() && filter.test(s)) return s;
        }
        int idx = Pack.find(player, filter);
        return idx < 0 ? ItemStack.EMPTY : Pack.get(player, idx);
    }

    /** Calls {@code each} with every carried stack: slot stacks as live references, pack entries as copies. */
    public static void forEach(Player player, Consumer<ItemStack> each) {
        Inventory inv = player.getInventory();
        for (int slot : carriedSlots(inv)) {
            if (slot >= inv.getContainerSize()) continue;
            ItemStack s = inv.getItem(slot);
            if (!s.isEmpty()) each.accept(s);
        }
        for (ItemStack s : Pack.entries(player)) {
            if (!s.isEmpty()) each.accept(s);
        }
    }

    /** Every matching carried stack (slots are live references, pack entries copies). */
    public static List<ItemStack> all(Player player, Predicate<ItemStack> filter) {
        List<ItemStack> out = new ArrayList<>();
        forEach(player, s -> {
            if (filter.test(s)) out.add(s);
        });
        return out;
    }

    // ------------------------------------------------------------------ spending and giving (server)

    /**
     * Removes up to {@code n} items matching {@code filter}: slots first, then the pack. Returns how many were
     * removed (less than {@code n} only when the player did not carry enough).
     */
    public static int take(Player player, Predicate<ItemStack> filter, int n) {
        if (n <= 0 || player.level().isClientSide) return 0;
        int left = n;
        Inventory inv = player.getInventory();
        for (int slot : carriedSlots(inv)) {
            if (left <= 0) break;
            if (slot >= inv.getContainerSize()) continue;
            ItemStack s = inv.getItem(slot);
            if (s.isEmpty() || !filter.test(s)) continue;
            int t = Math.min(left, s.getCount());
            s.shrink(t);
            if (s.isEmpty()) inv.setItem(slot, ItemStack.EMPTY);
            left -= t;
        }
        if (left > 0) left -= Pack.remove(player, filter, left);
        if (left < n) inv.setChanged();
        return n - left;
    }

    public static int take(Player player, Item item, int n) {
        return take(player, s -> s.is(item), n);
    }

    /** Removes {@code n} of the item only if the player carries all of them; true when it did. */
    public static boolean takeExactly(Player player, Item item, int n) {
        if (count(player, item) < n) return false;
        return take(player, item, n) == n;
    }

    /**
     * Gives a stack the way a pick-up does (merge into slots, free slots, then the pack); never refuses for lack of
     * room. Consumes {@code stack}; returns what could not be taken, which is only ever the pack's engineering cap.
     */
    public static ItemStack give(Player player, ItemStack stack) {
        return Pack.give(player, stack);
    }

    /** {@link #give}, dropping the (cap) remainder at the player's feet with a message. */
    public static void giveOrDrop(Player player, ItemStack stack) {
        Pack.giveOrDrop(player, stack);
    }

    /**
     * A real inventory slot holding a stack matching {@code filter}: where one is, or pulled out of the pack into a
     * slot (the least useful stack swaps into the pack when all slots are full). -1 when the player carries none.
     * For code that has to hand an item to vanilla (use it, shoot it, put it on).
     */
    public static int slotOf(ServerPlayer player, Predicate<ItemStack> filter) {
        Inventory inv = player.getInventory();
        for (int slot : carriedSlots(inv)) {
            if (slot >= inv.getContainerSize()) continue;
            ItemStack s = inv.getItem(slot);
            if (!s.isEmpty() && filter.test(s)) return slot;
        }
        int idx = Pack.find(player, filter);
        return idx < 0 ? -1 : Pack.materialize(player, idx, null, inv.selected);
    }
}
