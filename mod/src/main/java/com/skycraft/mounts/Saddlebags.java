package com.skycraft.mounts;

import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * Saddlebags: extra carrying room on the active mount (9 to 27 slots by kind, {@link MountKind#bagRows}). The items
 * belong to the player's mount record ({@code bags} in {@code module("mounts")}), so they stay with the mount when it
 * is called from afar or comes back after falling; switching to a mount with smaller bags gives back what no longer
 * fits. Opened by sneak-using your own mount, within reach; nobody else can open them.
 */
public final class Saddlebags {
    private Saddlebags() {}

    public static void open(ServerPlayer player, Entity mount, MountKind kind) {
        if (!Mounts.isActive(player, mount)) {
            Notifier.message(player, Component.translatable("mounts.skycraft.bags.not_active"));
            return;
        }
        int rows = Math.max(1, Math.min(3, kind.bagRows));
        Bag bag = new Bag(player, mount, rows * 9);
        MenuType<ChestMenu> type = switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            default -> MenuType.GENERIC_9x3;
        };
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new ChestMenu(type, id, inv, bag, rows),
                Component.translatable("mounts.skycraft.bags.title", kind.displayName())));
        mount.playSound(net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER, 0.8f, 1.0f);
    }

    /** Drops bag contents beyond {@code slots} into the player's inventory (after switching to a smaller mount). */
    public static void fit(ServerPlayer player, int slots) {
        CompoundTag tag = Mounts.data(player);
        ListTag list = tag.getList("bags", Tag.TAG_COMPOUND);
        ListTag keep = new ListTag();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            int slot = t.getByte("Slot") & 255;
            if (slot < slots) {
                keep.add(t);
            } else {
                ItemStack s = ItemStack.of(t);
                if (!player.getInventory().add(s)) player.drop(s, false);
            }
        }
        tag.put("bags", keep);
        SkyData.get(player).markDirty();
    }

    /** The bag's live container: loaded from the record, saved back when closed. */
    static final class Bag extends SimpleContainer {
        private final ServerPlayer owner;
        private final Entity mount;

        Bag(ServerPlayer owner, Entity mount, int size) {
            super(size);
            this.owner = owner;
            this.mount = mount;
            ListTag list = Mounts.data(owner).getList("bags", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag t = list.getCompound(i);
                int slot = t.getByte("Slot") & 255;
                if (slot < size) setItem(slot, ItemStack.of(t));
            }
        }

        @Override
        public boolean stillValid(Player player) {
            return player == owner && mount.isAlive() && player.distanceToSqr(mount) < 8 * 8;
        }

        @Override
        public void stopOpen(Player player) {
            super.stopOpen(player);
            CompoundTag tag = Mounts.data(owner);
            ListTag old = tag.getList("bags", Tag.TAG_COMPOUND);
            ListTag list = new ListTag();
            // keep slots beyond this bag's size (a bigger mount's bags) untouched
            for (int i = 0; i < old.size(); i++) {
                if ((old.getCompound(i).getByte("Slot") & 255) >= getContainerSize()) list.add(old.getCompound(i));
            }
            for (int i = 0; i < getContainerSize(); i++) {
                ItemStack s = getItem(i);
                if (s.isEmpty()) continue;
                CompoundTag t = new CompoundTag();
                t.putByte("Slot", (byte) i);
                s.save(t);
                list.add(t);
            }
            tag.put("bags", list);
            SkyData.get(owner).markDirty();
        }
    }
}
