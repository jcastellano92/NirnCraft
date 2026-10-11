package com.skycraft.crafting.arcane.alchemy;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.skycraft.crafting.arcane.alchemy.AlchemyEffect.*;

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
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
            return item == null || item == Items.AIR ? null : item;
        }
    }

    private static final Map<String, Ingredient> BY_ID = new LinkedHashMap<>();

    static {
        // ------------------------------------------------------------ vanilla plants & fungi
        add("minecraft:red_mushroom", RESTORE_STAMINA, FORTIFY_TWO_HANDED, FRENZY, REGENERATE_STAMINA);
        add("minecraft:brown_mushroom", DAMAGE_STAMINA, FRENZY, RESTORE_HEALTH, FORTIFY_SMITHING);
        add("minecraft:crimson_fungus", FRENZY, RESIST_FIRE, DAMAGE_MAGICKA, FORTIFY_ONE_HANDED);
        add("minecraft:warped_fungus", RESTORE_MAGICKA, FEAR, FORTIFY_ALTERATION, DAMAGE_STAMINA_REGEN);
        add("minecraft:glow_berries", NIGHT_EYE, RESTORE_MAGICKA, FORTIFY_ILLUSION, REGENERATE_MAGICKA);
        add("minecraft:sweet_berries", RESIST_FIRE, FORTIFY_ENCHANTING, RESIST_FROST, RESIST_SHOCK);
        add("minecraft:dandelion", RESIST_FIRE, FORTIFY_BARTER, FORTIFY_TWO_HANDED, FORTIFY_ILLUSION);
        add("minecraft:poppy", RESTORE_MAGICKA, RAVAGE_MAGICKA, DAMAGE_STAMINA, FORTIFY_ALTERATION);
        add("minecraft:blue_orchid", RESTORE_HEALTH, FORTIFY_CONJURATION, FORTIFY_HEALTH, DAMAGE_MAGICKA_REGEN);
        add("minecraft:allium", RESTORE_STAMINA, FORTIFY_SNEAK, RESIST_FROST, RAVAGE_MAGICKA);
        add("minecraft:azure_bluet", RESIST_MAGIC, FORTIFY_CONJURATION, RAVAGE_STAMINA, FORTIFY_ENCHANTING);
        add("minecraft:cornflower", RESIST_SHOCK, FORTIFY_DESTRUCTION, DAMAGE_STAMINA_REGEN, RESTORE_MAGICKA);
        add("minecraft:lily_of_the_valley", DAMAGE_HEALTH, SLOW, RESIST_POISON, FORTIFY_RESTORATION);
        add("minecraft:wither_rose", RAVAGE_HEALTH, DAMAGE_HEALTH, FEAR, WEAKNESS_TO_MAGIC);
        add("minecraft:oxeye_daisy", RESTORE_HEALTH, CURE_DISEASE, REGENERATE_HEALTH, FORTIFY_LIGHT_ARMOR);
        add("skycraft:nirnroot", DAMAGE_HEALTH, DAMAGE_STAMINA, INVISIBILITY, RESIST_MAGIC);
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
        add("minecraft:salmon", RESTORE_STAMINA, WATERBREATHING, INVISIBILITY, FORTIFY_HEALTH);
        add("minecraft:cod", RESTORE_HEALTH, RESTORE_STAMINA, WATERBREATHING, DAMAGE_MAGICKA_REGEN);
        add("minecraft:tropical_fish", WEAKNESS_TO_FROST, FORTIFY_SNEAK, WEAKNESS_TO_POISON, FORTIFY_RESTORATION);
        add("minecraft:pufferfish", LINGERING_DAMAGE_HEALTH, PARALYSIS, WATERBREATHING, RAVAGE_MAGICKA);
        add("minecraft:rotten_flesh", DAMAGE_HEALTH, RAVAGE_STAMINA, FORTIFY_TWO_HANDED, ENFEEBLE);
        add("minecraft:spider_eye", DAMAGE_STAMINA, DAMAGE_MAGICKA_REGEN, FORTIFY_LOCKPICKING, FORTIFY_ARCHERY);
        // ------------------------------------------------------------ vanilla creature parts & minerals
        add("minecraft:fermented_spider_eye", ENFEEBLE, DAMAGE_MAGICKA, INVISIBILITY, FORTIFY_SNEAK);
        add("minecraft:blaze_powder", FORTIFY_DESTRUCTION, WEAKNESS_TO_FROST, SWIFTNESS, DAMAGE_STAMINA_REGEN);
        add("minecraft:ghast_tear", REGENERATE_HEALTH, FEAR, RESIST_MAGIC, INVISIBILITY);
        add("minecraft:magma_cream", RESIST_FIRE, WEAKNESS_TO_FROST, FORTIFY_BLOCK, SLOW);
        add("minecraft:rabbit_foot", LEAP, FORTIFY_PICKPOCKET, FORTIFY_BARTER, SWIFTNESS);
        add("minecraft:phantom_membrane", FEATHERFALL, DAMAGE_STAMINA_REGEN, NIGHT_EYE, FORTIFY_ALTERATION);
        add("minecraft:glowstone_dust", FORTIFY_ENCHANTING, NIGHT_EYE, FORTIFY_ILLUSION, RESIST_SHOCK);
        add("minecraft:honeycomb", RESTORE_STAMINA, FORTIFY_BLOCK, FORTIFY_LIGHT_ARMOR, RAVAGE_STAMINA);
        add("minecraft:feather", FEATHERFALL, FORTIFY_ARCHERY, LEAP, DAMAGE_STAMINA);
        add("minecraft:bone", FORTIFY_HEAVY_ARMOR, DAMAGE_STAMINA, FORTIFY_CONJURATION, WEAKNESS_TO_MAGIC);
        add("minecraft:gunpowder", WEAKNESS_TO_FIRE, FORTIFY_DESTRUCTION, DAMAGE_HEALTH, FORTIFY_SMITHING);
        add("minecraft:slime_ball", LEAP, SLOW, RESIST_POISON, FORTIFY_CARRY_WEIGHT);
        add("minecraft:ink_sac", FORTIFY_SNEAK, WATERBREATHING, DAMAGE_MAGICKA, FEAR);
        add("minecraft:glow_ink_sac", NIGHT_EYE, FORTIFY_ILLUSION, RESTORE_MAGICKA, WATERBREATHING);
        add("minecraft:scute", WATERBREATHING, FORTIFY_HEAVY_ARMOR, RESIST_POISON, FORTIFY_BLOCK);
        add("minecraft:amethyst_shard", FORTIFY_ENCHANTING, RESIST_MAGIC, REGENERATE_MAGICKA, FORTIFY_ILLUSION);
        // ------------------------------------------------------------ Skycraft ingredients (arcane)
        add("skycraft:salt_pile", WEAKNESS_TO_MAGIC, FORTIFY_RESTORATION, SLOW, REGENERATE_MAGICKA);
        add("skycraft:nightshade", DAMAGE_HEALTH, DAMAGE_MAGICKA_REGEN, FORTIFY_DESTRUCTION, RAVAGE_STAMINA);
        add("skycraft:deathbell", DAMAGE_HEALTH, RAVAGE_STAMINA, SLOW, WEAKNESS_TO_POISON);
        add("skycraft:nirnroot", DAMAGE_HEALTH, DAMAGE_STAMINA, INVISIBILITY, RESIST_MAGIC);
        add("skycraft:frost_mirriam", RESIST_FROST, FORTIFY_SNEAK, RAVAGE_MAGICKA, DAMAGE_STAMINA_REGEN);
        add("skycraft:void_salts", WEAKNESS_TO_SHOCK, RESIST_MAGIC, DAMAGE_HEALTH, PARALYSIS);
        add("skycraft:fire_salts", WEAKNESS_TO_FROST, RESIST_FIRE, RESTORE_MAGICKA, REGENERATE_MAGICKA);
        add("skycraft:frost_salts", WEAKNESS_TO_FIRE, RESIST_FROST, RESTORE_MAGICKA, FORTIFY_CONJURATION);
        add("skycraft:vampire_dust", INVISIBILITY, RESTORE_MAGICKA, REGENERATE_HEALTH, CURE_DISEASE);
        add("skycraft:bear_claws", RESTORE_STAMINA, FORTIFY_HEALTH, FORTIFY_ONE_HANDED, DAMAGE_MAGICKA_REGEN);
        add("skycraft:hawk_feather", CURE_DISEASE, FORTIFY_LIGHT_ARMOR, FORTIFY_ONE_HANDED, FORTIFY_SNEAK);
        // ------------------------------------------------------------ creature drops registered by the crafting module
        add("skycraft:giants_toe", DAMAGE_STAMINA, FORTIFY_HEALTH, FORTIFY_CARRY_WEIGHT, DAMAGE_STAMINA_REGEN);
        add("skycraft:troll_fat", RESIST_POISON, FORTIFY_TWO_HANDED, FRENZY, DAMAGE_HEALTH);
        add("skycraft:skeever_tail", DAMAGE_STAMINA_REGEN, DAMAGE_HEALTH, FORTIFY_LIGHT_ARMOR, LINGERING_DAMAGE_HEALTH);
        add("skycraft:bone_meal_draugr", DAMAGE_STAMINA, RESIST_FIRE, FORTIFY_CONJURATION, RAVAGE_STAMINA);
        add("skycraft:daedra_heart", RESTORE_HEALTH, DAMAGE_STAMINA_REGEN, DAMAGE_MAGICKA, FEAR);
        // ------------------------------------------------------------ wildlife & insects (fauna module)
        add("skycraft:small_antlers", WEAKNESS_TO_POISON, FORTIFY_RESTORATION, RAVAGE_STAMINA, DAMAGE_HEALTH);
        add("skycraft:large_antlers", RESTORE_STAMINA, REGENERATE_STAMINA, SLOW, DAMAGE_STAMINA_REGEN);
        add("skycraft:sabre_cat_tooth", RESTORE_STAMINA, FORTIFY_HEAVY_ARMOR, FORTIFY_SMITHING, WEAKNESS_TO_POISON);
        add("skycraft:mudcrab_chitin", RESTORE_STAMINA, CURE_DISEASE, RESIST_POISON, RESIST_FIRE);
        add("skycraft:slaughterfish_scales", RESIST_FROST, LINGERING_DAMAGE_HEALTH, FORTIFY_HEAVY_ARMOR, FORTIFY_BLOCK);
        add("skycraft:monarch_wing", RESTORE_HEALTH, FORTIFY_BARTER, RAVAGE_STAMINA, DAMAGE_MAGICKA);
        add("skycraft:blue_butterfly_wing", DAMAGE_STAMINA, FORTIFY_CONJURATION, DAMAGE_MAGICKA_REGEN, FORTIFY_ENCHANTING);
        add("skycraft:blue_dartwing", RESIST_SHOCK, FORTIFY_PICKPOCKET, RESTORE_HEALTH, FEAR);
        add("skycraft:torchbug_thorax", RESTORE_STAMINA, RAVAGE_MAGICKA, WEAKNESS_TO_MAGIC, REGENERATE_STAMINA);
        add("skycraft:luna_moth_wing", DAMAGE_MAGICKA, FORTIFY_LIGHT_ARMOR, REGENERATE_HEALTH, INVISIBILITY);
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
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
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
