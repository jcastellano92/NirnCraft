package com.skycraft.economy;

import com.skycraft.Skycraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BookItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.KnowledgeBookItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.WritableBookItem;
import net.minecraft.world.item.WrittenBookItem;

import java.util.Locale;

/** The barter menu's item categories (Skyrim inventory tabs). */
public enum ItemCategory {
    ALL, WEAPONS, APPAREL, POTIONS, INGREDIENTS, BOOKS, FOOD, MISC;

    public static final ItemCategory[] VALUES = values();

    public static final TagKey<Item> INGREDIENTS_TAG = TagKey.create(Registries.ITEM, new ResourceLocation(Skycraft.MODID, "merchant/ingredients"));
    public static final TagKey<Item> SPELL_TOMES = TagKey.create(Registries.ITEM, new ResourceLocation(Skycraft.MODID, "spell_tomes"));
    public static final TagKey<Item> STAVES = TagKey.create(Registries.ITEM, new ResourceLocation(Skycraft.MODID, "staves"));
    public static final TagKey<Item> SKILL_BOOKS =TagKey.create(Registries.ITEM, new ResourceLocation(Skycraft.MODID, "merchant/skill_books"));

    public Component displayName() {
        return Component.translatable("barter.skycraft.category." + name().toLowerCase(Locale.ROOT));
    }

    public static ItemCategory byOrdinal(int i) {
        return i >= 0 && i < VALUES.length ? VALUES[i] : MISC;
    }

    /** Which tab an item belongs in (never {@link #ALL}). */
    public static ItemCategory of(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof BookItem || item instanceof WritableBookItem || item instanceof WrittenBookItem
                || item instanceof EnchantedBookItem || item instanceof KnowledgeBookItem || item instanceof SkillBookItem
                || stack.is(SPELL_TOMES) || stack.is(SKILL_BOOKS) || item instanceof com.skycraft.lore.LoreBookItem
                || item instanceof com.skycraft.guide.HandbookItem) return BOOKS;
        // Skyrim lists staves with the weapons
        if (stack.is(STAVES) || item instanceof com.skycraft.arsenal.item.StaffItem
                || item instanceof com.skycraft.arsenal.item.EnchantedStaffItem) return WEAPONS;
        if (item instanceof PotionItem) return POTIONS;
        if (item instanceof SwordItem || item instanceof AxeItem || item instanceof TridentItem
                || item instanceof ProjectileWeaponItem || item instanceof ArrowItem) return WEAPONS;
        if (item instanceof ArmorItem || item instanceof ShieldItem || item instanceof ElytraItem || isJewelry(stack)) return APPAREL;
        if (stack.is(INGREDIENTS_TAG) || com.skycraft.crafting.arcane.alchemy.Ingredients.isIngredient(stack)) return INGREDIENTS;
        if (stack.isEdible() || stack.is(net.minecraft.world.item.Items.PUMPKIN)) return FOOD;
        if (!(item instanceof DiggerItem) && isWeaponLike(item)) return WEAPONS;
        return MISC;
    }

    /** Rings, amulets, necklaces, circlets, pendants and curios count as apparel and can be enchanted. */
    public static boolean isJewelry(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.is(ShopType.JEWELRY)) return true;
        net.minecraft.resources.ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
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
        try {
            double sum = 0;
            for (AttributeModifier m : item.getDefaultAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE)) {
                if (m.getOperation() == AttributeModifier.Operation.ADDITION) sum += m.getAmount();
            }
            return sum >= 3;
        } catch (Exception e) {
            return false;
        }
    }
}
