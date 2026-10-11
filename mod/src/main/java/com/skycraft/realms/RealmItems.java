package com.skycraft.realms;

import com.skycraft.Skycraft;
import com.skycraft.realms.item.SigilStoneItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The realms' items: Sigil Stones (enchanting). Black Books live in the loot module. */
public final class RealmItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Skycraft.MODID);

    public static final RegistryObject<Item> SIGIL_STONE = ITEMS.register("sigil_stone", () -> new SigilStoneItem(new Item.Properties()));
    // the Black Book item is com.skycraft.loot.LootModule.BLACK_BOOK (collectible + the way into Apocrypha)

    private RealmItems() {}
}
