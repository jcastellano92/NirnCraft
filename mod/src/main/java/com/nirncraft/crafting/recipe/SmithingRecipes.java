package com.nirncraft.crafting.recipe;

import com.nirncraft.NirnCraft;
import com.nirncraft.crafting.CraftingItems;
import com.nirncraft.crafting.SkyArmorMaterial;
import com.nirncraft.crafting.SkyOre;
import com.nirncraft.crafting.SmithingTier;
import com.nirncraft.crafting.StationType;
import com.nirncraft.crafting.WeaponType;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Every station recipe, defined in code (Skyrim-like material lists). Built lazily on first use, after registries are
 * populated; identical on client and server so the client screen can list them and the server re-validates by id.
 */
public final class SmithingRecipes {
    /** Category order per station (lang key {@code category.nirncraft.smithing.<id>}). */
    public static final Map<StationType, List<String>> CATEGORIES = new EnumMap<>(StationType.class);

    static {
        // Skyrim's forge lists materials in perk order; Dawnguard/Dragonborn, faction and provincial gear follow
        CATEGORIES.put(StationType.FORGE, List.of("iron", "steel", "imperial", "elven", "advanced", "glass", "dwarven", "orcish", "ebony",
                "daedric", "dragon", "silver", "stalhrim", "nordic", "ancient", "morrowind", "factions", "tamriel", "foreign", "leather",
                "jewelry", "arrows", "stations"));
        CATEGORIES.put(StationType.SMELTER, List.of("ingots", "alloys"));
        CATEGORIES.put(StationType.TANNING_RACK, List.of("tanning", "leather"));
        CATEGORIES.put(StationType.GRINDSTONE, List.of());
        CATEGORIES.put(StationType.ARMOR_WORKBENCH, List.of());
    }

    private static List<SmithingRecipe> all;
    private static Map<String, SmithingRecipe> byId;
    private static Map<StationType, Map<String, List<SmithingRecipe>>> byStation;

    private SmithingRecipes() {}

    public static synchronized List<SmithingRecipe> all() {
        if (all == null) build();
        return all;
    }

    @Nullable
    public static SmithingRecipe get(String id) {
        all();
        return byId.get(id);
    }

    /** Recipes of a station grouped by category, in display order. */
    public static Map<String, List<SmithingRecipe>> forStation(StationType station) {
        all();
        return byStation.getOrDefault(station, Collections.emptyMap());
    }

    // ------------------------------------------------------------------ definitions

    private static void build() {
        List<SmithingRecipe> out = new ArrayList<>();
        Supplier<Item> iron = () -> Items.IRON_INGOT;
        Supplier<Item> gold = () -> Items.GOLD_INGOT;
        Supplier<Item> strips = CraftingItems.LEATHER_STRIPS;
        Supplier<Item> moonstone = () -> CraftingItems.ingot(SkyOre.MOONSTONE);
        Supplier<Item> silver = () -> CraftingItems.ingot(SkyOre.SILVER);
        Supplier<Item> malachite = () -> CraftingItems.ingot(SkyOre.MALACHITE);

        // ---------------------------------------------------------------- forge: weapons
        for (SmithingTier tier : SmithingTier.values()) {
            for (WeaponType type : WeaponType.values()) {
                if (!tier.has(type)) continue;
                List<Cost> costs = new ArrayList<>();
                int main = tier == SmithingTier.DRAGONBONE ? Math.min(type.materialCount, 3) : type.materialCount;
                costs.add(Cost.of(tier::material, main));
                for (SmithingTier.Material extra : tier.extras()) costs.add(Cost.of(extra::item, extra.count()));
                if (type == WeaponType.BOW) costs.add(Cost.of(CraftingItems.FIREWOOD, 1));
                costs.add(Cost.of(strips, type.strips));
                Supplier<Item> result = CraftingItems.WEAPONS.get(tier).get(type);
                out.add(recipe("forge/" + type.itemId(tier), StationType.FORGE, tier.category, result, 1, costs, tier.perk));
            }
        }

        // ---------------------------------------------------------------- forge: armor and shields
        // (Iron is NirnCraft's Iron set; hide, leather, fur and studded sets can also be made at the tanning rack)
        int[] stripsByPiece = {1, 3, 2, 1};
        for (SkyArmorMaterial mat : SkyArmorMaterial.values()) {
            for (net.minecraft.world.item.equipment.ArmorType type : CraftingItems.ARMOR_TYPES) {
                int i = SkyArmorMaterial.index(type);
                List<Cost> costs = armorCosts(mat, mat.mainCount(i), stripsByPiece[i]);
                Supplier<Item> result = CraftingItems.ARMOR.get(mat).get(type);
                out.add(recipe("forge/" + mat.itemId(type), StationType.FORGE, mat.category, result, 1, costs, mat.perk));
                if (mat.category.equals("leather")) {
                    out.add(recipe("tanning_rack/" + mat.itemId(type), StationType.TANNING_RACK, "leather", result, 1, costs, null));
                }
            }
            if (mat.hasShield()) {
                List<Cost> costs = armorCosts(mat, mat.heavy ? 4 : 3, 2);
                Supplier<Item> result = CraftingItems.SHIELDS.get(mat);
                out.add(recipe("forge/" + mat.shieldId(), StationType.FORGE, mat.category, result, 1, costs, mat.perk));
                if (mat.category.equals("leather")) {
                    out.add(recipe("tanning_rack/" + mat.shieldId(), StationType.TANNING_RACK, "leather", result, 1, costs, null));
                }
            }
        }

        // ---------------------------------------------------------------- forge: jewelry
        // Plain rings and necklaces are just metal (Skyrim); circlets, amulets and pendants carry a gem, and the jade
        // pieces (refined malachite) need Elven Smithing like other moonstone/malachite work.
        String jadePerk = "smithing.elven_smithing";
        Supplier<Item> copper = () -> Items.COPPER_INGOT;
        out.add(recipe("forge/silver_ring", StationType.FORGE, "jewelry", CraftingItems.SILVER_RING, 1, List.of(Cost.of(silver, 1)), null));
        out.add(recipe("forge/gold_ring", StationType.FORGE, "jewelry", CraftingItems.GOLD_RING, 1, List.of(Cost.of(gold, 1)), null));
        out.add(recipe("forge/silver_necklace", StationType.FORGE, "jewelry", CraftingItems.SILVER_NECKLACE, 1, List.of(Cost.of(silver, 2)), null));
        out.add(recipe("forge/gold_necklace", StationType.FORGE, "jewelry", CraftingItems.GOLD_NECKLACE, 1, List.of(Cost.of(gold, 2)), null));
        out.add(recipe("forge/silver_garnet_ring", StationType.FORGE, "jewelry", CraftingItems.SILVER_GARNET_RING, 1,
                List.of(Cost.of(silver, 1), Cost.of(CraftingItems.GARNET, 1)), null));
        out.add(recipe("forge/gold_ruby_necklace", StationType.FORGE, "jewelry", CraftingItems.GOLD_RUBY_NECKLACE, 1,
                List.of(Cost.of(gold, 2), Cost.of(CraftingItems.FLAWLESS_RUBY, 1)), null));
        out.add(recipe("forge/gold_diamond_ring", StationType.FORGE, "jewelry", CraftingItems.GOLD_DIAMOND_RING, 1,
                List.of(Cost.of(gold, 1), Cost.of(CraftingItems.FLAWLESS_DIAMOND, 1)), null));
        out.add(recipe("forge/copper_circlet", StationType.FORGE, "jewelry", CraftingItems.COPPER_CIRCLET, 1,
                List.of(Cost.of(copper, 2), Cost.of(CraftingItems.GARNET, 1)), null));
        out.add(recipe("forge/silver_circlet", StationType.FORGE, "jewelry", CraftingItems.SILVER_CIRCLET, 1,
                List.of(Cost.of(silver, 2), Cost.of(CraftingItems.GARNET, 1)), null));
        out.add(recipe("forge/gold_circlet", StationType.FORGE, "jewelry", CraftingItems.GOLD_CIRCLET, 1,
                List.of(Cost.of(gold, 2), Cost.of(CraftingItems.RUBY, 1)), null));
        out.add(recipe("forge/jade_circlet", StationType.FORGE, "jewelry", CraftingItems.JADE_CIRCLET, 1,
                List.of(Cost.of(gold, 2), Cost.of(malachite, 2), Cost.of(CraftingItems.SAPPHIRE, 1)), jadePerk));
        out.add(recipe("forge/amulet_of_talos", StationType.FORGE, "jewelry", CraftingItems.AMULET_OF_TALOS, 1,
                List.of(Cost.of(iron, 1), Cost.of(silver, 1), Cost.of(CraftingItems.GARNET, 1)), null));
        out.add(recipe("forge/amulet_of_mara", StationType.FORGE, "jewelry", CraftingItems.AMULET_OF_MARA, 1,
                List.of(Cost.of(gold, 2), Cost.of(CraftingItems.GARNET, 1)), null));
        out.add(recipe("forge/jade_pendant", StationType.FORGE, "jewelry", CraftingItems.JADE_PENDANT, 1,
                List.of(Cost.of(silver, 1), Cost.of(malachite, 1), Cost.of(CraftingItems.GARNET, 1)), jadePerk));
        out.add(recipe("forge/sapphire_pendant", StationType.FORGE, "jewelry", CraftingItems.SAPPHIRE_PENDANT, 1,
                List.of(Cost.of(silver, 1), Cost.of(CraftingItems.SAPPHIRE, 1)), null));
        add(out, "forge/soul_gem_petty", StationType.FORGE, "jewelry", "soul_gem_petty", 1, null,
                Cost.of(() -> Items.AMETHYST_SHARD, 4), Cost.of(() -> Items.GLASS, 1));

        // ---------------------------------------------------------------- forge: arrows, bolts and crossbows
        // (the arsenal module's items, same materials as its crafting JSONs, most of which need a 3x3 grid)
        Supplier<Item> stick = () -> Items.STICK;
        Supplier<Item> feather = () -> Items.FEATHER;
        Supplier<Item> steel = CraftingItems.STEEL_INGOT;
        Supplier<Item> dwarven = CraftingItems.DWARVEN_METAL_INGOT;
        arrow(out, "iron_arrow", () -> Items.IRON_NUGGET, 6, SmithingTier.IRON);
        arrow(out, "steel_arrow", steel, 12, SmithingTier.STEEL);
        arrow(out, "orcish_arrow", () -> CraftingItems.ingot(SkyOre.ORICHALCUM), 12, SmithingTier.ORCISH);
        arrow(out, "dwarven_arrow", dwarven, 12, SmithingTier.DWARVEN);
        arrow(out, "elven_arrow", moonstone, 12, SmithingTier.ELVEN);
        arrow(out, "glass_arrow", malachite, 12, SmithingTier.GLASS);
        arrow(out, "ebony_arrow", () -> CraftingItems.ingot(SkyOre.EBONY), 12, SmithingTier.EBONY);
        arrow(out, "dragonbone_arrow", CraftingItems.DRAGON_BONE, 12, SmithingTier.DRAGONBONE);
        add(out, "forge/daedric_arrow", StationType.FORGE, "arrows", "daedric_arrow", 24, SmithingTier.DAEDRIC.perk,
                Cost.of(() -> CraftingItems.ingot(SkyOre.EBONY), 1), Cost.of(CraftingItems.DAEDRA_HEART, 1), Cost.of(stick, 1), Cost.of(feather, 1));
        add(out, "forge/fire_arrow", StationType.FORGE, "arrows", "fire_arrow", 4, null,
                Cost.of(byId("steel_arrow"), 4), Cost.of(() -> Items.BLAZE_POWDER, 1));
        add(out, "forge/frost_arrow", StationType.FORGE, "arrows", "frost_arrow", 4, null,
                Cost.of(byId("steel_arrow"), 4), Cost.of(() -> Items.BLUE_ICE, 1));
        add(out, "forge/shock_arrow", StationType.FORGE, "arrows", "shock_arrow", 4, null,
                Cost.of(byId("steel_arrow"), 4), Cost.of(() -> Items.GLOWSTONE_DUST, 1));
        add(out, "forge/steel_bolt", StationType.FORGE, "arrows", "steel_bolt", 10, SmithingTier.STEEL.perk,
                Cost.of(steel, 1), Cost.of(stick, 1));
        add(out, "forge/dwarven_bolt", StationType.FORGE, "arrows", "dwarven_bolt", 10, SmithingTier.DWARVEN.perk,
                Cost.of(dwarven, 1), Cost.of(stick, 1));
        add(out, "forge/explosive_bolt", StationType.FORGE, "arrows", "explosive_bolt", 4, null,
                Cost.of(byId("steel_bolt"), 4), Cost.of(() -> Items.GUNPOWDER, 2));
        Supplier<Item> string = () -> Items.STRING;
        Supplier<Item> hook = () -> Items.TRIPWIRE_HOOK;
        add(out, "forge/imperial_crossbow", StationType.FORGE, "arrows", "imperial_crossbow", 1, SmithingTier.STEEL.perk,
                Cost.of(steel, 1), Cost.of(stick, 3), Cost.of(string, 2), Cost.of(hook, 1));
        add(out, "forge/enhanced_crossbow", StationType.FORGE, "arrows", "enhanced_crossbow", 1, SmithingTier.STEEL.perk,
                Cost.of(byId("imperial_crossbow"), 1), Cost.of(steel, 4));
        add(out, "forge/dwarven_crossbow", StationType.FORGE, "arrows", "dwarven_crossbow", 1, SmithingTier.DWARVEN.perk,
                Cost.of(dwarven, 4), Cost.of(string, 2), Cost.of(hook, 1));
        add(out, "forge/enhanced_dwarven_crossbow", StationType.FORGE, "arrows", "enhanced_dwarven_crossbow", 1, SmithingTier.DWARVEN.perk,
                Cost.of(byId("dwarven_crossbow"), 1), Cost.of(dwarven, 4));

        // ---------------------------------------------------------------- forge: polearms and magic stations (arsenal/magic items)
        for (SmithingTier tier : SmithingTier.values()) {
            for (com.nirncraft.arsenal.item.PolearmKind kind : com.nirncraft.arsenal.item.PolearmKind.VALUES) {
                int main = tier == SmithingTier.DRAGONBONE ? Math.min(kind.materialCount, 3) : kind.materialCount;
                add(out, "forge/" + kind.itemId(tier), StationType.FORGE, tier.category, kind.itemId(tier), 1, tier.perk,
                        Cost.of(tier::material, main), Cost.of(() -> Items.STICK, 2), Cost.of(strips, kind.strips));
            }
        }
        add(out, "forge/unenchanted_staff", StationType.FORGE, "stations", "unenchanted_staff", 1, null,
                Cost.of(() -> Items.STICK, 3), Cost.of(iron, 1), Cost.of(() -> Items.AMETHYST_SHARD, 2));
        add(out, "forge/spellmaking_altar", StationType.FORGE, "stations", "spellmaking_altar", 1, null,
                Cost.of(() -> Items.STONE_BRICKS, 6), Cost.of(() -> Items.AMETHYST_SHARD, 6), Cost.of(gold, 2), Cost.of(() -> Items.BOOK, 1));
        add(out, "forge/staff_enchanter", StationType.FORGE, "stations", "staff_enchanter", 1, null,
                Cost.of(() -> Items.STONE_BRICKS, 4), Cost.of(dwarven, 2), Cost.of(() -> Items.AMETHYST_SHARD, 4));

        // ---------------------------------------------------------------- forge: stations and shrines
        // (their crafting JSONs need a 3x3 grid, and a vanilla crafting table opens the armor workbench unless sneaking)
        Cost planks = Cost.tag(ItemTags.PLANKS, () -> Items.OAK_PLANKS, 5);
        add(out, "forge/blacksmith_forge", StationType.FORGE, "stations", "blacksmith_forge", 1, null,
                Cost.of(iron, 3), Cost.tag(ItemTags.STONE_CRAFTING_MATERIALS, () -> Items.COBBLESTONE, 5), Cost.of(() -> Items.FURNACE, 1));
        add(out, "forge/smelter", StationType.FORGE, "stations", "smelter", 1, null,
                Cost.of(iron, 1), Cost.tag(ItemTags.STONE_CRAFTING_MATERIALS, () -> Items.COBBLESTONE, 7), Cost.of(() -> Items.FURNACE, 1));
        add(out, "forge/armor_workbench", StationType.FORGE, "stations", "armor_workbench", 1, null, Cost.of(iron, 3), planks);
        add(out, "forge/tanning_rack", StationType.FORGE, "stations", "tanning_rack", 1, null,
                Cost.of(stick, 6), Cost.of(() -> Items.LEATHER, 2));
        add(out, "forge/cooking_pot", StationType.FORGE, "stations", "cooking_pot", 1, null, Cost.of(iron, 5), Cost.of(stick, 1));
        add(out, "forge/alchemy_lab", StationType.FORGE, "stations", "alchemy_lab", 1, null,
                Cost.of(() -> Items.BREWING_STAND, 1), Cost.of(() -> Items.CAULDRON, 1), Cost.of(() -> Items.GLASS_BOTTLE, 2), planks);
        add(out, "forge/arcane_enchanter", StationType.FORGE, "stations", "arcane_enchanter", 1, null,
                Cost.of(() -> Items.OBSIDIAN, 4), Cost.of(() -> Items.AMETHYST_SHARD, 2), Cost.of(gold, 2), Cost.of(() -> Items.BOOK, 1));
        out.add(recipe("forge/enchanting_table", StationType.FORGE, "stations", () -> Items.ENCHANTING_TABLE, 1,
                List.of(Cost.of(() -> Items.OBSIDIAN, 4), Cost.of(() -> Items.DIAMOND, 2), Cost.of(() -> Items.BOOK, 1)), null));
        shrine(out, "akatosh", () -> Items.CLOCK);
        shrine(out, "arkay", () -> Items.BONE);
        shrine(out, "dibella", () -> Items.PINK_TULIP);
        shrine(out, "julianos", () -> Items.BOOK);
        shrine(out, "kynareth", () -> Items.FEATHER);
        shrine(out, "mara", () -> Items.POPPY);
        shrine(out, "stendarr", () -> Items.SHIELD);
        shrine(out, "talos", () -> Items.IRON_SWORD);
        shrine(out, "zenithar", () -> Items.GOLD_INGOT);

        // ---------------------------------------------------------------- smelter
        for (SkyOre ore : SkyOre.values()) {
            out.add(recipe("smelter/" + ore.ingotId, StationType.SMELTER, "ingots", CraftingItems.INGOTS.get(ore), 1,
                    List.of(Cost.of(CraftingItems.RAW.get(ore), 1)), null));
        }
        for (SkyOre ore : SkyOre.values()) {
            // silk-touched vein blocks (the smelting/blasting JSONs only run in a vanilla furnace)
            out.add(recipe("smelter/" + ore.ingotId + "_from_" + ore.oreId(), StationType.SMELTER, "ingots", CraftingItems.INGOTS.get(ore), 1,
                    List.of(Cost.of(CraftingItems.BLOCK_ITEMS.get(ore.oreId()), 1)), null));
            out.add(recipe("smelter/" + ore.ingotId + "_from_" + ore.deepslateOreId(), StationType.SMELTER, "ingots", CraftingItems.INGOTS.get(ore), 1,
                    List.of(Cost.of(CraftingItems.BLOCK_ITEMS.get(ore.deepslateOreId()), 1)), null));
        }
        out.add(recipe("smelter/iron_ingot", StationType.SMELTER, "ingots", iron, 1, List.of(Cost.of(() -> Items.RAW_IRON, 1)), null));
        out.add(recipe("smelter/gold_ingot", StationType.SMELTER, "ingots", gold, 1, List.of(Cost.of(() -> Items.RAW_GOLD, 1)), null));
        out.add(recipe("smelter/copper_ingot", StationType.SMELTER, "ingots", () -> Items.COPPER_INGOT, 1, List.of(Cost.of(() -> Items.RAW_COPPER, 1)), null));
        out.add(recipe("smelter/dwarven_metal_ingot", StationType.SMELTER, "ingots", CraftingItems.DWARVEN_METAL_INGOT, 2,
                List.of(Cost.of(CraftingItems.DWARVEN_SCRAP, 1)), null));
        Cost coal = Cost.tag(ItemTags.COALS, () -> Items.COAL, 1);
        out.add(recipe("smelter/steel_ingot", StationType.SMELTER, "alloys", CraftingItems.STEEL_INGOT, 2,
                List.of(Cost.of(iron, 1), Cost.of(() -> CraftingItems.ingot(SkyOre.CORUNDUM), 1), coal), null));
        out.add(recipe("smelter/steel_ingot_from_ore", StationType.SMELTER, "alloys", CraftingItems.STEEL_INGOT, 2,
                List.of(Cost.of(() -> Items.RAW_IRON, 1), Cost.of(() -> CraftingItems.raw(SkyOre.CORUNDUM), 1), coal), null));

        // ---------------------------------------------------------------- tanning rack
        out.add(recipe("tanning_rack/leather", StationType.TANNING_RACK, "tanning", () -> Items.LEATHER, 1,
                List.of(Cost.of(CraftingItems.HIDE, 1)), null));
        out.add(recipe("tanning_rack/leather_from_rabbit_hide", StationType.TANNING_RACK, "tanning", () -> Items.LEATHER, 1,
                List.of(Cost.of(() -> Items.RABBIT_HIDE, 3)), null));
        out.add(recipe("tanning_rack/leather_strips", StationType.TANNING_RACK, "tanning", CraftingItems.LEATHER_STRIPS, 4,
                List.of(Cost.of(() -> Items.LEATHER, 1)), null));

        // ---------------------------------------------------------------- index
        Map<String, SmithingRecipe> ids = new LinkedHashMap<>();
        for (SmithingRecipe r : out) ids.put(r.id(), r);
        Map<StationType, Map<String, List<SmithingRecipe>>> stations = new EnumMap<>(StationType.class);
        for (StationType station : StationType.values()) {
            Map<String, List<SmithingRecipe>> cats = new LinkedHashMap<>();
            for (String cat : CATEGORIES.get(station)) {
                List<SmithingRecipe> list = new ArrayList<>();
                for (SmithingRecipe r : out) if (r.station() == station && r.category().equals(cat)) list.add(r);
                if (!list.isEmpty()) cats.put(cat, Collections.unmodifiableList(list));
            }
            stations.put(station, Collections.unmodifiableMap(cats));
        }
        all = Collections.unmodifiableList(out);
        byId = ids;
        byStation = stations;
    }

    private static List<Cost> armorCosts(SkyArmorMaterial mat, int main, int strips) {
        List<Cost> costs = new ArrayList<>();
        costs.add(Cost.of(mat::material, main));
        for (SmithingTier.Material extra : mat.extras()) costs.add(Cost.of(extra::item, extra.count()));
        costs.add(Cost.of(CraftingItems.LEATHER_STRIPS, strips));
        return List.copyOf(costs);
    }

    private static SmithingRecipe recipe(String id, StationType station, String category, Supplier<? extends ItemLike> result, int count,
                                         List<Cost> costs, @Nullable String perk) {
        return new SmithingRecipe(id, station, category, result, count, List.copyOf(costs), perk);
    }

    /** A forge recipe for another module's {@code nirncraft:<resultId>} item; skipped if that item isn't registered. */
    private static void add(List<SmithingRecipe> out, String id, StationType station, String category, String resultId, int count,
                            @Nullable String perk, Cost... costs) {
        Supplier<Item> result = byId(resultId);
        if (result.get() == Items.AIR) return;
        out.add(recipe(id, station, category, result, count, List.of(costs), perk));
    }

    /** Tiered arrow: one head material, a stick and a feather; needs the tier's smithing perk like its weapons. */
    private static void arrow(List<SmithingRecipe> out, String id, Supplier<? extends ItemLike> head, int count, SmithingTier tier) {
        add(out, "forge/" + id, StationType.FORGE, "arrows", id, count, tier.perk,
                Cost.of(head, 1), Cost.of(() -> Items.STICK, 1), Cost.of(() -> Items.FEATHER, 1));
    }

    private static void shrine(List<SmithingRecipe> out, String divine, Supplier<? extends ItemLike> offering) {
        add(out, "forge/shrine_of_" + divine, StationType.FORGE, "stations", "shrine_of_" + divine, 1, null,
                Cost.of(() -> Items.STONE_BRICKS, 5), Cost.of(() -> Items.CANDLE, 1), Cost.of(offering, 1));
    }

    private static Supplier<Item> byId(String path) {
        Identifier id = Identifier.fromNamespaceAndPath(NirnCraft.MODID, path);
        return () -> {
            Item item = BuiltInRegistries.ITEM.getValue(id);
            return item == null ? Items.AIR : item;
        };
    }
}
