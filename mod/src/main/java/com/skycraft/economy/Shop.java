package com.skycraft.economy;

import com.skycraft.perk.Perks;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.TippedArrowItem;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * A merchant's shop, stored in the entity's persistent data under {@code skycraft_shop}:
 * {@code stock} (list of item stacks), {@code gold} (int), {@code invested} (bool), {@code restock_day} (long) and
 * {@code mt_used} (per-player use of the Master Trader bonus gold since the last restock).
 *
 * <p>Stock and gold are regenerated every two in-game days. Always obtain through {@link #of(LivingEntity)} on the
 * server and call {@link #save()} after changing the stock.</p>
 */
public final class Shop {
    public static final String KEY = "skycraft_shop";
    public static final int RESTOCK_DAYS = 2;
    public static final int MAX_ENTRIES = 64;
    public static final int INVEST_COST = 500;
    public static final int INVEST_BONUS = 500;
    public static final int MASTER_TRADER_BONUS = 1000;

    private static final Potion[] SHOP_POTIONS = {
            Potions.HEALING, Potions.HEALING, Potions.STRONG_HEALING, Potions.REGENERATION, Potions.LONG_REGENERATION,
            Potions.SWIFTNESS, Potions.LONG_SWIFTNESS, Potions.STRENGTH, Potions.FIRE_RESISTANCE, Potions.LONG_FIRE_RESISTANCE,
            Potions.NIGHT_VISION, Potions.WATER_BREATHING, Potions.INVISIBILITY, Potions.LEAPING, Potions.SLOW_FALLING,
            Potions.POISON, Potions.HARMING, Potions.WEAKNESS, Potions.SLOWNESS, Potions.STRONG_STRENGTH
    };

    private final LivingEntity npc;
    private final CompoundTag tag;
    private final List<ItemStack> stock = new ArrayList<>();

    private Shop(LivingEntity npc, CompoundTag tag) {
        this.npc = npc;
        this.tag = tag;
        ListTag list = tag.getList("stock", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ItemStack s = ItemStack.of(list.getCompound(i));
            if (!s.isEmpty()) stock.add(s);
        }
    }

    /**
     * Contract 10 (docs/PLAYTEST-1.md): shops are open from 8:00 to 20:00 in-game, so NPC routines and Open/Closed
     * signs agree. Works on both sides.
     */
    public static boolean isOpen(net.minecraft.world.level.Level level) {
        long t = level.getDayTime() % 24000L;
        return t >= 2000L && t < 14000L;
    }

    /** Loads (and restocks if due) the shop of a merchant. Server side only. */
    public static Shop of(LivingEntity npc) {
        return of(npc, null);
    }

    /**
     * Loads (and restocks if due) the shop of a merchant for a customer. Like Skyrim's vendors, a restock follows the
     * customer: blacksmiths' gear their character level, court wizards' tomes their skill in each school.
     */
    public static Shop of(LivingEntity npc, @org.jetbrains.annotations.Nullable Player customer) {
        CompoundTag root = npc.getPersistentData();
        if (!root.contains(KEY, Tag.TAG_COMPOUND)) root.put(KEY, new CompoundTag());
        Shop shop = new Shop(npc, root.getCompound(KEY));
        shop.restockIfDue(customer);
        return shop;
    }

    public LivingEntity npc() {
        return npc;
    }

    public List<ItemStack> stock() {
        return stock;
    }

    public void save() {
        ListTag list = new ListTag();
        for (ItemStack s : stock) {
            if (!s.isEmpty()) list.add(s.save(new CompoundTag()));
        }
        tag.put("stock", list);
    }

    // ------------------------------------------------------------------ gold

    public boolean invested() {
        return tag.getBoolean("invested");
    }

    public void invest() {
        tag.putBoolean("invested", true);
        tag.putInt("gold", tag.getInt("gold") + INVEST_BONUS);
    }

    public int baseGold() {
        return 500 + 150 * Merchants.level(npc) + (invested() ? INVEST_BONUS : 0);
    }

    /** Gold the merchant can pay out to this player (including the Master Trader bonus pool). */
    public int available(Player player) {
        return Math.max(0, tag.getInt("gold")) + masterTraderLeft(player);
    }

    private int masterTraderLeft(Player player) {
        if (!Perks.has(player, "speech.master_trader")) return 0;
        return Math.max(0, MASTER_TRADER_BONUS - tag.getCompound("mt_used").getInt(player.getStringUUID()));
    }

    /** The merchant pays {@code amount} to the player (Master Trader bonus first). Caller checked {@link #available}. */
    public void pay(Player player, int amount) {
        int bonus = Math.min(amount, masterTraderLeft(player));
        if (bonus > 0) {
            CompoundTag used = tag.getCompound("mt_used");
            used.putInt(player.getStringUUID(), used.getInt(player.getStringUUID()) + bonus);
            tag.put("mt_used", used);
        }
        tag.putInt("gold", Math.max(0, tag.getInt("gold") - (amount - bonus)));
    }

    /** The merchant receives gold from a sale. */
    public void receive(int amount) {
        long g = (long) tag.getInt("gold") + amount;
        tag.putInt("gold", (int) Math.min(Integer.MAX_VALUE / 2, g));
    }

    // ------------------------------------------------------------------ stock

    /** Adds an item the merchant bought (merging with existing stacks). Excess beyond the shop's capacity is lost. */
    public void addToStock(ItemStack incoming) {
        ItemStack s = incoming.copy();
        for (ItemStack existing : stock) {
            if (s.isEmpty()) break;
            if (ItemStack.isSameItemSameTags(existing, s) && existing.getCount() < existing.getMaxStackSize()) {
                int move = Math.min(s.getCount(), existing.getMaxStackSize() - existing.getCount());
                existing.grow(move);
                s.shrink(move);
            }
        }
        while (!s.isEmpty() && stock.size() < MAX_ENTRIES) {
            ItemStack part = s.split(Math.max(1, s.getMaxStackSize()));
            stock.add(part);
        }
    }

    public void restockIfDue() {
        restockIfDue(null);
    }

    public void restockIfDue(@org.jetbrains.annotations.Nullable Player customer) {
        // day time advances when sleeping; game time keeps going even with the daylight cycle off
        long day = Math.max(npc.level().getDayTime(), npc.level().getGameTime()) / 24000L;
        boolean fresh = !tag.contains("restock_day");
        long last = tag.getLong("restock_day");
        if (fresh || day - last >= RESTOCK_DAYS || day < last) {
            restock(day, customer);
        }
    }

    private void restock(long day, @org.jetbrains.annotations.Nullable Player customer) {
        tag.putLong("restock_day", day);
        tag.putInt("gold", baseGold());
        tag.remove("mt_used");
        stock.clear();
        ShopStock.generate(this, customer, npc.getRandom());
        save();
    }

    /**
     * One stock entry of {@code item}: enchanted books get an enchantment, potions become Skyrim potions (poison for a
     * splash potion), higher-level shops enchant some gear, and stack sizes shrink as the value grows.
     */
    static ItemStack makeStock(Item item, RandomSource rnd, int level) {
        if (item == Items.AIR) return ItemStack.EMPTY;
        if (item == Items.ENCHANTED_BOOK) {
            ItemStack book = EnchantmentHelper.enchantItem(rnd, new ItemStack(Items.BOOK), 5 + 5 * level, false);
            return book.is(Items.ENCHANTED_BOOK) ? book : ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item);
        if (item == Items.POTION || item == Items.SPLASH_POTION) {
            stack = EconomyLoot.skyrimPotion(item == Items.SPLASH_POTION, 4 + 8 * level, rnd);
        } else if (item instanceof PotionItem || item instanceof TippedArrowItem) {
            PotionUtils.setPotion(stack, SHOP_POTIONS[rnd.nextInt(SHOP_POTIONS.length)]);
        } else if (level >= 3 && stack.isEnchantable() && stack.getMaxStackSize() == 1 && !(item instanceof net.minecraft.world.item.DiggerItem)
                && rnd.nextFloat() < 0.12f * (level - 2)) {
            // Skyrim shops sell some enchanted gear at higher levels
            stack = EnchantmentHelper.enchantItem(rnd, stack, 5 + 4 * level, false);
        }
        int max = stack.getMaxStackSize();
        if (max > 1) {
            int value = ItemValues.get(stack);
            int count;
            if (value <= 5) count = 4 + rnd.nextInt(13);
            else if (value <= 25) count = 2 + rnd.nextInt(5);
            else if (value <= 100) count = 1 + rnd.nextInt(3);
            else count = 1;
            stack.setCount(Math.min(max, count));
        }
        return stack;
    }
}
