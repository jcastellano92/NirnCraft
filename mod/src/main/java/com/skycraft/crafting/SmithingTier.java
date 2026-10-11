package com.skycraft.crafting;

import com.google.common.base.Suppliers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Skyrim weapon materials, from Iron to Dragonbone, plus Dawnguard/Dragonborn materials and the arms of the other
 * provinces (Redguard scimitars, Akaviri katanas, Khajiiti sabers...). Implements the vanilla {@link Tier} so the
 * weapons are regular swords/axes. Damage bonus steps by 0.5 per point of Skyrim sword damage: an Iron Sword hits like
 * a vanilla iron sword (6), a Daedric Sword (9.5) beats netherite (8) and Dragonbone is the top tier.
 *
 * <p>The constants are generated from {@code tools/textures/gear_catalogue.py} by {@code tools/textures/gear.py};
 * edit the catalogue, not the block between the {@code gear:tiers} markers. Not every material makes every weapon
 * family ({@link #has}).</p>
 */
public enum SmithingTier implements Tier {
    // id, forge category, uses, mining speed, damage bonus, harvest level, enchantability, bow arrow damage, perk,
    // temper perk, rarity, fire resistant, main material, extra forge costs, weapon families, leveled-list level
    // <gear:tiers>
    IRON("iron", "iron", 250, 6.0f, 2.0f, 2, 14, 2.0f, null, "smithing.steel_smithing", Rarity.COMMON, false, "minecraft:iron_ingot", "", "dsamgbhw", 1),
    STEEL("steel", "steel", 400, 6.45f, 2.5f, 2, 12, 2.15f, "smithing.steel_smithing", "smithing.steel_smithing", Rarity.COMMON, false, "skycraft:steel_ingot", "minecraft:iron_ingot*1", "dsamgbhw", 6),
    ORCISH("orcish", "orcish", 550, 6.9f, 3.0f, 3, 10, 2.3f, "smithing.orcish_smithing", "smithing.orcish_smithing", Rarity.COMMON, false, "skycraft:orichalcum_ingot", "minecraft:iron_ingot*1", "dsamgbhw", 12),
    DWARVEN("dwarven", "dwarven", 700, 7.35f, 3.5f, 3, 12, 2.5f, "smithing.dwarven_smithing", "smithing.dwarven_smithing", Rarity.COMMON, false, "skycraft:dwarven_metal_ingot", "minecraft:iron_ingot*1", "dsamgbhw", 12),
    ELVEN("elven", "elven", 650, 7.8f, 4.0f, 3, 20, 2.65f, "smithing.elven_smithing", "smithing.elven_smithing", Rarity.UNCOMMON, false, "skycraft:refined_moonstone", "minecraft:iron_ingot*1,skycraft:quicksilver_ingot*1", "dsamgbhw", 19),
    GLASS("glass", "glass", 600, 8.25f, 4.5f, 3, 18, 2.85f, "smithing.glass_smithing", "smithing.glass_smithing", Rarity.UNCOMMON, false, "skycraft:refined_malachite", "skycraft:refined_moonstone*1", "dsamgbhw", 27),
    EBONY("ebony", "ebony", 1500, 8.7f, 5.0f, 4, 14, 3.05f, "smithing.ebony_smithing", "smithing.ebony_smithing", Rarity.UNCOMMON, false, "skycraft:ebony_ingot", "", "dsamgbhw", 36),
    DAEDRIC("daedric", "daedric", 2000, 9.15f, 5.5f, 4, 16, 3.3f, "smithing.daedric_smithing", "smithing.daedric_smithing", Rarity.RARE, true, "skycraft:ebony_ingot", "skycraft:daedra_heart*1", "dsamgbhw", 46),
    DRAGONBONE("dragonbone", "dragon", 2200, 9.6f, 6.0f, 4, 18, 3.5f, "smithing.dragon_armor", "smithing.dragon_armor", Rarity.RARE, true, "skycraft:dragon_bone", "skycraft:ebony_ingot*1", "dsamgbhw", 46),
    SILVER("silver", "silver", 380, 6.45f, 2.5f, 2, 22, 2.15f, "smithing.steel_smithing", "smithing.steel_smithing", Rarity.UNCOMMON, false, "skycraft:silver_ingot", "skycraft:steel_ingot*1", "dsamgbhw", 6),
    ANCIENT_NORD("ancient_nord", "ancient", 380, 6.45f, 2.5f, 2, 12, 2.2f, "smithing.steel_smithing", "smithing.steel_smithing", Rarity.COMMON, false, "minecraft:iron_ingot", "skycraft:corundum_ingot*1", "dsamgbhw", 3),
    FORSWORN("forsworn", "ancient", 300, 6.45f, 2.5f, 2, 16, 2.25f, null, "smithing.steel_smithing", Rarity.COMMON, false, "minecraft:bone", "skycraft:hide*1", "dsamgbhw", 3),
    IMPERIAL("imperial", "imperial", 420, 6.45f, 2.5f, 2, 14, 2.2f, "smithing.steel_smithing", "smithing.steel_smithing", Rarity.COMMON, false, "skycraft:steel_ingot", "skycraft:corundum_ingot*1", "dsamgbhw", 3),
    FALMER("falmer", "ancient", 520, 7.35f, 3.5f, 3, 14, 2.5f, "smithing.elven_smithing", "smithing.elven_smithing", Rarity.COMMON, false, "skycraft:chaurus_chitin", "skycraft:refined_moonstone*1", "dsamgbhw", 15),
    NORDIC("nordic", "nordic", 900, 7.8f, 4.0f, 3, 16, 2.65f, "smithing.advanced_armors", "smithing.advanced_armors", Rarity.UNCOMMON, false, "skycraft:quicksilver_ingot", "skycraft:steel_ingot*1", "dsamgbhw", 27),
    SKYFORGE("skyforge", "steel", 820, 7.8f, 4.0f, 3, 16, 2.65f, "smithing.advanced_armors", "smithing.steel_smithing", Rarity.UNCOMMON, false, "skycraft:steel_ingot", "skycraft:corundum_ingot*1,skycraft:silver_ingot*1", "dsamgbhw", 19),
    AKAVIRI("akaviri", "foreign", 900, 7.8f, 4.0f, 3, 16, 2.65f, "smithing.advanced_armors", "smithing.advanced_armors", Rarity.UNCOMMON, false, "skycraft:steel_ingot", "skycraft:refined_moonstone*1", "dsamgbhw", 19),
    STALHRIM("stalhrim", "stalhrim", 1800, 8.93f, 5.25f, 4, 20, 3.2f, "smithing.ebony_smithing", "smithing.ebony_smithing", Rarity.RARE, false, "skycraft:stalhrim", "", "dsamgbhw", 40),
    CHITIN("chitin", "morrowind", 500, 6.9f, 3.0f, 2, 16, 2.3f, "smithing.elven_smithing", "smithing.elven_smithing", Rarity.COMMON, false, "skycraft:chitin_plate", "", "dsamw", 3),
    REDGUARD("redguard", "tamriel", 650, 7.35f, 3.5f, 3, 14, 2.45f, "smithing.steel_smithing", "smithing.steel_smithing", Rarity.COMMON, false, "skycraft:steel_ingot", "skycraft:corundum_ingot*1", "dsamgw", 6),
    YOKUDAN("yokudan", "foreign", 950, 8.25f, 4.5f, 3, 18, 2.8f, "smithing.advanced_armors", "smithing.advanced_armors", Rarity.UNCOMMON, false, "skycraft:steel_ingot", "skycraft:refined_moonstone*1", "dsgbw", 19),
    KHAJIIT("khajiit", "tamriel", 600, 7.12f, 3.25f, 3, 16, 2.35f, "smithing.steel_smithing", "smithing.steel_smithing", Rarity.COMMON, false, "skycraft:steel_ingot", "minecraft:gold_ingot*1", "dsagw", 6),
    BOSMER("bosmer", "tamriel", 460, 6.9f, 3.0f, 2, 18, 2.6f, null, "smithing.steel_smithing", Rarity.COMMON, false, "minecraft:bone", "skycraft:hide*1", "dsamw", 3),
    BRETON("breton", "tamriel", 680, 7.35f, 3.5f, 3, 16, 2.4f, "smithing.steel_smithing", "smithing.steel_smithing", Rarity.COMMON, false, "skycraft:steel_ingot", "skycraft:silver_ingot*1", "dsmghw", 6),
    ARGONIAN("argonian", "tamriel", 480, 6.9f, 3.0f, 2, 18, 2.35f, null, "smithing.elven_smithing", Rarity.COMMON, false, "skycraft:chitin_plate", "minecraft:bone*1", "dsamw", 3),
    ATMORAN("atmoran", "foreign", 750, 7.35f, 3.5f, 3, 14, 2.45f, "smithing.steel_smithing", "smithing.steel_smithing", Rarity.UNCOMMON, false, "skycraft:steel_ingot", "skycraft:corundum_ingot*1", "dsagbh", 12),
    MAORMER("maormer", "foreign", 700, 7.8f, 4.0f, 3, 20, 2.7f, "smithing.elven_smithing", "smithing.elven_smithing", Rarity.UNCOMMON, false, "skycraft:refined_moonstone", "minecraft:prismarine_shard*1", "dsaw", 15);
    // </gear:tiers>

    public final String id;
    /** Forge recipe category. */
    public final String category;
    private final int uses;
    private final float speed;
    private final float damage;
    private final int level;
    private final int enchantment;
    /** Arrow base damage when shot from this tier's bow (vanilla arrows: 2.0). */
    public final float bowDamage;
    /** Perk needed to forge this tier (null = anyone). */
    public final String perk;
    /** Perk that doubles tempering for this tier and allows Legendary. */
    public final String temperPerk;
    public final Rarity rarity;
    public final boolean fireResistant;
    /** Character level from which leveled and regional loot hands out this material. */
    public final int minLevel;
    private final ResourceLocation mainId;
    private final List<Material> extras;
    private final String families;
    private final Supplier<Item> main;
    private final Supplier<Ingredient> repair = Suppliers.memoize(() -> Ingredient.of(material()));

    /** An extra forge cost: {@code count} of the item {@code id}. */
    public record Material(ResourceLocation id, int count) {
        public Item item() {
            Item item = ForgeRegistries.ITEMS.getValue(id);
            return item == null ? Items.AIR : item;
        }

        /** Parses {@code "ns:item*2,ns:other*1"}. */
        static List<Material> parse(String spec) {
            List<Material> out = new ArrayList<>();
            if (spec == null || spec.isEmpty()) return List.of();
            for (String part : spec.split(",")) {
                String[] kv = part.split("\\*");
                out.add(new Material(new ResourceLocation(kv[0]), kv.length > 1 ? Integer.parseInt(kv[1]) : 1));
            }
            return List.copyOf(out);
        }
    }

    SmithingTier(String id, String category, int uses, float speed, float damage, int level, int enchantment, float bowDamage,
                 String perk, String temperPerk, Rarity rarity, boolean fireResistant, String main, String extras, String families,
                 int minLevel) {
        this.id = id;
        this.category = category;
        this.uses = uses;
        this.speed = speed;
        this.damage = damage;
        this.level = level;
        this.enchantment = enchantment;
        this.bowDamage = bowDamage;
        this.perk = perk;
        this.temperPerk = temperPerk;
        this.rarity = rarity;
        this.fireResistant = fireResistant;
        this.minLevel = minLevel;
        this.mainId = new ResourceLocation(main);
        this.extras = Material.parse(extras);
        this.families = families;
        this.main = Suppliers.memoize(() -> {
            Item item = ForgeRegistries.ITEMS.getValue(mainId);
            return item == null ? Items.IRON_INGOT : item;
        });
    }

    /** The main crafting and tempering material of this tier. */
    public Item material() {
        return main.get();
    }

    /** Extra forge costs besides the main material and leather strips. */
    public List<Material> extras() {
        return extras;
    }

    /** Whether this material makes the weapon family (Chitin has no greatswords, Akaviri makes every one...). */
    public boolean has(WeaponType type) {
        return families.indexOf(type.letter) >= 0;
    }

    @Override
    public int getUses() {
        return uses;
    }

    @Override
    public float getSpeed() {
        return speed;
    }

    @Override
    public float getAttackDamageBonus() {
        return damage;
    }

    @Override
    public int getLevel() {
        return level;
    }

    @Override
    public int getEnchantmentValue() {
        return enchantment;
    }

    @Override
    public Ingredient getRepairIngredient() {
        return repair.get();
    }
}
