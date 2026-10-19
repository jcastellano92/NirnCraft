package com.nirncraft.survival.cooking;

import com.nirncraft.crafting.arcane.ArcaneRegistry;
import com.nirncraft.crafting.recipe.Cost;
import com.nirncraft.survival.SurvivalRegistry;
import com.nirncraft.survival.fishing.FishingRegistry;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Every cooking pot recipe (Skyrim's, with Minecraft stand-ins where Skyrim ingredients don't exist). */
public final class CookingRecipes {
    /** Categories in menu order. */
    public static final List<String> CATEGORIES = List.of("stews", "meat", "baking", "drinks");

    private static Map<String, List<CookingRecipe>> byCategory;
    private static Map<String, CookingRecipe> byId;

    private CookingRecipes() {}

    private static synchronized void build() {
        if (byId != null) return;
        Map<String, List<CookingRecipe>> cats = new LinkedHashMap<>();
        Map<String, CookingRecipe> ids = new LinkedHashMap<>();
        for (String c : CATEGORIES) cats.put(c, new ArrayList<>());
        Builder b = new Builder(cats, ids);

        Supplier<ItemLike> salt = () -> ArcaneRegistry.SALT_PILE.get();

        // ---------------------------------------------------------------- soups & stews
        b.add("stews", "apple_cabbage_stew", SurvivalRegistry.APPLE_CABBAGE_STEW::get, 1,
                Cost.of(() -> Items.APPLE, 1), Cost.of(SurvivalRegistry.CABBAGE, 1), Cost.of(salt, 1));
        b.add("stews", "beef_stew", SurvivalRegistry.BEEF_STEW::get, 1,
                Cost.of(() -> Items.BEEF, 1), Cost.of(() -> Items.CARROT, 1), Cost.of(SurvivalRegistry.GARLIC, 1), Cost.of(salt, 1));
        b.add("stews", "venison_stew", SurvivalRegistry.VENISON_STEW::get, 1,
                Cost.of(SurvivalRegistry.RAW_VENISON, 1), Cost.of(() -> Items.POTATO, 1), Cost.of(SurvivalRegistry.LEEK, 1), Cost.of(salt, 1));
        b.add("stews", "horker_stew", SurvivalRegistry.HORKER_STEW::get, 1,
                Cost.of(SurvivalRegistry.RAW_HORKER_MEAT, 1), Cost.of(SurvivalRegistry.GARLIC, 1), Cost.of(() -> Items.POTATO, 1), Cost.of(salt, 1));
        b.add("stews", "vegetable_soup", SurvivalRegistry.VEGETABLE_SOUP::get, 1,
                Cost.of(SurvivalRegistry.CABBAGE, 1), Cost.of(SurvivalRegistry.LEEK, 1), Cost.of(() -> Items.POTATO, 1), Cost.of(SurvivalRegistry.TOMATO, 1));
        b.add("stews", "tomato_soup", SurvivalRegistry.TOMATO_SOUP::get, 1,
                Cost.of(SurvivalRegistry.TOMATO, 2), Cost.of(SurvivalRegistry.GARLIC, 1), Cost.of(SurvivalRegistry.LEEK, 1), Cost.of(salt, 1));
        b.add("stews", "potato_soup", SurvivalRegistry.POTATO_SOUP::get, 1,
                Cost.of(() -> Items.POTATO, 2), Cost.of(SurvivalRegistry.TOMATO, 1), Cost.of(salt, 1));
        b.add("stews", "elsweyr_fondue", SurvivalRegistry.ELSWEYR_FONDUE::get, 1,
                Cost.of(SurvivalRegistry.EIDAR_CHEESE_WEDGE, 1), Cost.of(SurvivalRegistry.ALE, 1), Cost.of(() -> Items.SUGAR, 1));

        // ---------------------------------------------------------------- meat & fish
        b.add("meat", "cooked_beef", () -> Items.COOKED_BEEF, 1, Cost.of(() -> Items.BEEF, 1), Cost.of(salt, 1));
        b.add("meat", "venison_chop", SurvivalRegistry.VENISON_CHOP::get, 1, Cost.of(SurvivalRegistry.RAW_VENISON, 1), Cost.of(salt, 1));
        b.add("meat", "horker_loaf", SurvivalRegistry.HORKER_LOAF::get, 1, Cost.of(SurvivalRegistry.RAW_HORKER_MEAT, 1), Cost.of(salt, 1));
        b.add("meat", "mammoth_steak", SurvivalRegistry.MAMMOTH_STEAK::get, 1, Cost.of(SurvivalRegistry.RAW_MAMMOTH_SNOUT, 1), Cost.of(salt, 1));
        b.add("meat", "leg_of_goat_roast", SurvivalRegistry.LEG_OF_GOAT_ROAST::get, 1, Cost.of(SurvivalRegistry.RAW_GOAT_MEAT, 1), Cost.of(salt, 1));
        b.add("meat", "grilled_chicken_breast", () -> Items.COOKED_CHICKEN, 1, Cost.of(() -> Items.CHICKEN, 1), Cost.of(salt, 1));
        b.add("meat", "cooked_rabbit", () -> Items.COOKED_RABBIT, 1, Cost.of(() -> Items.RABBIT, 1), Cost.of(salt, 1));
        b.add("meat", "salmon_steak", SurvivalRegistry.SALMON_STEAK::get, 1, salmonid(), Cost.of(salt, 1));
        // fish from NirnCraft rods (any species; survival/fishing)
        b.add("meat", "grilled_fish", FishingRegistry.GRILLED_FISH::get, 1, fish(1), Cost.of(salt, 1));
        b.add("meat", "smoked_fish", FishingRegistry.SMOKED_FISH::get, 2, fish(2), Cost.of(salt, 1), Cost.of(() -> Items.CHARCOAL, 1));
        b.add("meat", "steamed_mudcrab_legs", FishingRegistry.STEAMED_MUDCRAB_LEGS::get, 1,
                Cost.of(() -> FishingRegistry.FISH_ITEMS.get("juvenile_mudcrab").get(), 2), Cost.of(SurvivalRegistry.GARLIC, 1));
        b.add("stews", "fish_stew", FishingRegistry.FISH_STEW::get, 1,
                fish(2), Cost.of(() -> Items.POTATO, 1), Cost.of(SurvivalRegistry.LEEK, 1), Cost.of(salt, 1));
        b.add("stews", "fish_chowder", FishingRegistry.FISH_CHOWDER::get, 1,
                fish(1), Cost.of(() -> Items.MILK_BUCKET, 1), Cost.of(() -> Items.POTATO, 1), Cost.of(salt, 1));
        b.add("meat", "grilled_leeks", SurvivalRegistry.GRILLED_LEEKS::get, 1, Cost.of(SurvivalRegistry.LEEK, 1), Cost.of(salt, 1));
        b.add("meat", "baked_potatoes", () -> Items.BAKED_POTATO, 1, Cost.of(() -> Items.POTATO, 1));

        // ---------------------------------------------------------------- baking & dairy
        b.add("baking", "sweetroll", SurvivalRegistry.SWEETROLL::get, 2,
                Cost.of(() -> Items.WHEAT, 2), Cost.of(() -> Items.SUGAR, 1), Cost.of(() -> Items.EGG, 1), Cost.of(() -> Items.HONEY_BOTTLE, 1));
        b.add("baking", "bread", () -> Items.BREAD, 1, Cost.of(() -> Items.WHEAT, 3));
        b.add("baking", "garlic_bread", SurvivalRegistry.GARLIC_BREAD::get, 2, Cost.of(() -> Items.BREAD, 1), Cost.of(SurvivalRegistry.GARLIC, 1));
        b.add("baking", "honey_nut_treat", SurvivalRegistry.HONEY_NUT_TREAT::get, 2,
                Cost.of(() -> Items.HONEY_BOTTLE, 1), Cost.of(() -> Items.PUMPKIN_SEEDS, 2));
        b.add("baking", "boiled_creme_treat", SurvivalRegistry.BOILED_CREME_TREAT::get, 2,
                Cost.of(() -> Items.MILK_BUCKET, 1), Cost.of(() -> Items.EGG, 1), Cost.of(() -> Items.SUGAR, 1));
        b.add("baking", "cheese_wheel", SurvivalRegistry.CHEESE_WHEEL::get, 1, Cost.of(() -> Items.MILK_BUCKET, 2), Cost.of(salt, 1));
        b.add("baking", "eidar_cheese_wedge", SurvivalRegistry.EIDAR_CHEESE_WEDGE::get, 4, Cost.of(SurvivalRegistry.CHEESE_WHEEL, 1));
        b.add("baking", "goat_cheese_wedge", SurvivalRegistry.GOAT_CHEESE_WEDGE::get, 2, Cost.of(() -> Items.MILK_BUCKET, 1), Cost.of(salt, 1));
        b.add("baking", "sliced_goat_cheese", SurvivalRegistry.SLICED_GOAT_CHEESE::get, 2, Cost.of(SurvivalRegistry.GOAT_CHEESE_WEDGE, 1));

        // ---------------------------------------------------------------- drinks
        b.add("drinks", "nord_mead", SurvivalRegistry.NORD_MEAD::get, 1,
                Cost.of(() -> Items.HONEY_BOTTLE, 2), Cost.of(() -> Items.WHEAT, 1), Cost.of(() -> Items.GLASS_BOTTLE, 1));
        b.add("drinks", "honningbrew_mead", SurvivalRegistry.HONNINGBREW_MEAD::get, 1,
                Cost.of(SurvivalRegistry.NORD_MEAD, 1), Cost.of(() -> Items.HONEYCOMB, 1));
        b.add("drinks", "black_briar_mead", SurvivalRegistry.BLACK_BRIAR_MEAD::get, 1,
                Cost.of(SurvivalRegistry.NORD_MEAD, 1), Cost.of(() -> Items.SWEET_BERRIES, 2));
        b.add("drinks", "ale", SurvivalRegistry.ALE::get, 1, Cost.of(() -> Items.WHEAT, 3), Cost.of(() -> Items.GLASS_BOTTLE, 1));
        b.add("drinks", "alto_wine", SurvivalRegistry.ALTO_WINE::get, 1,
                Cost.of(() -> Items.SWEET_BERRIES, 4), Cost.of(() -> Items.SUGAR, 1), Cost.of(() -> Items.GLASS_BOTTLE, 1));
        b.add("drinks", "spiced_wine", SurvivalRegistry.SPICED_WINE::get, 1,
                Cost.of(SurvivalRegistry.ALTO_WINE, 1), Cost.of(() -> Items.SUGAR, 1), Cost.of(() -> Items.COCOA_BEANS, 1));

        cats.replaceAll((k, v) -> Collections.unmodifiableList(v));
        byCategory = Collections.unmodifiableMap(cats);
        byId = Collections.unmodifiableMap(ids);
    }

    /** Any caught fish ({@code #nirncraft:fish}, vanilla cod and salmon too). */
    private static Cost fish(int count) {
        return new Cost(stack -> stack.is(FishingRegistry.FISH) || stack.is(Items.COD) || stack.is(Items.SALMON),
                () -> new ItemStack(FishingRegistry.FISH_ITEMS.get("carp").get()), count);
    }

    /** Salmon, char, trout or grayling (and vanilla salmon). */
    private static Cost salmonid() {
        return new Cost(stack -> stack.is(FishingRegistry.SALMONID) || stack.is(Items.SALMON),
                () -> new ItemStack(FishingRegistry.FISH_ITEMS.get("salmon").get()), 1);
    }

    public static Map<String, List<CookingRecipe>> byCategory() {
        build();
        return byCategory;
    }

    public static CookingRecipe get(String id) {
        build();
        return byId.get(id);
    }

    private record Builder(Map<String, List<CookingRecipe>> cats, Map<String, CookingRecipe> ids) {
        void add(String category, String id, Supplier<? extends ItemLike> result, int count, Cost... costs) {
            CookingRecipe r = new CookingRecipe(id, category, result, count, List.of(costs));
            cats.get(category).add(r);
            ids.put(id, r);
        }
    }
}
