package com.skycraft.inventory;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The reusable bulk store behind every Skycraft overflow: a list of {@code (item + NBT, int count)} entries with no
 * slots and no stack-size limit (docs/INVENTORY.md). One class, three users: the player's {@link Pack} (a view over the
 * list in the player's SkyData), the overflow of every block container the Skyrim container screen opens
 * ({@link ContainerOverflow}, a block-entity capability) and the corpses.
 *
 * <p><b>Identity.</b> An entry is the item saved with count 1 (id, tag, capability data); adding an equal stack merges
 * into it. Counts are ints, never bytes, so a 1000-arrow stack stays 1000.</p>
 *
 * <p><b>Order.</b> Entries keep insertion order (new kinds at the end, a drained entry is removed in place) which makes
 * indexes stable between a server's request handling and the client mirror of one sync, and every iteration
 * deterministic (save files and selftests compare byte for byte).</p>
 *
 * <p><b>Persistence.</b> {@link #save()} writes {@code {v: VERSION, e: [{s: <stack, count 1>, n: <int>}]}}; {@link
 * #load} accepts the same, repairs duplicates and bad rows and never fails on old data. The player's pack keeps the
 * older tag layout {@code {e: [...]}} (a view without {@code v}); the entry rows are identical.</p>
 *
 * <p><b>Caps.</b> Engineering limits, not gameplay: at most {@code maxEntries} distinct entries and {@code maxPerEntry}
 * items each; {@link #add} returns what could not be taken, callers decide what to do with the rest (the pack drops it
 * at the player's feet, a corpse spills it to the ground).</p>
 */
public final class BulkStore {
    public static final int VERSION = 1;
    static final String VERSION_KEY = "v";
    public static final String LIST = "e";
    static final String STACK = "s";
    static final String COUNT = "n";

    private final ListTag list;
    private final int maxEntries;
    private final int maxPerEntry;
    private final Runnable onChange;
    private int modCount;

    /** An empty store of its own. */
    public BulkStore(int maxEntries, int maxPerEntry, Runnable onChange) {
        this(new ListTag(), maxEntries, maxPerEntry, onChange);
    }

    /** A store over an existing list (the pack's list inside the player's SkyData module). */
    public BulkStore(ListTag backing, int maxEntries, int maxPerEntry, Runnable onChange) {
        this.list = backing;
        this.maxEntries = maxEntries;
        this.maxPerEntry = maxPerEntry;
        this.onChange = onChange;
    }

    // ------------------------------------------------------------------ identity

    /** The identity of an item in a store: the saved stack with count 1 (id, tag, capability data). */
    public static CompoundTag key(ItemStack stack) {
        ItemStack one = stack.copy();
        one.setCount(1);
        return one.save(new CompoundTag());
    }

    private static ItemStack stackOf(CompoundTag entry) {
        ItemStack s = ItemStack.of(entry.getCompound(STACK));
        if (!s.isEmpty()) s.setCount(Math.max(1, entry.getInt(COUNT)));
        return s;
    }

    /** Splits {@code all} (count may exceed the max stack size) into max-size stacks; {@code all} is consumed. */
    public static List<ItemStack> splitToStacks(ItemStack all) {
        List<ItemStack> out = new ArrayList<>();
        while (!all.isEmpty()) out.add(all.split(Math.max(1, all.getMaxStackSize())));
        return out;
    }

    private void changed() {
        modCount++;
        if (onChange != null) onChange.run();
    }

    /** Incremented by every mutation; lets a sync loop tell "same as last time" without comparing lists. */
    public int modCount() {
        return modCount;
    }

    public int maxEntries() {
        return maxEntries;
    }

    public int maxPerEntry() {
        return maxPerEntry;
    }

    // ------------------------------------------------------------------ reading

    /** Number of entries (distinct item+NBT). */
    public int size() {
        return list.size();
    }

    public boolean isEmpty() {
        return list.isEmpty();
    }

    /** Copy of entry {@code index} with its full (int) count; EMPTY when out of range or the item no longer exists. */
    public ItemStack get(int index) {
        if (index < 0 || index >= list.size()) return ItemStack.EMPTY;
        return stackOf(list.getCompound(index));
    }

    /** Raw count of entry {@code index} (0 when out of range). */
    public int countAt(int index) {
        return index < 0 || index >= list.size() ? 0 : list.getCompound(index).getInt(COUNT);
    }

    /** The key (stack saved with count 1) of entry {@code index}; do not modify. */
    public CompoundTag keyAt(int index) {
        return index < 0 || index >= list.size() ? new CompoundTag() : list.getCompound(index).getCompound(STACK);
    }

    /** All entries as copies, index-aligned (unreadable entries are EMPTY). */
    public List<ItemStack> entries() {
        List<ItemStack> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) out.add(stackOf(list.getCompound(i)));
        return out;
    }

    /** Calls {@code each} with every readable entry (a fresh copy) in order. */
    public void forEach(Consumer<ItemStack> each) {
        for (int i = 0; i < list.size(); i++) {
            ItemStack s = stackOf(list.getCompound(i));
            if (!s.isEmpty()) each.accept(s);
        }
    }

    /** Total number of items. */
    public long totalItems() {
        long n = 0;
        for (int i = 0; i < list.size(); i++) n += list.getCompound(i).getInt(COUNT);
        return n;
    }

    /** Weight of everything in the store (not cached: owners cache it as they see fit). */
    public float weight() {
        float sum = 0f;
        for (int i = 0; i < list.size(); i++) {
            ItemStack s = stackOf(list.getCompound(i));
            if (!s.isEmpty()) sum += ItemWeights.getStack(s);
        }
        return sum;
    }

    /** Items matching {@code filter}. */
    public int count(Predicate<ItemStack> filter) {
        long n = 0;
        for (int i = 0; i < list.size(); i++) {
            ItemStack s = stackOf(list.getCompound(i));
            if (!s.isEmpty() && filter.test(s)) n += s.getCount();
        }
        return (int) Math.min(Integer.MAX_VALUE, n);
    }

    /** Items of one type (cheap: compares the item id only). */
    public int count(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) return 0;
        String want = id.toString();
        long n = 0;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            if (want.equals(e.getCompound(STACK).getString("id"))) n += e.getInt(COUNT);
        }
        return (int) Math.min(Integer.MAX_VALUE, n);
    }

    public boolean has(Predicate<ItemStack> filter) {
        return find(filter) >= 0;
    }

    /** Index of the first entry matching, or -1. */
    public int find(Predicate<ItemStack> filter) {
        for (int i = 0; i < list.size(); i++) {
            ItemStack s = stackOf(list.getCompound(i));
            if (!s.isEmpty() && filter.test(s)) return i;
        }
        return -1;
    }

    /** Index of the entry equal to {@code template} (item and NBT), or -1. */
    public int indexOf(ItemStack template) {
        return template.isEmpty() ? -1 : indexOfKey(key(template));
    }

    private int indexOfKey(CompoundTag key) {
        for (int i = 0; i < list.size(); i++) {
            if (list.getCompound(i).getCompound(STACK).equals(key)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ writing

    /**
     * Adds {@code stack}, merging with an equal entry. Shrinks {@code stack} by what was taken and returns that number;
     * only the caps can make it less than the whole stack.
     */
    public int add(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        int want = stack.getCount();
        CompoundTag key = key(stack);
        int idx = indexOfKey(key);
        int taken;
        if (idx >= 0) {
            CompoundTag e = list.getCompound(idx);
            taken = Math.min(maxPerEntry - e.getInt(COUNT), want);
            if (taken <= 0) return 0;
            e.putInt(COUNT, e.getInt(COUNT) + taken);
        } else {
            if (list.size() >= maxEntries) return 0;
            taken = Math.min(maxPerEntry, want);
            CompoundTag e = new CompoundTag();
            e.put(STACK, key);
            e.putInt(COUNT, taken);
            list.add(e);
        }
        stack.shrink(taken);
        changed();
        return taken;
    }

    /** Removes up to {@code max} items of entry {@code index} and returns them as one stack (may exceed the max stack size). */
    public ItemStack take(int index, int max) {
        if (index < 0 || index >= list.size() || max <= 0) return ItemStack.EMPTY;
        CompoundTag e = list.getCompound(index);
        ItemStack s = stackOf(e);
        if (s.isEmpty()) return ItemStack.EMPTY;
        int n = e.getInt(COUNT);
        int t = Math.min(n, max);
        s.setCount(t);
        if (t >= n) list.remove(index);
        else e.putInt(COUNT, n - t);
        changed();
        return s;
    }

    /** Removes up to {@code max} items matching {@code filter} (first entries first); returns how many were removed. */
    public int remove(Predicate<ItemStack> filter, int max) {
        int left = max;
        for (int i = 0; i < list.size() && left > 0; ) {
            ItemStack s = stackOf(list.getCompound(i));
            if (s.isEmpty() || !filter.test(s)) {
                i++;
                continue;
            }
            int before = list.size();
            left -= take(i, left).getCount();
            if (list.size() == before) i++; // entry kept (partly taken): done with it
        }
        return max - left;
    }

    /** Removes every item equal to {@code template} (item and NBT) and returns the stack (count = all of them). */
    public ItemStack takeAll(ItemStack template) {
        int idx = indexOf(template);
        return idx < 0 ? ItemStack.EMPTY : take(idx, Integer.MAX_VALUE);
    }

    /** Removes up to {@code max} items equal to {@code template}; returns them as one stack. */
    public ItemStack take(ItemStack template, int max) {
        int idx = indexOf(template);
        return idx < 0 ? ItemStack.EMPTY : take(idx, max);
    }

    public void clear() {
        if (list.isEmpty()) return;
        list.clear();
        changed();
    }

    /** Drops unreadable rows (an item that no longer exists is kept as is: it may come back with its mod). */
    public int removeBadRows() {
        int removed = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            CompoundTag e = list.getCompound(i);
            if (e.getInt(COUNT) <= 0 || !e.contains(STACK, Tag.TAG_COMPOUND) || e.getCompound(STACK).getString("id").isEmpty()) {
                list.remove(i);
                removed++;
            }
        }
        if (removed > 0) changed();
        return removed;
    }

    /**
     * Moves everything of {@code other} into this store (what the caps refuse stays in {@code other}); returns true if
     * anything moved. Used when two halves of a double chest become one.
     */
    public boolean absorb(BulkStore other) {
        if (other == this || other.isEmpty()) return false;
        boolean moved = false;
        for (int i = 0; i < other.size(); ) {
            ItemStack all = other.get(i);
            if (all.isEmpty()) {
                i++;
                continue;
            }
            int total = all.getCount();
            int taken = add(all); // shrinks `all` by what fitted
            if (taken >= total) {
                other.take(i, total); // whole entry moved: the next one slides into index i
                moved = true;
            } else {
                if (taken > 0) {
                    other.take(i, taken);
                    moved = true;
                }
                i++;
            }
        }
        return moved;
    }

    // ------------------------------------------------------------------ persistence

    /** {@code {v, e}}: the whole store as NBT (rows are copies). */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(VERSION_KEY, VERSION);
        ListTag out = new ListTag();
        for (int i = 0; i < list.size(); i++) out.add(list.getCompound(i).copy());
        tag.put(LIST, out);
        return tag;
    }

    /**
     * Replaces the content with {@code tag} (the layout of {@link #save()}, or a bare {@code {e: [...]}}). Rows with no
     * item or a non-positive count are dropped, duplicates merged, the caps enforced; nothing is thrown for old data.
     * Does not call the change listener (loading is not a change).
     */
    public void load(CompoundTag tag) {
        list.clear();
        if (tag == null || !tag.contains(LIST, Tag.TAG_LIST)) return;
        ListTag in = tag.getList(LIST, Tag.TAG_COMPOUND);
        for (int i = 0; i < in.size(); i++) {
            CompoundTag row = in.getCompound(i);
            CompoundTag stack = row.getCompound(STACK);
            int n = row.getInt(COUNT);
            if (stack.getString("id").isEmpty() || n <= 0) continue;
            // the key is normalised through an ItemStack so that a hand-edited or older row still merges
            CompoundTag key = stack;
            ItemStack probe = ItemStack.of(stack);
            if (!probe.isEmpty()) key = key(probe);
            int idx = indexOfKey(key);
            if (idx >= 0) {
                CompoundTag e = list.getCompound(idx);
                e.putInt(COUNT, (int) Math.min(maxPerEntry, (long) e.getInt(COUNT) + n));
            } else if (list.size() < maxEntries) {
                CompoundTag e = new CompoundTag();
                e.put(STACK, key.copy());
                e.putInt(COUNT, Math.min(maxPerEntry, n));
                list.add(e);
            }
        }
    }

    /** A new store of its own loaded from {@code tag}. */
    public static BulkStore loaded(CompoundTag tag, int maxEntries, int maxPerEntry, Runnable onChange) {
        BulkStore s = new BulkStore(maxEntries, maxPerEntry, onChange);
        s.load(tag);
        return s;
    }

    /** Content equality of two stores (same entries, same counts, same order). */
    public boolean sameAs(BulkStore other) {
        return list.equals(other.list);
    }
}
