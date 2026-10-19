package com.nirncraft.platform;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.storage.ValueInput;

/** Reads only a NirnCraft object's saved fields, retaining its existing on-disk schema. */
public final class ValueTags {
    private ValueTags() {}

    public static CompoundTag read(ValueInput input, String... keys) {
        CompoundTag tag = new CompoundTag();
        for (String key : keys) {
            input.read(key, ExtraCodecs.NBT).ifPresent(value -> tag.put(key, value));
        }
        return tag;
    }
}
