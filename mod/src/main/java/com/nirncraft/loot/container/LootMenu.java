package com.nirncraft.loot.container;

import com.nirncraft.inventory.ContainerOverflow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The menu of an open {@link LootWindow}: a {@code GENERIC_9x3} chest menu (the client builds an ordinary chest menu and
 * the Skyrim container screen skins it) whose 27 container slots are take-only.
 *
 * <p>Why: a menu {@link Slot} never asks its container's {@code canPlaceItem} ({@code Slot#mayPlace} is simply true),
 * so with plain chest slots every store path that fills slots (the Skyrim screen's Store and Store All, which merge
 * into and fill slots before the overflow; shift-click; clicking and dragging in the vanilla screen) put items in the
 * window without the stash allowance. Here no slot accepts an item, and a shift-click from the player's inventory goes
 * through the overflow's room rule ({@link LootWindow#overflowRoom}), so every way in is the allowance's way.</p>
 */
public final class LootMenu extends ChestMenu {
    private final LootWindow window;

    public LootMenu(int id, Inventory inventory, LootWindow window) {
        super(MenuType.GENERIC_9x3, id, inventory, window, 3);
        this.window = window;
        for (int i = 0; i < LootWindow.SLOTS; i++) {
            Slot old = slots.get(i);
            Slot takeOnly = new TakeOnly(window, old.getContainerSlot(), old.x, old.y);
            takeOnly.index = old.index;
            slots.set(i, takeOnly);
        }
    }

    public LootWindow window() {
        return window;
    }

    /** A window slot: its item can be taken, nothing can be put in (stores go through the room rule). */
    static final class TakeOnly extends Slot {
        TakeOnly(LootWindow window, int slot, int x, int y) {
            super(window, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }

    /**
     * Shift-click: out of the window as a chest does; into it through the overflow and its room rule (what the
     * allowance refuses stays in the player's slot).
     */
    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (slotIndex < LootWindow.SLOTS) return super.quickMoveStack(player, slotIndex);
        Slot slot = slots.get(slotIndex);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        int taken = new ContainerOverflow.Bound(window.overflow(), window).add(stack); // shrinks the slot's stack
        if (taken > 0) {
            if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
            else slot.setChanged();
            if (player instanceof ServerPlayer sp) com.nirncraft.inventory.ContainerSync.markDirty(sp);
        }
        return ItemStack.EMPTY; // one move per click: nothing for the caller to repeat
    }
}
