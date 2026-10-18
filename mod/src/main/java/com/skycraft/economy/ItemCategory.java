package com.skycraft.economy;

import com.skycraft.Skycraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.KnowledgeBookItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.WritableBookItem;
import net.minecraft.world.item.WrittenBookItem;

import java.util.Locale;

/** The barter menu's item categories (Skyrim inventory tabs). */
public enum ItemCategory {
    ALL, WEAPONS, APPAREL, POTIONS, INGREDIENTS, BOOKS, FOOD, MISC;

    public static final ItemCategory[] VALUES = values();

    public static final TagKey<Item> INGREDIENTS_TAG = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Skycraft.MODID, "merchant/ingredients"));
    public static final TagKey<Item> SPELL_TOMES = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Skycraft.MODID, "spell_tomes"));
    public static final TagKey<Item> STAVES = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Skycraft.MODID, "staves"));
    public static final TagKey<Item> SKILL_BOOKS =TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Skycraft.MODID, "merchant/skill_books"));

    public Component displayName() {
        return Component.translatable("barter.skycraft.category." + name().toLowerCase(Locale.ROOT));
    }

    public static ItemCategory byOrdinal(int i) {
        return i >= 0 && i < VALUES.length ? VALUES[i] : MISC;
    }

    /** Which tab an item belongs in (never {@link #ALL}). */
    public static ItemCategory of(ItemStack stack) {
        Item item = stack.getItem();
        if (stack.is(net.minecraft.world.item.Items.BOOK) || item instanceof WritableBookItem || item instanceof WrittenBookItem
                || stack.is(net.minecraft.world.item.Items.ENCHANTED_BOOK) || item instanceof KnowledgeBookItem || item instanceof SkillBookItem
                || stack.is(SPELL_TOMES) || stack.is(SKILL_BOOKS) || item instanceof com.skycraft.lore.LoreBookItem
                || item instanceof com.skycraft.guide.HandbookItem) return BOOKS;
        // Skyrim lists staves with the weapons
        if (stack.is(STAVES) || item instanceof com.skycraft.arsenal.item.StaffItem
                || item instanceof com.skycraft.arsenal.item.EnchantedStaffItem) return WEAPONS;
        if (item instanceof PotionItem) return POTIONS;
        if (stack.is(net.minecraft.tags.ItemTags.SWORDS) || stack.is(net.minecraft.tags.ItemTags.AXES) || item instanceof TridentItem
                || item instanceof ProjectileWeaponItem || item instanceof ArrowItem) return WEAPONS;
        if (com.skycraft.inventory.EquipmentData.isArmor(stack) || item instanceof ShieldItem || stack.is(net.minecraft.world.item.Items.ELYTRA) || isJewelry(stack)) return APPAREL;
        if (stack.is(INGREDIENTS_TAG) || com.skycraft.crafting.arcane.alchemy.Ingredients.isIngredient(stack)) return INGREDIENTS;
        if (stack.has(net.minecraft.core.component.DataComponents.FOOD) || stack.is(net.minecraft.world.item.Items.PUMPKIN)) return FOOD;
        if (!(isDigger(stack)) && isWeaponLike(item)) return WEAPONS;
        return MISC;
    }

    /** Rings, amulets, necklaces, circlets, pendants and curios count as apparel and can be enchanted. */
    public static boolean isJewelry(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.is(ShopType.JEWELRY)) return true;
        net.minecraft.resources.Identifier key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key == null) return false;
        // whole words only: "gold_ring" is jewellery, "string" and "spring_water" are not
        for (String word : key.getPath().split("_")) {
            switch (word) {
                case "ring", "rings", "necklace", "amulet", "circlet", "pendant", "curio", "torc", "choker" -> {
                    return true;
                }
                default -> {
                }
            }
        }
        return false;
    }

    /** Modded weapons that don't extend SwordItem: anything with a big main-hand damage bonus. */
    private static boolean isWeaponLike(Item item) {
        return ItemValues.attackDamage(item) >= 3;
    }

    public static boolean isDigger(ItemStack stack) {
        return stack.is(net.minecraft.tags.ItemTags.AXES) || stack.is(net.minecraft.tags.ItemTags.PICKAXES)
                || stack.is(net.minecraft.tags.ItemTags.SHOVELS) || stack.is(net.minecraft.tags.ItemTags.HOES);
    }
}
