package com.skycraft.crafting;

import com.google.common.base.Suppliers;
import com.skycraft.Skycraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * Skyrim armour sets, light and heavy, from Hide and Iron to Daedric and Dragonplate, the faction sets (Thieves Guild,
 * Nightingale, Shrouded, Blades, Dawnguard, Companions' Wolf...) and the armour of the other provinces (Bonemold,
 * Ordinator, Thalmor, Ra Gada, Breton knights, Akaviri lamellar...).
 *
 * <p>The four Minecraft slots carry Skyrim's four pieces: helmet, cuirass, gauntlets (the leggings slot: its model
 * draws gauntlets on the forearms and the cuirass' greaves on the legs) and boots. Sets with {@link #shieldDefense}
 * also have a shield. Each set renders with the 3D model {@link #style} (see
 * {@code crafting.client.armor.GearArmorModels}); {@link #getName()} is {@code skycraft:<id>} so the layer textures
 * are {@code assets/skycraft/textures/models/armor/<id>_layer_1.png} / {@code _layer_2.png}.</p>
 *
 * <p>The constants are generated from {@code tools/textures/gear_catalogue.py} by {@code tools/textures/gear.py}
 * (Skyrim armour ratings converted to armour points); edit the catalogue, not the block between the markers.</p>
 */
public enum SkyArmorMaterial  {
    // id, heavy, defense {helmet, chest, gauntlets, boots}, durability mult, enchantability, toughness, knockback,
    // shield armour (0 = no shield), perk, temper perk, forge category, rarity, fire resistant, main material,
    // extra forge costs, model style, equip sound, leveled-list level, main material per piece (null = default)
    // <gear:armor>
    HIDE("hide", false, new int[]{2, 4, 3, 1}, 7, 15, 0.0f, 0.0f, 2, null, "smithing.steel_smithing", "leather", Rarity.COMMON, false, "skycraft:hide", "", "hide", "leather", 1, null),
    LEATHER("leather", false, new int[]{2, 5, 4, 1}, 13, 15, 0.0f, 0.0f, 0, null, "smithing.steel_smithing", "leather", Rarity.COMMON, false, "minecraft:leather", "", "leather", "leather", 3, null),
    FUR("fur", false, new int[]{2, 5, 4, 1}, 10, 14, 0.0f, 0.0f, 0, null, "smithing.steel_smithing", "leather", Rarity.COMMON, false, "skycraft:hide", "minecraft:leather*1", "fur", "leather", 1, null),
    STUDDED("studded", false, new int[]{2, 5, 4, 1}, 10, 14, 0.0f, 0.0f, 0, null, "smithing.steel_smithing", "leather", Rarity.COMMON, false, "minecraft:leather", "minecraft:iron_ingot*1", "studded", "chain", 3, null),
    ELVEN("elven", false, new int[]{2, 6, 4, 1}, 17, 20, 0.5f, 0.0f, 2, "smithing.elven_smithing", "smithing.elven_smithing", "elven", Rarity.UNCOMMON, false, "skycraft:refined_moonstone", "minecraft:iron_ingot*1", "elven", "gold", 12, null),
    ELVEN_GILDED("elven_gilded", false, new int[]{2, 7, 5, 2}, 23, 22, 0.75f, 0.0f, 0, "smithing.elven_smithing", "smithing.elven_smithing", "elven", Rarity.UNCOMMON, false, "skycraft:refined_moonstone", "minecraft:gold_ingot*1,minecraft:iron_ingot*1", "elven_gilded", "gold", 19, null),
    SCALED("scaled", false, new int[]{2, 6, 5, 2}, 20, 16, 0.75f, 0.0f, 0, "smithing.advanced_armors", "smithing.advanced_armors", "advanced", Rarity.UNCOMMON, false, "skycraft:steel_ingot", "skycraft:corundum_ingot*1", "scaled", "chain", 12, null),
    GLASS("glass", false, new int[]{2, 8, 6, 2}, 26, 18, 1.0f, 0.0f, 3, "smithing.glass_smithing", "smithing.glass_smithing", "glass", Rarity.UNCOMMON, false, "skycraft:refined_malachite", "skycraft:refined_moonstone*1", "glass", "diamond", 27, null),
    STALHRIM_LIGHT("stalhrim_light", false, new int[]{3, 8, 6, 2}, 28, 20, 1.5f, 0.0f, 0, "smithing.ebony_smithing", "smithing.ebony_smithing", "stalhrim", Rarity.RARE, false, "skycraft:stalhrim", "", "stalhrim_light", "diamond", 40, null),
    DRAGONSCALE("dragonscale", false, new int[]{3, 8, 6, 2}, 30, 16, 2.0f, 0.0f, 3, "smithing.dragon_armor", "smithing.dragon_armor", "dragon", Rarity.RARE, true, "skycraft:dragon_scale", "", "dragonscale", "leather", 46, null),
    FORSWORN("forsworn", false, new int[]{2, 4, 3, 1}, 7, 18, 0.0f, 0.0f, 0, null, "smithing.steel_smithing", "ancient", Rarity.COMMON, false, "skycraft:hide", "minecraft:bone*1", "forsworn", "leather", 3, null),
    THIEVES_GUILD("thieves_guild", false, new int[]{2, 6, 4, 1}, 17, 20, 0.5f, 0.0f, 0, "smithing.elven_smithing", "smithing.elven_smithing", "factions", Rarity.UNCOMMON, false, "minecraft:leather", "minecraft:gold_ingot*1", "thieves_guild", "leather", 10, null),
    NIGHTINGALE("nightingale", false, new int[]{2, 7, 5, 2}, 22, 24, 1.0f, 0.0f, 0, "smithing.glass_smithing", "smithing.glass_smithing", "factions", Rarity.RARE, false, "minecraft:leather", "skycraft:ebony_ingot*1", "nightingale", "leather", 30, null),
    SHROUDED("shrouded", false, new int[]{2, 6, 4, 2}, 15, 22, 0.5f, 0.0f, 0, "smithing.elven_smithing", "smithing.elven_smithing", "factions", Rarity.UNCOMMON, false, "minecraft:leather", "skycraft:leather_strips*2", "shrouded", "leather", 15, null),
    VAMPIRE("vampire", false, new int[]{2, 6, 4, 1}, 17, 24, 0.5f, 0.0f, 0, "smithing.elven_smithing", "smithing.elven_smithing", "factions", Rarity.UNCOMMON, false, "minecraft:leather", "skycraft:silver_ingot*1", "vampire", "leather", 20, null),
    CHITIN("chitin", false, new int[]{2, 6, 5, 1}, 18, 18, 0.5f, 0.0f, 0, "smithing.elven_smithing", "smithing.elven_smithing", "morrowind", Rarity.COMMON, false, "skycraft:chitin_plate", "", "chitin", "chain", 6, null),
    IMPERIAL_LIGHT("imperial_light", false, new int[]{2, 5, 4, 1}, 10, 14, 0.0f, 0.0f, 2, null, "smithing.steel_smithing", "imperial", Rarity.COMMON, false, "minecraft:leather", "minecraft:iron_ingot*1", "imperial_light", "leather", 3, null),
    IRON("iron", true, new int[]{2, 5, 4, 2}, 12, 9, 0.0f, 0.0f, 2, null, "smithing.steel_smithing", "iron", Rarity.COMMON, false, "minecraft:iron_ingot", "", "iron", "iron", 1, null),
    BANDED_IRON("banded_iron", true, new int[]{2, 6, 4, 2}, 15, 9, 0.25f, 0.0f, 2, "smithing.steel_smithing", "smithing.steel_smithing", "iron", Rarity.COMMON, false, "minecraft:iron_ingot", "skycraft:corundum_ingot*1", "banded_iron", "iron", 3, null),
    STEEL("steel", true, new int[]{3, 6, 5, 2}, 19, 9, 0.5f, 0.0f, 3, "smithing.steel_smithing", "smithing.steel_smithing", "steel", Rarity.COMMON, false, "skycraft:steel_ingot", "minecraft:iron_ingot*1", "steel", "iron", 6, null),
    STEEL_PLATE("steel_plate", true, new int[]{3, 8, 6, 3}, 29, 10, 1.5f, 0.0f, 0, "smithing.advanced_armors", "smithing.advanced_armors", "advanced", Rarity.UNCOMMON, false, "skycraft:steel_ingot", "skycraft:corundum_ingot*1", "steel_plate", "iron", 19, null),
    DWARVEN("dwarven", true, new int[]{3, 7, 5, 2}, 22, 10, 1.0f, 0.0f, 3, "smithing.dwarven_smithing", "smithing.dwarven_smithing", "dwarven", Rarity.COMMON, false, "skycraft:dwarven_metal_ingot", "skycraft:steel_ingot*1", "dwarven", "gold", 12, null),
    ORCISH("orcish", true, new int[]{3, 8, 6, 3}, 29, 10, 1.5f, 0.0f, 3, "smithing.orcish_smithing", "smithing.orcish_smithing", "orcish", Rarity.COMMON, false, "skycraft:orichalcum_ingot", "minecraft:iron_ingot*1", "orcish", "iron", 19, null),
    EBONY("ebony", true, new int[]{3, 9, 7, 3}, 32, 12, 2.5f, 0.05f, 4, "smithing.ebony_smithing", "smithing.ebony_smithing", "ebony", Rarity.UNCOMMON, false, "skycraft:ebony_ingot", "", "ebony", "netherite", 36, null),
    STALHRIM_HEAVY("stalhrim_heavy", true, new int[]{3, 9, 7, 3}, 35, 18, 3.0f, 0.1f, 0, "smithing.ebony_smithing", "smithing.ebony_smithing", "stalhrim", Rarity.RARE, false, "skycraft:stalhrim", "skycraft:ebony_ingot*1", "stalhrim_heavy", "diamond", 40, null),
    DAEDRIC("daedric", true, new int[]{4, 10, 8, 3}, 39, 15, 3.5f, 0.1f, 4, "smithing.daedric_smithing", "smithing.daedric_smithing", "daedric", Rarity.RARE, true, "skycraft:ebony_ingot", "skycraft:daedra_heart*1", "daedric", "netherite", 46, null),
    DRAGONPLATE("dragonplate", true, new int[]{3, 9, 7, 3}, 35, 14, 3.0f, 0.15f, 4, "smithing.dragon_armor", "smithing.dragon_armor", "dragon", Rarity.RARE, true, "skycraft:dragon_bone", "skycraft:dragon_scale*1", "dragonplate", "netherite", 46, new int[]{1, 2, 2, 1}),
    NORDIC("nordic", true, new int[]{3, 9, 7, 3}, 32, 12, 2.0f, 0.0f, 4, "smithing.advanced_armors", "smithing.advanced_armors", "nordic", Rarity.UNCOMMON, false, "skycraft:quicksilver_ingot", "skycraft:steel_ingot*1", "nordic", "iron", 27, null),
    BONEMOLD("bonemold", true, new int[]{3, 7, 5, 2}, 22, 12, 1.0f, 0.0f, 3, "smithing.advanced_armors", "smithing.advanced_armors", "morrowind", Rarity.COMMON, false, "skycraft:netch_leather", "minecraft:bone*2,minecraft:iron_ingot*1", "bonemold", "chain", 12, null),
    ANCIENT_NORD("ancient_nord", true, new int[]{2, 5, 4, 2}, 12, 9, 0.0f, 0.0f, 0, "smithing.steel_smithing", "smithing.steel_smithing", "ancient", Rarity.COMMON, false, "minecraft:iron_ingot", "skycraft:bone_meal_draugr*1", "ancient_nord", "iron", 3, null),
    FALMER("falmer", true, new int[]{3, 8, 6, 3}, 28, 14, 1.5f, 0.0f, 0, "smithing.elven_smithing", "smithing.elven_smithing", "ancient", Rarity.UNCOMMON, false, "skycraft:chaurus_chitin", "skycraft:refined_moonstone*1", "falmer", "chain", 15, null),
    BLADES("blades", true, new int[]{3, 9, 7, 2}, 35, 12, 2.0f, 0.0f, 3, "smithing.advanced_armors", "smithing.advanced_armors", "factions", Rarity.UNCOMMON, false, "skycraft:steel_ingot", "skycraft:refined_moonstone*1", "blades", "iron", 25, null),
    IMPERIAL("imperial", true, new int[]{3, 5, 4, 2}, 12, 10, 0.25f, 0.0f, 2, "smithing.steel_smithing", "smithing.steel_smithing", "imperial", Rarity.COMMON, false, "skycraft:steel_ingot", "minecraft:leather*1", "imperial", "iron", 3, null),
    STORMCLOAK("stormcloak", true, new int[]{3, 5, 4, 2}, 12, 10, 0.25f, 0.0f, 2, "smithing.steel_smithing", "smithing.steel_smithing", "imperial", Rarity.COMMON, false, "minecraft:iron_ingot", "minecraft:leather*1", "stormcloak", "iron", 3, null),
    DAWNGUARD("dawnguard", true, new int[]{3, 8, 6, 2}, 28, 12, 1.5f, 0.0f, 3, "smithing.advanced_armors", "smithing.advanced_armors", "factions", Rarity.UNCOMMON, false, "skycraft:steel_ingot", "skycraft:quicksilver_ingot*1", "dawnguard", "iron", 20, null),
    WOLF("wolf", true, new int[]{3, 6, 5, 2}, 19, 11, 0.75f, 0.0f, 0, "smithing.steel_smithing", "smithing.steel_smithing", "factions", Rarity.UNCOMMON, false, "skycraft:steel_ingot", "skycraft:hide*1", "wolf", "iron", 15, null),
    NETCH("netch", false, new int[]{2, 5, 4, 1}, 13, 16, 0.0f, 0.0f, 0, null, "smithing.steel_smithing", "morrowind", Rarity.COMMON, false, "skycraft:netch_leather", "", "netch", "leather", 3, null),
    ORDINATOR("ordinator", true, new int[]{3, 8, 6, 3}, 31, 12, 2.0f, 0.0f, 0, "smithing.advanced_armors", "smithing.advanced_armors", "morrowind", Rarity.UNCOMMON, false, "skycraft:ebony_ingot", "minecraft:gold_ingot*1", "ordinator", "netherite", 27, null),
    THALMOR("thalmor", false, new int[]{2, 5, 4, 1}, 13, 26, 0.25f, 0.0f, 0, "smithing.elven_smithing", "smithing.elven_smithing", "tamriel", Rarity.UNCOMMON, false, "minecraft:black_wool", "skycraft:refined_moonstone*1", "thalmor", "leather", 12, null),
    BOSMER("bosmer", false, new int[]{2, 5, 4, 1}, 14, 18, 0.25f, 0.0f, 2, null, "smithing.steel_smithing", "tamriel", Rarity.COMMON, false, "skycraft:hide", "minecraft:bone*1", "bosmer", "leather", 3, null),
    RA_GADA("ra_gada", false, new int[]{2, 6, 5, 2}, 19, 16, 0.75f, 0.0f, 2, "smithing.steel_smithing", "smithing.steel_smithing", "tamriel", Rarity.COMMON, false, "minecraft:leather", "skycraft:steel_ingot*1", "ra_gada", "chain", 6, null),
    BRETON("breton", true, new int[]{3, 8, 6, 3}, 30, 11, 1.5f, 0.0f, 3, "smithing.advanced_armors", "smithing.advanced_armors", "tamriel", Rarity.UNCOMMON, false, "skycraft:steel_ingot", "skycraft:silver_ingot*1", "breton", "iron", 12, null),
    ARGONIAN("argonian", false, new int[]{2, 6, 5, 1}, 18, 18, 0.5f, 0.0f, 0, null, "smithing.elven_smithing", "tamriel", Rarity.COMMON, false, "skycraft:chitin_plate", "minecraft:leather*1", "argonian", "chain", 3, null),
    KHAJIIT("khajiit", false, new int[]{2, 6, 4, 1}, 17, 18, 0.5f, 0.0f, 2, "smithing.steel_smithing", "smithing.steel_smithing", "tamriel", Rarity.COMMON, false, "minecraft:leather", "minecraft:gold_ingot*1", "khajiit", "chain", 6, null),
    ORSINIUM("orsinium", true, new int[]{3, 8, 6, 3}, 30, 10, 2.0f, 0.0f, 3, "smithing.orcish_smithing", "smithing.orcish_smithing", "orcish", Rarity.UNCOMMON, false, "skycraft:orichalcum_ingot", "minecraft:bone*1", "orsinium", "iron", 19, null),
    AKAVIRI("akaviri", false, new int[]{2, 7, 6, 2}, 24, 18, 1.0f, 0.0f, 0, "smithing.advanced_armors", "smithing.advanced_armors", "foreign", Rarity.UNCOMMON, false, "skycraft:steel_ingot", "minecraft:leather*1", "akaviri", "chain", 19, null),
    ATMORAN("atmoran", true, new int[]{3, 6, 5, 2}, 20, 10, 0.75f, 0.0f, 3, "smithing.steel_smithing", "smithing.steel_smithing", "foreign", Rarity.UNCOMMON, false, "skycraft:steel_ingot", "skycraft:corundum_ingot*1", "atmoran", "iron", 9, null),
    MAORMER("maormer", false, new int[]{2, 7, 6, 2}, 25, 20, 1.0f, 0.0f, 3, "smithing.elven_smithing", "smithing.elven_smithing", "foreign", Rarity.UNCOMMON, false, "skycraft:refined_moonstone", "minecraft:prismarine_shard*2", "maormer", "diamond", 15, null);
    // </gear:armor>

    /** Vanilla durability per piece type (multiplied by the material's durability multiplier). */
    private static final int[] HEALTH = {11, 16, 15, 13};

    public final String id;
    public final boolean heavy;
    private final int[] defense;
    private final int durability;
    private final int enchantment;
    private final float toughness;
    private final float knockback;
    /** Armour points of the set's shield while held (0: the set has no shield). */
    public final int shieldDefense;
    public final String perk;
    public final String temperPerk;
    public final String category;
    public final Rarity rarity;
    public final boolean fireResistant;
    /** 3D model style of the worn armour. */
    public final String style;
    private final String sound;
    /** Character level from which leveled and regional loot hands out this set. */
    public final int minLevel;
    @Nullable
    private final int[] counts;
    private final Identifier mainId;
    private final List<SmithingTier.Material> extras;
    private final Supplier<Item> main;
    private final Supplier<Ingredient> repair = Suppliers.memoize(() -> Ingredient.of(material()));

    SkyArmorMaterial(String id, boolean heavy, int[] defense, int durability, int enchantment, float toughness, float knockback,
                     int shieldDefense, String perk, String temperPerk, String category, Rarity rarity, boolean fireResistant,
                     String main, String extras, String style, String sound, int minLevel, @Nullable int[] counts) {
        this.id = id;
        this.heavy = heavy;
        this.defense = defense;
        this.durability = durability;
        this.enchantment = enchantment;
        this.toughness = toughness;
        this.knockback = knockback;
        this.shieldDefense = shieldDefense;
        this.perk = perk;
        this.temperPerk = temperPerk;
        this.category = category;
        this.rarity = rarity;
        this.fireResistant = fireResistant;
        this.style = style;
        this.sound = sound;
        this.minLevel = minLevel;
        this.counts = counts;
        this.mainId = Identifier.parse(main);
        this.extras = SmithingTier.Material.parse(extras);
        this.main = Suppliers.memoize(() -> {
            Item item = BuiltInRegistries.ITEM.getValue(mainId);
            return item == null ? Items.LEATHER : item;
        });
    }

    public static int index(ArmorType type) {
        return switch (type) {
            case HELMET -> 0;
            case CHESTPLATE -> 1;
            case LEGGINGS -> 2;
            default -> 3;
        };
    }

    /** Value multiplier per piece relative to the cuirass. */
    public static float valueMult(ArmorType type) {
        return switch (index(type)) {
            case 0 -> 0.5f;
            case 1 -> 1.0f;
            case 2 -> 0.7f;
            default -> 0.4f;
        };
    }

    /** Item id suffix per piece: helmet, chestplate, leggings (the gauntlets), boots. */
    public static String pieceName(ArmorType type) {
        return switch (index(type)) {
            case 0 -> "helmet";
            case 1 -> "chestplate";
            case 2 -> "leggings";
            default -> "boots";
        };
    }

    public String itemId(ArmorType type) {
        return id + "_" + pieceName(type);
    }

    public String shieldId() {
        return id + "_shield";
    }

    public boolean hasShield() {
        return shieldDefense > 0;
    }

    /** Main crafting and tempering material. */
    public Item material() {
        return main.get();
    }

    /** Extra forge costs besides the main material and leather strips. */
    public List<SmithingTier.Material> extras() {
        return extras;
    }

    /** Main material needed per piece (helmet, cuirass, gauntlets, boots). */
    public int mainCount(int piece) {
        if (counts != null) return counts[piece];
        return (heavy ? new int[]{3, 5, 4, 2} : new int[]{2, 4, 3, 2})[piece];
    }

    /** Real equipment components preserve the material's complete defense, durability and sound profile. */
    public ArmorMaterial vanilla() { return nativeMaterial.get(); }

    private final Supplier<ArmorMaterial> nativeMaterial = Suppliers.memoize(this::buildNativeMaterial);

    private ArmorMaterial buildNativeMaterial() {
        java.util.Map<ArmorType, Integer> values = new java.util.EnumMap<>(ArmorType.class);
        for (ArmorType type : new ArmorType[]{ArmorType.HELMET, ArmorType.CHESTPLATE, ArmorType.LEGGINGS, ArmorType.BOOTS}) {
            values.put(type, getDefenseForType(type));
        }
        return new ArmorMaterial(durability, java.util.Map.copyOf(values), enchantment, getEquipSound(), toughness, knockback,
                net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                        Identifier.fromNamespaceAndPath(Skycraft.MODID, "repair_armor/" + id)),
                net.minecraft.resources.ResourceKey.create(net.minecraft.world.item.equipment.EquipmentAssets.ROOT_ID,
                        Identifier.fromNamespaceAndPath(Skycraft.MODID, id)));
    }

    public int getDurabilityForType(ArmorType type) {
        return HEALTH[index(type)] * durability;
    }

    /** Shield durability: like a cuirass of the set, a little sturdier than the vanilla shield's 336 for Iron. */
    public int shieldDurability() {
        return 120 + 18 * durability;
    }

    public int getDefenseForType(ArmorType type) {
        return defense[index(type)];
    }

    public int getEnchantmentValue() {
        return enchantment;
    }

    public net.minecraft.core.Holder<SoundEvent> getEquipSound() {
        return switch (sound) {
            case "chain" -> SoundEvents.ARMOR_EQUIP_CHAIN;
            case "iron" -> SoundEvents.ARMOR_EQUIP_IRON;
            case "gold" -> SoundEvents.ARMOR_EQUIP_GOLD;
            case "diamond" -> SoundEvents.ARMOR_EQUIP_DIAMOND;
            case "netherite" -> SoundEvents.ARMOR_EQUIP_NETHERITE;
            default -> SoundEvents.ARMOR_EQUIP_LEATHER;
        };
    }

    public Ingredient getRepairIngredient() {
        return repair.get();
    }

    public String getName() {
        return Skycraft.MODID + ":" + id;
    }

    public float getToughness() {
        return toughness;
    }

    public float getKnockbackResistance() {
        return knockback;
    }
}
