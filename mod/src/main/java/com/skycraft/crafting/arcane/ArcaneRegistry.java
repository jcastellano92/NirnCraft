package com.skycraft.crafting.arcane;

import com.skycraft.Skycraft;
import com.skycraft.crafting.arcane.block.IngredientPlantBlock;
import com.skycraft.crafting.arcane.block.StationBlock;
import com.skycraft.crafting.arcane.item.SoulGemItem;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/** Blocks and items of the arcane sub-module (soul gems, crafting stations, alchemy ingredients and plants). */
public final class ArcaneRegistry {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Skycraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Skycraft.MODID);

    /** Every alchemy ingredient can be eaten (Skyrim style) to learn its first effect. */
    public static final FoodProperties INGREDIENT_FOOD = new FoodProperties.Builder().nutrition(1).saturationMod(0.1f).alwaysEat().fast().build();

    // ---------------------------------------------------------------- stations
    public static final RegistryObject<Block> ARCANE_ENCHANTER = BLOCKS.register("arcane_enchanter",
            () -> new StationBlock(StationBlock.Kind.ENCHANTER, BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE)
                    .strength(3.5f, 1200f).sound(SoundType.WOOD).noOcclusion().lightLevel(s -> 7)));
    public static final RegistryObject<Block> ALCHEMY_LAB = BLOCKS.register("alchemy_lab",
            () -> new StationBlock(StationBlock.Kind.ALCHEMY, BlockBehaviour.Properties.of().mapColor(MapColor.WOOD)
                    .strength(2.5f, 6f).sound(SoundType.WOOD).noOcclusion().lightLevel(s -> 3)));

    // ---------------------------------------------------------------- wild alchemy plants
    public static final RegistryObject<Block> NIGHTSHADE = BLOCKS.register("nightshade",
            () -> new IngredientPlantBlock(MobEffects.POISON, 10, false, BlockBehaviour.Properties.copy(Blocks.POPPY)));
    public static final RegistryObject<Block> DEATHBELL = BLOCKS.register("deathbell",
            () -> new IngredientPlantBlock(MobEffects.WITHER, 6, false, BlockBehaviour.Properties.copy(Blocks.POPPY)));
    public static final RegistryObject<Block> NIRNROOT = BLOCKS.register("nirnroot",
            () -> new IngredientPlantBlock(MobEffects.INVISIBILITY, 8, true, BlockBehaviour.Properties.copy(Blocks.POPPY)
                    .lightLevel(s -> s.getValue(IngredientPlantBlock.HARVESTED) ? 0 : 6)));
    public static final RegistryObject<Block> FROST_MIRRIAM = BLOCKS.register("frost_mirriam",
            () -> new IngredientPlantBlock(MobEffects.FIRE_RESISTANCE, 8, false, BlockBehaviour.Properties.copy(Blocks.POPPY)));

    public static final RegistryObject<Item> ARCANE_ENCHANTER_ITEM = ITEMS.register("arcane_enchanter",
            () -> new BlockItem(ARCANE_ENCHANTER.get(), new Item.Properties().rarity(Rarity.UNCOMMON)));
    public static final RegistryObject<Item> ALCHEMY_LAB_ITEM = ITEMS.register("alchemy_lab",
            () -> new BlockItem(ALCHEMY_LAB.get(), new Item.Properties()));
    public static final RegistryObject<Item> NIGHTSHADE_ITEM = plantItem("nightshade", NIGHTSHADE);
    public static final RegistryObject<Item> DEATHBELL_ITEM = plantItem("deathbell", DEATHBELL);
    public static final RegistryObject<Item> NIRNROOT_ITEM = ITEMS.register("nirnroot",
            () -> new BlockItem(NIRNROOT.get(), new Item.Properties().food(INGREDIENT_FOOD).rarity(Rarity.UNCOMMON)));
    public static final RegistryObject<Item> FROST_MIRRIAM_ITEM = plantItem("frost_mirriam", FROST_MIRRIAM);

    // ---------------------------------------------------------------- soul gems
    public static final RegistryObject<Item> SOUL_GEM_PETTY = gem("soul_gem_petty", 1, false, 10, Rarity.COMMON);
    public static final RegistryObject<Item> SOUL_GEM_LESSER = gem("soul_gem_lesser", 2, false, 25, Rarity.COMMON);
    public static final RegistryObject<Item> SOUL_GEM_COMMON = gem("soul_gem_common", 3, false, 50, Rarity.COMMON);
    public static final RegistryObject<Item> SOUL_GEM_GREATER = gem("soul_gem_greater", 4, false, 100, Rarity.UNCOMMON);
    public static final RegistryObject<Item> SOUL_GEM_GRAND = gem("soul_gem_grand", 5, false, 200, Rarity.UNCOMMON);
    public static final RegistryObject<Item> SOUL_GEM_BLACK = gem("soul_gem_black", 5, true, 250, Rarity.RARE);
    public static final RegistryObject<Item> AZURAS_STAR = ITEMS.register("azuras_star",
            () -> new SoulGemItem(5, false, true, 1500, new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant()));

    // ---------------------------------------------------------------- ingredients
    public static final RegistryObject<Item> SALT_PILE = ingredient("salt_pile");
    public static final RegistryObject<Item> VOID_SALTS = ingredient("void_salts");
    public static final RegistryObject<Item> FIRE_SALTS = ingredient("fire_salts");
    public static final RegistryObject<Item> FROST_SALTS = ingredient("frost_salts");
    public static final RegistryObject<Item> VAMPIRE_DUST = ingredient("vampire_dust");
    public static final RegistryObject<Item> BEAR_CLAWS = ingredient("bear_claws");
    public static final RegistryObject<Item> HAWK_FEATHER = ingredient("hawk_feather");

    /**
     * Skyrim's other ingredients plus the classic ingredients of Morrowind, Elsweyr and Black Marsh. Their effects are in
     * {@link com.skycraft.crafting.arcane.alchemy.Ingredients}; they come from flora loot tables, foraging, creature
     * loot, chests and province merchants. Icons are drawn by {@code tools/textures/ingredients.py}.
     */
    public static final List<String> MORE_INGREDIENTS = List.of(
            "canis_root", "creep_cluster", "elves_ear", "imp_stool", "jazbay_grapes", "juniper_berries", "namiras_rot",
            "white_cap", "glowing_mushroom", "bleeding_crown", "hanging_moss", "swamp_fungal_pod", "thistle_branch",
            "giant_lichen", "mora_tapinella", "poison_bloom", "emperor_parasol_moss", "burnt_spriggan_wood",
            "rock_warbler_egg", "pine_thrush_egg", "hawks_egg", "hawk_beak", "pearl", "small_pearl", "nordic_barnacle",
            "trama_root", "ashen_grass_pod", "ash_creep_cluster", "ash_hopper_jelly", "boar_tusk", "spawn_ash",
            "comberry", "marshmerrow", "saltrice", "hypha_facia", "bittergreen_petals", "gold_kanet", "scrib_jelly",
            "kwama_egg", "ash_yam", "moon_sugar", "hist_sap",
            "briar_heart", "falmer_ear", "spider_egg", "eye_of_sabre_cat", "powdered_mammoth_tusk", "wisp_wrappings",
            "dwarven_oil", "chaurus_hunter_antennae");

    static {
        for (String id : MORE_INGREDIENTS) ingredient(id);
    }

    private ArcaneRegistry() {}

    private static RegistryObject<Item> gem(String id, int capacity, boolean black, int value, Rarity rarity) {
        return ITEMS.register(id, () -> new SoulGemItem(capacity, black, false, value, new Item.Properties().stacksTo(16).rarity(rarity)));
    }

    private static RegistryObject<Item> ingredient(String id) {
        return ITEMS.register(id, () -> new Item(new Item.Properties().food(INGREDIENT_FOOD)));
    }

    private static RegistryObject<Item> plantItem(String id, RegistryObject<Block> block) {
        return ITEMS.register(id, () -> new BlockItem(block.get(), new Item.Properties().food(INGREDIENT_FOOD)));
    }

    /** All soul gems, smallest first. */
    public static List<RegistryObject<Item>> soulGems() {
        return List.of(SOUL_GEM_PETTY, SOUL_GEM_LESSER, SOUL_GEM_COMMON, SOUL_GEM_GREATER, SOUL_GEM_GRAND, SOUL_GEM_BLACK, AZURAS_STAR);
    }

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
    }
}
