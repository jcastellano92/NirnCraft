package com.nirncraft.loot.container;

import com.nirncraft.inventory.OverflowChestMenu;
import net.minecraft.world.entity.player.Inventory;

/**
 * The menu of an open {@link LootWindow}: an {@link OverflowChestMenu}, so its 27 slots are take-only and every store
 * (the Skyrim screen's Store and Store All, shift-click, clicks and drags) goes through {@link LootWindow#overflowRoom}:
 * the stash allowance of world containers, the books-only rule of shelves. A plain chest menu let the slots take
 * everything past the allowance, because a menu slot never asks its container's {@code canPlaceItem}.
 */
public final class LootMenu extends OverflowChestMenu {
    private final LootWindow window;

    public LootMenu(int id, Inventory inventory, LootWindow window) {
        super(id, inventory, window);
        this.window = window;
    }

    public LootWindow window() {
        return window;
    }
}
