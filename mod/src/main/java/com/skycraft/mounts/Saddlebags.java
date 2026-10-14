package com.skycraft.mounts;

import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.inventory.ItemWeights;
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
 * ({@link MountGear#bagKg}, kilograms in the unit of {@link ItemWeights}) in up to {@value #CARGO} stacks. The items
 * belong to the player's mount record ({@code bags} in {@code module("mounts")}), so they stay with the mount when it
 * is called from afar or comes back after falling; a smaller saddle (or none) spills what no longer fits into your
 * bags, never into the void ({@link #fit}).
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

    private Saddlebags() {}

    public static void open(ServerPlayer player, Entity mount, MountKind kind) {
        if (!(mount instanceof Mob mob) || !mob.isAlive()) return;
        if (!Mounts.isActive(player, mount)) {
            Notifier.message(player, Component.translatable("mounts.skycraft.bags.not_active"));
            return;
        }
        Bag bag = new Bag(player, mob, kind);
        // the title carries the beast's bag scale: the screen sums the cargo it lists against the saddle in slot 0
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new SaddlebagMenu(id, inv, bag),
                Component.translatable("mounts.skycraft.bags.title", kind.displayName(), String.valueOf(kind.bagRows / 2f))));
        mount.playSound(net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER, 0.8f, 1.0f);
    }

    /**
     * Spills bag contents beyond {@code capacityKg} into the player's bags (the last stacks first, a stack is split to
     * fit exactly). Called after every change of saddle and when a mount of another size is brought out.
     */
    public static void fit(ServerPlayer player, float capacityKg) {
        CompoundTag tag = Mounts.data(player);
        ListTag list = tag.getList(BAGS, Tag.TAG_COMPOUND);
        List<CompoundTag> entries = new ArrayList<>();
        float total = 0f;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            entries.add(t);
            total += ItemWeights.getStack(ItemStack.of(t));
        }
        if (total <= capacityKg + 0.001f) return;
        entries.sort((a, b) -> Integer.compare(a.getByte("Slot") & 255, b.getByte("Slot") & 255));
        boolean spilled = false;
        for (int i = entries.size() - 1; i >= 0 && total > capacityKg + 0.001f; i--) {
            CompoundTag t = entries.get(i);
            ItemStack s = ItemStack.of(t);
            float unit = ItemWeights.get(s);
            if (unit <= 0f) continue;
            int n = Math.min(s.getCount(), (int) Math.ceil((total - capacityKg) / unit - 1e-4));
            if (n <= 0) continue;
            ItemStack out = s.copy();
            out.setCount(n);
            s.shrink(n);
            total -= unit * n;
            MountGear.give(player, out);
            spilled = true;
            if (s.isEmpty()) {
                entries.remove(i);
            } else {
                CompoundTag rest = new CompoundTag();
                s.save(rest);
                rest.putByte("Slot", t.getByte("Slot"));
                entries.set(i, rest);
            }
        }
        ListTag keep = new ListTag();
        keep.addAll(entries);
        tag.put(BAGS, keep);
        SkyData.get(player).markDirty();
        if (spilled) Notifier.message(player, Component.translatable("mounts.skycraft.bags.spilled"));
    }

    /** Weight (kg) of everything in the bags of the player's record. */
    public static float load(Player player) {
        float sum = 0f;
        ListTag list = Mounts.data(player).getList(BAGS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) sum += ItemWeights.getStack(ItemStack.of(list.getCompound(i)));
        return sum;
    }

    /**
     * The bag's live container: gear in slots 0 and 1 (shown from the mount), cargo from slot {@value #GEAR} (stored in
     * the record as slot - {@value #GEAR}, which keeps the first version's records valid). Saved back when closed.
     */
    static final class Bag extends SimpleContainer {
        final ServerPlayer owner;
        final Mob mount;
        final MountKind kind;

        Bag(ServerPlayer owner, Mob mount, MountKind kind) {
            super(GEAR + CARGO);
            this.owner = owner;
            this.mount = mount;
            this.kind = kind;
            reload();
        }

        float capacity() {
            return MountGear.bagKg(kind, MountGear.saddle(mount));
        }

        /** Weight of the cargo stacks. */
        float cargoWeight() {
            float sum = 0f;
            for (int i = GEAR; i < getContainerSize(); i++) sum += ItemWeights.getStack(getItem(i));
            return sum;
        }

        /** Loads gear from the mount and cargo from the record (after anything changed either). */
        void reload() {
            for (int i = 0; i < getContainerSize(); i++) setItem(i, ItemStack.EMPTY);
            ItemStack saddle = MountGear.saddle(mount) == null ? ItemStack.EMPTY : MountGear.saddle(mount).stack();
            setItem(0, saddle);
            setItem(1, MountGear.barding(mount));
            ListTag list = Mounts.data(owner).getList(BAGS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag t = list.getCompound(i);
                int slot = (t.getByte("Slot") & 255) + GEAR;
                if (slot < getContainerSize()) setItem(slot, ItemStack.of(t));
            }
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
                s.save(t);
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

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            if (slot < GEAR || stack.isEmpty()) return false;
            float unit = ItemWeights.get(stack);
            return unit <= 0f || cargoWeight() - ItemWeights.getStack(getItem(slot)) + unit <= capacity() + 0.001f;
        }

        @Override
        public boolean stillValid(Player player) {
            return player == owner && mount.isAlive() && player.distanceToSqr(mount) < 8 * 8;
        }

        @Override
        public void stopOpen(Player player) {
            super.stopOpen(player);
            persist();
            fit(owner, capacity());
        }
    }
}
