package com.nirncraft.platform;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Explicit NBT type checks retained for repairing optional and legacy module data. */
public final class NbtData {
    public static final int ANY_NUMERIC = 99;
    private NbtData() {}

    public static boolean isListOf(net.minecraft.nbt.ListTag list, int type) {
        for (Tag value : list) if (value.getId() != type) return false;
        return true;
    }

    public static boolean hasType(CompoundTag tag, String key, int type) {
        Tag value = tag.get(key);
        if (value == null) return false;
        return value.getId() == type || type == 99 && value.getId() >= Tag.TAG_BYTE && value.getId() <= Tag.TAG_DOUBLE;
    }
}
