package com.nirncraft.platform;

import com.nirncraft.NirnCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Explicit registry identities must be assigned to properties before native item/block constructors run. */
public final class SkyProperties {
    private SkyProperties() {}
    public static Item.Properties item(String id) {
        return new Item.Properties().setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(NirnCraft.MODID, id)));
    }
    public static BlockBehaviour.Properties block(String id) { return block(id, BlockBehaviour.Properties.of()); }
    public static BlockBehaviour.Properties block(String id, BlockBehaviour.Properties properties) {
        return properties.setId(ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(NirnCraft.MODID, id)));
    }
}
