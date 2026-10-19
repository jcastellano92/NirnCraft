package com.nirncraft.inventory;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A {@code GENERIC_9x3} chest menu over an {@link OverflowHost} whose 27 slots are only a view of its store: the slots
 * are take-only and every store goes into the overflow through {@link ContainerOverflow.Bound#add}, so the host's room
 * rule ({@link OverflowHost#overflowRoom}) applies to every way in. The client builds an ordinary chest menu for the
 * type and the Skyrim container screen skins it.
 *
 * <p>Why a menu: a menu {@link Slot} never asks its container's {@code canPlaceItem} ({@code Slot#mayPlace} is simply
 * true), so a host that returns false there still got items put in its slots by the Skyrim screen's Store and Store
 * All (they merge into and fill the menu's container slots before the overflow), shift-click and vanilla clicks and
 * drags. Users: {@code loot.container.LootMenu} (the stash allowance of world loot containers) and the jail's
 * confiscated goods ({@code crime.JailEvidence}, whose slots belong to a container that is thrown away on close).</p>
 */
public class OverflowChestMenu extends ChestMenu {
    public static final int SLOTS = 27;
    private final OverflowHost host;

    public OverflowChestMenu(int id, Inventory inventory, OverflowHost host) {
        super(MenuType.GENERIC_9x3, id, inventory, host, 3);
        this.host = host;
        for (int i = 0; i < SLOTS; i++) {
            Slot old = slots.get(i);
            Slot takeOnly = new TakeOnly(host, old.getContainerSlot(), old.x, old.y);
            takeOnly.index = old.index;
            slots.set(i, takeOnly);
        }
    }

    public OverflowHost host() {
        return host;
    }

    /** A view slot: its item can be taken, nothing can be put in (stores go through the overflow's room rule). */
    public static final class TakeOnly extends Slot {
        TakeOnly(OverflowHost host, int slot, int x, int y) {
            super(host, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }

    /** Shift-click: out of the container as a chest does; into it through the overflow (what is refused stays put). */
    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (slotIndex < SLOTS) return super.quickMoveStack(player, slotIndex);
        Slot slot = slots.get(slotIndex);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        BulkStore store = host.overflow();
        if (store == null) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        int taken = new ContainerOverflow.Bound(store, host).add(stack); // shrinks the slot's stack
        if (taken > 0) {
            if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
            else slot.setChanged();
            if (player instanceof ServerPlayer sp) ContainerSync.markDirty(sp);
        }
        return ItemStack.EMPTY; // one move per click: nothing for the caller to repeat
    }
}
