package com.nirncraft.realms;

import net.minecraft.resources.ResourceKey;
import com.nirncraft.NirnCraft;
import com.nirncraft.realms.item.SigilStoneItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import java.util.function.Supplier;

/** The realms' items: Sigil Stones (enchanting). Black Books live in the loot module. */
public final class RealmItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, NirnCraft.MODID);

    public static final Supplier<Item> SIGIL_STONE = ITEMS.register("sigil_stone", key -> new SigilStoneItem(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, key))));
    // the Black Book item is com.nirncraft.loot.LootModule.BLACK_BOOK (collectible + the way into Apocrypha)

    private RealmItems() {}
}
