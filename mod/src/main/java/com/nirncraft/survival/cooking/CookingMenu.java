package com.nirncraft.survival.cooking;

import com.nirncraft.survival.SurvivalRegistry;
import com.nirncraft.survival.block.CookingPotBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Menu of the cooking pot. Like the smithing stations it has no slots of its own: hidden mirror slots keep the
 * client's copy of the player inventory in sync so the screen can show have/need counts. Cooking happens through
 * {@link com.nirncraft.survival.SurvivalPackets.Cook}, accepted only while this menu is open and valid.
 */
public class CookingMenu extends AbstractContainerMenu {
    private final ContainerLevelAccess access;
    public final BlockPos pos;

    public CookingMenu(int id, Inventory inventory, BlockPos pos, ContainerLevelAccess access) {
        super(SurvivalRegistry.COOKING_MENU.get(), id);
        this.access = access;
        this.pos = pos;
        for (int i = 0; i < inventory.getContainerSize(); i++) addSlot(new HiddenSlot(inventory, i));
    }

    public static CookingMenu fromNetwork(int id, Inventory inventory, FriendlyByteBuf buf) {
        return new CookingMenu(id, inventory, buf.readBlockPos(), ContainerLevelAccess.NULL);
    }

    public static void open(ServerPlayer player, BlockPos pos) {
        player.openMenu(
                new SimpleMenuProvider((id, inv, p) -> new CookingMenu(id, inv, pos, ContainerLevelAccess.create(player.level(), pos)),
                        Component.translatable("block.nirncraft.cooking_pot")),
                buf -> buf.writeBlockPos(pos));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return access.evaluate((level, p) -> {
            net.minecraft.world.level.block.Block b = level.getBlockState(p).getBlock();
            boolean valid = b instanceof CookingPotBlock || b == net.minecraft.world.level.block.Blocks.SMOKER
                    || b == net.minecraft.world.level.block.Blocks.CAMPFIRE || b == net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE;
            return valid && player.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) <= 64.0;
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
