package com.skycraft.fauna;

import com.skycraft.Skycraft;
import com.skycraft.crafting.arcane.ArcaneRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

/**
 * Animal parts and insect ingredients of the fauna module. Alchemy ingredients are edible (Skyrim style, see
 * {@link ArcaneRegistry#INGREDIENT_FOOD}); their effects live in arcane's ingredient table.
 */
public final class FaunaItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, Skycraft.MODID);

    // ---------------------------------------------------------------- animal parts
    public static final Supplier<Item> SMALL_ANTLERS = ingredient("small_antlers");
    public static final Supplier<Item> LARGE_ANTLERS = ingredient("large_antlers");
    public static final Supplier<Item> SABRE_CAT_TOOTH = ingredient("sabre_cat_tooth");
    public static final Supplier<Item> MUDCRAB_CHITIN = ingredient("mudcrab_chitin");
    public static final Supplier<Item> SLAUGHTERFISH_SCALES = ingredient("slaughterfish_scales");
    public static final Supplier<Item> HORKER_TUSK = ITEMS.register("horker_tusk", () -> new Item(com.skycraft.platform.SkyProperties.item("horker_tusk")));
    public static final Supplier<Item> MAMMOTH_TUSK = ITEMS.register("mammoth_tusk",
            () -> new Item(com.skycraft.platform.SkyProperties.item("mammoth_tusk").rarity(Rarity.UNCOMMON)));

    // ---------------------------------------------------------------- insects (caught by hand)
    public static final Supplier<Item> MONARCH_WING = ingredient("monarch_wing");
    public static final Supplier<Item> BLUE_BUTTERFLY_WING = ingredient("blue_butterfly_wing");
    public static final Supplier<Item> BLUE_DARTWING = ingredient("blue_dartwing");
    public static final Supplier<Item> TORCHBUG_THORAX = ingredient("torchbug_thorax");
    public static final Supplier<Item> LUNA_MOTH_WING = ingredient("luna_moth_wing");

    private FaunaItems() {}

    private static Supplier<Item> ingredient(String id) {
        return ITEMS.register(id, () -> new Item(com.skycraft.platform.SkyProperties.item(id).food(ArcaneRegistry.INGREDIENT_FOOD)));
    }

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
