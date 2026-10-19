package com.nirncraft.survival.fishing;

import com.nirncraft.NirnCraft;
import com.nirncraft.crafting.arcane.alchemy.Ingredients;
import com.nirncraft.survival.item.FoodSpec;
import com.nirncraft.survival.item.SkyFoodItem;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

import java.util.LinkedHashMap;
import java.util.Map;

/** Items of NirnCraft fishing: the rods, bait, every {@link FishSpecies} and the fish dishes. */
public final class FishingRegistry {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, NirnCraft.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, NirnCraft.MODID);

    /** The bobber of NirnCraft rods (rendered like the vanilla one). */
    public static final Supplier<EntityType<SkyFishingHook>> HOOK = ENTITIES.register("fishing_hook",
            () -> EntityType.Builder.<SkyFishingHook>of(SkyFishingHook::new, MobCategory.MISC).noSave().noSummon()
                    .sized(0.25f, 0.25f).clientTrackingRange(4).updateInterval(5).build(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "fishing_hook"))));

    /** Every fish (raw catch). */
    public static final TagKey<Item> FISH = TagKey.create(net.minecraft.core.registries.Registries.ITEM, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "fish"));
    /** Salmon, char, trout and grayling: what a salmon steak can be cut from. */
    public static final TagKey<Item> SALMONID = TagKey.create(net.minecraft.core.registries.Registries.ITEM, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "fish/salmonid"));

    public static final Supplier<Item> FISHING_ROD = ITEMS.register("fishing_rod",
            () -> new SkyFishingRodItem(SkyFishingRodItem.Tier.COMMON, com.nirncraft.platform.SkyProperties.item("fishing_rod").durability(96)));
    public static final Supplier<Item> FINE_FISHING_ROD = ITEMS.register("fine_fishing_rod",
            () -> new SkyFishingRodItem(SkyFishingRodItem.Tier.FINE, com.nirncraft.platform.SkyProperties.item("fine_fishing_rod").durability(192)));
    public static final Supplier<Item> DWARVEN_FISHING_ROD = ITEMS.register("dwarven_fishing_rod",
            () -> new SkyFishingRodItem(SkyFishingRodItem.Tier.DWARVEN, com.nirncraft.platform.SkyProperties.item("dwarven_fishing_rod").durability(512).rarity(Rarity.UNCOMMON)));
    public static final Supplier<Item> BAIT = ITEMS.register("fishing_bait", () -> new Item(com.nirncraft.platform.SkyProperties.item("fishing_bait")));
    public static final Supplier<Item> GLOW_BAIT = ITEMS.register("glow_bait", () -> new Item(com.nirncraft.platform.SkyProperties.item("glow_bait").rarity(Rarity.UNCOMMON)));

    public static final Map<String, Supplier<Item>> FISH_ITEMS = new LinkedHashMap<>();

    static {
        for (FishSpecies s : FishSpecies.all()) {
            FISH_ITEMS.put(s.id(), ITEMS.register(s.id(), () -> new FishItem(s, com.nirncraft.platform.SkyProperties.item(s.id())
                    .rarity(s.rarity() >= 2 ? Rarity.UNCOMMON : Rarity.COMMON))));
        }
    }

    public static final Supplier<Item> GRILLED_FISH = food("grilled_fish", FoodSpec.cooked(4, 0.5f, 10, 20), 64);
    public static final Supplier<Item> SMOKED_FISH = food("smoked_fish", FoodSpec.cooked(4, 0.6f, 5, 25), 64);
    public static final Supplier<Item> FISH_STEW = food("fish_stew", FoodSpec.stew(6, 0.6f, 20, 40, 1), 16);
    public static final Supplier<Item> FISH_CHOWDER = food("fish_chowder", FoodSpec.stew(6, 0.7f, 20, 40, 1), 16);
    public static final Supplier<Item> STEAMED_MUDCRAB_LEGS = food("steamed_mudcrab_legs", FoodSpec.cooked(3, 0.4f, 8, 15), 64);

    private FishingRegistry() {}

    private static Supplier<Item> food(String id, FoodSpec spec, int stack) {
        return ITEMS.register(id, () -> new SkyFoodItem(spec, true, com.nirncraft.platform.SkyProperties.item(id).stacksTo(stack)));
    }

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
        ENTITIES.register(modBus);
        // every fish is an alchemy ingredient (keyed by id, so nothing to wait for)
        for (FishSpecies s : FishSpecies.all()) {
            var e = s.effects();
            Ingredients.register(NirnCraft.MODID + ":" + s.id(), e[0], e[1], e[2], e[3]);
        }
    }
}
