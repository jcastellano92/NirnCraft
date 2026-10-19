package com.nirncraft.crafting.arcane.alchemy;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.nirncraft.crafting.arcane.alchemy.AlchemyEffect.*;

/**
 * The ingredient table: every alchemy ingredient and its four effects, Skyrim style. Ingredients are keyed by item
 * registry id so items owned by other modules (or optional mods) need no compile dependency; missing items are
 * simply never matched.
 */
public final class Ingredients {
    /** An alchemy ingredient: item id plus exactly four effects (effect index 0..3 is used by the knowledge bitmask). */
    public record Ingredient(String id, AlchemyEffect[] effects) {
        public int indexOf(AlchemyEffect effect) {
            for (int i = 0; i < effects.length; i++) if (effects[i] == effect) return i;
            return -1;
        }

        public boolean has(AlchemyEffect effect) {
            return indexOf(effect) >= 0;
        }

        /** The item, or {@code null} if it isn't registered (e.g. an optional mod is absent). */
        @Nullable
        public Item item() {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
            return item == null || item == Items.AIR ? null : item;
        }
    }

    private static final Map<String, Ingredient> BY_ID = new LinkedHashMap<>();

    static {
        // Skyrim's own ingredients carry Skyrim's four effects in Skyrim's order. Vanilla plants stand in for Skyrim's
        // flora (the biome catalogue places them that way: cornflower = Blue Mountain Flower, azure bluet = Tundra
        // Cotton, red mushroom = Fly Amanita...). Ingredients new to Skyrim get lore-based effects, after their
        // Morrowind/Oblivion/ESO properties translated into Skyrim's effect list.

        // ------------------------------------------------------------ vanilla plants & fungi (Skyrim stand-ins)
        add("minecraft:red_mushroom", RESIST_FIRE, FORTIFY_TWO_HANDED, FRENZY, REGENERATE_STAMINA);          // Fly Amanita
        add("minecraft:brown_mushroom", DAMAGE_STAMINA, FRENZY, RESTORE_HEALTH, FORTIFY_SMITHING);           // Blisterwort
        add("minecraft:crimson_fungus", FRENZY, RESIST_FIRE, DAMAGE_MAGICKA, FORTIFY_ONE_HANDED);
        add("minecraft:warped_fungus", RESTORE_MAGICKA, FEAR, FORTIFY_ALTERATION, DAMAGE_STAMINA_REGEN);
        add("minecraft:glow_berries", NIGHT_EYE, RESTORE_MAGICKA, FORTIFY_ILLUSION, REGENERATE_MAGICKA);
        add("minecraft:sweet_berries", RESIST_FIRE, FORTIFY_ENCHANTING, RESIST_FROST, RESIST_SHOCK);         // Snowberries
        add("minecraft:dandelion", RESIST_POISON, FORTIFY_RESTORATION, FORTIFY_HEALTH, DAMAGE_STAMINA_REGEN); // Yellow Mountain Flower
        add("minecraft:poppy", RESTORE_MAGICKA, RAVAGE_MAGICKA, FORTIFY_MAGICKA, DAMAGE_HEALTH);             // Red Mountain Flower
        add("minecraft:blue_orchid", RESIST_MAGIC, FEAR, REGENERATE_HEALTH, PARALYSIS);                     // Gleamblossom
        add("minecraft:allium", RESTORE_STAMINA, FORTIFY_SNEAK, LINGERING_DAMAGE_MAGICKA, RESIST_FROST);     // Purple Mountain Flower
        add("minecraft:azure_bluet", RESIST_MAGIC, FORTIFY_MAGICKA, FORTIFY_BLOCK, FORTIFY_BARTER);          // Tundra Cotton
        add("minecraft:cornflower", RESTORE_HEALTH, FORTIFY_CONJURATION, FORTIFY_HEALTH, DAMAGE_MAGICKA_REGEN); // Blue Mountain Flower
        add("minecraft:lily_of_the_valley", DAMAGE_HEALTH, SLOW, RESIST_POISON, FORTIFY_RESTORATION);
        add("minecraft:wither_rose", RAVAGE_HEALTH, DAMAGE_HEALTH, FEAR, WEAKNESS_TO_MAGIC);
        add("minecraft:oxeye_daisy", RESTORE_HEALTH, CURE_DISEASE, REGENERATE_HEALTH, FORTIFY_LIGHT_ARMOR);
        add("minecraft:orange_tulip", RESIST_FIRE, FORTIFY_BARTER, FORTIFY_ILLUSION, FORTIFY_TWO_HANDED);    // Dragon's Tongue
        add("minecraft:lilac", RESIST_MAGIC, FORTIFY_STAMINA, RAVAGE_MAGICKA, FORTIFY_CONJURATION);          // Lavender
        add("minecraft:white_tulip", WEAKNESS_TO_SHOCK, FORTIFY_ONE_HANDED, FORTIFY_MAGICKA, FORTIFY_ENCHANTING); // Stoneflower
        add("minecraft:red_tulip", DAMAGE_HEALTH, RESIST_FIRE, RESIST_MAGIC, PARALYSIS);                     // Fire Petal
        add("minecraft:pink_tulip", FORTIFY_BARTER, FORTIFY_CARRY_WEIGHT, SLOW, RESTORE_STAMINA);            // Heather
        add("minecraft:sunflower", PARALYSIS, RAVAGE_HEALTH, WEAKNESS_TO_FROST, FORTIFY_SMITHING);           // Gold Kanet
        add("minecraft:wheat", RESTORE_HEALTH, FORTIFY_HEALTH, DAMAGE_STAMINA_REGEN, LINGERING_DAMAGE_MAGICKA);
        add("minecraft:egg", RESIST_MAGIC, DAMAGE_MAGICKA_REGEN, WATERBREATHING, LINGERING_DAMAGE_STAMINA);  // Chicken's Egg
        add("minecraft:torchflower", NIGHT_EYE, RESIST_FROST, FORTIFY_DESTRUCTION, REGENERATE_MAGICKA);
        add("minecraft:pitcher_plant", FORTIFY_ALCHEMY, RESTORE_MAGICKA, RESIST_POISON, FORTIFY_RESTORATION);
        add("minecraft:spore_blossom", FORTIFY_LOCKPICKING, FEATHERFALL, CURE_DISEASE, WEAKNESS_TO_SHOCK);
        add("minecraft:glow_lichen", WEAKNESS_TO_SHOCK, NIGHT_EYE, REGENERATE_MAGICKA, FORTIFY_ILLUSION);
        add("minecraft:chorus_fruit", FORTIFY_ALTERATION, INVISIBILITY, LEAP, DAMAGE_MAGICKA);
        add("minecraft:nether_wart", FORTIFY_ALCHEMY, RAVAGE_HEALTH, RESIST_FIRE, FORTIFY_CONJURATION);
        add("minecraft:kelp", WATERBREATHING, RESTORE_STAMINA, CURE_DISEASE, SLOW);
        add("minecraft:sea_pickle", FORTIFY_PICKPOCKET, WATERBREATHING, NIGHT_EYE, REGENERATE_STAMINA);
        add("minecraft:sugar", SWIFTNESS, RESTORE_STAMINA, FRENZY, WEAKNESS_TO_FIRE);
        add("minecraft:cocoa_beans", SWIFTNESS, FORTIFY_SMITHING, RESTORE_STAMINA, FRENZY);
        // ------------------------------------------------------------ vanilla food
        add("minecraft:apple", RESTORE_STAMINA, RESTORE_HEALTH, FORTIFY_HEAVY_ARMOR, CURE_DISEASE);
        add("minecraft:carrot", NIGHT_EYE, RESTORE_STAMINA, CURE_DISEASE, REGENERATE_STAMINA);
        add("minecraft:golden_carrot", NIGHT_EYE, FORTIFY_HEALTH, RESTORE_STAMINA, FORTIFY_ARCHERY);
        add("minecraft:beetroot", RESTORE_HEALTH, FORTIFY_HEALTH, DAMAGE_MAGICKA_REGEN, FORTIFY_BARTER);
        add("minecraft:poisonous_potato", LINGERING_DAMAGE_HEALTH, DAMAGE_STAMINA, WEAKNESS_TO_POISON, FORTIFY_CARRY_WEIGHT);
        add("minecraft:glistering_melon_slice", RESTORE_HEALTH, REGENERATE_HEALTH, FORTIFY_HEALTH, FORTIFY_RESTORATION);
        add("minecraft:salmon", RESTORE_STAMINA, WATERBREATHING, FORTIFY_MAGICKA, REGENERATE_MAGICKA);       // Salmon Roe
        add("minecraft:cod", RESTORE_STAMINA, DAMAGE_STAMINA_REGEN, RAVAGE_HEALTH, RESIST_FROST);            // Silverside Perch
        add("minecraft:tropical_fish", WEAKNESS_TO_FROST, FORTIFY_SNEAK, WEAKNESS_TO_POISON, FORTIFY_RESTORATION); // Abecean Longfin
        add("minecraft:pufferfish", LINGERING_DAMAGE_HEALTH, PARALYSIS, WATERBREATHING, RAVAGE_MAGICKA);
        add("minecraft:rotten_flesh", DAMAGE_HEALTH, PARALYSIS, RESTORE_MAGICKA, FORTIFY_SNEAK);             // Human Flesh
        add("minecraft:spider_eye", DAMAGE_STAMINA, DAMAGE_MAGICKA_REGEN, FORTIFY_LOCKPICKING, FORTIFY_ARCHERY);
        // ------------------------------------------------------------ vanilla creature parts & minerals
        add("minecraft:fermented_spider_eye", ENFEEBLE, DAMAGE_MAGICKA, INVISIBILITY, FORTIFY_SNEAK);
        add("minecraft:blaze_powder", FORTIFY_DESTRUCTION, WEAKNESS_TO_FROST, SWIFTNESS, DAMAGE_STAMINA_REGEN);
        add("minecraft:ghast_tear", REGENERATE_HEALTH, FEAR, RESIST_MAGIC, INVISIBILITY);
        add("minecraft:magma_cream", RESIST_FIRE, WEAKNESS_TO_FROST, FORTIFY_BLOCK, SLOW);
        add("minecraft:rabbit_foot", LEAP, FORTIFY_PICKPOCKET, FORTIFY_BARTER, SWIFTNESS);
        add("minecraft:phantom_membrane", FEATHERFALL, DAMAGE_STAMINA_REGEN, NIGHT_EYE, FORTIFY_ALTERATION);
        add("minecraft:glowstone_dust", FORTIFY_ENCHANTING, NIGHT_EYE, FORTIFY_ILLUSION, RESIST_SHOCK);
        add("minecraft:honeycomb", RESTORE_STAMINA, FORTIFY_BLOCK, FORTIFY_LIGHT_ARMOR, RAVAGE_STAMINA);      // Honeycomb
        add("minecraft:feather", FEATHERFALL, FORTIFY_ARCHERY, LEAP, DAMAGE_STAMINA);
        add("minecraft:bone", FORTIFY_HEAVY_ARMOR, DAMAGE_STAMINA, FORTIFY_CONJURATION, WEAKNESS_TO_MAGIC);
        add("minecraft:gunpowder", WEAKNESS_TO_FIRE, FORTIFY_DESTRUCTION, DAMAGE_HEALTH, FORTIFY_SMITHING);
        add("minecraft:slime_ball", LEAP, SLOW, RESIST_POISON, FORTIFY_CARRY_WEIGHT);
        add("minecraft:ink_sac", FORTIFY_SNEAK, WATERBREATHING, DAMAGE_MAGICKA, FEAR);
        add("minecraft:glow_ink_sac", NIGHT_EYE, FORTIFY_ILLUSION, RESTORE_MAGICKA, WATERBREATHING);
        add("minecraft:turtle_scute", WATERBREATHING, FORTIFY_HEAVY_ARMOR, RESIST_POISON, FORTIFY_BLOCK);
        add("minecraft:amethyst_shard", FORTIFY_ENCHANTING, RESIST_MAGIC, REGENERATE_MAGICKA, FORTIFY_ILLUSION);
        // ------------------------------------------------------------ NirnCraft ingredients (arcane)
        add("nirncraft:salt_pile", WEAKNESS_TO_MAGIC, FORTIFY_RESTORATION, SLOW, REGENERATE_MAGICKA);
        add("nirncraft:nightshade", DAMAGE_HEALTH, DAMAGE_MAGICKA_REGEN, LINGERING_DAMAGE_STAMINA, FORTIFY_DESTRUCTION);
        add("nirncraft:deathbell", DAMAGE_HEALTH, RAVAGE_STAMINA, SLOW, WEAKNESS_TO_POISON);
        add("nirncraft:nirnroot", DAMAGE_HEALTH, DAMAGE_STAMINA, INVISIBILITY, RESIST_MAGIC);
        add("nirncraft:frost_mirriam", RESIST_FROST, FORTIFY_SNEAK, RAVAGE_MAGICKA, DAMAGE_STAMINA_REGEN);
        add("nirncraft:void_salts", WEAKNESS_TO_SHOCK, RESIST_MAGIC, DAMAGE_HEALTH, FORTIFY_MAGICKA);
        add("nirncraft:fire_salts", WEAKNESS_TO_FROST, RESIST_FIRE, RESTORE_MAGICKA, REGENERATE_MAGICKA);
        add("nirncraft:frost_salts", WEAKNESS_TO_FIRE, RESIST_FROST, RESTORE_MAGICKA, FORTIFY_CONJURATION);
        add("nirncraft:vampire_dust", INVISIBILITY, RESTORE_MAGICKA, REGENERATE_HEALTH, CURE_DISEASE);
        add("nirncraft:bear_claws", RESTORE_STAMINA, FORTIFY_HEALTH, FORTIFY_ONE_HANDED, DAMAGE_MAGICKA_REGEN);
        add("nirncraft:hawk_feather", CURE_DISEASE, FORTIFY_LIGHT_ARMOR, FORTIFY_ONE_HANDED, FORTIFY_SNEAK);
        add("nirncraft:garlic", RESIST_POISON, FORTIFY_STAMINA, REGENERATE_MAGICKA, REGENERATE_HEALTH);
        // Skyrim flora and fungi
        add("nirncraft:canis_root", DAMAGE_STAMINA, FORTIFY_ONE_HANDED, FORTIFY_ARCHERY, PARALYSIS);
        add("nirncraft:creep_cluster", RESTORE_MAGICKA, DAMAGE_STAMINA_REGEN, FORTIFY_CARRY_WEIGHT, WEAKNESS_TO_MAGIC);
        add("nirncraft:elves_ear", RESTORE_MAGICKA, FORTIFY_ARCHERY, WEAKNESS_TO_FROST, RESIST_FIRE);
        add("nirncraft:imp_stool", DAMAGE_HEALTH, LINGERING_DAMAGE_HEALTH, PARALYSIS, RESTORE_HEALTH);
        add("nirncraft:jazbay_grapes", WEAKNESS_TO_MAGIC, FORTIFY_MAGICKA, REGENERATE_MAGICKA, RAVAGE_HEALTH);
        add("nirncraft:juniper_berries", WEAKNESS_TO_FIRE, FORTIFY_ARCHERY, REGENERATE_HEALTH, DAMAGE_STAMINA_REGEN);
        add("nirncraft:namiras_rot", DAMAGE_MAGICKA, FORTIFY_LOCKPICKING, FEAR, REGENERATE_HEALTH);
        add("nirncraft:white_cap", WEAKNESS_TO_FROST, FORTIFY_HEAVY_ARMOR, RESTORE_MAGICKA, RAVAGE_MAGICKA);
        add("nirncraft:glowing_mushroom", RESIST_SHOCK, FORTIFY_DESTRUCTION, FORTIFY_SMITHING, FORTIFY_HEALTH);
        add("nirncraft:bleeding_crown", WEAKNESS_TO_FIRE, FORTIFY_BLOCK, WEAKNESS_TO_POISON, RESIST_MAGIC);
        add("nirncraft:hanging_moss", DAMAGE_MAGICKA, FORTIFY_HEALTH, DAMAGE_MAGICKA_REGEN, FORTIFY_ONE_HANDED);
        add("nirncraft:swamp_fungal_pod", RESIST_SHOCK, LINGERING_DAMAGE_MAGICKA, PARALYSIS, RESTORE_HEALTH);
        add("nirncraft:thistle_branch", RESIST_FROST, RAVAGE_STAMINA, RESIST_POISON, FORTIFY_HEAVY_ARMOR);
        add("nirncraft:giant_lichen", WEAKNESS_TO_SHOCK, RAVAGE_HEALTH, WEAKNESS_TO_POISON, RESTORE_MAGICKA);
        add("nirncraft:mora_tapinella", RESTORE_MAGICKA, LINGERING_DAMAGE_HEALTH, REGENERATE_STAMINA, FORTIFY_ILLUSION);
        add("nirncraft:poison_bloom", DAMAGE_HEALTH, SLOW, FORTIFY_CARRY_WEIGHT, FEAR);
        add("nirncraft:emperor_parasol_moss", DAMAGE_HEALTH, FORTIFY_MAGICKA, REGENERATE_HEALTH, FORTIFY_TWO_HANDED);
        add("nirncraft:burnt_spriggan_wood", WEAKNESS_TO_FIRE, FORTIFY_ALTERATION, DAMAGE_MAGICKA_REGEN, SLOW);
        // Skyrim birds' eggs, beaks and sea life
        add("nirncraft:rock_warbler_egg", RESTORE_HEALTH, FORTIFY_ONE_HANDED, DAMAGE_STAMINA, WEAKNESS_TO_MAGIC);
        add("nirncraft:pine_thrush_egg", RESTORE_STAMINA, FORTIFY_LOCKPICKING, WEAKNESS_TO_POISON, RESIST_SHOCK);
        add("nirncraft:hawks_egg", RESIST_MAGIC, DAMAGE_MAGICKA_REGEN, WATERBREATHING, LINGERING_DAMAGE_STAMINA);
        add("nirncraft:hawk_beak", RESTORE_STAMINA, RESIST_FROST, FORTIFY_CARRY_WEIGHT, RESIST_SHOCK);
        add("nirncraft:pearl", RESTORE_STAMINA, FORTIFY_BLOCK, RESTORE_MAGICKA, RESIST_SHOCK);
        add("nirncraft:small_pearl", RESTORE_STAMINA, FORTIFY_ONE_HANDED, FORTIFY_RESTORATION, RESIST_FROST);
        add("nirncraft:nordic_barnacle", DAMAGE_MAGICKA, WATERBREATHING, REGENERATE_HEALTH, FORTIFY_PICKPOCKET);
        // Solstheim and the ashlands (Dragonborn)
        add("nirncraft:scathecraw", RAVAGE_HEALTH, RAVAGE_STAMINA, RAVAGE_MAGICKA, LINGERING_DAMAGE_HEALTH);
        add("nirncraft:trama_root", WEAKNESS_TO_SHOCK, FORTIFY_CARRY_WEIGHT, DAMAGE_MAGICKA, SLOW);
        add("nirncraft:ashen_grass_pod", RESIST_FIRE, WEAKNESS_TO_SHOCK, FORTIFY_LOCKPICKING, FORTIFY_SNEAK);
        add("nirncraft:ash_creep_cluster", DAMAGE_STAMINA, INVISIBILITY, RESIST_FIRE, FORTIFY_DESTRUCTION);
        add("nirncraft:ash_hopper_jelly", RESTORE_HEALTH, FORTIFY_LIGHT_ARMOR, RESIST_SHOCK, WEAKNESS_TO_FROST);
        add("nirncraft:boar_tusk", FORTIFY_STAMINA, FORTIFY_HEALTH, FORTIFY_BLOCK, FRENZY);
        add("nirncraft:spawn_ash", RAVAGE_STAMINA, RESIST_FIRE, FORTIFY_ENCHANTING, RAVAGE_MAGICKA);
        // Morrowind: the classic ingredients of Vvardenfell and the mainland
        add("nirncraft:comberry", DAMAGE_STAMINA, RESIST_MAGIC, RESTORE_MAGICKA, FORTIFY_DESTRUCTION);
        add("nirncraft:marshmerrow", RESTORE_HEALTH, FORTIFY_CARRY_WEIGHT, WEAKNESS_TO_MAGIC, DAMAGE_STAMINA);
        add("nirncraft:saltrice", RESTORE_STAMINA, FORTIFY_MAGICKA, DAMAGE_STAMINA_REGEN, RESTORE_HEALTH);
        add("nirncraft:hypha_facia", WEAKNESS_TO_POISON, FRENZY, RAVAGE_STAMINA, RESIST_MAGIC);
        add("nirncraft:bittergreen_petals", LINGERING_DAMAGE_STAMINA, INVISIBILITY, CURE_POISON, DAMAGE_MAGICKA);
        add("nirncraft:gold_kanet", PARALYSIS, RAVAGE_HEALTH, WEAKNESS_TO_FROST, FORTIFY_SMITHING);
        add("nirncraft:scrib_jelly", REGENERATE_MAGICKA, CURE_POISON, CURE_DISEASE, REGENERATE_STAMINA);
        // Morrowind lore: kwama eggs restore fatigue and fortify health, ash yam sharpens the mind and the arm
        add("nirncraft:kwama_egg", RESTORE_STAMINA, PARALYSIS, RESIST_FROST, FORTIFY_HEALTH);
        add("nirncraft:ash_yam", FORTIFY_MAGICKA, FORTIFY_TWO_HANDED, CURE_DISEASE, FORTIFY_LOCKPICKING);
        // Elsweyr and Black Marsh
        add("nirncraft:moon_sugar", WEAKNESS_TO_FIRE, RESIST_FROST, RESTORE_MAGICKA, REGENERATE_MAGICKA);
        add("nirncraft:hist_sap", REGENERATE_HEALTH, RESIST_POISON, CURE_DISEASE, FORTIFY_ALTERATION);
        // ------------------------------------------------------------ creature drops registered by the crafting module
        add("nirncraft:giants_toe", DAMAGE_STAMINA, FORTIFY_HEALTH, FORTIFY_CARRY_WEIGHT, DAMAGE_STAMINA_REGEN);
        add("nirncraft:troll_fat", RESIST_POISON, FORTIFY_TWO_HANDED, FRENZY, DAMAGE_HEALTH);
        add("nirncraft:skeever_tail", DAMAGE_STAMINA_REGEN, RAVAGE_HEALTH, DAMAGE_HEALTH, FORTIFY_LIGHT_ARMOR);
        add("nirncraft:bone_meal_draugr", DAMAGE_STAMINA, RESIST_FIRE, FORTIFY_CONJURATION, RAVAGE_STAMINA);
        add("nirncraft:daedra_heart", RESTORE_HEALTH, DAMAGE_STAMINA_REGEN, DAMAGE_MAGICKA, FEAR);
        // Skyrim creature parts (arcane module, dropped through the creatures' loot tables)
        add("nirncraft:briar_heart", RESTORE_MAGICKA, FORTIFY_BLOCK, PARALYSIS, FORTIFY_MAGICKA);
        add("nirncraft:falmer_ear", DAMAGE_HEALTH, FRENZY, RESIST_POISON, FORTIFY_LOCKPICKING);
        add("nirncraft:spider_egg", DAMAGE_STAMINA, DAMAGE_MAGICKA_REGEN, FORTIFY_LOCKPICKING, FORTIFY_ARCHERY);
        add("nirncraft:eye_of_sabre_cat", RESTORE_STAMINA, RAVAGE_HEALTH, DAMAGE_MAGICKA, RESTORE_HEALTH);
        add("nirncraft:powdered_mammoth_tusk", RESTORE_STAMINA, FORTIFY_SNEAK, WEAKNESS_TO_FIRE, FEAR);
        add("nirncraft:wisp_wrappings", RESTORE_STAMINA, FORTIFY_DESTRUCTION, FORTIFY_CARRY_WEIGHT, RESIST_MAGIC);
        add("nirncraft:dwarven_oil", WEAKNESS_TO_MAGIC, FORTIFY_ILLUSION, REGENERATE_MAGICKA, RESTORE_MAGICKA);
        add("nirncraft:chaurus_hunter_antennae", DAMAGE_STAMINA, FORTIFY_CONJURATION, DAMAGE_MAGICKA_REGEN, FORTIFY_ENCHANTING);
        // ------------------------------------------------------------ wildlife & insects (fauna module)
        add("nirncraft:small_antlers", WEAKNESS_TO_POISON, FORTIFY_RESTORATION, LINGERING_DAMAGE_STAMINA, DAMAGE_HEALTH);
        add("nirncraft:large_antlers", RESTORE_STAMINA, FORTIFY_STAMINA, SLOW, DAMAGE_STAMINA_REGEN);
        add("nirncraft:sabre_cat_tooth", RESTORE_STAMINA, FORTIFY_HEAVY_ARMOR, FORTIFY_SMITHING, WEAKNESS_TO_POISON);
        add("nirncraft:mudcrab_chitin", RESTORE_STAMINA, CURE_DISEASE, RESIST_POISON, RESIST_FIRE);
        add("nirncraft:slaughterfish_scales", RESIST_FROST, LINGERING_DAMAGE_HEALTH, FORTIFY_HEAVY_ARMOR, FORTIFY_BLOCK);
        add("nirncraft:monarch_wing", RESTORE_HEALTH, FORTIFY_BARTER, LINGERING_DAMAGE_STAMINA, DAMAGE_MAGICKA);   // Butterfly Wing
        add("nirncraft:blue_butterfly_wing", DAMAGE_STAMINA, FORTIFY_CONJURATION, DAMAGE_MAGICKA_REGEN, FORTIFY_ENCHANTING);
        add("nirncraft:blue_dartwing", RESIST_SHOCK, FORTIFY_PICKPOCKET, RESTORE_HEALTH, FEAR);
        add("nirncraft:torchbug_thorax", RESTORE_STAMINA, LINGERING_DAMAGE_MAGICKA, WEAKNESS_TO_MAGIC, FORTIFY_STAMINA);
        add("nirncraft:luna_moth_wing", DAMAGE_MAGICKA, FORTIFY_LIGHT_ARMOR, REGENERATE_HEALTH, INVISIBILITY);
        // ------------------------------------------------------------ bestiary of Nirn (creatures module)
        add("nirncraft:chaurus_eggs", WEAKNESS_TO_POISON, FORTIFY_STAMINA, DAMAGE_MAGICKA, INVISIBILITY);
        add("nirncraft:spriggan_sap", DAMAGE_MAGICKA_REGEN, FORTIFY_ENCHANTING, FORTIFY_SMITHING, FORTIFY_ALTERATION);
        add("nirncraft:hagraven_feathers", DAMAGE_MAGICKA, FORTIFY_CONJURATION, FRENZY, WEAKNESS_TO_SHOCK);
        add("nirncraft:hagraven_claw", RESIST_MAGIC, LINGERING_DAMAGE_MAGICKA, FORTIFY_ENCHANTING, FORTIFY_BARTER);
        add("nirncraft:ice_wraith_teeth", WEAKNESS_TO_FROST, FORTIFY_HEAVY_ARMOR, INVISIBILITY, WEAKNESS_TO_FIRE);
        add("nirncraft:ectoplasm", RESTORE_MAGICKA, FORTIFY_DESTRUCTION, FORTIFY_MAGICKA, DAMAGE_HEALTH);
        add("nirncraft:glow_dust", DAMAGE_MAGICKA, DAMAGE_MAGICKA_REGEN, FORTIFY_DESTRUCTION, RESIST_SHOCK);
        add("nirncraft:netch_jelly", PARALYSIS, FORTIFY_CARRY_WEIGHT, RESTORE_STAMINA, FEAR);
        add("nirncraft:kagouti_hide", LINGERING_DAMAGE_STAMINA, NIGHT_EYE, FORTIFY_CARRY_WEIGHT, RESIST_SHOCK);
        add("nirncraft:minotaur_horn", RESIST_POISON, DAMAGE_MAGICKA_REGEN, REGENERATE_HEALTH, REGENERATE_MAGICKA);
        add("nirncraft:ogre_tooth", WEAKNESS_TO_SHOCK, RESIST_POISON, LINGERING_DAMAGE_MAGICKA, REGENERATE_HEALTH);
        add("nirncraft:dreugh_wax", WEAKNESS_TO_MAGIC, FRENZY, FORTIFY_ENCHANTING, FORTIFY_SMITHING);
        // new to Skyrim: their Morrowind/Oblivion/ESO properties in Skyrim's terms
        add("nirncraft:frostbite_venom", RESIST_FROST, PARALYSIS, LINGERING_DAMAGE_HEALTH, DAMAGE_STAMINA);
        add("nirncraft:guar_hide", DAMAGE_STAMINA, FORTIFY_HEALTH, FORTIFY_BARTER, FORTIFY_LOCKPICKING);
        add("nirncraft:alit_hide", DAMAGE_MAGICKA, RESIST_POISON, FORTIFY_PICKPOCKET, FORTIFY_LOCKPICKING);
        add("nirncraft:scamp_skin", DAMAGE_MAGICKA, RESIST_FIRE, FORTIFY_TWO_HANDED, FORTIFY_BARTER);
        add("nirncraft:cliff_racer_plumes", DAMAGE_MAGICKA_REGEN, FEATHERFALL, FEAR, SWIFTNESS);
        add("nirncraft:ash_salts", SLOW, RESIST_MAGIC, CURE_DISEASE, RESTORE_MAGICKA);
        add("nirncraft:nix_hound_meat", RESTORE_STAMINA, FORTIFY_STAMINA, RESIST_MAGIC, FORTIFY_ENCHANTING);
        add("nirncraft:harpy_feather", FEATHERFALL, SWIFTNESS, FRENZY, WEAKNESS_TO_SHOCK);
        add("nirncraft:scorpion_stinger", LINGERING_DAMAGE_HEALTH, PARALYSIS, RESIST_POISON, FORTIFY_ARCHERY);
        add("nirncraft:sea_serpent_scale", WATERBREATHING, RESIST_FROST, FORTIFY_HEAVY_ARMOR, RAVAGE_STAMINA);
        add("nirncraft:kamal_horn", RESIST_FROST, FORTIFY_TWO_HANDED, FRENZY, WEAKNESS_TO_FIRE);
        add("nirncraft:tsaesci_scale", LINGERING_DAMAGE_HEALTH, FORTIFY_SNEAK, RESIST_POISON, RAVAGE_MAGICKA);
        add("nirncraft:indrik_feather", RESTORE_HEALTH, REGENERATE_HEALTH, CURE_DISEASE, FEATHERFALL);
        add("nirncraft:leviathan_tooth", FORTIFY_TWO_HANDED, WATERBREATHING, RAVAGE_HEALTH, FEAR);
        add("nirncraft:wamasu_scale", RESIST_SHOCK, WEAKNESS_TO_FROST, FORTIFY_DESTRUCTION, PARALYSIS);
        add("nirncraft:gargoyle_shard", FORTIFY_HEAVY_ARMOR, RESIST_MAGIC, SLOW, FORTIFY_BLOCK);
    }

    private Ingredients() {}

    private static void add(String id, AlchemyEffect a, AlchemyEffect b, AlchemyEffect c, AlchemyEffect d) {
        BY_ID.put(id, new Ingredient(id, new AlchemyEffect[]{a, b, c, d}));
    }

    /** Adds an ingredient owned by another module (e.g. the survival module's fish). Call during mod construction. */
    public static synchronized void register(String id, AlchemyEffect a, AlchemyEffect b, AlchemyEffect c, AlchemyEffect d) {
        if (!BY_ID.containsKey(id)) add(id, a, b, c, d);
    }

    public static Collection<Ingredient> all() {
        return Collections.unmodifiableCollection(BY_ID.values());
    }

    @Nullable
    public static Ingredient get(String id) {
        return BY_ID.get(id);
    }

    @Nullable
    public static Ingredient get(Item item) {
        Identifier key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? null : BY_ID.get(key.toString());
    }

    @Nullable
    public static Ingredient get(ItemStack stack) {
        return stack.isEmpty() ? null : get(stack.getItem());
    }

    public static boolean isIngredient(ItemStack stack) {
        return get(stack) != null;
    }
}
