package com.nirncraft.economy;

import com.nirncraft.arsenal.LeveledGear;
import com.nirncraft.arsenal.item.ArtifactItem;
import com.nirncraft.inventory.BulkStore;
import com.nirncraft.crafting.RegionalGear;
import com.nirncraft.crafting.SoulGems;
import com.nirncraft.crafting.arcane.item.SoulGemItem;
import com.nirncraft.lore.LoreBookItem;
import com.nirncraft.lore.LoreBooks;
import com.nirncraft.magic.spell.School;
import com.nirncraft.tamriel.Region;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What a merchant has for sale after a restock (every {@value Shop#RESTOCK_DAYS} days), Skyrim style:
 * <ul>
 *   <li>every shop type sells from its tag {@code #nirncraft:merchant/<type>} plus its province's goods
 *   {@code #nirncraft:merchant/<type>/<gear key>} (three times as likely): Morrowind's apothecaries have kwama eggs and
 *   saltrice, Khajiit caravans moon sugar and skooma;</li>
 *   <li>blacksmiths and fletchers forge the province's gear ({@code #nirncraft:gear/<gear key>}) leveled to the customer,
 *   with some enchanted pieces in bigger shops;</li>
 *   <li>court wizards sell spell tomes the customer's school skill is ready for, soul gems (some filled) and staves;</li>
 *   <li>booksellers sell the province's own books, skill books and novice tomes;</li>
 *   <li>apothecaries, general stores and fences sell Skyrim potions and poisons;</li>
 *   <li>jewellers sell rings, necklaces and circlets (some enchanted), gems and precious metals.</li>
 * </ul>
 * The shop's level (1..5) sets how much it carries and how rare its goods may be.
 */
public final class ShopStock {
    private static final com.nirncraft.crafting.arcane.alchemy.AlchemyEffect[] RESTORATIVES = {
            com.nirncraft.crafting.arcane.alchemy.AlchemyEffect.RESTORE_HEALTH, com.nirncraft.crafting.arcane.alchemy.AlchemyEffect.RESTORE_HEALTH,
            com.nirncraft.crafting.arcane.alchemy.AlchemyEffect.RESTORE_MAGICKA, com.nirncraft.crafting.arcane.alchemy.AlchemyEffect.RESTORE_STAMINA,
            com.nirncraft.crafting.arcane.alchemy.AlchemyEffect.CURE_DISEASE};

    private ShopStock() {}

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * The seed of one trade's goods in one shop for one restock period: a pure function of the world, the shop's key,
     * the period and the trade, so a list never depends on who opens the shop first or when.
     */
    public static long seed(net.minecraft.world.level.Level world, String shopKey, long period, ShopType trade) {
        long h = world instanceof net.minecraft.server.level.ServerLevel sl ? sl.getSeed() : 0L;
        long k = 0xCBF29CE484222325L; // FNV-1a over the key: String.hashCode is 32 bits and collides on similar keys
        for (int i = 0; i < shopKey.length(); i++) k = (k ^ shopKey.charAt(i)) * 0x100000001B3L;
        h = mix(h ^ k);
        h = mix(h + period * 0x9E3779B97F4A7C15L);
        return mix(h + (trade.ordinal() + 1) * 0xD6E8FEB86659FD93L);
    }

    /** The goods of one trade of a shop for one period (merged rows, deterministic for {@code seed}). */
    public static List<ItemStack> generate(Shop shop, ShopType trade, long seed) {
        BulkStore out = new BulkStore(ShopBook.MAX_ROWS, ShopBook.MAX_PER_ROW, null);
        generate(out, shop.npc().level(), shop.pos(), shop.level(), shop.record().share, shop.inn(), trade, RandomSource.create(seed));
        return out.entries();
    }

    /**
     * Stocks {@code out} with the goods of {@code trade} for a shop of this level at this place. The shop's size
     * stands in for the customer's level (a shop's list is the same for every player); the place sets the province.
     */
    static void generate(BulkStore out, net.minecraft.world.level.Level world, net.minecraft.core.BlockPos pos, int level, int share,
                         boolean inn, ShopType t, RandomSource rnd) {
        Region region = EconomyLoot.regionAt(world, pos);
        String key = RegionalGear.gearKey(region);
        String province = region == null ? "skyrim" : region.province();
        String placeId = null;
        if (pos != null) {
            var near = com.nirncraft.tamriel.Places.nearest(null, pos.getX(), pos.getZ(), 240);
            if (near.isPresent() && near.get().type().isSettlement()) {
                placeId = near.get().id();
            }
        }
        generateForPlace(out, key, province, placeId, level, share, inn, t, rnd);
    }

    /** Generates deterministic shop stock for a given province gear key and optional settlement {@code placeId}. */
    public static List<ItemStack> generateForPlace(String gearKey, @org.jetbrains.annotations.Nullable String placeId,
                                                   int level, int share, boolean inn, ShopType t, long seed) {
        BulkStore out = new BulkStore(ShopBook.MAX_ROWS, ShopBook.MAX_PER_ROW, null);
        String key = TradeNetwork.canonProvince(gearKey);
        String province = "solstheim".equals(key) ? "morrowind" : ("orsinium".equals(key) ? "high_rock" : key);
        generateForPlace(out, key, province, placeId, level, share, inn, t, RandomSource.create(seed));
        return out.entries();
    }

    static void generateForPlace(BulkStore out, String key, String province, @org.jetbrains.annotations.Nullable String placeId,
                                 int level, int share, boolean inn, ShopType t, RandomSource rnd) {
        int gearLevel = 1 + 6 * (level - 1);
        Player customer = null;

        {
            tagged(out, t, key, placeId, share, level, rnd);
            switch (t) {
                case BLACKSMITH -> {
                    int n = 3 + level;
                    for (int i = 0; i < n; i++) {
                        float f = rnd.nextFloat();
                        LeveledGear.Kind kind = f < 0.45f ? LeveledGear.Kind.WEAPON : f < 0.85f ? LeveledGear.Kind.ARMOR : LeveledGear.Kind.SHIELD;
                        gear(out, LeveledGear.regional(key, kind, gearLevel, rnd), gearLevel, level, rnd);
                    }
                }
                case FLETCHER -> {
                    for (int i = 0; i < 1 + level / 2; i++) gear(out, LeveledGear.regional(key, LeveledGear.Kind.BOW, gearLevel, rnd), gearLevel, level, rnd);
                    for (int i = 0; i < 2 + level; i++) {
                        ItemStack arrows = LeveledGear.arrows(gearLevel, rnd);
                        int[] lot = ShopTrades.quantity(ItemValues.get(arrows), ShopType.FLETCHER);
                        arrows.setCount(Math.min(arrows.getMaxStackSize(), lot[0] + rnd.nextInt(lot[1] - lot[0] + 1)));
                        add(out, arrows);
                    }
                }
                case ARCANE -> {
                    Set<School> any = EnumSet.noneOf(School.class);
                    for (int i = 0; i < 2 + level; i++) add(out, EconomyLoot.tome(gearLevel, customer, true, level, any, rnd));
                    soulGems(out, level, rnd);
                    if (level >= 2) staves(out, gearLevel, level, rnd);
                    for (int i = 0; i < 1 + level / 2; i++) add(out, potion(false, rnd, level, true));
                }
                case LIBRARY -> {
                    for (int i = 0; i < 2 + level; i++) {
                        LoreBooks.Book b = LoreBooks.randomFor(rnd, province, book -> true);
                        if (b != null) add(out, LoreBookItem.create(b));
                    }
                    List<Item> skillBooks = items(ItemCategory.SKILL_BOOKS);
                    int n = 1 + (level >= 3 ? 1 : 0) + (level >= 5 ? 1 : 0);
                    for (int i = 0; i < n && !skillBooks.isEmpty(); i++) add(out, new ItemStack(skillBooks.get(rnd.nextInt(skillBooks.size()))));
                    add(out, EconomyLoot.tome(1, null, false, level, EnumSet.noneOf(School.class), rnd));
                }
                case APOTHECARY -> {
                    for (int i = 0; i < 2 + level; i++) add(out, potion(false, rnd, level, false));
                    if (rnd.nextFloat() < 0.6f) add(out, potion(true, rnd, level, false));
                }
                case GENERAL -> {
                    for (int i = 0; i < 1 + level / 2; i++) add(out, potion(false, rnd, level, true));
                    if (rnd.nextFloat() < 0.5f) gear(out, LeveledGear.regional(key, LeveledGear.Kind.WEAPON, Math.min(gearLevel, 12), rnd), gearLevel, 1, rnd);
                }
                case FENCE -> {
                    for (int i = 0; i < 1 + level / 2; i++) add(out, potion(true, rnd, level, false));
                    jewelry(out, level, rnd, 2);
                }
                case JEWELER -> jewelry(out, level, rnd, 3 + level);
                case FOOD -> {
                    // an inn's own table (mead, ale, stews, sweetrolls...): the province's, from #nirncraft:inn_goods
                    if (inn) innGoods(out, key, placeId, rnd);
                }
                default -> {
                }
            }
            topUp(out, t, key, placeId, level, rnd);
        }
    }

    /** The inn's own goods: 5 to 8 kinds of #nirncraft:inn_goods this province serves, 1 to 4 each. */
    private static void innGoods(BulkStore out, String gearKey, @org.jetbrains.annotations.Nullable String placeId, RandomSource rnd) {
        List<Item> goods = new ArrayList<>();
        for (Item item : items(com.nirncraft.survival.inn.Innkeepers.INN_GOODS)) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null && ShopTrades.servedAt(id.toString(), gearKey, placeId, ShopType.FOOD)) goods.add(item);
        }
        // an inn beside hives sells their honey: the place's own produce (see LocalProduce)
        if (placeId != null && TradeNetwork.localExports(placeId).contains("minecraft:honey_bottle")) {
            ItemStack honey = new ItemStack(net.minecraft.world.item.Items.HONEY_BOTTLE);
            honey.setCount(2 + rnd.nextInt(4));
            add(out, honey);
            ItemStack comb = new ItemStack(net.minecraft.world.item.Items.HONEYCOMB);
            comb.setCount(1 + rnd.nextInt(3));
            add(out, comb);
        }
        if (goods.isEmpty()) return;
        int entries = 5 + rnd.nextInt(4);
        for (int i = 0; i < entries; i++) {
            ItemStack stack = new ItemStack(goods.get(rnd.nextInt(goods.size())));
            stack.setCount(Math.min(stack.getMaxStackSize(), 1 + rnd.nextInt(4)));
            add(out, stack);
        }
    }

    // ------------------------------------------------------------------ pieces

    /** Random goods of the type's tag and of its province tag (province goods three times as likely). */
    private static void tagged(BulkStore out, ShopType t, String key, @org.jetbrains.annotations.Nullable String placeId,
                               int entries, int level, RandomSource rnd) {
        ShopTrades.Trade trade = ShopTrades.trade(t);
        List<Item> base = t.stockItems();
        // goods the data ties to other provinces, embargoed goods, or categories outside the trade are not on this shelf
        base.removeIf(i -> {
            Identifier id = BuiltInRegistries.ITEM.getKey(i);
            return (id != null && !ShopTrades.servedAt(id.toString(), key, placeId, t))
                    || !trade.allows(ItemCategory.of(new ItemStack(i)));
        });
        List<Item> local = new ArrayList<>(t.provinceItems(key));
        for (String expId : placeId != null && !TradeNetwork.localExports(placeId).isEmpty()
                ? TradeNetwork.localExports(placeId) : TradeNetwork.provinceExports(key)) {
            Identifier rl = Identifier.tryParse(expId);
            if (rl == null) continue;
            Item expItem = BuiltInRegistries.ITEM.getValue(rl);
            if (expItem != null && expItem != net.minecraft.world.item.Items.AIR && !local.contains(expItem)) {
                if (t == ShopType.GENERAL || t == ShopType.EXOTIC || base.contains(expItem)) {
                    local.add(expItem);
                }
            }
        }
        local.removeIf(i -> {
            Identifier id = BuiltInRegistries.ITEM.getKey(i);
            return (id != null && !TradeNetwork.mayStock(t, key, placeId, id.toString()))
                    || (id != null && t != ShopType.FENCE && t != ShopType.EXOTIC && !ShopTrades.servedAt(id.toString(), key, placeId, t))
                    || !trade.allows(ItemCategory.of(new ItemStack(i)));
        });
        if (base.isEmpty() && local.isEmpty()) return;
        for (int i = 0; i < entries; i++) {
            boolean fromLocal = !local.isEmpty() && (base.isEmpty() || rnd.nextInt(4) > 0);
            List<Item> pool = fromLocal ? local : base;
            ItemStack stack = ItemStack.EMPTY;
            // pricey items are rarer, and rarer still in small shops
            for (int attempt = 0; attempt < 6 && stack.isEmpty(); attempt++) {
                Item item = pool.get(rnd.nextInt(pool.size()));
                if (item instanceof ArtifactItem) continue;
                ItemStack candidate = Shop.makeStock(item, rnd, level, t);
                if (candidate.isEmpty() || !trade.allows(ItemCategory.of(candidate))) continue;
                int value = ItemValues.get(candidate);
                float accept = value <= 0 ? 1f : Math.min(1f, 120f * level / value);
                if (rnd.nextFloat() < accept) stack = candidate;
            }
            add(out, stack);
        }
    }

    /** Ensures the shop carries at least its trade's declared minimum number of rows when random draws merged or rejected. */
    private static void topUp(BulkStore out, ShopType t, String key, @org.jetbrains.annotations.Nullable String placeId,
                              int level, RandomSource rnd) {
        ShopTrades.Trade trade = ShopTrades.trade(t);
        int min = trade.minRows();
        if (out.size() >= min) return;
        List<Item> candidates = new ArrayList<>();
        for (Item item : t.provinceItems(key)) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null && TradeNetwork.mayStock(t, key, placeId, id.toString())
                    && (t == ShopType.FENCE || t == ShopType.EXOTIC || ShopTrades.servedAt(id.toString(), key, placeId, t))) {
                candidates.add(item);
            }
        }
        for (Item item : t.stockItems()) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null && ShopTrades.servedAt(id.toString(), key, placeId, t) && !candidates.contains(item)) {
                candidates.add(item);
            }
        }
        candidates.removeIf(i -> i instanceof ArtifactItem || !trade.allows(ItemCategory.of(new ItemStack(i))));
        if (candidates.isEmpty()) return;
        int start = rnd.nextInt(candidates.size());
        for (int k = 0; k < candidates.size() && out.size() < min; k++) {
            Item item = candidates.get((start + k) % candidates.size());
            if (out.count(item) > 0) continue;
            ItemStack stack = Shop.makeStock(item, rnd, level, t);
            if (!stack.isEmpty() && trade.allows(ItemCategory.of(stack)) && !Resale.isKeepsake(stack)) {
                add(out, stack);
            }
        }
    }

    /** A piece of gear: worn a little, sometimes enchanted (bigger shops more often), sometimes tempered. */
    private static void gear(BulkStore out, ItemStack stack, int gearLevel, int shopLevel, RandomSource rnd) {
        if (stack.isEmpty()) return;
        float enchant = shopLevel >= 2 ? 0.06f * shopLevel : 0f;
        LeveledGear.decorate(stack, gearLevel, rnd, enchant, 0.1f);
        add(out, stack);
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
    private static void soulGems(BulkStore out, int level, RandomSource rnd) {
        List<Item> gems = items(TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("nirncraft", "soul_gems")));
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
            add(out, stack);
        }
    }

    /** Plain (non-artifact) staves whose spell level suits the customer. */
    private static void staves(BulkStore out, int gearLevel, int level, RandomSource rnd) {
        List<Item> staves = items(TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("nirncraft", "staves")));
        staves.removeIf(s -> s instanceof ArtifactItem);
        if (staves.isEmpty()) return;
        for (int i = 0; i < level / 2; i++) {
            ItemStack staff = new ItemStack(staves.get(rnd.nextInt(staves.size())));
            int value = ItemValues.get(staff);
            if (value <= 120 + 25 * gearLevel) add(out, staff);
        }
    }

    /** Rings, necklaces and circlets (bigger shops enchant some), gems and precious metals. */
    private static void jewelry(BulkStore out, int level, RandomSource rnd, int n) {
        List<Item> jewels = items(ShopType.JEWELRY);
        jewels.removeIf(i -> i instanceof ArtifactItem || BuiltInRegistries.ITEM.getKey(i).getPath().startsWith("mask_"));
        List<Item> gems = items(TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("nirncraft", "merchant/gems")));
        for (int i = 0; i < n; i++) {
            boolean gem = !gems.isEmpty() && (jewels.isEmpty() || rnd.nextInt(3) == 0);
            List<Item> pool = gem ? gems : jewels;
            if (pool.isEmpty()) return;
            ItemStack stack = new ItemStack(pool.get(rnd.nextInt(pool.size())));
            int value = ItemValues.get(stack);
            if (value > 150 * level && rnd.nextInt(3) > 0) continue;
            if (!gem && level >= 2 && rnd.nextFloat() < 0.1f * level) {
                stack = StockEnchantments.enchant(rnd, stack, 5 + 4 * level);
                if (!stack.isEnchanted()) stack = enchantJewelry(stack, rnd, level);
            }
            add(out, stack);
        }
    }

    /**
     * Vanilla rolls only enchant enchantable items; jewellery takes the armour enchantments at the Arcane Enchanter, so
     * the jeweller's own enchanted pieces pick one of those.
     */
    private static ItemStack enchantJewelry(ItemStack stack, RandomSource rnd, int level) {
        List<net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>> options = new ArrayList<>();
        for (var e : com.nirncraft.inventory.ItemData.currentRegistries().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).listElements().toList()) {
            if (!e.is(net.minecraft.tags.EnchantmentTags.CURSE) && !e.is(net.minecraft.tags.EnchantmentTags.TREASURE)
                    && com.nirncraft.crafting.arcane.enchant.Enchanting.fits(stack, e.value())) options.add(e);
        }
        if (options.isEmpty()) return stack;
        var e = options.get(rnd.nextInt(options.size()));
        ItemStack out = stack.copy();
        out.setCount(1);
        out.enchant(e, Math.min(e.value().getMaxLevel(), 1 + rnd.nextInt(Math.max(1, level / 2 + 1))));
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
                put.accept(i, i instanceof com.nirncraft.crafting.item.SkyBowItem ? ShopType.FLETCHER : ShopType.BLACKSMITH);
            }
        }
        for (var ro : com.nirncraft.magic.MagicRegistry.TOMES.values()) put.accept(ro.get(), ShopType.ARCANE);
        for (Item i : items(TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("nirncraft", "soul_gems")))) {
            if (!(i instanceof SoulGemItem s) || !s.reusable) put.accept(i, ShopType.ARCANE);
        }
        for (Item i : items(TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("nirncraft", "staves")))) {
            if (!(i instanceof ArtifactItem)) put.accept(i, ShopType.ARCANE);
        }
        for (Item i : items(ItemCategory.SKILL_BOOKS)) put.accept(i, ShopType.LIBRARY);
        put.accept(com.nirncraft.lore.LoreModule.BOOK.get(), ShopType.LIBRARY);
        for (Item i : items(ShopType.JEWELRY)) {
            if (!(i instanceof ArtifactItem) && !BuiltInRegistries.ITEM.getKey(i).getPath().startsWith("mask_")) put.accept(i, ShopType.JEWELER);
        }
        return out;
    }

    private static void add(BulkStore out, ItemStack stack) {
        if (!stack.isEmpty()) out.add(stack.copy());
    }

    private static List<Item> items(TagKey<Item> tag) {
        List<Item> out = new ArrayList<>();
        for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) out.add(h.value());
        return out;
    }
}
