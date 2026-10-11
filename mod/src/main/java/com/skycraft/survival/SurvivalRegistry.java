package com.skycraft.survival;

import com.skycraft.Skycraft;
import com.skycraft.survival.block.CheeseWheelBlock;
import com.skycraft.survival.block.CookingPotBlock;
import com.skycraft.survival.block.DepletedOreBlock;
import com.skycraft.survival.block.DepletedOreBlockEntity;
import com.skycraft.survival.block.ShrineBlock;
import com.skycraft.survival.block.SkyCropBlock;
import com.skycraft.survival.camping.BedrollBlock;
import com.skycraft.survival.camping.CampfireKitItem;
import com.skycraft.survival.camping.TentBlock;
import com.skycraft.survival.camping.TentKitItem;
import com.skycraft.survival.corpse.PlayerCorpseEntity;
import com.skycraft.survival.cooking.CookingMenu;
import com.skycraft.survival.item.FoodSpec;
import com.skycraft.survival.item.SkyFoodItem;
import com.skycraft.survival.world.WaysideShrineFeature;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemNameBlockItem;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;

/** Blocks, items, block entities, menus and world features of the survival module. */
public final class SurvivalRegistry {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Skycraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Skycraft.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Skycraft.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, Skycraft.MODID);
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(ForgeRegistries.FEATURES, Skycraft.MODID);
    public static final DeferredRegister<net.minecraft.world.entity.EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Skycraft.MODID);

    // ================================================================ blocks
    public static final RegistryObject<Block> COOKING_POT = BLOCKS.register("cooking_pot",
            () -> new CookingPotBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(2.0f, 6f)
                    .sound(SoundType.METAL).noOcclusion()));
    public static final RegistryObject<Block> CHEESE_WHEEL_BLOCK = BLOCKS.register("cheese_wheel",
            () -> new CheeseWheelBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(0.5f)
                    .sound(SoundType.WOOL).noOcclusion().pushReaction(PushReaction.DESTROY)));
    public static final RegistryObject<Block> DEPLETED_ORE = BLOCKS.register("depleted_ore",
            () -> new DepletedOreBlock(BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(-1.0f, 3600000.0f)
                    .noLootTable().randomTicks().sound(SoundType.STONE).pushReaction(PushReaction.BLOCK)));
    public static final Map<Divine, RegistryObject<Block>> SHRINES = new EnumMap<>(Divine.class);

    // camping (replaces the Comforts mod): bedroll, tent, campfire kit
    public static final RegistryObject<Block> BEDROLL = BLOCKS.register("bedroll",
            () -> new BedrollBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BROWN).strength(0.2f)
                    .sound(SoundType.WOOL).noOcclusion().pushReaction(PushReaction.DESTROY)));
    public static final RegistryObject<Block> TENT = BLOCKS.register("tent",
            () -> new TentBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BROWN).strength(0.6f)
                    .sound(SoundType.WOOL).noOcclusion().noLootTable().pushReaction(PushReaction.BLOCK)));

    // crops (the produce/seed items are below; suppliers resolve lazily)
    public static final RegistryObject<Block> CABBAGE_CROP = crop("cabbage_crop", "cabbage_seeds");
    public static final RegistryObject<Block> LEEK_CROP = crop("leek_crop", "leek_seeds");
    public static final RegistryObject<Block> TOMATO_CROP = crop("tomato_crop", "tomato_seeds");
    public static final RegistryObject<Block> GARLIC_CROP = crop("garlic_crop", "garlic");

    static {
        for (Divine d : Divine.values()) {
            SHRINES.put(d, BLOCKS.register(d.shrineId(), () -> new ShrineBlock(d, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE).strength(2.5f, 6f).sound(SoundType.STONE).noOcclusion().lightLevel(s -> 7))));
        }
    }

    public static final RegistryObject<BlockEntityType<DepletedOreBlockEntity>> DEPLETED_ORE_ENTITY = BLOCK_ENTITIES.register("depleted_ore",
            () -> BlockEntityType.Builder.of(DepletedOreBlockEntity::new, DEPLETED_ORE.get()).build(null));

    public static final RegistryObject<MenuType<CookingMenu>> COOKING_MENU = MENUS.register("cooking_pot",
            () -> IForgeMenuType.create(CookingMenu::fromNetwork));

    public static final RegistryObject<Feature<NoneFeatureConfiguration>> WAYSIDE_SHRINE = FEATURES.register("wayside_shrine",
            () -> new WaysideShrineFeature(NoneFeatureConfiguration.CODEC));
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> NIRNROOT_PATCH = FEATURES.register("nirnroot_patch",
            () -> new com.skycraft.survival.world.NirnrootFeature(NoneFeatureConfiguration.CODEC));

    // ================================================================ items: blocks
    public static final RegistryObject<Item> COOKING_POT_ITEM = ITEMS.register("cooking_pot",
            () -> new BlockItem(COOKING_POT.get(), new Item.Properties()));
    public static final RegistryObject<Item> CHEESE_WHEEL = ITEMS.register("cheese_wheel",
            () -> new BlockItem(CHEESE_WHEEL_BLOCK.get(), new Item.Properties().stacksTo(16).food(FoodSpec.plain(6, 0.6f).properties(false))));

    static {
        for (Divine d : Divine.values()) {
            ITEMS.register(d.shrineId(), () -> new BlockItem(SHRINES.get(d).get(), new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));
        }
    }

    public static final RegistryObject<Item> BEDROLL_ITEM = ITEMS.register("bedroll",
            () -> new BedItem(BEDROLL.get(), new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> TENT_KIT = ITEMS.register("tent_kit",
            () -> new TentKitItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> CAMPFIRE_KIT = ITEMS.register("campfire_kit",
            () -> new CampfireKitItem(new Item.Properties().stacksTo(16)));

    // ================================================================ entities
    /** A dead player's body (replaces the Corpse mod). */
    public static final RegistryObject<net.minecraft.world.entity.EntityType<PlayerCorpseEntity>> PLAYER_CORPSE = ENTITIES.register("player_corpse",
            () -> net.minecraft.world.entity.EntityType.Builder.<PlayerCorpseEntity>of(PlayerCorpseEntity::new, net.minecraft.world.entity.MobCategory.MISC)
                    .sized(1.0f, 0.5f).fireImmune().clientTrackingRange(10).updateInterval(2).build(Skycraft.MODID + ":player_corpse"));

    // ================================================================ items: produce & seeds
    public static final RegistryObject<Item> CABBAGE = food("cabbage", FoodSpec.snack(2, 0.2f), false, 64);
    public static final RegistryObject<Item> LEEK = food("leek", FoodSpec.snack(2, 0.1f), false, 64);
    public static final RegistryObject<Item> TOMATO = food("tomato", FoodSpec.snack(2, 0.2f), false, 64);
    public static final RegistryObject<Item> GARLIC = ITEMS.register("garlic",
            () -> new ItemNameBlockItem(GARLIC_CROP.get(), new Item.Properties().food(FoodSpec.snack(1, 0.1f).properties(false))));
    public static final RegistryObject<Item> CABBAGE_SEEDS = seeds("cabbage_seeds", CABBAGE_CROP);
    public static final RegistryObject<Item> LEEK_SEEDS = seeds("leek_seeds", LEEK_CROP);
    public static final RegistryObject<Item> TOMATO_SEEDS = seeds("tomato_seeds", TOMATO_CROP);

    // ================================================================ items: meat (fauna drops the raw ones by id)
    public static final RegistryObject<Item> RAW_VENISON = food("raw_venison", FoodSpec.plain(2, 0.2f), true, 64);
    public static final RegistryObject<Item> RAW_HORKER_MEAT = food("raw_horker_meat", FoodSpec.plain(2, 0.2f), true, 64);
    public static final RegistryObject<Item> RAW_MAMMOTH_SNOUT = food("raw_mammoth_snout", FoodSpec.plain(3, 0.3f), true, 64);
    public static final RegistryObject<Item> RAW_GOAT_MEAT = food("raw_goat_meat", FoodSpec.plain(2, 0.2f), true, 64);
    public static final RegistryObject<Item> VENISON_CHOP = food("venison_chop", FoodSpec.cooked(4, 0.5f, 10, 20), true, 64);
    public static final RegistryObject<Item> HORKER_LOAF = food("horker_loaf", FoodSpec.cooked(4, 0.5f, 10, 20), true, 64);
    public static final RegistryObject<Item> MAMMOTH_STEAK = food("mammoth_steak", FoodSpec.cooked(6, 0.6f, 15, 30), true, 64);
    public static final RegistryObject<Item> LEG_OF_GOAT_ROAST = food("leg_of_goat_roast", FoodSpec.cooked(5, 0.5f, 12, 25), true, 64);
    public static final RegistryObject<Item> SALMON_STEAK = food("salmon_steak", FoodSpec.cooked(4, 0.5f, 10, 20), false, 64);
    public static final RegistryObject<Item> GRILLED_LEEKS = food("grilled_leeks", FoodSpec.cooked(3, 0.3f, 5, 10), false, 64);

    // ================================================================ items: stews & soups
    public static final RegistryObject<Item> APPLE_CABBAGE_STEW = food("apple_cabbage_stew", FoodSpec.stew(5, 0.6f, 15, 30, 1), false, 16);
    public static final RegistryObject<Item> BEEF_STEW = food("beef_stew", FoodSpec.stew(6, 0.6f, 20, 40, 1), false, 16);
    public static final RegistryObject<Item> VENISON_STEW = food("venison_stew", FoodSpec.stew(6, 0.6f, 20, 40, 1), false, 16);
    public static final RegistryObject<Item> HORKER_STEW = food("horker_stew", FoodSpec.stew(6, 0.6f, 20, 40, 1), false, 16);
    public static final RegistryObject<Item> VEGETABLE_SOUP = food("vegetable_soup", FoodSpec.stew(5, 0.5f, 15, 30, 1), false, 16);
    public static final RegistryObject<Item> TOMATO_SOUP = food("tomato_soup", FoodSpec.stew(5, 0.5f, 15, 30, 1), false, 16);
    public static final RegistryObject<Item> POTATO_SOUP = food("potato_soup", FoodSpec.stew(5, 0.5f, 15, 30, 1), false, 16);
    public static final RegistryObject<Item> ELSWEYR_FONDUE = food("elsweyr_fondue", FoodSpec.stew(6, 0.7f, 20, 40, 2), false, 16);

    // ================================================================ items: baked goods & dairy
    public static final RegistryObject<Item> SWEETROLL = food("sweetroll", FoodSpec.cooked(3, 0.3f, 5, 10), false, 64);
    public static final RegistryObject<Item> GARLIC_BREAD = food("garlic_bread", FoodSpec.cooked(4, 0.4f, 5, 15), false, 64);
    public static final RegistryObject<Item> HONEY_NUT_TREAT = food("honey_nut_treat", FoodSpec.cooked(3, 0.4f, 5, 15), false, 64);
    public static final RegistryObject<Item> BOILED_CREME_TREAT = food("boiled_creme_treat", FoodSpec.cooked(3, 0.4f, 5, 15), false, 64);
    public static final RegistryObject<Item> EIDAR_CHEESE_WEDGE = food("eidar_cheese_wedge", FoodSpec.snack(2, 0.3f), false, 64);
    public static final RegistryObject<Item> GOAT_CHEESE_WEDGE = food("goat_cheese_wedge", FoodSpec.snack(2, 0.3f), false, 64);
    public static final RegistryObject<Item> SLICED_GOAT_CHEESE = food("sliced_goat_cheese", FoodSpec.snack(1, 0.2f), false, 64);

    // ================================================================ items: drinks
    public static final RegistryObject<Item> NORD_MEAD = food("nord_mead", FoodSpec.alcohol(20, 10, 10), false, 16);
    public static final RegistryObject<Item> HONNINGBREW_MEAD = food("honningbrew_mead", FoodSpec.alcohol(30, 15, 10), false, 16);
    public static final RegistryObject<Item> BLACK_BRIAR_MEAD = food("black_briar_mead", FoodSpec.alcohol(35, 20, 12), false, 16);
    public static final RegistryObject<Item> ALE = food("ale", FoodSpec.alcohol(15, 10, 8), false, 16);
    public static final RegistryObject<Item> ALTO_WINE = food("alto_wine", FoodSpec.alcohol(20, 10, 10), false, 16);
    public static final RegistryObject<Item> SPICED_WINE = food("spiced_wine", FoodSpec.alcohol(25, 20, 10), false, 16);
    public static final RegistryObject<Item> SKOOMA = ITEMS.register("skooma",
            () -> new SkyFoodItem(FoodSpec.skoomaSpec(), false, new Item.Properties().stacksTo(16).rarity(Rarity.UNCOMMON)));

    private SurvivalRegistry() {}

    private static RegistryObject<Block> crop(String id, String seedId) {
        return BLOCKS.register(id, () -> new SkyCropBlock(() -> ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(Skycraft.MODID, seedId)),
                BlockBehaviour.Properties.copy(Blocks.WHEAT)));
    }

    private static RegistryObject<Item> seeds(String id, RegistryObject<Block> crop) {
        return ITEMS.register(id, () -> new ItemNameBlockItem(crop.get(), new Item.Properties()));
    }

    private static RegistryObject<Item> food(String id, FoodSpec spec, boolean meat, int stack) {
        return ITEMS.register(id, () -> new SkyFoodItem(spec, meat, new Item.Properties().stacksTo(stack)));
    }

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);
        FEATURES.register(modBus);
        ENTITIES.register(modBus);
    }
}
