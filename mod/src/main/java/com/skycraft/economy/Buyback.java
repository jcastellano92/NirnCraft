package com.skycraft.economy;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-merchant, per-player buy-back list: what this player sold to this merchant in the last {@link #TTL_TICKS}
 * (and this session), returned at the price the merchant paid. Held in memory only; the sold goods themselves sit in
 * the merchant's stock until bought back, so nothing is ever lost by a sale.
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = com.skycraft.Skycraft.MODID)
public final class Buyback {
    /** 30 minutes of game time. */
    public static final long TTL_TICKS = 30L * 60L * 20L;
    public static final int MAX_PER_MERCHANT = 24;
    /** After a buy-back the same merchant pays no Speech XP for a while (sell/buy-back loops would farm it). */
    private static final long NO_XP_TICKS = 2L * 60L * 20L;

    /** {@code original} is what the player gets back (stolen mark kept); {@code inStock} is how it sits in the shop. */
    public static final class Entry {
        public final ItemStack original;
        public final ItemStack inStock;
        public final int unit;
        public final long at;
        public int count;

        Entry(ItemStack original, ItemStack inStock, int count, int unit, long at) {
            this.original = original;
            this.inStock = inStock;
            this.count = count;
            this.unit = unit;
            this.at = at;
        }
    }

    private static final Map<UUID, Map<UUID, List<Entry>>> SOLD = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<UUID, Long>> BOUGHT_BACK = new ConcurrentHashMap<>();

    private Buyback() {}

    private static long now(ServerPlayer player) {
        return player.level().getGameTime();
    }

    /** Records a sale of {@code count} of {@code original} (which entered the shop as {@code inStock}) at {@code unit} each. */
    public static void record(ServerPlayer player, LivingEntity npc, ItemStack original, ItemStack inStock, int count, int unit) {
        if (count <= 0 || original.isEmpty()) return;
        List<Entry> list = SOLD.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(npc.getUUID(), k -> new ArrayList<>());
        synchronized (list) {
            for (Entry e : list) {
                if (e.unit == unit && ItemStack.isSameItemSameTags(e.original, original)) {
                    e.count += count;
                    return;
                }
            }
            ItemStack o = original.copy();
            o.setCount(1);
            ItemStack s = inStock.copy();
            s.setCount(1);
            list.add(new Entry(o, s, count, unit, now(player)));
            while (list.size() > MAX_PER_MERCHANT) list.remove(0);
        }
    }

    /** The live entries (expired ones are dropped). The list is a snapshot; entries are shared. */
    public static List<Entry> list(ServerPlayer player, LivingEntity npc) {
        Map<UUID, List<Entry>> byNpc = SOLD.get(player.getUUID());
        if (byNpc == null) return List.of();
        List<Entry> list = byNpc.get(npc.getUUID());
        if (list == null) return List.of();
        long t = now(player);
        synchronized (list) {
            list.removeIf(e -> e.count <= 0 || t - e.at > TTL_TICKS);
            return new ArrayList<>(list);
        }
    }

    /** Takes {@code n} items off an entry after a buy-back. */
    public static void consume(ServerPlayer player, LivingEntity npc, Entry entry, int n) {
        entry.count -= n;
        Map<UUID, List<Entry>> byNpc = SOLD.get(player.getUUID());
        List<Entry> list = byNpc == null ? null : byNpc.get(npc.getUUID());
        if (list != null) synchronized (list) {
            list.removeIf(e -> e.count <= 0);
        }
        BOUGHT_BACK.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>()).put(npc.getUUID(), now(player));
    }

    /** True for a short while after this player bought something back from this merchant (no Speech XP then). */
    public static boolean noXp(ServerPlayer player, LivingEntity npc) {
        Map<UUID, Long> m = BOUGHT_BACK.get(player.getUUID());
        Long at = m == null ? null : m.get(npc.getUUID());
        return at != null && now(player) - at < NO_XP_TICKS;
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        forget(event.getEntity().getUUID());
    }

    public static void forget(UUID player) {
        SOLD.remove(player);
        BOUGHT_BACK.remove(player);
    }
}
