package com.nirncraft.platform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.Locale;

/** Stable, case-insensitive enum ids for NirnCraft's authored data. */
public final class SkyCodecs {
    private SkyCodecs() {}

    public static <E extends Enum<E>> Codec<E> enumNames(Class<E> type) {
        return Codec.STRING.comapFlatMap(id -> {
            try {
                return DataResult.success(Enum.valueOf(type, id.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                return DataResult.error(() -> "Unknown " + type.getSimpleName() + " '" + id + "'");
            }
        }, value -> value.name().toLowerCase(Locale.ROOT));
    }
}
