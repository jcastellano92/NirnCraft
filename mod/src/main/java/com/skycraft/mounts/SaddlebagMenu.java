package com.skycraft.mounts;

import com.skycraft.inventory.ItemWeights;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The mount menu: a six-row chest menu (so the client shows it with the Skyrim container screen, and the container
 * take/store packets of {@code vitals.ActionHandler} drive it) whose first two slots are the mount's gear and whose
 * other stacks are weight-limited ({@link Saddlebags.Bag}).
 *
 * <ul>
 *     <li>Slot 0 is the saddle, slot 1 the barding. Taking one is "Unequip" ({@link MountGear#unequipSaddle}); storing a
 *     saddle or barding from your bags is "Equip" and swaps with what is worn ({@link #equipFrom}). Nothing else can be
 *     put in them.</li>
 *     <li>A cargo stack goes in only while the load stays within the saddle's capacity: a partial stack is accepted up
 *     to the last kilogram that fits.</li>
 * </ul>
 */
public final class SaddlebagMenu extends ChestMenu {
    private final Saddlebags.Bag bag;

    SaddlebagMenu(int id, Inventory inv, Saddlebags.Bag bag) {
        super(MenuType.GENERIC_9x6, id, inv, bag, 6);
        this.bag = bag;
    }

    /** Shift-click never moves worn gear (vanilla would bypass {@link GearSlot#onTake} and duplicate the saddle). */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < Saddlebags.GEAR) return ItemStack.EMPTY;
        return super.quickMoveStack(player, index);
    }

    /** True for an item that is gear: it goes on the mount through {@link #equipFrom}, never into the cargo. */
    public boolean isGear(ItemStack stack) {
        return SaddleType.of(stack) != null || MountGear.wearsBarding(bag.mount, bag.kind, stack)
                || stack.getItem() instanceof net.minecraft.world.item.HorseArmorItem;
    }

    /**
     * Equips a saddle or barding from a slot of the player's inventory, swapping with what is worn (the old piece goes
     * to the player's bags, the saddlebags are fitted to the new saddle). True when something changed.
     */
    public boolean equipFrom(ServerPlayer player, Slot from) {
        ItemStack stack = from.getItem();
        if (stack.isEmpty() || !from.mayPickup(player)) return false;
        MountGear.Result[] result = new MountGear.Result[1];
        bag.change(() -> {
            result[0] = SaddleType.of(stack) != null
                    ? MountGear.equipSaddle(player, bag.mount, bag.kind, stack)
                    : MountGear.equipBarding(player, bag.mount, bag.kind, stack);
        });
        if (stack.isEmpty()) from.set(ItemStack.EMPTY);
        else from.setChanged();
        MountGear.say(player, result[0]);
        return result[0] == MountGear.Result.OK;
    }

    @Override
    protected Slot addSlot(Slot slot) {
        // the chest menu lays out plain slots over the container: swap the bag's for gear and weight-limited ones
        if (slot.container instanceof Saddlebags.Bag b) {
            int i = slot.getContainerSlot();
            slot = i < Saddlebags.GEAR ? new GearSlot(b, i, slot.x, slot.y) : new CargoSlot(b, i, slot.x, slot.y);
        }
        return super.addSlot(slot);
    }

    /** The saddle (0) or barding (1): worn gear, changed only by {@link #equipFrom} and by taking it off. */
    private static final class GearSlot extends Slot {
        private final Saddlebags.Bag bag;
        private final int index;

        GearSlot(Saddlebags.Bag bag, int index, int x, int y) {
            super(bag, index, x, y);
            this.bag = bag;
            this.index = index;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return hasItem() && !MountGear.fixedTack(bag.kind);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public void onTake(Player player, ItemStack stack) {
            // the item is in the player's hands now: take it off the mount and fit the bags to what is left
            if (player instanceof ServerPlayer sp) {
                bag.change(() -> {
                    if (index == 0) MountGear.unequipSaddle(sp, bag.mount, bag.kind, false);
                    else MountGear.unequipBarding(sp, bag.mount, bag.kind, false);
                });
            }
            super.onTake(player, stack);
        }
    }

    /** A cargo stack: accepts only what the saddle's capacity has room for. */
    private static final class CargoSlot extends Slot {
        private final Saddlebags.Bag bag;

        CargoSlot(Saddlebags.Bag bag, int index, int x, int y) {
            super(bag, index, x, y);
            this.bag = bag;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return bag.canPlaceItem(getContainerSlot(), stack);
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            float unit = ItemWeights.get(stack);
            int cap = Math.min(super.getMaxStackSize(stack), stack.getMaxStackSize());
            if (unit <= 0f) return cap;
            ItemStack here = getItem();
            float others = bag.cargoWeight() - ItemWeights.getStack(here);
            int fits = (int) Math.floor((bag.capacity() - others + 0.001f) / unit);
            return Math.max(0, Math.min(cap, fits));
        }
    }
}
