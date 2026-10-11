package com.skycraft.economy;

import com.skycraft.arsenal.LeveledGear;
import com.skycraft.arsenal.item.ArtifactItem;
import com.skycraft.core.SkyData;
import com.skycraft.crafting.RegionalGear;
import com.skycraft.crafting.SoulGems;
import com.skycraft.crafting.arcane.item.SoulGemItem;
import com.skycraft.lore.LoreBookItem;
import com.skycraft.lore.LoreBooks;
import com.skycraft.magic.spell.School;
import com.skycraft.tamriel.Region;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What a merchant has for sale after a restock (every {@value Shop#RESTOCK_DAYS} days), Skyrim style:
 * <ul>
 *   <li>every shop type sells from its tag {@code #skycraft:merchant/<type>} plus its province's goods
 *   {@code #skycraft:merchant/<type>/<gear key>} (three times as likely): Morrowind's apothecaries have kwama eggs and
 *   saltrice, Khajiit caravans moon sugar and skooma;</li>
 *   <li>blacksmiths and fletchers forge the province's gear ({@code #skycraft:gear/<gear key>}) leveled to the customer,
 *   with some enchanted pieces in bigger shops;</li>
 *   <li>court wizards sell spell tomes the customer's school skill is ready for, soul gems (some filled) and staves;</li>
 *   <li>booksellers sell the province's own books, skill books and novice tomes;</li>
 *   <li>apothecaries, general stores and fences sell Skyrim potions and poisons;</li>
 *   <li>jewellers sell rings, necklaces and circlets (some enchanted), gems and precious metals.</li>
 * </ul>
 * The shop's level (1..5) sets how much it carries and how rare its goods may be.
 */
public final class ShopStock {
    private static final com.skycraft.crafting.arcane.alchemy.AlchemyEffect[] RESTORATIVES = {
            com.skycraft.crafting.arcane.alchemy.AlchemyEffect.RESTORE_HEALTH, com.skycraft.crafting.arcane.alchemy.AlchemyEffect.RESTORE_HEALTH,
            com.skycraft.crafting.arcane.alchemy.AlchemyEffect.RESTORE_MAGICKA, com.skycraft.crafting.arcane.alchemy.AlchemyEffect.RESTORE_STAMINA,
            com.skycraft.crafting.arcane.alchemy.AlchemyEffect.CURE_DISEASE};

    private ShopStock() {}

    public static void generate(Shop shop, @Nullable Player customer, RandomSource rnd) {
        LivingEntity npc = shop.npc();
        int level = Merchants.level(npc);
        Region region = EconomyLoot.regionAt(npc.level(), npc.blockPosition());
        String key = RegionalGear.gearKey(region);
        String province = region == null ? "skyrim" : region.province();
        // like Skyrim, gear follows the customer's level; without one, the shop's size stands in
        int gearLevel = customer != null ? Math.max(1, SkyData.get(customer).getLevel()) : 1 + 6 * (level - 1);
        List<ShopType> pools = Merchants.pools(npc);
        int share = Math.max(3, (6 + 3 * level) / Math.max(1, pools.size()));

        for (ShopType t : pools) {
            tagged(shop, t, key, share, level, rnd);
            switch (t) {
                case BLACKSMITH -> {
                    int n = 3 + level;
                    for (int i = 0; i < n; i++) {
                        float f = rnd.nextFloat();
                        LeveledGear.Kind kind = f < 0.45f ? LeveledGear.Kind.WEAPON : f < 0.85f ? LeveledGear.Kind.ARMOR : LeveledGear.Kind.SHIELD;
                        gear(shop, LeveledGear.regional(key, kind, gearLevel, rnd), gearLevel, level, rnd);
                    }
                }
                case FLETCHER -> {
                    for (int i = 0; i < 1 + level / 2; i++) gear(shop, LeveledGear.regional(key, LeveledGear.Kind.BOW, gearLevel, rnd), gearLevel, level, rnd);
                    for (int i = 0; i < 2 + level; i++) {
                        ItemStack arrows = LeveledGear.arrows(gearLevel, rnd);
                        arrows.setCount(Math.min(arrows.getMaxStackSize(), 10 + rnd.nextInt(15)));
                        shop.addToStock(arrows);
                    }
                }
                case ARCANE -> {
                    Set<School> any = EnumSet.noneOf(School.class);
                    for (int i = 0; i < 2 + level; i++) add(shop, EconomyLoot.tome(gearLevel, customer, true, level, any, rnd));
                    soulGems(shop, level, rnd);
                    if (level >= 2) staves(shop, gearLevel, level, rnd);
                    for (int i = 0; i < 1 + level / 2; i++) add(shop, potion(false, rnd, level, true));
                }
                case LIBRARY -> {
                    for (int i = 0; i < 2 + level; i++) {
                        LoreBooks.Book b = LoreBooks.randomFor(rnd, province, book -> true);
                        if (b != null) add(shop, LoreBookItem.create(b));
                    }
                    List<Item> skillBooks = items(ItemCategory.SKILL_BOOKS);
                    int n = 1 + (level >= 3 ? 1 : 0) + (level >= 5 ? 1 : 0);
                    for (int i = 0; i < n && !skillBooks.isEmpty(); i++) add(shop, new ItemStack(skillBooks.get(rnd.nextInt(skillBooks.size()))));
                    add(shop, EconomyLoot.tome(1, null, false, level, EnumSet.noneOf(School.class), rnd));
                }
                case APOTHECARY -> {
                    for (int i = 0; i < 2 + level; i++) add(shop, potion(false, rnd, level, false));
                    if (rnd.nextFloat() < 0.6f) add(shop, potion(true, rnd, level, false));
                }
                case GENERAL -> {
                    for (int i = 0; i < 1 + level / 2; i++) add(shop, potion(false, rnd, level, true));
                    if (rnd.nextFloat() < 0.5f) gear(shop, LeveledGear.regional(key, LeveledGear.Kind.WEAPON, Math.min(gearLevel, 12), rnd), gearLevel, 1, rnd);
                }
                case FENCE -> {
                    for (int i = 0; i < 1 + level / 2; i++) add(shop, potion(true, rnd, level, false));
                    jewelry(shop, level, rnd, 2);
                }
                case JEWELER -> jewelry(shop, level, rnd, 3 + level);
                default -> {
                }
            }
        }
    }

    // ------------------------------------------------------------------ pieces

    /** Random goods of the type's tag and of its province tag (province goods three times as likely). */
    private static void tagged(Shop shop, ShopType t, String key, int entries, int level, RandomSource rnd) {
        List<Item> base = t.stockItems();
        List<Item> local = t.provinceItems(key);
        if (base.isEmpty() && local.isEmpty()) return;
        for (int i = 0; i < entries; i++) {
            boolean fromLocal = !local.isEmpty() && (base.isEmpty() || rnd.nextInt(4) > 0);
            List<Item> pool = fromLocal ? local : base;
            ItemStack stack = ItemStack.EMPTY;
            // pricey items are rarer, and rarer still in small shops
            for (int attempt = 0; attempt < 6 && stack.isEmpty(); attempt++) {
                Item item = pool.get(rnd.nextInt(pool.size()));
                if (item instanceof ArtifactItem) continue;
                ItemStack candidate = Shop.makeStock(item, rnd, level);
                if (candidate.isEmpty()) continue;
                int value = ItemValues.get(candidate);
                float accept = value <= 0 ? 1f : Math.min(1f, 120f * level / value);
                if (rnd.nextFloat() < accept) stack = candidate;
            }
            add(shop, stack);
        }
    }

    /** A piece of gear: worn a little, sometimes enchanted (bigger shops more often), sometimes tempered. */
    private static void gear(Shop shop, ItemStack stack, int gearLevel, int shopLevel, RandomSource rnd) {
        if (stack.isEmpty()) return;
        float enchant = shopLevel >= 2 ? 0.06f * shopLevel : 0f;
        LeveledGear.decorate(stack, gearLevel, rnd, enchant, 0.1f);
        add(shop, stack);
    }

    private static ItemStack potion(boolean poison, RandomSource rnd, int shopLevel, boolean restoreOnly) {
        ItemStack p;
        if (restoreOnly && !poison && rnd.nextInt(4) > 0) {
            // general stores and court wizards mostly carry the restoratives
            p = EconomyLoot.skyrimPotion(RESTORATIVES[rnd.nextInt(RESTORATIVES.length)], 4 + 8 * shopLevel, rnd);
        } else {
            p = EconomyLoot.skyrimPotion(poison, 4 + 8 * shopLevel, rnd);
        }
        if (p.getMaxStackSize() > 1) p.setCount(1 + rnd.nextInt(3));
        return p;
    }

    /** Empty soul gems by shop size, and a few filled ones (Skyrim's court wizards sell both). */
    private static void soulGems(Shop shop, int level, RandomSource rnd) {
        List<Item> gems = items(TagKey.create(Registries.ITEM, new ResourceLocation("skycraft", "soul_gems")));
        gems.removeIf(g -> !(g instanceof SoulGemItem s) || s.reusable || s.black && level < 4);
        if (gems.isEmpty()) return;
        for (int i = 0; i < 1 + level; i++) {
            SoulGemItem gem = (SoulGemItem) gems.get(rnd.nextInt(gems.size()));
            if (gem.capacity > 1 + level) continue;
            ItemStack stack = new ItemStack(gem, 1 + rnd.nextInt(2));
            if (!gem.black && rnd.nextFloat() < 0.35f) {
                stack.setCount(1);
                SoulGemItem.setSoul(stack, Math.max(SoulGems.PETTY, gem.capacity - rnd.nextInt(2)));
            }
            add(shop, stack);
        }
    }

    /** Plain (non-artifact) staves whose spell level suits the customer. */
    private static void staves(Shop shop, int gearLevel, int level, RandomSource rnd) {
        List<Item> staves = items(TagKey.create(Registries.ITEM, new ResourceLocation("skycraft", "staves")));
        staves.removeIf(s -> s instanceof ArtifactItem);
        if (staves.isEmpty()) return;
        for (int i = 0; i < level / 2; i++) {
            ItemStack staff = new ItemStack(staves.get(rnd.nextInt(staves.size())));
            int value = ItemValues.get(staff);
            if (value <= 120 + 25 * gearLevel) add(shop, staff);
        }
    }

    /** Rings, necklaces and circlets (bigger shops enchant some), gems and precious metals. */
    private static void jewelry(Shop shop, int level, RandomSource rnd, int n) {
        List<Item> jewels = items(ShopType.JEWELRY);
        jewels.removeIf(i -> i instanceof ArtifactItem || ForgeRegistries.ITEMS.getKey(i).getPath().startsWith("mask_"));
        List<Item> gems = items(TagKey.create(Registries.ITEM, new ResourceLocation("skycraft", "merchant/gems")));
        for (int i = 0; i < n; i++) {
            boolean gem = !gems.isEmpty() && (jewels.isEmpty() || rnd.nextInt(3) == 0);
            List<Item> pool = gem ? gems : jewels;
            if (pool.isEmpty()) return;
            ItemStack stack = new ItemStack(pool.get(rnd.nextInt(pool.size())));
            int value = ItemValues.get(stack);
            if (value > 150 * level && rnd.nextInt(3) > 0) continue;
            if (!gem && level >= 2 && rnd.nextFloat() < 0.1f * level) {
                stack = net.minecraft.world.item.enchantment.EnchantmentHelper.enchantItem(rnd, stack, 5 + 4 * level, false);
                if (!stack.isEnchanted()) stack = enchantJewelry(stack, rnd, level);
            }
            add(shop, stack);
        }
    }

    /**
     * Vanilla rolls only enchant enchantable items; jewellery takes the armour enchantments at the Arcane Enchanter, so
     * the jeweller's own enchanted pieces pick one of those.
     */
    private static ItemStack enchantJewelry(ItemStack stack, RandomSource rnd, int level) {
        List<net.minecraft.world.item.enchantment.Enchantment> options = new ArrayList<>();
        for (net.minecraft.world.item.enchantment.Enchantment e : ForgeRegistries.ENCHANTMENTS) {
            if (!e.isCurse() && !e.isTreasureOnly() && com.skycraft.crafting.arcane.enchant.Enchanting.fits(stack, e)) options.add(e);
        }
        if (options.isEmpty()) return stack;
        net.minecraft.world.item.enchantment.Enchantment e = options.get(rnd.nextInt(options.size()));
        ItemStack out = stack.copy();
        out.setCount(1);
        out.enchant(e, Math.min(e.getMaxLevel(), 1 + rnd.nextInt(Math.max(1, level / 2 + 1))));
        return out;
    }

    // ------------------------------------------------------------------ audit

    /** Gear keys that have province goods or gear (see {@link RegionalGear#gearKey}). */
    public static final List<String> GEAR_KEYS = List.of("skyrim", "solstheim", "cyrodiil", "morrowind", "summerset", "valenwood",
            "hammerfell", "high_rock", "orsinium", "black_marsh", "elsweyr", "akavir", "atmora", "yokuda", "pyandonea", "realm");

    /**
     * Every item some merchant can stock, with the shop types that may sell it (for {@code tools/audit_items.py}).
     * Mirrors {@link #generate}: tags, province tags, province gear, tomes, soul gems, staves, books and jewellery.
     */
    public static java.util.Map<Item, Set<ShopType>> candidates() {
        java.util.Map<Item, Set<ShopType>> out = new java.util.LinkedHashMap<>();
        java.util.function.BiConsumer<Item, ShopType> put = (item, t) -> out.computeIfAbsent(item, k -> EnumSet.noneOf(ShopType.class)).add(t);
        for (ShopType t : ShopType.values()) {
            for (Item i : t.stockItems()) put.accept(i, t);
            for (String key : GEAR_KEYS) for (Item i : t.provinceItems(key)) put.accept(i, t);
        }
        for (String key : GEAR_KEYS) {
            for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(RegionalGear.tag(key))) {
                Item i = h.value();
                if (i instanceof ArtifactItem) continue;
                put.accept(i, i instanceof com.skycraft.crafting.item.SkyBowItem ? ShopType.FLETCHER : ShopType.BLACKSMITH);
            }
        }
        for (var ro : com.skycraft.magic.MagicRegistry.TOMES.values()) put.accept(ro.get(), ShopType.ARCANE);
        for (Item i : items(TagKey.create(Registries.ITEM, new ResourceLocation("skycraft", "soul_gems")))) {
            if (!(i instanceof SoulGemItem s) || !s.reusable) put.accept(i, ShopType.ARCANE);
        }
        for (Item i : items(TagKey.create(Registries.ITEM, new ResourceLocation("skycraft", "staves")))) {
            if (!(i instanceof ArtifactItem)) put.accept(i, ShopType.ARCANE);
        }
        for (Item i : items(ItemCategory.SKILL_BOOKS)) put.accept(i, ShopType.LIBRARY);
        put.accept(com.skycraft.lore.LoreModule.BOOK.get(), ShopType.LIBRARY);
        for (Item i : items(ShopType.JEWELRY)) {
            if (!(i instanceof ArtifactItem) && !ForgeRegistries.ITEMS.getKey(i).getPath().startsWith("mask_")) put.accept(i, ShopType.JEWELER);
        }
        return out;
    }

    private static void add(Shop shop, ItemStack stack) {
        if (!stack.isEmpty()) shop.addToStock(stack);
    }

    private static List<Item> items(TagKey<Item> tag) {
        List<Item> out = new ArrayList<>();
        for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) out.add(h.value());
        return out;
    }
}
