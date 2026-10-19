package com.nirncraft.economy;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-shop, per-player buy-back list: what this player sold to this shop in the last {@link #TTL_TICKS} (30 minutes
 * of game time), returned at the price the shop paid. The list belongs to the shop, not to the person at the counter:
 * what you sold to the innkeeper you buy back from the cook.
 *
 * <p>Two kinds of entry:
 * <ul>
 *   <li><b>shelf entries</b> (memory only): the goods sit in the shop's shared stock, on sale to everybody, until
 *   somebody buys them. Buying back takes them off the shelf; if another customer bought them meanwhile they are gone
 *   (nothing is duplicated). When the entry ages out the goods simply stay on the shelf for everyone.</li>
 *   <li><b>kept entries</b> (persisted in the shop's record, {@link ShopBook.Kept}): goods the shop must not resell
 *   (artifacts, unique items, cursed or bound things, player-written books). Only this player can see and buy them
 *   back, for 30 minutes of game time, even across a relog or a restart.</li>
 * </ul>
 * </p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = com.nirncraft.NirnCraft.MODID)
public final class Buyback {
    /** 30 minutes of game time. */
    public static final long TTL_TICKS = 30L * 60L * 20L;
    public static final int MAX_PER_MERCHANT = 24;
    /** After a buy-back the same shop pays no Speech XP for a while (sell/buy-back loops would farm it). */
    private static final long NO_XP_TICKS = 2L * 60L * 20L;

    /** {@code original} is what the player gets back (stolen mark kept); {@code inStock} is how it sits in the shop. */
    public static final class Entry {
        public final ItemStack original;
        public final ItemStack inStock;
        public final int unit;
        public final long at;
        public int count;
        /** Count of items that aged off the public shelf but remain available for the seller's 30-min buy-back. */
        public int shelfExpiredCount;
        /** Non-null for goods the shop never resold: they are held in the shop's record, not on its shelves. */
        public final ShopBook.Kept kept;

        Entry(ItemStack original, ItemStack inStock, int count, int unit, long at, ShopBook.Kept kept) {
            this.original = original;
            this.inStock = inStock;
            this.count = count;
            this.unit = unit;
            this.at = at;
            this.kept = kept;
        }

        /** True when the goods are held for this seller only and are not in the shared stock. */
        public boolean held() {
            return kept != null;
        }

        /** How many items the seller can currently buy back (either still on shelf or aged off shelf into backroom). */
        public int availableCount(Shop shop) {
            if (held()) return count;
            return Math.min(count, shop.count(inStock) + shelfExpiredCount);
        }
    }

    /** player -> shop key -> entries (shelf entries only). */
    private static final Map<UUID, Map<String, List<Entry>>> SOLD = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, Long>> BOUGHT_BACK = new ConcurrentHashMap<>();

    private Buyback() {}

    /**
     * Called when a player-sold batch expires off the public shelf after {@link Shop#SOLD_TTL_TICKS}: preserves the
     * items for the original seller's 30-minute buy-back window without keeping them on the public shelf.
     */
    public static void markShelfExpired(String shopKey, ItemStack inStockKey, int expiredCount) {
        if (expiredCount <= 0 || inStockKey.isEmpty()) return;
        int rem = expiredCount;
        for (Map<String, List<Entry>> byShop : SOLD.values()) {
            List<Entry> list = byShop.get(shopKey);
            if (list == null) continue;
            synchronized (list) {
                for (Entry e : list) {
                    if (rem <= 0) break;
                    if (!e.held() && ItemStack.isSameItemSameComponents(e.inStock, inStockKey)) {
                        int room = Math.max(0, e.count - e.shelfExpiredCount);
                        int add = Math.min(rem, room);
                        if (add > 0) {
                            e.shelfExpiredCount += add;
                            rem -= add;
                        }
                    }
                }
            }
            if (rem <= 0) break;
        }
    }

    private static long now(ServerPlayer player) {
        return player.level().getGameTime();
    }

    /** Records a sale of {@code count} of {@code original} (which entered the shop as {@code inStock}) at {@code unit} each. */
    public static void record(ServerPlayer player, Shop shop, ItemStack original, ItemStack inStock, int count, int unit) {
        if (count <= 0 || original.isEmpty()) return;
        List<Entry> list = SOLD.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(shop.key(), k -> new ArrayList<>());
        synchronized (list) {
            for (Entry e : list) {
                if (e.unit == unit && ItemStack.isSameItemSameComponents(e.original, original)) {
                    e.count += count;
                    return;
                }
            }
            ItemStack o = original.copy();
            o.setCount(1);
            ItemStack s = inStock.copy();
            s.setCount(1);
            list.add(new Entry(o, s, count, unit, now(player), null));
            while (list.size() > MAX_PER_MERCHANT) list.remove(0);
        }
    }

    /** Records goods the shop keeps for this player only (see the class comment); they are persisted with the shop. */
    public static void recordHeld(ServerPlayer player, Shop shop, ItemStack original, int count, int unit) {
        if (count <= 0 || original.isEmpty()) return;
        shop.keep(player.getUUID(), original, count, unit, now(player));
        shop.save();
    }

    /**
     * The live entries (expired ones are dropped): shelf entries then kept entries. The list is a snapshot; entries
     * are shared.
     */
    public static List<Entry> list(ServerPlayer player, Shop shop) {
        long t = now(player);
        List<Entry> out = new ArrayList<>();
        Map<String, List<Entry>> byShop = SOLD.get(player.getUUID());
        List<Entry> list = byShop == null ? null : byShop.get(shop.key());
        if (list != null) {
            synchronized (list) {
                list.removeIf(e -> e.count <= 0 || t - e.at > TTL_TICKS);
                out.addAll(list);
            }
        }
        for (ShopBook.Kept k : shop.keptFor(player.getUUID(), t)) {
            ItemStack stack = k.one.copy();
            out.add(new Entry(stack, ItemStack.EMPTY, k.count, k.unit, k.at, k));
        }
        return out;
    }

    /** Takes {@code n} items off an entry after a buy-back. */
    public static void consume(ServerPlayer player, Shop shop, Entry entry, int n) {
        entry.count -= n;
        if (entry.shelfExpiredCount > entry.count) entry.shelfExpiredCount = Math.max(0, entry.count);
        if (entry.kept != null) {
            shop.unkeep(entry.kept, n);
            shop.save();
        } else {
            Map<String, List<Entry>> byShop = SOLD.get(player.getUUID());
            List<Entry> list = byShop == null ? null : byShop.get(shop.key());
            if (list != null) synchronized (list) {
                list.removeIf(e -> e.count <= 0);
            }
        }
        BOUGHT_BACK.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>()).put(shop.key(), now(player));
    }

    /** True for a short while after this player bought something back from this shop (no Speech XP then). */
    public static boolean noXp(ServerPlayer player, Shop shop) {
        Map<String, Long> m = BOUGHT_BACK.get(player.getUUID());
        Long at = m == null ? null : m.get(shop.key());
        return at != null && now(player) - at < NO_XP_TICKS;
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onLogout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        // a relog inside the 30 minutes keeps the list (game time counts on); only what has run out is dropped
        UUID id = event.getEntity().getUUID();
        long t = event.getEntity().level().getGameTime();
        Map<String, List<Entry>> byShop = SOLD.get(id);
        if (byShop != null) {
            byShop.values().removeIf(list -> {
                synchronized (list) {
                    list.removeIf(e -> e.count <= 0 || t - e.at > TTL_TICKS);
                    return list.isEmpty();
                }
            });
            if (byShop.isEmpty()) SOLD.remove(id);
        }
        Map<String, Long> bb = BOUGHT_BACK.get(id);
        if (bb != null) {
            bb.values().removeIf(at -> t - at >= NO_XP_TICKS);
            if (bb.isEmpty()) BOUGHT_BACK.remove(id);
        }
    }

    public static void forget(UUID player) {
        SOLD.remove(player);
        BOUGHT_BACK.remove(player);
    }
}
