package com.skycraft.arsenal;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.skycraft.Skycraft;
import com.skycraft.arsenal.item.ArrowKind;
import com.skycraft.arsenal.item.ArtifactItem;
import com.skycraft.arsenal.item.PolearmItem;
import com.skycraft.arsenal.item.SkyArrowItem;
import com.skycraft.crafting.item.SkyAxeItem;
import com.skycraft.crafting.item.SkyBowItem;
import com.skycraft.crafting.item.SkyMeleeItem;
import com.skycraft.crafting.item.SkyShieldItem;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import java.util.ArrayList;
import java.util.List;
import com.skycraft.core.SkyData;
import com.skycraft.crafting.CraftingItems;
import com.skycraft.crafting.SkyArmorMaterial;
import com.skycraft.crafting.SmithingTier;
import com.skycraft.crafting.Tempering;
import com.skycraft.crafting.WeaponType;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.Locale;

/**
 * Skyrim leveled lists. The loot function {@code skycraft:leveled_gear} replaces the rolled item with a Skyrim
 * weapon, armor piece, bow or arrows whose material follows the looting player's character level:
 *
 * <pre>{"function": "skycraft:leveled_gear", "kind": "weapon", "enchant_chance": 0.15, "quality_chance": 0.2}</pre>
 *
 * <p>kind is {@code weapon}, {@code armor}, {@code bow} or {@code arrow} (arrows keep the incoming stack count).
 * The player is the context's THIS_ENTITY if it's a player (chests opened by a player, including Lootr's per-player
 * rolls), else the killer / last damaging player, else the nearest player within 64 blocks of the origin, else
 * level 1; the level is clamped like Skyrim's encounter zones ({@link com.skycraft.loot.LootLevels}). Materials follow
 * the ladders of {@link com.skycraft.loot.LootTiers} (Iron/Hide 1, Steel/Leather 6, Orcish/Elven armour 12,
 * Dwarven/Scaled 18, Glass 27, Ebony 32, Daedric 46, Dragon 50): nothing above the level drops, the newest rung is 60%
 * of the drops, the one before 30%, older ones 10%. Enchanted drops get one enchantment whose chance and strength grow
 * with the level ({@link com.skycraft.loot.LootLevels#enchantChance}). Shields come from the armour ladders.
 * {@link #regional} and {@link #outfit} give the other provinces their own gear.</p>
 */
public class LeveledGear extends LootItemConditionalFunction {
    public static final DeferredRegister<LootItemFunctionType> FUNCTIONS = DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, Skycraft.MODID);
    public static final RegistryObject<LootItemFunctionType> LEVELED_GEAR = FUNCTIONS.register("leveled_gear",
            () -> new LootItemFunctionType(new Serializer()));
    public static final RegistryObject<LootItemFunctionType> LEGENDARY_ITEM = FUNCTIONS.register("legendary_item",
            () -> new LootItemFunctionType(new com.skycraft.combat.LegendaryItemFunction.Serializer()));

    public enum Kind { WEAPON, ARMOR, BOW, ARROW, SHIELD }

    /** Arrow bands (Iron, Steel, Orcish, Dwarven, Elven/Glass, Glass/Ebony, Daedric/Dragonbone), on the LootTiers ladder. */
    private static final int[] BAND_START = {1, 6, 12, 18, 27, 32, 46};

    private final Kind kind;
    private final float enchantChance;
    private final float qualityChance;

    protected LeveledGear(LootItemCondition[] conditions, Kind kind, float enchantChance, float qualityChance) {
        super(conditions);
        this.kind = kind;
        this.enchantChance = enchantChance;
        this.qualityChance = qualityChance;
    }

    @Override
    public LootItemFunctionType getType() {
        return LEVELED_GEAR.get();
    }

    @Override
    protected ItemStack run(ItemStack stack, LootContext context) {
        int level = levelOf(context);
        RandomSource random = context.getRandom();
        ItemStack out = roll(kind, level, random);
        if (out.isEmpty()) return stack;
        if (kind == Kind.ARROW) {
            out.setCount(Math.max(1, Math.min(out.getMaxStackSize(), stack.getCount() > 1 ? stack.getCount() : 4 + random.nextInt(9))));
            return out;
        }
        decorate(out, level, random, enchantChance, qualityChance);
        return out;
    }

    // ------------------------------------------------------------------ level lookup

    /**
     * Loot level of the player this loot is for (see class doc): the character level, capped by the region's band and,
     * for corpses, by the creature's level ({@link com.skycraft.loot.LootLevels#gear}).
     */
    public static int levelOf(LootContext context) {
        return com.skycraft.loot.LootLevels.gear(context);
    }

    // ------------------------------------------------------------------ rolls

    /** Skyrim leveled-list band 0..6 for a character level. */
    public static int band(int level) {
        int band = 0;
        for (int i = 0; i < BAND_START.length; i++) {
            if (level >= BAND_START[i]) band = i;
        }
        return band;
    }

    /** The current band most of the time, sometimes one or two lower. */
    public static int rollBand(int level, RandomSource random) {
        int band = band(level);
        float f = random.nextFloat();
        if (f < 0.10f) band -= 2;
        else if (f < 0.40f) band -= 1;
        return Math.max(0, band);
    }

    public static ItemStack roll(Kind kind, int level, RandomSource random) {
        return switch (kind) {
            case WEAPON -> weapon(level, random);
            case ARMOR -> armor(level, random);
            case BOW -> bow(level, random);
            case ARROW -> arrows(level, random);
            case SHIELD -> shield(level, random);
        };
    }

    /**
     * Weapon material of a band (Skyrim's leveled lists follow each material's level: Orcish and Dwarven from 12, Elven
     * from 19, Glass and Nordic from 27, Ebony from 36, Stalhrim from 40, Daedric and Dragonbone from 46).
     */
    public static SmithingTier weaponTier(int band, RandomSource random) {
        boolean alt = random.nextBoolean();
        return switch (band) {
            case 0 -> SmithingTier.IRON;
            case 1 -> random.nextInt(6) == 0 ? SmithingTier.SILVER : SmithingTier.STEEL;
            case 2 -> alt ? SmithingTier.ORCISH : SmithingTier.DWARVEN;
            case 3 -> alt ? SmithingTier.DWARVEN : SmithingTier.ELVEN;
            case 4 -> random.nextInt(4) == 0 ? SmithingTier.NORDIC : alt ? SmithingTier.ELVEN : SmithingTier.GLASS;
            case 5 -> random.nextInt(5) == 0 ? SmithingTier.STALHRIM : alt ? SmithingTier.GLASS : SmithingTier.EBONY;
            default -> alt ? SmithingTier.DAEDRIC : SmithingTier.DRAGONBONE;
        };
    }

    private static final WeaponType[] MELEE = {WeaponType.DAGGER, WeaponType.SWORD, WeaponType.WAR_AXE, WeaponType.MACE,
            WeaponType.GREATSWORD, WeaponType.BATTLEAXE, WeaponType.WARHAMMER};
    private static final int[] MELEE_WEIGHT = {15, 25, 16, 12, 12, 10, 10};

    public static ItemStack weapon(int level, RandomSource random) {
        SmithingTier tier = com.skycraft.loot.LootTiers.weapon(level, random);
        for (int attempt = 0; attempt < 4; attempt++) {
            int total = 0;
            for (int w : MELEE_WEIGHT) total += w;
            int pick = random.nextInt(total);
            WeaponType type = MELEE[0];
            for (int i = 0; i < MELEE.length; i++) {
                pick -= MELEE_WEIGHT[i];
                if (pick < 0) {
                    type = MELEE[i];
                    break;
                }
            }
            Item item = CraftingItems.weaponItem(tier, type);
            if (item != Items.AIR) return new ItemStack(item);
        }
        return new ItemStack(CraftingItems.weaponItem(SmithingTier.IRON, WeaponType.SWORD));
    }

    public static ItemStack bow(int level, RandomSource random) {
        Item bow = Items.AIR;
        for (int attempt = 0; attempt < 4 && bow == Items.AIR; attempt++) {
            bow = CraftingItems.weaponItem(com.skycraft.loot.LootTiers.weapon(level, random), WeaponType.BOW);
        }
        if (bow == Items.AIR) bow = CraftingItems.weaponItem(SmithingTier.IRON, WeaponType.BOW);
        return new ItemStack(bow);
    }

    /** Armour set of a band: heavy or light, Skycraft's own Iron and Hide at the bottom, Daedric and Dragonscale on top. */
    public static SkyArmorMaterial armorMaterial(int band, boolean heavy, RandomSource random) {
        boolean alt = random.nextBoolean();
        if (heavy) {
            return switch (band) {
                case 0 -> random.nextInt(3) == 0 ? SkyArmorMaterial.BANDED_IRON : SkyArmorMaterial.IRON;
                case 1 -> alt ? SkyArmorMaterial.STEEL : SkyArmorMaterial.BANDED_IRON;
                case 2 -> random.nextInt(3) == 0 ? SkyArmorMaterial.STEEL : SkyArmorMaterial.DWARVEN;
                case 3 -> alt ? SkyArmorMaterial.ORCISH : SkyArmorMaterial.STEEL_PLATE;
                case 4 -> random.nextInt(3) == 0 ? SkyArmorMaterial.NORDIC : alt ? SkyArmorMaterial.ORCISH : SkyArmorMaterial.STEEL_PLATE;
                case 5 -> random.nextInt(4) == 0 ? SkyArmorMaterial.STALHRIM_HEAVY : SkyArmorMaterial.EBONY;
                default -> alt ? SkyArmorMaterial.DAEDRIC : SkyArmorMaterial.DRAGONPLATE;
            };
        }
        return switch (band) {
            case 0 -> alt ? SkyArmorMaterial.HIDE : SkyArmorMaterial.FUR;
            case 1 -> alt ? SkyArmorMaterial.LEATHER : SkyArmorMaterial.STUDDED;
            case 2 -> alt ? SkyArmorMaterial.ELVEN : SkyArmorMaterial.SCALED;
            case 3 -> alt ? SkyArmorMaterial.ELVEN_GILDED : SkyArmorMaterial.SCALED;
            case 4 -> SkyArmorMaterial.GLASS;
            case 5 -> random.nextInt(4) == 0 ? SkyArmorMaterial.STALHRIM_LIGHT : SkyArmorMaterial.GLASS;
            default -> SkyArmorMaterial.DRAGONSCALE;
        };
    }

    public static ItemStack armor(int level, RandomSource random) {
        ArmorItem.Type piece = CraftingItems.ARMOR_TYPES[random.nextInt(CraftingItems.ARMOR_TYPES.length)];
        return new ItemStack(CraftingItems.armor(com.skycraft.loot.LootTiers.armor(level, random.nextBoolean(), random), piece));
    }

    /** A shield of the armour ladders (sets without a shield roll again, then fall back to Iron). */
    public static ItemStack shield(int level, RandomSource random) {
        boolean heavy = random.nextInt(3) > 0;
        for (int attempt = 0; attempt < 6; attempt++) {
            Item s = CraftingItems.shield(com.skycraft.loot.LootTiers.armor(level, attempt < 3 ? heavy : !heavy, random));
            if (s != null) return new ItemStack(s);
        }
        Item iron = CraftingItems.shield(SkyArmorMaterial.IRON);
        return iron == null ? ItemStack.EMPTY : new ItemStack(iron);
    }

    // ------------------------------------------------------------------ the other provinces' gear

    /**
     * Province gear from {@code #skycraft:gear/<gearKey>} (see {@link com.skycraft.crafting.RegionalGear}): a piece of the
     * kind that the level allows, recent materials three times as likely as old ones. Falls back to Skyrim's leveled
     * lists when the province has nothing of that kind.
     */
    public static ItemStack regional(String gearKey, Kind kind, int level, RandomSource random) {
        List<Item> items = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        int total = 0;
        int best = 0;
        for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(com.skycraft.crafting.RegionalGear.tag(gearKey))) {
            Item item = h.value();
            if (!kindMatches(kind, item)) continue;
            int min = com.skycraft.crafting.RegionalGear.minLevel(item);
            if (min > level) continue;
            int w = level - min < 12 ? 3 : 1;
            items.add(item);
            weights.add(w);
            total += w;
            best = Math.max(best, min);
        }
        // a province whose own gear tops out far below the character (Elsweyr's Khajiit steel at level 40) mixes in the
        // high materials traded across Tamriel: Glass, Ebony, Daedric
        if (total > 0 && level - best >= 15 && random.nextInt(3) == 0) {
            return kind == Kind.SHIELD ? shield(level, random) : roll(kind, level, random);
        }
        if (total > 0) {
            int pick = random.nextInt(total);
            for (int i = 0; i < items.size(); i++) {
                pick -= weights.get(i);
                if (pick < 0) return new ItemStack(items.get(i));
            }
        }
        return kind == Kind.SHIELD ? shield(level, random) : roll(kind, level, random);
    }

    private static boolean kindMatches(Kind kind, Item item) {
        return switch (kind) {
            case WEAPON -> (item instanceof SkyMeleeItem || item instanceof SkyAxeItem || item instanceof PolearmItem)
                    && !(item instanceof ArtifactItem);
            case BOW -> item instanceof SkyBowItem && !(item instanceof ArtifactItem);
            case ARMOR -> item instanceof ArmorItem && !(item instanceof ArtifactItem);
            case SHIELD -> item instanceof SkyShieldItem;
            case ARROW -> item instanceof SkyArrowItem;
        };
    }

    /** What an NPC fighter wears (see {@link #outfit}). */
    public enum Outfit { BANDIT, DRAUGR, SOLDIER }

    /**
     * Dresses a fighter from the leveled lists of the province it stands in: bandits in Morrowind wear Chitin and
     * Netch leather, in Hammerfell Ra Gada, in Cyrodiil Imperial gear; draugr wear Ancient Nord pieces. Level is the
     * creature's Skycraft level, else its region's level band.
     */
    public static void outfit(net.minecraft.world.entity.Mob mob, Outfit style, RandomSource random, float dropChance) {
        int level = npcLevel(mob);
        String key = com.skycraft.crafting.RegionalGear.gearKey(com.skycraft.economy.EconomyLoot.regionAt(mob.level(), mob.blockPosition()));
        boolean archer = random.nextFloat() < (style == Outfit.DRAUGR ? 0.25f : 0.33f);
        if (style == Outfit.DRAUGR) {
            equip(mob, EquipmentSlot.MAINHAND, archer ? ancientNord(WeaponType.BOW) : ancientNord(MELEE[1 + random.nextInt(MELEE.length - 1)]), dropChance);
            for (ArmorItem.Type piece : CraftingItems.ARMOR_TYPES) {
                if (piece != ArmorItem.Type.HELMET && random.nextFloat() < 0.4f) {
                    equip(mob, piece.getSlot(), new ItemStack(CraftingItems.armor(SkyArmorMaterial.ANCIENT_NORD, piece)), dropChance);
                } else if (piece == ArmorItem.Type.HELMET && random.nextFloat() < 0.25f) {
                    equip(mob, piece.getSlot(), new ItemStack(CraftingItems.armor(SkyArmorMaterial.ANCIENT_NORD, piece)), dropChance);
                }
            }
            return;
        }
        equip(mob, EquipmentSlot.MAINHAND, regional(key, archer ? Kind.BOW : Kind.WEAPON, level, random), dropChance);
        if (!archer && random.nextFloat() < (style == Outfit.SOLDIER ? 0.7f : 0.3f)) {
            ItemStack main = mob.getMainHandItem();
            boolean twoHanded = main.getItem() instanceof SkyMeleeItem m && m.type.twoHanded()
                    || main.getItem() instanceof SkyAxeItem a && a.type.twoHanded() || main.getItem() instanceof PolearmItem;
            if (!twoHanded) equip(mob, EquipmentSlot.OFFHAND, regional(key, Kind.SHIELD, level, random), dropChance);
        }
        float wear = style == Outfit.SOLDIER ? 0.9f : 0.5f;
        for (ArmorItem.Type piece : CraftingItems.ARMOR_TYPES) {
            if (random.nextFloat() > wear) continue;
            ItemStack stack = ItemStack.EMPTY;
            for (int attempt = 0; attempt < 8 && stack.isEmpty(); attempt++) {
                ItemStack candidate = regional(key, Kind.ARMOR, level, random);
                if (candidate.getItem() instanceof ArmorItem a && a.getType() == piece) stack = candidate;
            }
            if (stack.isEmpty()) {
                stack = new ItemStack(CraftingItems.armor(com.skycraft.loot.LootTiers.armor(level, random.nextBoolean(), random), piece));
            }
            equip(mob, piece.getSlot(), stack, dropChance);
        }
    }

    private static ItemStack ancientNord(WeaponType type) {
        Item item = CraftingItems.weaponItem(SmithingTier.ANCIENT_NORD, type);
        return new ItemStack(item == Items.AIR ? CraftingItems.weaponItem(SmithingTier.ANCIENT_NORD, WeaponType.SWORD) : item);
    }

    private static void equip(net.minecraft.world.entity.Mob mob, EquipmentSlot slot, ItemStack stack, float dropChance) {
        if (stack.isEmpty()) return;
        mob.setItemSlot(slot, stack);
        mob.setDropChance(slot, dropChance);
    }

    /** A creature's level: its Skycraft level, else the middle of its region's level band, else 1. */
    public static int npcLevel(net.minecraft.world.entity.Mob mob) {
        int lvl = mob.getPersistentData().getInt("skycraft_level");
        if (lvl > 0) return lvl;
        com.skycraft.tamriel.Region region = com.skycraft.economy.EconomyLoot.regionAt(mob.level(), mob.blockPosition());
        if (region == null) return 1;
        return Math.max(1, (region.minLevel() + region.maxLevel()) / 2);
    }

    public static ItemStack arrows(int level, RandomSource random) {
        int band = rollBand(level, random);
        boolean alt = random.nextBoolean();
        ArrowKind kind = switch (band) {
            case 0 -> ArrowKind.IRON;
            case 1 -> ArrowKind.STEEL;
            case 2 -> ArrowKind.ORCISH;
            case 3 -> ArrowKind.DWARVEN;
            case 4 -> alt ? ArrowKind.ELVEN : ArrowKind.GLASS;
            case 5 -> alt ? ArrowKind.GLASS : ArrowKind.EBONY;
            default -> alt ? ArrowKind.DAEDRIC : ArrowKind.DRAGONBONE;
        };
        if (band >= 2 && random.nextFloat() < 0.08f) {
            kind = switch (random.nextInt(3)) {
                case 0 -> ArrowKind.FIRE;
                case 1 -> ArrowKind.FROST;
                default -> ArrowKind.SHOCK;
            };
        }
        return new ItemStack(ArsenalItems.arrow(kind), 4 + random.nextInt(9));
    }

    /**
     * Optional enchantment and tempering quality ({@code skycraft_quality}). {@code enchantChance} is the table's base
     * chance, scaled by the level ({@link com.skycraft.loot.LootLevels#enchantChance}); an enchanted drop carries one
     * enchantment (two, rarely, from level 36) whose level grows with the character's.
     */
    public static void decorate(ItemStack stack, int level, RandomSource random, float enchantChance, float qualityChance) {
        float chance = com.skycraft.loot.LootLevels.enchantChance(enchantChance, level);
        if (chance > 0 && random.nextFloat() < chance && stack.isEnchantable()) enchantLeveled(stack, level, random);
        if (qualityChance > 0 && random.nextFloat() < qualityChance && Tempering.kind(stack) != Tempering.Kind.NONE) {
            int q = 1 + random.nextInt(Math.max(1, Math.min(5, 1 + level / 12)));
            Tempering.setQuality(stack, Math.min(5, q));
        }
    }

    /** Puts one leveled enchantment on the item (Skyrim's enchanted leveled items: "of Embers", "of Scorching"...). */
    public static void enchantLeveled(ItemStack stack, int level, RandomSource random) {
        int cost = Math.max(1, Math.min(30, 4 + level / 2 + random.nextInt(6)));
        java.util.List<net.minecraft.world.item.enchantment.EnchantmentInstance> options =
                EnchantmentHelper.selectEnchantment(random, stack, cost, false);
        if (options.isEmpty()) return;
        int extra = level >= 36 && options.size() > 1 && random.nextFloat() < 0.15f ? 2 : 1;
        for (int i = 0; i < extra; i++) {
            net.minecraft.world.item.enchantment.Enchantment e = options.get(i).enchantment;
            stack.enchant(e, com.skycraft.loot.LootLevels.enchantLevel(level, e.getMaxLevel(), random));
        }
    }

    // ------------------------------------------------------------------ JSON

    public static class Serializer extends LootItemConditionalFunction.Serializer<LeveledGear> {
        @Override
        public void serialize(JsonObject json, LeveledGear fn, JsonSerializationContext ctx) {
            super.serialize(json, fn, ctx);
            json.addProperty("kind", fn.kind.name().toLowerCase(Locale.ROOT));
            if (fn.enchantChance > 0) json.addProperty("enchant_chance", fn.enchantChance);
            if (fn.qualityChance > 0) json.addProperty("quality_chance", fn.qualityChance);
        }

        @Override
        public LeveledGear deserialize(JsonObject json, JsonDeserializationContext ctx, LootItemCondition[] conditions) {
            String kindName = GsonHelper.getAsString(json, "kind", "weapon").toUpperCase(Locale.ROOT);
            Kind kind;
            try {
                kind = Kind.valueOf(kindName);
            } catch (IllegalArgumentException e) {
                throw new com.google.gson.JsonSyntaxException("Unknown leveled_gear kind '" + kindName.toLowerCase(Locale.ROOT)
                        + "' (expected weapon, armor, bow, arrow or shield)");
            }
            float enchant = GsonHelper.getAsFloat(json, "enchant_chance", 0f);
            float quality = GsonHelper.getAsFloat(json, "quality_chance", 0f);
            return new LeveledGear(conditions, kind, enchant, quality);
        }
    }
}
