package com.nirncraft.crafting.arcane;

import com.nirncraft.NirnCraft;
import com.nirncraft.crafting.arcane.block.IngredientPlantBlock;
import com.nirncraft.crafting.arcane.block.StationBlock;
import com.nirncraft.crafting.arcane.item.SoulGemItem;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Blocks and items of the arcane sub-module (soul gems, crafting stations, alchemy ingredients and plants). */
public final class ArcaneRegistry {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(BuiltInRegistries.BLOCK, NirnCraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, NirnCraft.MODID);

    /** Every alchemy ingredient can be eaten (Skyrim style) to learn its first effect. */
    public static final FoodProperties INGREDIENT_FOOD = new FoodProperties.Builder().nutrition(1).saturationModifier(0.1f).alwaysEdible().build();

    /** All registered 3D harvestable alchemy flora blocks keyed by block ID. */
    public static final Map<String, Supplier<Block>> FLORA_BLOCKS = new LinkedHashMap<>();

    // ---------------------------------------------------------------- stations
    public static final Supplier<Block> ARCANE_ENCHANTER = BLOCKS.register("arcane_enchanter",
            () -> new StationBlock(StationBlock.Kind.ENCHANTER, com.nirncraft.platform.SkyProperties.block("arcane_enchanter").mapColor(MapColor.COLOR_PURPLE)
                    .strength(3.5f, 1200f).sound(SoundType.WOOD).noOcclusion().lightLevel(s -> 7)));
    public static final Supplier<Block> ALCHEMY_LAB = BLOCKS.register("alchemy_lab",
            () -> new StationBlock(StationBlock.Kind.ALCHEMY, com.nirncraft.platform.SkyProperties.block("alchemy_lab").mapColor(MapColor.WOOD)
                    .strength(2.5f, 6f).sound(SoundType.WOOD).noOcclusion().lightLevel(s -> 3)));

    // ---------------------------------------------------------------- wild alchemy plants
    public static final Supplier<Block> NIGHTSHADE = registerFloraBlock("nightshade",
            () -> new IngredientPlantBlock(MobEffects.POISON, 10, false, com.nirncraft.platform.SkyProperties.block("nightshade", BlockBehaviour.Properties.ofFullCopy(Blocks.POPPY))
                    .offsetType(BlockBehaviour.OffsetType.XZ)));
    public static final Supplier<Block> DEATHBELL = registerFloraBlock("deathbell",
            () -> new IngredientPlantBlock(MobEffects.WITHER, 6, false, com.nirncraft.platform.SkyProperties.block("deathbell", BlockBehaviour.Properties.ofFullCopy(Blocks.POPPY))
                    .offsetType(BlockBehaviour.OffsetType.XZ)));
    public static final Supplier<Block> NIRNROOT = registerFloraBlock("nirnroot",
            () -> new IngredientPlantBlock(MobEffects.INVISIBILITY, 8, true, com.nirncraft.platform.SkyProperties.block("nirnroot", BlockBehaviour.Properties.ofFullCopy(Blocks.POPPY))
                    .offsetType(BlockBehaviour.OffsetType.XZ)
                    .lightLevel(s -> s.getValue(IngredientPlantBlock.HARVESTED) ? 0 : 6)));
    public static final Supplier<Block> FROST_MIRRIAM = registerFloraBlock("frost_mirriam",
            () -> new IngredientPlantBlock(MobEffects.FIRE_RESISTANCE, 8, false, com.nirncraft.platform.SkyProperties.block("frost_mirriam", BlockBehaviour.Properties.ofFullCopy(Blocks.POPPY))
                    .offsetType(BlockBehaviour.OffsetType.XZ)));

    public static final Supplier<Item> ARCANE_ENCHANTER_ITEM = ITEMS.register("arcane_enchanter",
            () -> new BlockItem(ARCANE_ENCHANTER.get(), com.nirncraft.platform.SkyProperties.item("arcane_enchanter").rarity(Rarity.UNCOMMON)));
    public static final Supplier<Item> ALCHEMY_LAB_ITEM = ITEMS.register("alchemy_lab",
            () -> new BlockItem(ALCHEMY_LAB.get(), com.nirncraft.platform.SkyProperties.item("alchemy_lab")));
    public static final Supplier<Item> NIGHTSHADE_ITEM = plantItem("nightshade", NIGHTSHADE);
    public static final Supplier<Item> DEATHBELL_ITEM = plantItem("deathbell", DEATHBELL);
    public static final Supplier<Item> NIRNROOT_ITEM = ITEMS.register("nirnroot",
            () -> new BlockItem(NIRNROOT.get(), com.nirncraft.platform.SkyProperties.item("nirnroot").food(INGREDIENT_FOOD, net.minecraft.world.item.component.Consumables.defaultFood().consumeSeconds(0.8f).build()).rarity(Rarity.UNCOMMON)));
    public static final Supplier<Item> FROST_MIRRIAM_ITEM = plantItem("frost_mirriam", FROST_MIRRIAM);

    // ---------------------------------------------------------------- soul gems
    public static final Supplier<Item> SOUL_GEM_PETTY = gem("soul_gem_petty", 1, false, 10, Rarity.COMMON);
    public static final Supplier<Item> SOUL_GEM_LESSER = gem("soul_gem_lesser", 2, false, 25, Rarity.COMMON);
    public static final Supplier<Item> SOUL_GEM_COMMON = gem("soul_gem_common", 3, false, 50, Rarity.COMMON);
    public static final Supplier<Item> SOUL_GEM_GREATER = gem("soul_gem_greater", 4, false, 100, Rarity.UNCOMMON);
    public static final Supplier<Item> SOUL_GEM_GRAND = gem("soul_gem_grand", 5, false, 200, Rarity.UNCOMMON);
    public static final Supplier<Item> SOUL_GEM_BLACK = gem("soul_gem_black", 5, true, 250, Rarity.RARE);
    public static final Supplier<Item> AZURAS_STAR = ITEMS.register("azuras_star",
            () -> new SoulGemItem(5, false, true, 1500, com.nirncraft.platform.SkyProperties.item("azuras_star").stacksTo(1).rarity(Rarity.EPIC).fireResistant()));

    // ---------------------------------------------------------------- ingredients
    public static final Supplier<Item> SALT_PILE = ingredient("salt_pile");
    public static final Supplier<Item> VOID_SALTS = ingredient("void_salts");
    public static final Supplier<Item> FIRE_SALTS = ingredient("fire_salts");
    public static final Supplier<Item> FROST_SALTS = ingredient("frost_salts");
    public static final Supplier<Item> VAMPIRE_DUST = ingredient("vampire_dust");
    public static final Supplier<Item> BEAR_CLAWS = ingredient("bear_claws");
    public static final Supplier<Item> HAWK_FEATHER = ingredient("hawk_feather");

    /**
     * Skyrim's other ingredients plus the classic ingredients of Morrowind, Elsweyr and Black Marsh. Their effects are in
     * {@link com.nirncraft.crafting.arcane.alchemy.Ingredients}; they come from 3D world flora blocks, foraging, creature
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

    /**
     * Regional 3D botanical and fungal specimens placed across Tamriel's surface biomes and subterranean caverns whose
     * harvest yields the corresponding regional alchemy ingredient.
     */
    private static final Map<String, String> REGIONAL_FLORA_DROPS = Map.ofEntries(
            Map.entry("crimson_nirnroot", "nirnroot"),
            Map.entry("blisterwort", "imp_stool"),
            Map.entry("fly_amanita", "mora_tapinella"),
            Map.entry("scaly_pholiota", "mora_tapinella"),
            Map.entry("rot_scale_fungus", "namiras_rot"),
            Map.entry("kresh_fiber", "marshmerrow"),
            Map.entry("coda_flower", "bittergreen_petals"),
            Map.entry("hackle_lo", "ash_yam"),
            Map.entry("desert_rose", "gold_kanet"),
            Map.entry("cactus_pulp", "moon_sugar"),
            Map.entry("hist_carp", "hist_sap"),
            Map.entry("bog_beacon", "swamp_fungal_pod"),
            Map.entry("luminous_russula", "glowing_mushroom"),
            Map.entry("snowberries", "juniper_berries")
    );

    private static final Set<String> FUNGI_IDS = Set.of(
            "imp_stool", "namiras_rot", "white_cap", "glowing_mushroom", "bleeding_crown", "swamp_fungal_pod",
            "giant_lichen", "mora_tapinella", "hypha_facia", "blisterwort", "fly_amanita", "scaly_pholiota",
            "rot_scale_fungus", "bog_beacon", "luminous_russula"
    );

    private static final Set<String> CEILING_FLORA_IDS = Set.of(
            "hanging_moss", "emperor_parasol_moss", "wisp_wrappings"
    );

    static {
        for (String id : MORE_INGREDIENTS) {
            registerMoreIngredientFlora(id);
            ingredient(id);
        }
        // (sorted: Map.ofEntries iterates in an order salted per JVM launch, which numbered these 14 blocks differently at every
        // launch and made every cache keyed by block ids (the atlas materials, the self-baking cache) unreadable on the next)
        for (Map.Entry<String, String> entry : new java.util.TreeMap<>(REGIONAL_FLORA_DROPS).entrySet()) {
            registerRegionalFloraBlock(entry.getKey(), entry.getValue());
        }
    }

    private ArcaneRegistry() {}

    private static Supplier<Block> registerFloraBlock(String id, Supplier<Block> factory) {
        Supplier<Block> ro = BLOCKS.register(id, factory);
        FLORA_BLOCKS.put(id, ro);
        return ro;
    }

    private static void registerMoreIngredientFlora(String id) {
        int light = floraLightLevel(id);
        boolean glowing = "crimson_nirnroot".equals(id);
        boolean ceiling = CEILING_FLORA_IDS.contains(id);
        SoundType sound = FUNGI_IDS.contains(id) ? SoundType.FUNGUS : SoundType.GRASS;
        net.minecraft.core.Holder<MobEffect> effect = FUNGI_IDS.contains(id) ? MobEffects.NIGHT_VISION : MobEffects.REGENERATION;
        registerFloraBlock(id, () -> new IngredientPlantBlock(
                effect, 6, glowing, ceiling,
                () -> lookupItem(id),
                com.nirncraft.platform.SkyProperties.block(id, BlockBehaviour.Properties.ofFullCopy(Blocks.POPPY)).sound(sound)
                        .offsetType(BlockBehaviour.OffsetType.XZ)
                        .lightLevel(s -> s.getValue(IngredientPlantBlock.HARVESTED) ? 0 : light)));
    }

    private static void registerRegionalFloraBlock(String blockId, String dropItemId) {
        int light = floraLightLevel(blockId);
        boolean glowing = "crimson_nirnroot".equals(blockId);
        boolean ceiling = CEILING_FLORA_IDS.contains(blockId);
        SoundType sound = FUNGI_IDS.contains(blockId) ? SoundType.FUNGUS : SoundType.GRASS;
        net.minecraft.core.Holder<MobEffect> effect = glowing ? MobEffects.INVISIBILITY : (FUNGI_IDS.contains(blockId) ? MobEffects.NIGHT_VISION : MobEffects.REGENERATION);
        registerFloraBlock(blockId, () -> new IngredientPlantBlock(
                effect, 7, glowing, ceiling,
                () -> lookupItem(dropItemId),
                com.nirncraft.platform.SkyProperties.block(blockId, BlockBehaviour.Properties.ofFullCopy(Blocks.POPPY)).sound(sound)
                        .offsetType(BlockBehaviour.OffsetType.XZ)
                        .lightLevel(s -> s.getValue(IngredientPlantBlock.HARVESTED) ? 0 : light)));
    }

    private static int floraLightLevel(String id) {
        return switch (id) {
            case "glowing_mushroom" -> 8;
            case "crimson_nirnroot", "luminous_russula" -> 7;
            case "nirnroot", "bog_beacon" -> 6;
            case "wisp_wrappings", "poison_bloom" -> 5;
            case "emperor_parasol_moss", "hist_sap", "hist_carp" -> 4;
            default -> 0;
        };
    }

    private static Item lookupItem(String id) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(NirnCraft.MODID, id));
        return item != null && item != Items.AIR ? item : NIRNROOT_ITEM.get();
    }

    /** Returns a registered 3D alchemy plant block by ID, defaulting to {@link #NIGHTSHADE} if unknown. */
    public static Block floraBlock(String id) {
        Supplier<Block> ro = FLORA_BLOCKS.get(id);
        return ro != null ? ro.get() : NIGHTSHADE.get();
    }

    /** Returns the unharvested default state of a registered 3D alchemy plant block by ID. */
    public static BlockState floraState(String id) {
        Block b = floraBlock(id);
        BlockState state = b.defaultBlockState();
        return state.hasProperty(IngredientPlantBlock.HARVESTED)
                ? state.setValue(IngredientPlantBlock.HARVESTED, false)
                : state;
    }

    /** Returns true if the given block is one of the cave/marsh fungi species. */
    public static boolean isFungusBlock(Block block) {
        Identifier key = BuiltInRegistries.BLOCK.getKey(block);
        return key != null && FUNGI_IDS.contains(key.getPath());
    }

    private static Supplier<Item> gem(String id, int capacity, boolean black, int value, Rarity rarity) {
        return ITEMS.register(id, () -> new SoulGemItem(capacity, black, false, value, com.nirncraft.platform.SkyProperties.item(id).stacksTo(16).rarity(rarity)));
    }

    private static Supplier<Item> ingredient(String id) {
        Supplier<Block> plant = FLORA_BLOCKS.get(id);
        if (plant != null) {
            return ITEMS.register(id, () -> new BlockItem(plant.get(), com.nirncraft.platform.SkyProperties.item(id).food(INGREDIENT_FOOD, net.minecraft.world.item.component.Consumables.defaultFood().consumeSeconds(0.8f).build())));
        }
        return ITEMS.register(id, () -> new Item(com.nirncraft.platform.SkyProperties.item(id).food(INGREDIENT_FOOD, net.minecraft.world.item.component.Consumables.defaultFood().consumeSeconds(0.8f).build())));
    }

    private static Supplier<Item> plantItem(String id, Supplier<Block> block) {
        return ITEMS.register(id, () -> new BlockItem(block.get(), com.nirncraft.platform.SkyProperties.item(id).food(INGREDIENT_FOOD, net.minecraft.world.item.component.Consumables.defaultFood().consumeSeconds(0.8f).build())));
    }

    /** All soul gems, smallest first. */
    public static List<Supplier<Item>> soulGems() {
        return List.of(SOUL_GEM_PETTY, SOUL_GEM_LESSER, SOUL_GEM_COMMON, SOUL_GEM_GREATER, SOUL_GEM_GRAND, SOUL_GEM_BLACK, AZURAS_STAR);
    }

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
    }
}
