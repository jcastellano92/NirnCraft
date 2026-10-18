package com.skycraft.arsenal;

import com.skycraft.Skycraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;

/** Item and entity tags used by the arsenal. */
public final class ArsenalTags {
    /** Crossbow bolts: only Skycraft crossbows load them. */
    public static final TagKey<Item> BOLTS = item("bolts");
    /** Named artifacts (excluded from merchant stock). */
    public static final TagKey<Item> ARTIFACTS = item("artifacts");
    public static final TagKey<Item> STAVES = item("staves");
    /** Filled/empty soul gems (owned by crafting/arcane); NBT int {@code soul} holds the soul size. */
    public static final TagKey<Item> SOUL_GEMS = item("soul_gems");

    public static final TagKey<EntityType<?>> UNDEAD = entity("undead");
    public static final TagKey<EntityType<?>> DRAGONS = entity("dragons");
    public static final TagKey<EntityType<?>> GUARDS = entity("guards");

    private ArsenalTags() {}

    private static TagKey<Item> item(String name) {
        return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Skycraft.MODID, name));
    }

    private static TagKey<EntityType<?>> entity(String name) {
        return TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Skycraft.MODID, name));
    }
}
