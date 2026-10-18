package com.skycraft.inventory;

import com.skycraft.vitals.ActionHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Server side of the Skyrim machine screen ({@code inventory.client.SkyrimMachineScreen}), which shows the vanilla
 * workstation menus (furnaces, brewing stand, anvil, grindstone, smithing table, stonecutter, loom, cartography table,
 * enchanting table, beacon, crafting table, trader) as lists. The menus themselves are untouched: every move here is a
 * real vanilla click on the real menu ({@link AbstractContainerMenu#clicked}) or the slot's own take rules, so
 * recipes, fuel rules, xp costs and result slots behave exactly as with the vanilla screen. What this adds is that
 * the player's pack (the overflow behind the 36 slots, see {@link Pack}) is reachable: a pack entry is first pulled
 * into a real slot ({@link Pack#resolveSlot}), and taken results go to the bags ({@link ActionHandler#addToBags})
 * instead of staying behind when the 36 slots are full.
 */
public final class MachineActions {
    private MachineActions() {}

    /** The menus the machine screen shows (a request for any other menu is ignored). */
    public static boolean supports(AbstractContainerMenu menu) {
        return menu instanceof AbstractFurnaceMenu || menu instanceof BrewingStandMenu || menu instanceof AnvilMenu
                || menu instanceof GrindstoneMenu || menu instanceof SmithingMenu || menu instanceof StonecutterMenu
                || menu instanceof LoomMenu || menu instanceof CartographyTableMenu || menu instanceof EnchantmentMenu
                || menu instanceof BeaconMenu || menu instanceof MerchantMenu || menu instanceof CraftingMenu;
    }

    public static void handle(ServerPlayer player, int containerId, int kind, int a, int b) {
        if (player == null || !player.isAlive() || player.isSpectator()) return;
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu == player.inventoryMenu || menu.containerId != containerId || !menu.stillValid(player)
                || !supports(menu) || !menu.getCarried().isEmpty()) {
            return;
        }
        Inventory inv = player.getInventory();
        int machine = machineSlots(menu, inv);
        if (machine <= 0) return;
        switch (kind) {
            case InventoryPackets.MachineAction.TAKE -> {
                if (a < 0 || a >= machine) return;
                if (take(player, menu.getSlot(a))) done(player, menu);
            }
            case InventoryPackets.MachineAction.FEED -> {
                int src = source(player, menu, inv, a);
                if (src < 0) return;
                // shift-click: the menu's own quickMoveStack decides which slot takes it (and loops as vanilla does)
                menu.clicked(src, 0, ContainerInput.QUICK_MOVE, player);
                done(player, menu);
            }
            case InventoryPackets.MachineAction.PLACE_ALL, InventoryPackets.MachineAction.PLACE_ONE -> {
                if (b < 0 || b >= machine) return;
                int src = source(player, menu, inv, a);
                if (src < 0) return;
                // pick the stack up, put it (or one of it) into the chosen slot, hand the rest back: all vanilla clicks
                menu.clicked(src, 0, ContainerInput.PICKUP, player);
                if (!menu.getCarried().isEmpty()) {
                    menu.clicked(b, kind == InventoryPackets.MachineAction.PLACE_ONE ? 1 : 0, ContainerInput.PICKUP, player);
                    if (!menu.getCarried().isEmpty()) menu.clicked(src, 0, ContainerInput.PICKUP, player);
                }
                if (!menu.getCarried().isEmpty()) {
                    // the source slot was taken meanwhile: never keep or lose it
                    ItemStack rest = menu.getCarried().copy();
                    menu.setCarried(ItemStack.EMPTY);
                    Pack.giveOrDrop(player, rest);
                }
                done(player, menu);
            }
            default -> {
            }
        }
    }

    /** Leading slots of the menu that are not the player's inventory: the machine's own. */
    private static int machineSlots(AbstractContainerMenu menu, Inventory inv) {
        int n = 0;
        for (Slot s : menu.slots) {
            if (s.container == inv) break;
            n++;
        }
        return n;
    }

    /**
     * Menu slot of the player stack the request names: a menu slot id of the player's inventory part, or a pack entry
     * (pulled into a real slot first). -1 when the request is stale.
     */
    private static int source(ServerPlayer player, AbstractContainerMenu menu, Inventory inv, int a) {
        int invSlot;
        if (Pack.isVirtual(a)) {
            invSlot = Pack.resolveSlot(player, a, null);
            if (invSlot < 0) return -1;
        } else {
            if (a < 0 || a >= menu.slots.size()) return -1;
            Slot s = menu.getSlot(a);
            if (s.container != inv || !s.hasItem()) return -1;
            return a;
        }
        for (Slot s : menu.slots) {
            if (s.container == inv && s.getContainerSlot() == invSlot && s.hasItem()) return s.index;
        }
        return -1; // pulled into a slot the menu does not show (armor, offhand): it is in the inventory, nothing more
    }

    /** Takes a machine slot's stack into the bags through the slot's own pickup rules; leftovers go back or drop. */
    private static boolean take(ServerPlayer player, Slot slot) {
        if (slot == null || !slot.hasItem() || !slot.mayPickup(player)) return false;
        ItemStack taken = slot.safeTake(slot.getItem().getCount(), Integer.MAX_VALUE, player);
        if (taken.isEmpty()) return false;
        int before = taken.getCount();
        ActionHandler.addToBags(player, taken, false);
        boolean moved = taken.getCount() < before;
        if (!taken.isEmpty()) {
            ItemStack rest = slot.safeInsert(taken);
            if (!rest.isEmpty()) Pack.giveOrDrop(player, rest);
        }
        return moved;
    }

    private static void done(ServerPlayer player, AbstractContainerMenu menu) {
        menu.broadcastChanges();
        player.inventoryMenu.broadcastChanges();
        player.getInventory().setChanged();
    }
}
