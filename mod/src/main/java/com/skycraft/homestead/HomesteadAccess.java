package com.skycraft.homestead;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jetbrains.annotations.Nullable;

/** Which access kind a block of a home belongs to (doors, containers, crafting stations, beds) and its canonical position. */
public final class HomesteadAccess {
    private HomesteadAccess() {}

    @Nullable
    public static HomesteadClaim.Access classify(Level level, BlockPos pos, BlockState state) {
        var b = state.getBlock();
        if (b instanceof DraftingTableBlock) return null; // the owner's table has its own rule
        if (b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock) return HomesteadClaim.Access.DOOR;
        if (b instanceof BedBlock) return HomesteadClaim.Access.BED;
        if (HomesteadBuildMode.isStation(state)) return HomesteadClaim.Access.STATION;
        if (b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock || b instanceof HopperBlock
                || b instanceof DispenserBlock) {
            return HomesteadClaim.Access.CONTAINER;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof Container) return HomesteadClaim.Access.CONTAINER;
        return null;
    }

    /** One position per physical thing: a door's lower half, a double chest's lower-id half, a bed's head. */
    public static BlockPos canonical(BlockPos pos, BlockState state) {
        var b = state.getBlock();
        if (b instanceof DoorBlock && state.hasProperty(DoorBlock.HALF) && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            return pos.below();
        }
        if (b instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            return other.asLong() < pos.asLong() ? other : pos;
        }
        if (b instanceof BedBlock && state.hasProperty(BedBlock.PART) && state.getValue(BedBlock.PART) == BedPart.FOOT) {
            return pos.relative(state.getValue(BedBlock.FACING));
        }
        return pos;
    }

    public static String lockedKey(HomesteadClaim.Access kind) {
        return switch (kind) {
            case DOOR -> "message.skycraft.homestead.door_locked";
            case CONTAINER -> "message.skycraft.homestead.container_locked";
            case STATION -> "message.skycraft.homestead.station_locked";
            case BED -> "message.skycraft.homestead.bed_locked";
        };
    }
}
