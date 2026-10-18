package com.skycraft.worldgen;

import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

/** Strict native registry lookup: unknown authored IDs remain absent instead of resolving a default entity. */
public final class RegistryTypes {
    private RegistryTypes() {}
    public static Optional<EntityType<?>> entity(String id) {
        Identifier parsed = Identifier.tryParse(id);
        return parsed == null ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getOptional(parsed);
    }
}
