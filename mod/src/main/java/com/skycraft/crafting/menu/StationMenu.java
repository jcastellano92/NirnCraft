package com.skycraft.crafting.menu;

import com.skycraft.crafting.StationType;
import com.skycraft.crafting.block.StationBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Menu of every crafting station. It has no slots of its own: it mirrors the whole player inventory with hidden,
 * non-interactive slots so the server keeps the client's inventory in sync while the station screen is open (the
 * screen counts materials and lists improvable items from it). Crafting/tempering happen through packets, which the
 * server only accepts while this menu is open and still valid.
 */
public class StationMenu extends AbstractContainerMenu {
    public final StationType type;
    private final ContainerLevelAccess access;

    public StationMenu(int id, Inventory inventory, StationType type, ContainerLevelAccess access) {
        super(CraftingMenus.STATION.get(), id);
        this.type = type;
        this.access = access;
        for (int i = 0; i < inventory.getContainerSize(); i++) addSlot(new HiddenSlot(inventory, i));
    }

    /** Client-side factory (see {@link CraftingMenus}). */
    public static StationMenu fromNetwork(int id, Inventory inventory, FriendlyByteBuf buf) {
        return new StationMenu(id, inventory, buf.readEnum(StationType.class), ContainerLevelAccess.NULL);
    }

    public static void open(ServerPlayer player, StationType type, BlockPos pos) {
        player.openMenu(
                new SimpleMenuProvider((id, inv, p) -> new StationMenu(id, inv, type, ContainerLevelAccess.create(player.level(), pos)), type.title()),
                buf -> buf.writeEnum(type));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return access.evaluate((level, pos) -> {
            net.minecraft.world.level.block.Block b = level.getBlockState(pos).getBlock();
            boolean valid = false;
            if (b instanceof StationBlock block && block.type == type) valid = true;
            else if (type == StationType.ARMOR_WORKBENCH && (b == net.minecraft.world.level.block.Blocks.CRAFTING_TABLE || b == net.minecraft.world.level.block.Blocks.SMITHING_TABLE)) valid = true;
            else if (type == StationType.FORGE && (b instanceof net.minecraft.world.level.block.AnvilBlock)) valid = true;
            else if (type == StationType.SMELTER && (b == net.minecraft.world.level.block.Blocks.FURNACE || b == net.minecraft.world.level.block.Blocks.BLAST_FURNACE)) valid = true;
            else if (type == StationType.GRINDSTONE && b == net.minecraft.world.level.block.Blocks.GRINDSTONE) valid = true;
            return valid && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
        }, true);
    }

    /** Invisible, locked mirror of a player inventory slot. */
    static class HiddenSlot extends Slot {
        HiddenSlot(Inventory inventory, int index) {
            super(inventory, index, -10000, -10000);
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }
}
