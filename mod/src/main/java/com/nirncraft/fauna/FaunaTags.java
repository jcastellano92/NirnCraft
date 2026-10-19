package com.nirncraft.fauna;

import com.nirncraft.NirnCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

/** Tags owned by the fauna module. */
public final class FaunaTags {
    /** Wild beasts of Skyrim (fauna wildlife plus vanilla wolves and polar bears): {@code #nirncraft:beasts}. */
    public static final TagKey<EntityType<?>> BEASTS = TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "beasts"));

    private FaunaTags() {}
}
