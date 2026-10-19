package com.nirncraft.creatures;

import com.nirncraft.NirnCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

/** Entity type tags owned by the creatures module. */
public final class CreatureTags {
    /** Every dragon (magic absorbs the soul of anything in this tag on death). */
    public static final TagKey<EntityType<?>> DRAGONS = tag("dragons");
    /** Hold guards (crime decides when they turn hostile). */
    public static final TagKey<EntityType<?>> GUARDS = tag("guards");
    /** Creatures that never leave a lootable body. */
    public static final TagKey<EntityType<?>> NO_CORPSE = tag("no_corpse");
    /** Undead creatures (draugr and friends). */
    public static final TagKey<EntityType<?>> UNDEAD = tag("undead");

    private CreatureTags() {}

    private static TagKey<EntityType<?>> tag(String path) {
        return TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(NirnCraft.MODID, path));
    }
}
