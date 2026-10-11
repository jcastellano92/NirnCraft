package com.skycraft.homestead;

import com.skycraft.crafting.menu.CraftingMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkHooks;

/**
 * Menu of the drafting table. Like the cooking pot it has no slots of its own: hidden mirror slots keep the client's
 * copy of the inventory in sync so the screen can show have/need counts. The claim's state ({@link Hearthfire#state})
 * comes with the opening packet and is refreshed by {@link HomesteadPackets.State} after every action.
 */
public class HomesteadMenu extends AbstractContainerMenu {
    public final BlockPos pos;
    /** The claim as the screen shows it (see {@link Hearthfire#state}). */
    public CompoundTag state;

    public HomesteadMenu(int id, Inventory inventory, BlockPos pos, CompoundTag state) {
        super(CraftingMenus.HOMESTEAD.get(), id);
        this.pos = pos;
        this.state = state;
        for (int i = 0; i < inventory.getContainerSize(); i++) addSlot(new HiddenSlot(inventory, i));
    }

    public static HomesteadMenu fromNetwork(int id, Inventory inventory, FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        CompoundTag state = buf.readNbt();
        return new HomesteadMenu(id, inventory, pos, state == null ? new CompoundTag() : state);
    }

    public static void open(ServerPlayer player, HomesteadClaim claim, BlockPos pos) {
        CompoundTag state = Hearthfire.state(player, claim);
        NetworkHooks.openScreen(player,
                new SimpleMenuProvider((id, inv, p) -> new HomesteadMenu(id, inv, pos, state), claim.displayName()),
                buf -> {
                    buf.writeBlockPos(pos);
                    buf.writeNbt(state);
                });
    }

    // legacy accessors used by the permission buttons
    public int doorPerm() {
        return state.getInt("door");
    }

    public int containerPerm() {
        return state.getInt("container");
    }

    public int buildPerm() {
        return state.getInt("build");
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0
                && player.level().getBlockState(pos).getBlock() instanceof DraftingTableBlock;
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
