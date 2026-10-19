package com.nirncraft.economy;

import com.nirncraft.inventory.BulkStore;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every shop's goods and gold, one record per shop (a building, see {@link ShopKeys}), saved with the overworld. The
 * stock belongs to the establishment: every merchant working there reads and changes the same rows and the same gold.
 *
 * <p>A record keeps two {@link BulkStore}s, the engineering caps of docs/INVENTORY.md applied to a shop:
 * <ul>
 *   <li>{@code stock}: everything for sale, in row order. Rows are the generated goods of the current restock period
 *   followed by what players sold. Counts are ints (a sold stack of 5000 arrows is one row).</li>
 *   <li>{@code sold}: the ledger of what players sold, a subset of {@code stock} that survives a restock (the
 *   generated goods are rebuilt from the shop key and the period, the sold goods stay for everybody). Buying takes
 *   sold goods first, so the ledger turns over instead of growing without end.</li>
 * </ul>
 * Goods the shop must not resell (artifacts, quest items...) never enter {@code stock}: they wait, for the seller only,
 * in {@link Kept} (the buy-back list), persisted so a restart cannot lose them.</p>
 *
 * <p>All access happens on the server thread (packet handlers are {@code consumerMainThread}), so a purchase, a sale
 * and a buy-back are each one uninterrupted read-modify-write of the record: nothing can be duplicated.</p>
 */
public final class ShopBook extends SavedData {
    public static final String NAME = "nirncraft_shops";
    /** Distinct kinds a shop can stock / the sold ledger can hold (the barter packet carries at most 512 rows). */
    public static final int MAX_ROWS = 256, MAX_SOLD_ROWS = 128;
    /** Same per-row cap as the player's pack and containers (docs/INVENTORY.md). */
    public static final int MAX_PER_ROW = 1_000_000;
    /** Goods held back for one player / for everybody (oldest first out). */
    public static final int KEPT_PER_PLAYER = 24, KEPT_TOTAL = 512;
    /** A record of a wandering NPC nobody visited for this many days is dropped when the book grows large. */
    private static final int PRUNE_DAYS = 14, PRUNE_ABOVE = 1024;

    /** Goods the shop bought from a player but must not resell: they only show on that player's buy-back list. */
    public static final class Kept {
        public final UUID player;
        /** One item (count 1) with its NBT. */
        public final ItemStack one;
        public int count;
        public final int unit;
        public final long at;

        Kept(UUID player, ItemStack one, int count, int unit, long at) {
            this.player = player;
            this.one = one;
            this.count = count;
            this.unit = unit;
            this.at = at;
        }
    }

    /** A batch of player-sold items on the public shelf, tracked so sold goods expire over time. */
    public static final class SoldBatch {
        public final ItemStack one;
        public int count;
        public final long addedAt;
        public final long period;

        SoldBatch(ItemStack one, int count, long addedAt, long period) {
            this.one = one;
            this.count = count;
            this.addedAt = addedAt;
            this.period = period;
        }
    }

    /** One shop. Fields are package-private: {@link Shop} is the only writer. */
    public static final class Record {
        public final String key;
        final ShopBook book;
        int level = 3;
        /** Shop types this establishment sells, in {@link ShopType} order. */
        final List<ShopType> pools = new ArrayList<>();
        int share = 6;
        boolean inn;
        long period = Long.MIN_VALUE;
        long lastDayTime = Long.MIN_VALUE;
        int gold;
        boolean invested;
        CompoundTag mtUsed = new CompoundTag();
        BlockPos pos = BlockPos.ZERO;
        long lastDay;
        final BulkStore stock;
        final BulkStore sold;
        final List<SoldBatch> soldBatches = new ArrayList<>();
        final List<Kept> kept = new ArrayList<>();

        Record(String key, ShopBook book) {
            this.key = key;
            this.book = book;
            this.stock = new BulkStore(MAX_ROWS, MAX_PER_ROW, book::setDirty);
            this.sold = new BulkStore(MAX_SOLD_ROWS, MAX_PER_ROW, book::setDirty);
        }

        public int level() {
            return level;
        }

        public List<ShopType> pools() {
            return pools;
        }

        public boolean inn() {
            return inn;
        }

        public BulkStore stock() {
            return stock;
        }

        public BulkStore sold() {
            return sold;
        }

        public List<Kept> kept() {
            return kept;
        }

        public void dirty() {
            book.setDirty();
        }

        boolean hasPool(ShopType t) {
            return pools.contains(t);
        }

        void addPool(ShopType t) {
            if (pools.contains(t)) return;
            pools.add(t);
            pools.sort(java.util.Comparator.comparingInt(Enum::ordinal));
        }
    }

    private final Map<String, Record> shops = new LinkedHashMap<>();

    public static ShopBook get(MinecraftServer server) {
        return com.nirncraft.platform.SavedDataTypes.get(server.overworld(), NAME, ShopBook::load, ShopBook::new, value263 -> value263.save(new net.minecraft.nbt.CompoundTag()));
    }

    // ------------------------------------------------------------------ records

    @Nullable
    public Record find(String key) {
        return shops.get(key);
    }

    public int size() {
        return shops.size();
    }

    public Iterable<Record> records() {
        return shops.values();
    }

    /** The record of {@code key}, created empty when new ({@link Shop} fills and restocks it). */
    Record create(String key, long today) {
        Record r = new Record(key, this);
        r.lastDay = today;
        shops.put(key, r);
        if (shops.size() > PRUNE_ABOVE) prune(today);
        setDirty();
        return r;
    }

    /** Removes a record (selftests, discarded sample shops). */
    public void forget(String key) {
        if (shops.remove(key) != null) setDirty();
    }

    /** Drops records of travellers (keys {@code npc:...}) nobody has visited for a while; settlement shops stay. */
    private void prune(long today) {
        boolean any = false;
        for (Iterator<Record> it = shops.values().iterator(); it.hasNext(); ) {
            Record r = it.next();
            if (r.key.startsWith("npc:") && today - r.lastDay > PRUNE_DAYS) {
                it.remove();
                any = true;
            }
        }
        if (any) setDirty();
    }

    // ------------------------------------------------------------------ persistence

    private static final int VERSION = 1;

    public CompoundTag save(CompoundTag tag) {
        tag.putInt("v", VERSION);
        ListTag list = new ListTag();
        for (Record r : shops.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("k", r.key);
            t.putInt("lv", r.level);
            ListTag pools = new ListTag();
            for (ShopType p : r.pools) pools.add(StringTag.valueOf(p.id()));
            t.put("pools", pools);
            t.putInt("sh", r.share);
            t.putBoolean("inn", r.inn);
            t.putLong("period", r.period);
            if (r.lastDayTime != Long.MIN_VALUE) t.putLong("last_dt", r.lastDayTime);
            t.putInt("gold", r.gold);
            t.putBoolean("invested", r.invested);
            t.put("mt", r.mtUsed.copy());
            t.putInt("x", r.pos.getX());
            t.putInt("y", r.pos.getY());
            t.putInt("z", r.pos.getZ());
            t.putLong("used", r.lastDay);
            t.put("stock", r.stock.save());
            t.put("sold", r.sold.save());
            ListTag batches = new ListTag();
            for (SoldBatch sb : r.soldBatches) {
                if (sb.count <= 0 || sb.one.isEmpty()) continue;
                CompoundTag bTag = new CompoundTag();
                bTag.put("s", com.nirncraft.inventory.ItemData.save(sb.one));
                bTag.putInt("n", sb.count);
                bTag.putLong("at", sb.addedAt);
                bTag.putLong("p", sb.period);
                batches.add(bTag);
            }
            t.put("sold_batches", batches);
            ListTag kept = new ListTag();
            for (Kept k : r.kept) {
                CompoundTag c = new CompoundTag();
                c.store("p", net.minecraft.core.UUIDUtil.CODEC,  k.player);
                c.put("s", com.nirncraft.inventory.ItemData.save(k.one));
                c.putInt("n", k.count);
                c.putInt("u", k.unit);
                c.putLong("at", k.at);
                kept.add(c);
            }
            t.put("kept", kept);
            list.add(t);
        }
        tag.put("shops", list);
        return tag;
    }

    public static ShopBook load(CompoundTag tag) {
        ShopBook book = new ShopBook();
        ListTag list = tag.getListOrEmpty("shops");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompoundOrEmpty(i);
            String key = t.getStringOr("k","");
            if (key.isEmpty() || book.shops.containsKey(key)) continue;
            Record r = new Record(key, book);
            r.level = Math.max(1, Math.min(5, t.getIntOr("lv",0)));
            ListTag pools = t.getListOrEmpty("pools");
            for (int p = 0; p < pools.size(); p++) {
                ShopType type = ShopType.byId(pools.getStringOr(p, ""));
                if (type != null) r.addPool(type);
            }
            r.share = Math.max(3, t.getIntOr("sh",0));
            r.inn = t.getBooleanOr("inn",false);
            r.period = t.contains("period") ? t.getLongOr("period",0L) : Long.MIN_VALUE;
            r.lastDayTime = t.contains("last_dt") ? t.getLongOr("last_dt",0L) : Long.MIN_VALUE;
            r.gold = t.getIntOr("gold",0);
            r.invested = t.getBooleanOr("invested",false);
            r.mtUsed = t.getCompoundOrEmpty("mt");
            r.pos = new BlockPos(t.getIntOr("x",0), t.getIntOr("y",0), t.getIntOr("z",0));
            r.lastDay = t.getLongOr("used",0L);
            r.stock.load(t.getCompoundOrEmpty("stock"));
            r.sold.load(t.getCompoundOrEmpty("sold"));
            ListTag batches = t.getListOrEmpty("sold_batches");
            for (int b = 0; b < batches.size(); b++) {
                CompoundTag bTag = batches.getCompoundOrEmpty(b);
                ItemStack one = com.nirncraft.inventory.ItemData.parse(bTag.getCompoundOrEmpty("s"));
                int count = bTag.getIntOr("n",0);
                if (one.isEmpty() || count <= 0) continue;
                one.setCount(1);
                r.soldBatches.add(new SoldBatch(one, count, bTag.getLongOr("at",0L), bTag.getLongOr("p",0L)));
            }
            ListTag kept = t.getListOrEmpty("kept");
            for (int k = 0; k < kept.size() && r.kept.size() < KEPT_TOTAL; k++) {
                CompoundTag c = kept.getCompoundOrEmpty(k);
                ItemStack one = com.nirncraft.inventory.ItemData.parse(c.getCompoundOrEmpty("s"));
                if (one.isEmpty() || c.getIntOr("n",0) <= 0 || !c.read("p", net.minecraft.core.UUIDUtil.CODEC).isPresent()) continue;
                one.setCount(1);
                r.kept.add(new Kept(c.read("p", net.minecraft.core.UUIDUtil.CODEC).orElseThrow(), one, c.getIntOr("n",0), Math.max(1, c.getIntOr("u",0)), c.getLongOr("at",0L)));
            }
            book.shops.put(key, r);
        }
        return book;
    }
}
