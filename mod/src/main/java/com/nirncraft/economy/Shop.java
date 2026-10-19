package com.nirncraft.economy;

import net.minecraft.core.BlockPos;
import com.nirncraft.perk.Perks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.TippedArrowItem;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A merchant's view of the shop it works in. The goods and the gold are a property of the establishment, not of the
 * person ({@link ShopKeys}, {@link ShopBook}): the innkeeper and the cook of one inn are two views of one shop, so a
 * purchase at one counter is gone at the other, and what a player sells is on sale to everybody afterwards.
 *
 * <p>The goods of a restock period are a pure function of the shop's key, its trades, its level and the period
 * ({@link ShopStock}); every player and every client sees the same list. Always obtain through {@link #of} on the
 * server thread and call {@link #save()} after changing anything.</p>
 */
public final class Shop {
    public static final String KEY = "nirncraft_shop";
    public static final int RESTOCK_DAYS = 2;
    /** Distinct kinds on the shelves (the barter packet carries at most 512 rows). */
    public static final int MAX_ENTRIES = ShopBook.MAX_ROWS;
    public static final int INVEST_COST = 500;
    public static final int INVEST_BONUS = 500;
    public static final int MASTER_TRADER_BONUS = 1000;
    /** How long a sale stays on the seller's buy-back list (30 minutes). */
    public static final long BUYBACK_TICKS = Buyback.TTL_TICKS;
    /** How long player-sold items stay on the public shop shelf before clearing (10 minutes or 2 restock periods). */
    public static final long SOLD_TTL_TICKS = 10L * 60L * 20L;

    private static final List<net.minecraft.core.Holder<Potion>> SHOP_POTIONS = List.of(
            Potions.HEALING, Potions.HEALING, Potions.STRONG_HEALING, Potions.REGENERATION, Potions.LONG_REGENERATION,
            Potions.SWIFTNESS, Potions.LONG_SWIFTNESS, Potions.STRENGTH, Potions.FIRE_RESISTANCE, Potions.LONG_FIRE_RESISTANCE,
            Potions.NIGHT_VISION, Potions.WATER_BREATHING, Potions.INVISIBILITY, Potions.LEAPING, Potions.SLOW_FALLING,
            Potions.POISON, Potions.HARMING, Potions.WEAKNESS, Potions.SLOWNESS, Potions.STRONG_STRENGTH
    );

    private final LivingEntity npc;
    private final ShopBook.Record rec;
    /** True for a preview that is not registered in the book (audits). */
    private final boolean ephemeral;

    private Shop(LivingEntity npc, ShopBook.Record rec, boolean ephemeral) {
        this.npc = npc;
        this.rec = rec;
        this.ephemeral = ephemeral;
    }

    /**
     * Contract 10 (docs/PLAYTEST-1.md): shops are open from 8:00 to 20:00 in-game, so NPC routines and Open/Closed
     * signs agree. Works on both sides.
     */
    public static boolean isOpen(net.minecraft.world.level.Level level) {
        long t = level.getOverworldClockTime() % 24000L;
        return t >= 2000L && t < 14000L;
    }

    /** Loads (and restocks if due) the shop of a merchant. Server side only. */
    public static Shop of(LivingEntity npc) {
        return of(npc, null);
    }

    /**
     * Loads (and restocks if due) the shop a merchant works in. The customer no longer shapes the goods: a shop's list
     * is the same for every player (it used to follow the first customer's level, so two players could be offered
     * different lists by the same counter).
     */
    public static Shop of(LivingEntity npc, @Nullable Player customer) {
        if (!(npc.level() instanceof ServerLevel level)) throw new IllegalStateException("Shop.of is server side only");
        ShopBook book = ShopBook.get(level.getServer());
        ShopKeys.Info info = ShopKeys.resolve(npc);
        String key = info != null ? info.key() : "npc:" + npc.getUUID();
        long today = today(level);
        ShopBook.Record rec = book.find(key);
        boolean created = rec == null;
        if (created) {
            rec = book.create(key, today);
            rec.pos = npc.blockPosition();
            rec.inn = (info != null && info.inn()) || com.nirncraft.survival.inn.Innkeepers.isInnkeeper(npc);
        }
        Shop shop = new Shop(npc, rec, false);
        shop.join(info, created);
        shop.restockIfDue();
        rec.lastDay = today;
        return shop;
    }

    /** A shop that is not registered in the book (audit samples): stocked like a real one, discarded afterwards. */
    public static Shop preview(LivingEntity npc) {
        if (!(npc.level() instanceof ServerLevel level)) throw new IllegalStateException("Shop.preview is server side only");
        ShopBook.Record rec = new ShopBook.Record("npc:" + npc.getUUID(), new ShopBook());
        rec.pos = npc.blockPosition();
        Shop shop = new Shop(npc, rec, true);
        shop.join(null, true);
        shop.restockIfDue();
        return shop;
    }

    static long today(net.minecraft.world.level.Level level) {
        // day time advances when sleeping; game time keeps going even with the daylight cycle off
        return Math.max(level.getOverworldClockTime(), level.getGameTime()) / 24000L;
    }

    /**
     * Brings this NPC into the record: the trades it adds (a pool the shop did not sell yet is stocked at once, from
     * its own seed), a higher level, and the old per-NPC stock of saves from before shops were shared.
     */
    private void join(@Nullable ShopKeys.Info info, boolean created) {
        List<ShopType> mine = info != null && !info.pools().isEmpty() ? info.pools() : Merchants.ownPools(npc);
        int level = Merchants.level(npc);
        if (created) {
            rec.level = level;
            for (ShopType t : mine) rec.addPool(t);
            if (rec.pools.isEmpty()) rec.addPool(ShopType.GENERAL);
            rec.share = Math.max(3, (6 + 3 * rec.level) / Math.max(1, rec.pools.size()));
        } else {
            if (level > rec.level) rec.level = Math.min(5, level);
            // a trade the shop did not sell yet (another counter of the same building): its goods come at once, from
            // their own seed, so the result does not depend on which counter was opened first
            for (ShopType t : mine) {
                if (rec.hasPool(t)) continue;
                rec.addPool(t);
                if (rec.period != Long.MIN_VALUE) {
                    for (ItemStack s : ShopStock.generate(this, t, ShopStock.seed(npc.level(), rec.key, rec.period, t))) rec.stock.add(s);
                    rec.dirty();
                }
            }
        }
        migrateLegacy(created);
    }

    /** Takes over (once) what an NPC of an older save carried in its own persistent data. */
    private void migrateLegacy(boolean created) {
        CompoundTag root = npc.getPersistentData();
        if (!com.nirncraft.platform.NbtData.hasType(root, KEY, Tag.TAG_COMPOUND)) return;
        CompoundTag old = root.getCompoundOrEmpty(KEY);
        boolean legacy = com.nirncraft.platform.NbtData.hasType(old, "stock", Tag.TAG_LIST) || old.contains("restock_day") || old.contains("gold") || old.contains("invested");
        if (!legacy) return;
        if (old.getBooleanOr("invested",false) && !rec.invested) rec.invested = true;
        if (created && old.contains("restock_day")) {
            long day = old.getLongOr("restock_day",0L);
            rec.period = Math.floorDiv(day, (long) RESTOCK_DAYS);
            rec.gold = old.getIntOr("gold",0);
            ListTag list = old.getListOrEmpty("stock");
            for (int i = 0; i < list.size(); i++) {
                ItemStack s = com.nirncraft.inventory.ItemData.parse(list.getCompoundOrEmpty(i));
                if (!s.isEmpty()) rec.stock.add(s);
            }
            if (com.nirncraft.platform.NbtData.hasType(old, "mt_used", Tag.TAG_COMPOUND)) rec.mtUsed = old.getCompoundOrEmpty("mt_used").copy();
            rec.dirty();
        }
        old.remove("stock");
        old.remove("gold");
        old.remove("restock_day");
        old.remove("mt_used");
        old.remove("invested");
    }

    // ------------------------------------------------------------------ identity

    public LivingEntity npc() {
        return npc;
    }

    /** The establishment's key: the same for every merchant of the building. */
    public String key() {
        return rec.key;
    }

    public int level() {
        return rec.level;
    }

    /** The shop types this establishment sells. */
    public List<ShopType> pools() {
        return List.copyOf(rec.pools);
    }

    public boolean inn() {
        return rec.inn;
    }

    BlockPos pos() {
        return rec.pos;
    }

    ShopBook.Record record() {
        return rec;
    }

    /** Makes the change durable (the record is saved with the world). */
    public void save() {
        if (!ephemeral) rec.dirty();
    }

    // ------------------------------------------------------------------ gold

    public boolean invested() {
        return rec.invested;
    }

    public void invest() {
        rec.invested = true;
        rec.gold += INVEST_BONUS;
    }

    public int baseGold() {
        return 500 + 150 * rec.level + (rec.invested ? INVEST_BONUS : 0);
    }

    /** Gold the shop can pay out to this player (including the Master Trader bonus pool). */
    public int available(Player player) {
        return Math.max(0, rec.gold) + masterTraderLeft(player);
    }

    private int masterTraderLeft(Player player) {
        if (!Perks.has(player, "speech.master_trader")) return 0;
        return Math.max(0, MASTER_TRADER_BONUS - rec.mtUsed.getIntOr(player.getStringUUID(), 0));
    }

    /** The shop pays {@code amount} to the player (Master Trader bonus first). Caller checked {@link #available}. */
    public void pay(Player player, int amount) {
        int bonus = Math.min(amount, masterTraderLeft(player));
        if (bonus > 0) rec.mtUsed.putInt(player.getStringUUID(), rec.mtUsed.getIntOr(player.getStringUUID(), 0) + bonus);
        rec.gold = Math.max(0, rec.gold - (amount - bonus));
    }

    /** The shop receives gold from a sale. */
    public void receive(int amount) {
        long g = (long) rec.gold + amount;
        rec.gold = (int) Math.min(Integer.MAX_VALUE / 2, g);
    }

    // ------------------------------------------------------------------ stock

    /** Number of rows on the shelves (indexes {@code 0..rows()-1}). */
    public int rows() {
        return rec.stock.size();
    }

    /** Row {@code index} with its full count (a copy), EMPTY when out of range. */
    public ItemStack row(int index) {
        return rec.stock.get(index);
    }

    /** Every row, index-aligned (copies). Unreadable rows are EMPTY. */
    public List<ItemStack> stock() {
        return rec.stock.entries();
    }

    /** How many of exactly {@code key} (item and NBT) are on the shelves. */
    public int count(ItemStack key) {
        int idx = rec.stock.indexOf(key);
        return idx < 0 ? 0 : rec.stock.countAt(idx);
    }

    /**
     * Removes up to {@code n} of row {@code index} (a purchase). Sold goods are drawn first, so the ledger of what
     * players sold turns over. Returns what was taken.
     */
    public ItemStack take(int index, int n) {
        ItemStack key = rec.stock.get(index);
        if (key.isEmpty() || n <= 0) return ItemStack.EMPTY;
        ItemStack taken = rec.stock.take(index, n);
        if (!taken.isEmpty()) unsell(taken, taken.getCount());
        return taken;
    }

    /** Removes up to {@code n} items equal to {@code key} (a buy-back); returns how many were removed. */
    public int takeEqual(ItemStack key, int n) {
        ItemStack taken = rec.stock.take(key, n);
        if (taken.isEmpty()) return 0;
        unsell(taken, taken.getCount());
        return taken.getCount();
    }

    private void unsell(ItemStack key, int n) {
        int idx = rec.sold.indexOf(key);
        if (idx >= 0) rec.sold.take(idx, n);
        int rem = n;
        for (var it = rec.soldBatches.iterator(); it.hasNext() && rem > 0; ) {
            ShopBook.SoldBatch b = it.next();
            if (ItemStack.isSameItemSameComponents(b.one, key)) {
                int take = Math.min(rem, b.count);
                b.count -= take;
                rem -= take;
                if (b.count <= 0) it.remove();
            }
        }
    }

    /** Adds goods the shop made or bought from an NPC (not a player's sale). Excess beyond the caps is lost. */
    public void addToStock(ItemStack incoming) {
        if (incoming.isEmpty()) return;
        ItemStack s = incoming.copy();
        rec.stock.add(s);
    }

    /**
     * Stocks what a player sold: on sale to everybody from now on and kept across restocks. When the ledger is full
     * the oldest sold kind is dumped to make room. Returns how many of {@code sold} are on the shelves afterwards
     * (the caps may refuse some; nothing is duplicated either way).
     */
    public int addSold(ItemStack sold) {
        if (sold.isEmpty()) return 0;
        ItemStack s = sold.copy();
        int want = s.getCount();
        if (rec.sold.indexOf(s) < 0 && rec.sold.size() >= ShopBook.MAX_SOLD_ROWS) {
            // oldest sold kind out: from the ledger and from the shelves
            ItemStack oldest = rec.sold.get(0);
            if (!oldest.isEmpty()) {
                rec.stock.take(oldest, oldest.getCount());
                rec.soldBatches.removeIf(b -> ItemStack.isSameItemSameComponents(b.one, oldest));
            }
            rec.sold.take(0, Integer.MAX_VALUE);
        }
        int taken = rec.stock.add(s);
        if (taken > 0) {
            ItemStack ledger = sold.copy();
            ledger.setCount(taken);
            rec.sold.add(ledger);
            ItemStack one = sold.copy();
            one.setCount(1);
            rec.soldBatches.add(new ShopBook.SoldBatch(one, taken, npc.level().getGameTime(), rec.period));
        }
        return want - s.getCount();
    }

    /** Count of {@code key} among the player-sold goods (what a buy-back can still reach). */
    public int soldCount(ItemStack key) {
        int idx = rec.sold.indexOf(key);
        return idx < 0 ? 0 : rec.sold.countAt(idx);
    }

    // ------------------------------------------------------------------ restock

    public void restockIfDue() {
        long dt = npc.level().getOverworldClockTime();
        long period = Math.floorDiv(today(npc.level()), (long) RESTOCK_DAYS);
        if (rec.lastDayTime != Long.MIN_VALUE && dt - rec.lastDayTime >= 24000L * RESTOCK_DAYS && period == rec.period) {
            period = rec.period + Math.floorDiv(dt - rec.lastDayTime, 24000L * RESTOCK_DAYS);
        }
        if (rec.period == Long.MIN_VALUE || rec.period != period) {
            rec.lastDayTime = dt;
            expireSoldIfDue(period);
            restock(period);
        } else {
            if (rec.lastDayTime == Long.MIN_VALUE) rec.lastDayTime = dt;
            expireSoldIfDue(period);
        }
    }

    /**
     * Expires player-sold items from the public shelf after {@link #SOLD_TTL_TICKS} (10 minutes) or 2 restock periods
     * so shop shelves do not accumulate player-sold junk forever, while preserving the seller's personal 30-minute
     * buy-back window via {@link Buyback#markShelfExpired}.
     */
    private void expireSoldIfDue(long currentPeriod) {
        if (rec.soldBatches.isEmpty()) return;
        long now = npc.level().getGameTime();
        boolean changed = false;
        for (var it = rec.soldBatches.iterator(); it.hasNext(); ) {
            ShopBook.SoldBatch b = it.next();
            if (b.count <= 0 || b.one.isEmpty()) {
                it.remove();
                changed = true;
                continue;
            }
            boolean timeExpired = b.addedAt > 0 && now - b.addedAt >= SOLD_TTL_TICKS;
            boolean periodExpired = b.period != Long.MIN_VALUE && currentPeriod != Long.MIN_VALUE && currentPeriod - b.period >= 2;
            if (timeExpired || periodExpired) {
                int sIdx = rec.sold.indexOf(b.one);
                int removedSold = 0;
                if (sIdx >= 0) {
                    ItemStack taken = rec.sold.take(sIdx, b.count);
                    removedSold = taken.getCount();
                }
                if (removedSold > 0) {
                    rec.stock.take(b.one, removedSold);
                    if (!ephemeral) Buyback.markShelfExpired(rec.key, b.one, removedSold);
                }
                it.remove();
                changed = true;
            }
        }
        if (changed && !ephemeral) rec.dirty();
    }

    /**
     * Rebuilds the generated goods of the period (pure function of key, pools, level, period) and puts the sold
     * ledger back on the shelves. A clock that went backwards (a restored save) restocks as well.
     */
    private void restock(long period) {
        rec.period = period;
        rec.gold = baseGold();
        rec.mtUsed = new CompoundTag();
        rec.stock.clear();
        for (ShopType t : rec.pools) {
            for (ItemStack s : ShopStock.generate(this, t, ShopStock.seed(npc.level(), rec.key, period, t))) rec.stock.add(s);
        }
        // what players sold stays for everybody (the ledger is a subset of the shelves)
        for (ItemStack s : rec.sold.entries()) {
            if (!s.isEmpty()) rec.stock.add(s);
        }
        rec.dirty();
    }

    // ------------------------------------------------------------------ kept (buy-back only)

    /** Specialty goods the shop bought from {@code player} but never resells: they wait on that player's buy-back list. */
    public void keep(UUID player, ItemStack one, int count, int unit, long gameTime) {
        for (ShopBook.Kept k : rec.kept) {
            if (k.player.equals(player) && k.unit == unit && ItemStack.isSameItemSameComponents(k.one, one)) {
                k.count += count;
                rec.dirty();
                return;
            }
        }
        ItemStack copy = one.copy();
        copy.setCount(1);
        rec.kept.add(new ShopBook.Kept(player, copy, count, unit, gameTime));
        // oldest first out, per player and in total
        int mine = 0;
        for (ShopBook.Kept k : rec.kept) if (k.player.equals(player)) mine++;
        for (int i = 0; i < rec.kept.size() && mine > ShopBook.KEPT_PER_PLAYER; ) {
            if (rec.kept.get(i).player.equals(player)) {
                rec.kept.remove(i);
                mine--;
            } else {
                i++;
            }
        }
        while (rec.kept.size() > ShopBook.KEPT_TOTAL) rec.kept.remove(0);
        rec.dirty();
    }

    /** The player's still-valid kept goods (older ones are dropped here). */
    public List<ShopBook.Kept> keptFor(UUID player, long gameTime) {
        List<ShopBook.Kept> out = new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < rec.kept.size(); ) {
            ShopBook.Kept k = rec.kept.get(i);
            if (gameTime - k.at > BUYBACK_TICKS || k.count <= 0) {
                rec.kept.remove(i);
                changed = true;
                continue;
            }
            if (k.player.equals(player)) out.add(k);
            i++;
        }
        if (changed) rec.dirty();
        return out;
    }

    /** Removes a kept row (it was bought back). */
    public void unkeep(ShopBook.Kept kept, int n) {
        kept.count -= n;
        if (kept.count <= 0) rec.kept.remove(kept);
        rec.dirty();
    }

    // ------------------------------------------------------------------ stock pieces

    /**
     * One stock entry of {@code item}: enchanted books get an enchantment, potions become Skyrim potions (poison for a
     * splash potion), higher-level shops enchant some gear, and stack sizes shrink as the value grows (the brackets and
     * the trade's scale are data: {@link ShopTrades#quantity}).
     */
    static ItemStack makeStock(Item item, RandomSource rnd, int level, ShopType trade) {
        if (item == Items.AIR) return ItemStack.EMPTY;
        if (item == Items.ENCHANTED_BOOK) {
            ItemStack book = StockEnchantments.enchant(rnd, new ItemStack(Items.BOOK), 5 + 5 * level);
            return book.is(Items.ENCHANTED_BOOK) ? book : ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item);
        if (item == Items.POTION || item == Items.SPLASH_POTION) {
            stack = EconomyLoot.skyrimPotion(item == Items.SPLASH_POTION, 4 + 8 * level, rnd);
        } else if (item instanceof PotionItem || item instanceof TippedArrowItem) {
            stack.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS, new net.minecraft.world.item.alchemy.PotionContents(SHOP_POTIONS.get(rnd.nextInt(SHOP_POTIONS.size()))));
        } else if (level >= 3 && stack.isEnchantable() && stack.getMaxStackSize() == 1 && !(ItemCategory.isDigger(stack))
                && rnd.nextFloat() < 0.12f * (level - 2)) {
            // Skyrim shops sell some enchanted gear at higher levels
            stack = StockEnchantments.enchant(rnd, stack, 5 + 4 * level);
        }
        int max = stack.getMaxStackSize();
        if (max > 1) {
            int[] range = ShopTrades.quantity(ItemValues.get(stack), trade);
            int count = range[0] + (range[1] > range[0] ? rnd.nextInt(range[1] - range[0] + 1) : 0);
            stack.setCount(Math.min(max, count));
        }
        return stack;
    }
}
