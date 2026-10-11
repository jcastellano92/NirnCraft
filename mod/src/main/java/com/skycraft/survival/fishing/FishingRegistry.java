package com.skycraft.survival.fishing;

import com.skycraft.Skycraft;
import com.skycraft.crafting.arcane.alchemy.Ingredients;
import com.skycraft.survival.item.FoodSpec;
import com.skycraft.survival.item.SkyFoodItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Items of Skycraft fishing: the rods, bait, every {@link FishSpecies} and the fish dishes. */
public final class FishingRegistry {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Skycraft.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Skycraft.MODID);

    /** The bobber of Skycraft rods (rendered like the vanilla one). */
    public static final RegistryObject<EntityType<SkyFishingHook>> HOOK = ENTITIES.register("fishing_hook",
            () -> EntityType.Builder.<SkyFishingHook>of(SkyFishingHook::new, MobCategory.MISC).noSave().noSummon()
                    .sized(0.25f, 0.25f).clientTrackingRange(4).updateInterval(5).build(Skycraft.MODID + ":fishing_hook"));

    /** Every fish (raw catch). */
    public static final TagKey<Item> FISH = TagKey.create(ForgeRegistries.Keys.ITEMS, new ResourceLocation(Skycraft.MODID, "fish"));
    /** Salmon, char, trout and grayling: what a salmon steak can be cut from. */
    public static final TagKey<Item> SALMONID = TagKey.create(ForgeRegistries.Keys.ITEMS, new ResourceLocation(Skycraft.MODID, "fish/salmonid"));

    public static final RegistryObject<Item> FISHING_ROD = ITEMS.register("fishing_rod",
            () -> new SkyFishingRodItem(SkyFishingRodItem.Tier.COMMON, new Item.Properties().durability(96)));
    public static final RegistryObject<Item> FINE_FISHING_ROD = ITEMS.register("fine_fishing_rod",
            () -> new SkyFishingRodItem(SkyFishingRodItem.Tier.FINE, new Item.Properties().durability(192)));
    public static final RegistryObject<Item> DWARVEN_FISHING_ROD = ITEMS.register("dwarven_fishing_rod",
            () -> new SkyFishingRodItem(SkyFishingRodItem.Tier.DWARVEN, new Item.Properties().durability(512).rarity(Rarity.UNCOMMON)));
    public static final RegistryObject<Item> BAIT = ITEMS.register("fishing_bait", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> GLOW_BAIT = ITEMS.register("glow_bait", () -> new Item(new Item.Properties().rarity(Rarity.UNCOMMON)));

    public static final Map<String, RegistryObject<Item>> FISH_ITEMS = new LinkedHashMap<>();

    static {
        for (FishSpecies s : FishSpecies.all()) {
            FISH_ITEMS.put(s.id(), ITEMS.register(s.id(), () -> new FishItem(s, new Item.Properties()
                    .rarity(s.rarity() >= 2 ? Rarity.UNCOMMON : Rarity.COMMON))));
        }
    }

    public static final RegistryObject<Item> GRILLED_FISH = food("grilled_fish", FoodSpec.cooked(4, 0.5f, 10, 20), 64);
    public static final RegistryObject<Item> SMOKED_FISH = food("smoked_fish", FoodSpec.cooked(4, 0.6f, 5, 25), 64);
    public static final RegistryObject<Item> FISH_STEW = food("fish_stew", FoodSpec.stew(6, 0.6f, 20, 40, 1), 16);
    public static final RegistryObject<Item> FISH_CHOWDER = food("fish_chowder", FoodSpec.stew(6, 0.7f, 20, 40, 1), 16);
    public static final RegistryObject<Item> STEAMED_MUDCRAB_LEGS = food("steamed_mudcrab_legs", FoodSpec.cooked(3, 0.4f, 8, 15), 64);

    private FishingRegistry() {}

    private static RegistryObject<Item> food(String id, FoodSpec spec, int stack) {
        return ITEMS.register(id, () -> new SkyFoodItem(spec, true, new Item.Properties().stacksTo(stack)));
    }

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
        ENTITIES.register(modBus);
        // every fish is an alchemy ingredient (keyed by id, so nothing to wait for)
        for (FishSpecies s : FishSpecies.all()) {
            var e = s.effects();
            Ingredients.register(Skycraft.MODID + ":" + s.id(), e[0], e[1], e[2], e[3]);
        }
    }
}
