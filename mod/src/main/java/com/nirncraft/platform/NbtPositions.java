package com.nirncraft.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** NirnCraft's existing X/Y/Z compounds remain readable independently of vanilla's new position codec. */
public final class NbtPositions {
    private NbtPositions() {}
    public static CompoundTag write(BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        return tag;
    }
    public static BlockPos read(CompoundTag tag) {
        return new BlockPos(tag.getIntOr("X", 0), tag.getIntOr("Y", 0), tag.getIntOr("Z", 0));
    }
}
