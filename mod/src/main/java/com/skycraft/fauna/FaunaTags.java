package com.skycraft.fauna;

import com.skycraft.Skycraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

/** Tags owned by the fauna module. */
public final class FaunaTags {
    /** Wild beasts of Skyrim (fauna wildlife plus vanilla wolves and polar bears): {@code #skycraft:beasts}. */
    public static final TagKey<EntityType<?>> BEASTS = TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Skycraft.MODID, "beasts"));

    private FaunaTags() {}
}
