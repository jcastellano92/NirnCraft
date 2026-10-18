package com.skycraft.crafting;

import com.skycraft.Skycraft;
import com.skycraft.crafting.item.SkyArmorItem;
import com.skycraft.crafting.item.SkyAxeItem;
import com.skycraft.crafting.item.SkyBowItem;
import com.skycraft.crafting.item.SkyMeleeItem;
import com.skycraft.crafting.item.SkyShieldItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Every item of the smithing module: raw ores, ingots, creature parts, gems, jewelry, the weapons of every
 * {@link SmithingTier} (only the families it makes), four pieces of every {@link SkyArmorMaterial} set plus its
 * shield, and the block items of ore veins and stations. Gold values live in
 * {@code data/skycraft/skycraft_values/crafting.json} (see {@link CraftingValues}).
 */
public final class CraftingItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, Skycraft.MODID);

    public static final Map<SkyOre, Supplier<Item>> RAW = new EnumMap<>(SkyOre.class);
    public static final Map<SkyOre, Supplier<Item>> INGOTS = new EnumMap<>(SkyOre.class);
    public static final Map<SmithingTier, Map<WeaponType, Supplier<Item>>> WEAPONS = new EnumMap<>(SmithingTier.class);
    public static final Map<SkyArmorMaterial, Map<net.minecraft.world.item.equipment.ArmorType, Supplier<Item>>> ARMOR = new EnumMap<>(SkyArmorMaterial.class);
    public static final Map<SkyArmorMaterial, Supplier<Item>> SHIELDS = new EnumMap<>(SkyArmorMaterial.class);
    public static final Map<String, Supplier<Item>> BLOCK_ITEMS = new LinkedHashMap<>();

    public static final net.minecraft.world.item.equipment.ArmorType[] ARMOR_TYPES = {net.minecraft.world.item.equipment.ArmorType.HELMET, net.minecraft.world.item.equipment.ArmorType.CHESTPLATE, net.minecraft.world.item.equipment.ArmorType.LEGGINGS, net.minecraft.world.item.equipment.ArmorType.BOOTS};

    static {
        for (SkyOre ore : SkyOre.values()) {
            RAW.put(ore, simple(ore.rawId()));
            INGOTS.put(ore, simple(ore.ingotId));
        }
    }

    // ------------------------------------------------------------------ materials
    public static final Supplier<Item> STEEL_INGOT = simple("steel_ingot");
    public static final Supplier<Item> DWARVEN_METAL_INGOT = simple("dwarven_metal_ingot");
    public static final Supplier<Item> DWARVEN_SCRAP = simple("dwarven_scrap");
    public static final Supplier<Item> LEATHER_STRIPS = simple("leather_strips");
    public static final Supplier<Item> HIDE = simple("hide");
    public static final Supplier<Item> FIREWOOD = simple("firewood");
    // Dragonborn and Morrowind materials: Stalhrim, chitin (Bonemold & Chitin sets), chaurus chitin (Falmer), netch leather
    public static final Supplier<Item> STALHRIM = item("stalhrim", () -> new Item(com.skycraft.platform.SkyProperties.item("stalhrim").rarity(Rarity.UNCOMMON)));
    public static final Supplier<Item> CHITIN_PLATE = simple("chitin_plate");
    public static final Supplier<Item> CHAURUS_CHITIN = simple("chaurus_chitin");
    public static final Supplier<Item> NETCH_LEATHER = simple("netch_leather");

    // ------------------------------------------------------------------ creature parts (dropped by creatures' loot tables)
    public static final Supplier<Item> DAEDRA_HEART = item("daedra_heart", () -> new Item(com.skycraft.platform.SkyProperties.item("daedra_heart").rarity(Rarity.UNCOMMON)));
    public static final Supplier<Item> DRAGON_BONE = item("dragon_bone", () -> new Item(com.skycraft.platform.SkyProperties.item("dragon_bone").rarity(Rarity.UNCOMMON).fireResistant()));
    public static final Supplier<Item> DRAGON_SCALE = item("dragon_scale", () -> new Item(com.skycraft.platform.SkyProperties.item("dragon_scale").rarity(Rarity.UNCOMMON).fireResistant()));
    public static final Supplier<Item> GIANTS_TOE = simple("giants_toe");
    public static final Supplier<Item> TROLL_FAT = simple("troll_fat");
    public static final Supplier<Item> SKEEVER_TAIL = simple("skeever_tail");
    public static final Supplier<Item> BONE_MEAL_DRAUGR = simple("bone_meal_draugr");

    // ------------------------------------------------------------------ gems & jewelry
    public static final Supplier<Item> GARNET = simple("garnet");
    public static final Supplier<Item> RUBY = simple("ruby");
    public static final Supplier<Item> SAPPHIRE = simple("sapphire");
    public static final Supplier<Item> FLAWLESS_GARNET = item("flawless_garnet", () -> new Item(com.skycraft.platform.SkyProperties.item("flawless_garnet").rarity(Rarity.UNCOMMON)));
    public static final Supplier<Item> FLAWLESS_RUBY = item("flawless_ruby", () -> new Item(com.skycraft.platform.SkyProperties.item("flawless_ruby").rarity(Rarity.UNCOMMON)));
    public static final Supplier<Item> FLAWLESS_SAPPHIRE = item("flawless_sapphire", () -> new Item(com.skycraft.platform.SkyProperties.item("flawless_sapphire").rarity(Rarity.UNCOMMON)));
    public static final Supplier<Item> FLAWLESS_DIAMOND = item("flawless_diamond", () -> new Item(com.skycraft.platform.SkyProperties.item("flawless_diamond").rarity(Rarity.RARE)));

    public static final Supplier<Item> SILVER_RING = jewelry("silver_ring");
    public static final Supplier<Item> GOLD_RING = jewelry("gold_ring");
    public static final Supplier<Item> SILVER_NECKLACE = jewelry("silver_necklace");
    public static final Supplier<Item> GOLD_NECKLACE = jewelry("gold_necklace");
    public static final Supplier<Item> SILVER_GARNET_RING = jewelry("silver_garnet_ring");
    public static final Supplier<Item> GOLD_RUBY_NECKLACE = jewelry("gold_ruby_necklace");
    public static final Supplier<Item> GOLD_DIAMOND_RING = jewelry("gold_diamond_ring");

    public static final Supplier<Item> COPPER_CIRCLET = jewelry("copper_circlet");
    public static final Supplier<Item> SILVER_CIRCLET = jewelry("silver_circlet");
    public static final Supplier<Item> GOLD_CIRCLET = jewelry("gold_circlet");
    public static final Supplier<Item> JADE_CIRCLET = jewelry("jade_circlet");
    public static final Supplier<Item> AMULET_OF_TALOS = jewelry("amulet_of_talos");
    public static final Supplier<Item> AMULET_OF_MARA = jewelry("amulet_of_mara");
    public static final Supplier<Item> JADE_PENDANT = jewelry("jade_pendant");
    public static final Supplier<Item> SAPPHIRE_PENDANT = jewelry("sapphire_pendant");

    // ------------------------------------------------------------------ weapons & armor
    static {
        for (SmithingTier tier : SmithingTier.values()) {
            Map<WeaponType, Supplier<Item>> byType = new EnumMap<>(WeaponType.class);
            for (WeaponType type : WeaponType.values()) {
                if (tier.has(type)) byType.put(type, item(type.itemId(tier), () -> weapon(tier, type)));
            }
            WEAPONS.put(tier, byType);
        }
        for (SkyArmorMaterial mat : SkyArmorMaterial.values()) {
            Map<net.minecraft.world.item.equipment.ArmorType, Supplier<Item>> byType = new EnumMap<>(net.minecraft.world.item.equipment.ArmorType.class);
            for (net.minecraft.world.item.equipment.ArmorType type : ARMOR_TYPES) {
                byType.put(type, item(mat.itemId(type), () -> {
                    Item.Properties props = com.skycraft.platform.SkyProperties.item(mat.itemId(type)).rarity(mat.rarity);
                    if (mat.fireResistant) props.fireResistant();
                    return new SkyArmorItem(mat, type, props);
                }));
            }
            ARMOR.put(mat, byType);
            if (mat.hasShield()) {
                SHIELDS.put(mat, item(mat.shieldId(), () -> {
                    Item.Properties props = com.skycraft.platform.SkyProperties.item(mat.shieldId()).rarity(mat.rarity).durability(mat.shieldDurability());
                    if (mat.fireResistant) props.fireResistant();
                    return new SkyShieldItem(mat, props);
                }));
            }
        }
        CraftingBlocks.ALL.forEach((id, block) ->
                BLOCK_ITEMS.put(id, ITEMS.register(id, () -> new BlockItem(block.get(), com.skycraft.platform.SkyProperties.item(id)))));
    }

    private CraftingItems() {}

    private static Item weapon(SmithingTier tier, WeaponType type) {
        Item.Properties props = com.skycraft.platform.SkyProperties.item(type.itemId(tier)).rarity(tier.rarity);
        if (tier.fireResistant) props.fireResistant();
        if (type == WeaponType.BOW) {
            props.durability(200 + Math.round(tier.getUses() * 0.8f));
            return new SkyBowItem(tier, props);
        }
        props.durability(Math.max(1, Math.round(tier.getUses() * type.durabilityMult)));
        if (type.axe()) return new SkyAxeItem(tier, type, props);
        return new SkyMeleeItem(tier, type, props);
    }

    private static Supplier<Item> simple(String id) {
        return item(id, () -> new Item(com.skycraft.platform.SkyProperties.item(id)));
    }

    private static Supplier<Item> jewelry(String id) {
        return item(id, () -> new Item(com.skycraft.platform.SkyProperties.item(id).stacksTo(16)));
    }

    private static Supplier<Item> item(String id, Supplier<Item> factory) {
        return ITEMS.register(id, factory);
    }

    // ------------------------------------------------------------------ lookups

    public static Item ingot(SkyOre ore) {
        return INGOTS.get(ore).get();
    }

    public static Item raw(SkyOre ore) {
        return RAW.get(ore).get();
    }

    /** The weapon of this material and family; check {@link SmithingTier#has} first (Air if the material lacks it). */
    public static Item weaponItem(SmithingTier tier, WeaponType type) {
        Supplier<Item> item = WEAPONS.get(tier).get(type);
        return item == null ? net.minecraft.world.item.Items.AIR : item.get();
    }

    @Nullable
    public static Item shield(SkyArmorMaterial mat) {
        Supplier<Item> item = SHIELDS.get(mat);
        return item == null ? null : item.get();
    }

    public static Item armor(SkyArmorMaterial mat, net.minecraft.world.item.equipment.ArmorType type) {
        return ARMOR.get(mat).get(type).get();
    }
}
