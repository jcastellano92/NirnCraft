package com.skycraft.mounts;

import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.inventory.BulkStore;
import com.skycraft.inventory.ContainerOverflow;
import com.skycraft.inventory.Inv;
import com.skycraft.inventory.ItemWeights;
import com.skycraft.inventory.OverflowHost;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Saddlebags: the carrying room of the active mount. Not slots but weight: the bags hold what the saddle allows
 * ({@link MountGear#bagKg}, kilograms in the unit of {@link ItemWeights}), however many different things that is.
 * The {@value #CARGO} cargo slots of the menu are only the first rows of the list: what the slots cannot hold goes to
 * an overflow {@link BulkStore} behind them ({@link OverflowHost}, the same mechanism as a chest's, docs/INVENTORY.md),
 * persisted as the player's {@code module("mount_bags")} (server data the client never reads, kept out of the data
 * sync like the ender chest's). The slot stacks stay in the mount record ({@code bags} in {@code module("mounts")}).
 * Both belong to the player's mount record, so they stay with the mount when it is called from afar or comes back
 * after falling; a smaller saddle (or none) spills what no longer fits into your bags, never into the void
 * ({@link #fit}), and a mount that is replaced or sold hands both parts over ({@link #emptyToPlayer}).
 *
 * <p>Opened by sneak-using your own mount, within reach; nobody else can open them. The screen is the Skyrim
 * container screen ({@code SkyrimContainerScreen}, the menu is a {@link SaddlebagMenu}): the first {@value #GEAR}
 * entries are the mount's saddle and barding, which you equip from "My Items" and unequip by taking them.</p>
 */
public final class Saddlebags {
    /** Menu slots 0..{@value} are the saddle and the barding. */
    public static final int GEAR = 2;
    /** Cargo stacks (six rows of nine less the gear slots): weight is the limit that matters. */
    public static final int CARGO = 54 - GEAR;
    static final String BAGS = "bags";
    /** SkyData module of the bags' overflow (a {@link BulkStore} list under {@link BulkStore#LIST}; never synced). */
    public static final String OVERFLOW_MODULE = "mount_bags";

    private Saddlebags() {}

    public static void open(ServerPlayer player, Entity mount, MountKind kind) {
        open(player, mount, kind, false);
    }

    /** Opens the mount menu; {@code sheet} makes the screen start on the stat sheet (an empty-handed sneak-use). */
    public static void open(ServerPlayer player, Entity mount, MountKind kind, boolean sheet) {
        if (!(mount instanceof Mob mob) || !mob.isAlive()) return;
        if (!Mounts.isActive(player, mount)) {
            Notifier.message(player, Component.translatable("mounts.skycraft.bags.not_active"));
            return;
        }
        Bag bag = new Bag(player, mob, kind);
        // the stat sheet reaches the screen ahead of the menu (the same connection, so in order)
        MountInspect.sendSheet(player, mob, kind, sheet);
        // the title carries the beast's bag scale (rows, its build and its owner's Pack Master): the screen sums the
        // cargo it lists against the saddle in slot 0
        float scale = kind.bagRows / 2f * MountGear.capacityMult(mob, kind);
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new SaddlebagMenu(id, inv, bag),
                Component.translatable("mounts.skycraft.bags.title", kind.displayName(), String.valueOf(scale))));
        mount.playSound(net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.8f, 1.0f);
    }

    /**
     * Spills bag contents beyond {@code capacityKg} into the player's bags (the last stacks first, a stack is split to
     * fit exactly). Called after every change of saddle and when a mount of another size is brought out.
     */
    public static void fit(ServerPlayer player, float capacityKg) {
        Bag open = openBag(player);
        if (open != null) open.persist(); // the menu's slots are the truth while it is open
        CompoundTag tag = Mounts.data(player);
        ListTag list = tag.getListOrEmpty(BAGS);
        BulkStore ov = overflowIfAny(player);
        List<CompoundTag> entries = new ArrayList<>();
        float total = ov == null ? 0f : ov.weight();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompoundOrEmpty(i);
            entries.add(t);
            total += ItemWeights.getStack(com.skycraft.inventory.ItemData.parse(t));
        }
        if (total <= capacityKg + 0.001f) return;
        entries.sort((a, b) -> Integer.compare(a.getByteOr("Slot",(byte)0) & 255, b.getByteOr("Slot",(byte)0) & 255));
        boolean spilled = false;
        // the overflow goes first (last row first): the slots are what you packed on purpose, the rows the surplus
        if (ov != null) {
            for (int i = ov.size() - 1; i >= 0 && total > capacityKg + 0.001f; i--) {
                ItemStack row = ov.get(i);
                float unit = ItemWeights.get(row);
                if (row.isEmpty() || unit <= 0f) continue; // (an unreadable row is kept as it is)
                int n = Math.min(row.getCount(), (int) Math.ceil((total - capacityKg) / unit - 1e-4));
                if (n <= 0) continue;
                ItemStack out = ov.take(i, n);
                total -= unit * out.getCount();
                Inv.giveOrDrop(player, out);
                spilled = true;
            }
        }
        for (int i = entries.size() - 1; i >= 0 && total > capacityKg + 0.001f; i--) {
            CompoundTag t = entries.get(i);
            ItemStack s = com.skycraft.inventory.ItemData.parse(t);
            float unit = ItemWeights.get(s);
            if (unit <= 0f) continue;
            int n = Math.min(s.getCount(), (int) Math.ceil((total - capacityKg) / unit - 1e-4));
            if (n <= 0) continue;
            ItemStack out = s.copy();
            out.setCount(n);
            s.shrink(n);
            total -= unit * n;
            Inv.giveOrDrop(player, out);
            spilled = true;
            if (s.isEmpty()) {
                entries.remove(i);
            } else {
                CompoundTag rest = new CompoundTag();
                com.skycraft.platform.ItemTags.saveInto(s, rest);
                rest.putByte("Slot", t.getByteOr("Slot",(byte)0));
                entries.set(i, rest);
            }
        }
        ListTag keep = new ListTag();
        keep.addAll(entries);
        tag.put(BAGS, keep);
        SkyData.get(player).markDirty();
        if (open != null) open.reload();
        if (spilled) Notifier.message(player, Component.translatable("mounts.skycraft.bags.spilled"));
    }

    /**
     * Hands everything in the bags back to the player's own bags (the floor when they are full): a mount that is
     * replaced, sold or released takes nothing with it. Never loses an item.
     */
    public static void emptyToPlayer(ServerPlayer player) {
        Bag open = openBag(player);
        if (open != null) open.persist(); // an open menu holds its slots in memory: write them first, then empty
        CompoundTag tag = Mounts.data(player);
        ListTag list = tag.getListOrEmpty(BAGS);
        BulkStore ov = overflowIfAny(player);
        boolean any = !list.isEmpty();
        if (any) tag.remove(BAGS);
        for (int i = 0; i < list.size(); i++) Inv.giveOrDrop(player, com.skycraft.inventory.ItemData.parse(list.getCompoundOrEmpty(i)));
        if (ov != null) {
            // whole rows with their int counts, last first (the indexes below stay valid); unreadable rows stay
            for (int i = ov.size() - 1; i >= 0; i--) {
                ItemStack all = ov.take(i, Integer.MAX_VALUE);
                if (all.isEmpty()) continue;
                Inv.giveOrDrop(player, all);
                any = true;
            }
        }
        if (!any) return;
        SkyData.get(player).markDirty();
        if (open != null) open.reload();
    }

    /** Weight (kg) of everything in the bags of the player's record: the slot stacks and the overflow. */
    public static float load(Player player) {
        float sum = 0f;
        ListTag list = Mounts.data(player).getListOrEmpty(BAGS);
        for (int i = 0; i < list.size(); i++) sum += ItemWeights.getStack(com.skycraft.inventory.ItemData.parse(list.getCompoundOrEmpty(i)));
        BulkStore ov = overflowIfAny(player);
        return ov == null ? sum : sum + ov.weight();
    }

    // ------------------------------------------------------------------ the overflow (persistence)

    /** The overflow store of a player's bags, created when missing: a view over the list in the player's data. */
    static BulkStore overflowOf(Player player) {
        Bag open = openBag(player);
        if (open != null) return open.overflow; // one instance per open menu: its version drives the screen sync
        com.skycraft.core.PlayerData data = SkyData.get(player);
        CompoundTag m = data.module(OVERFLOW_MODULE);
        if (!com.skycraft.platform.NbtData.hasType(m, BulkStore.LIST, Tag.TAG_LIST)) m.put(BulkStore.LIST, new ListTag());
        return new BulkStore(m.getListOrEmpty(BulkStore.LIST), ContainerOverflow.MAX_ENTRIES,
                ContainerOverflow.MAX_PER_ENTRY, data::markDirty);
    }

    /** The overflow store when the player has one with something in it, else null (reads create no list). */
    @org.jetbrains.annotations.Nullable
    static BulkStore overflowIfAny(Player player) {
        Bag open = openBag(player);
        if (open != null) return open.overflow.isEmpty() ? null : open.overflow;
        if (!com.skycraft.platform.NbtData.hasType(SkyData.get(player).module(OVERFLOW_MODULE), BulkStore.LIST, Tag.TAG_LIST)) return null;
        BulkStore s = overflowOf(player);
        return s.isEmpty() ? null : s;
    }

    /** The bag menu the player has open (their own mount's), or null. */
    @org.jetbrains.annotations.Nullable
    private static Bag openBag(Player player) {
        return player.containerMenu instanceof SaddlebagMenu m && m.bag().owner == player ? m.bag() : null;
    }

    /**
     * The bag's live container: gear in slots 0 and 1 (shown from the mount), cargo from slot {@value #GEAR} (stored in
     * the record as slot - {@value #GEAR}, which keeps the first version's records valid). Saved back when closed.
     * Behind the slots is the overflow ({@link OverflowHost}): rows of the record's {@link BulkStore}, listed, taken
     * and stored by the Skyrim container screen like a chest's, counted against the same weight limit.
     */
    static final class Bag extends SimpleContainer implements OverflowHost {
        final ServerPlayer owner;
        final Mob mount;
        final MountKind kind;
        /** The record's overflow (live: a change is persistent at once; this instance's version feeds the sync). */
        final BulkStore overflow;
        private boolean loading = true; // until the first reload has run
        private float overflowKg;
        private int overflowKgStamp = -1;

        Bag(ServerPlayer owner, Mob mount, MountKind kind) {
            super(GEAR + CARGO);
            this.owner = owner;
            this.mount = mount;
            this.kind = kind;
            this.overflow = overflowOf(owner);
            reload();
        }

        @Override
        public BulkStore overflow() {
            return overflow;
        }

        /** How many of {@code stack} still fit: the saddle's weight limit over slots and overflow together. */
        @Override
        public int overflowRoom(ItemStack stack) {
            float unit = ItemWeights.get(stack);
            if (unit <= 0f) return Integer.MAX_VALUE;
            float room = capacity() - cargoWeight() + 0.001f;
            return room <= 0f ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.floor(room / unit));
        }

        /** True when one more item of {@code stack} may be put in {@code slot} within the saddle's limit. */
        boolean fits(int slot, ItemStack stack) {
            if (slot < GEAR || stack.isEmpty()) return false;
            float unit = ItemWeights.get(stack);
            return unit <= 0f || cargoWeight() - ItemWeights.getStack(getItem(slot)) + unit <= capacity() + 0.001f;
        }

        float capacity() {
            return MountGear.bagCapacity(mount, kind);
        }

        /** Weight of everything in the bags: the cargo stacks and the overflow rows. */
        float cargoWeight() {
            float sum = 0f;
            for (int i = GEAR; i < getContainerSize(); i++) sum += ItemWeights.getStack(getItem(i));
            if (overflowKgStamp != overflow.modCount()) {
                overflowKg = overflow.weight();
                overflowKgStamp = overflow.modCount();
            }
            return sum + overflowKg;
        }

        /** Loads gear from the mount and cargo from the record (after anything changed either). */
        void reload() {
            loading = true;
            try {
                for (int i = 0; i < getContainerSize(); i++) setItem(i, ItemStack.EMPTY);
                ItemStack saddle = MountGear.saddle(mount) == null ? ItemStack.EMPTY : MountGear.saddle(mount).stack();
                setItem(0, saddle);
                setItem(1, MountGear.barding(mount));
                ListTag list = Mounts.data(owner).getListOrEmpty(BAGS);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag t = list.getCompoundOrEmpty(i);
                    int slot = (t.getByteOr("Slot",(byte)0) & 255) + GEAR;
                    if (slot < getContainerSize()) setItem(slot, com.skycraft.inventory.ItemData.parse(t));
                }
            } finally {
                loading = false;
            }
        }

        /**
         * Every change of the slots is written to the record at once. The overflow behind them is persistent the
         * moment it changes (it is the record's own list), so the slots must be too: a row drained into a slot and
         * a crash before the menu closed would otherwise lose it.
         */
        @Override
        public void setChanged() {
            super.setChanged();
            if (!loading) persist();
        }

        /** Writes the cargo to the record. */
        void persist() {
            CompoundTag tag = Mounts.data(owner);
            ListTag list = new ListTag();
            for (int i = GEAR; i < getContainerSize(); i++) {
                ItemStack s = getItem(i);
                if (s.isEmpty()) continue;
                CompoundTag t = new CompoundTag();
                t.putByte("Slot", (byte) (i - GEAR));
                com.skycraft.platform.ItemTags.saveInto(s, t);
                list.add(t);
            }
            tag.put(BAGS, list);
            SkyData.get(owner).markDirty();
        }

        /** Saves the cargo, runs a change of the mount's gear, and shows the result (spilled cargo included). */
        void change(Runnable gear) {
            persist();
            gear.run();
            reload();
        }

        /**
         * Structural only: cargo slots take cargo. The weight rule lives in {@link #fits} and {@link #overflowRoom}
         * (slot clicks and the overflow's store): moving a row from the overflow into a free slot, which the screen
         * does after every take, changes no weight and must never be refused for it.
         */
        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return slot >= GEAR && !stack.isEmpty();
        }

        @Override
        public boolean stillValid(Player player) {
            return player == owner && mount.isAlive() && player.distanceToSqr(mount) < 8 * 8;
        }

        @Override
        public void stopOpen(net.minecraft.world.entity.ContainerUser player) {
            super.stopOpen(player);
            persist();
            fit(owner, capacity());
        }
    }
}
