package com.skycraft.core;

import com.skycraft.registry.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Septims. Gold lives in a weightless wallet on {@link PlayerData}; physical Septim coin items are deposited into
 * the wallet automatically when picked up and can be withdrawn with {@code /withdraw}.
 */
public final class Currency {
    private Currency() {}

    public static long balance(Player player) {
        return SkyData.get(player).getGold();
    }

    public static boolean canAfford(Player player, long amount) {
        return balance(player) >= amount;
    }

    /** Removes gold if possible. Returns false (and changes nothing) if the player can't afford it. */
    public static boolean take(Player player, long amount) {
        PlayerData data = SkyData.get(player);
        if (amount < 0 || data.getGold() < amount) return false;
        data.setGold(data.getGold() - amount);
        return true;
    }

    public static void give(Player player, long amount) {
        give(player, amount, true);
    }

    /**
     * Adds gold. {@code found} says whether it counts towards the "gold found" stat: false for transfers between
     * players (/pay). Coins that left the wallet as items ({@link #markNotFound}) are not counted when picked back up.
     */
    public static void give(Player player, long amount, boolean found) {
        if (amount <= 0) return;
        PlayerData data = SkyData.get(player);
        data.setGold(data.getGold() + amount);
        if (found) {
            net.minecraft.nbt.CompoundTag tag = data.module("currency");
            long unfound = tag.getLongOr("unfound", 0L);
            long used = Math.min(unfound, amount);
            if (used > 0) tag.putLong("unfound", unfound - used);
            if (amount - used > 0) data.addStat("gold_found", (int) Math.min(Integer.MAX_VALUE, amount - used));
        }
        if (player instanceof ServerPlayer sp) {
            Notifier.message(sp, Component.translatable("message.skycraft.gold_added", amount));
        }
    }

    /** Records gold that left the wallet as coin items (withdrawn, dropped on death): picking it up again is no find. */
    public static void markNotFound(Player player, long amount) {
        if (amount <= 0) return;
        net.minecraft.nbt.CompoundTag tag = SkyData.get(player).module("currency");
        tag.putLong("unfound", Math.min(tag.getLongOr("unfound", 0L) + amount, 1_000_000_000L));
    }

    /** Creates physical gold: loose Septims for small amounts, a coin purse holding the amount otherwise. */
    public static ItemStack coins(long amount) {
        if (amount <= 64) return new ItemStack(ModItems.SEPTIM.get(), (int) Math.max(1, amount));
        ItemStack purse = new ItemStack(ModItems.COIN_PURSE.get());
        com.skycraft.inventory.ItemData.updateCustom(purse, customData263 -> customData263.putLong("gold", amount));
        return purse;
    }

    /** Gold value of a coin stack (Septims or purse), 0 for anything else. */
    public static long valueOf(ItemStack stack) {
        if (stack.is(ModItems.SEPTIM.get())) return stack.getCount();
        if (stack.is(ModItems.COIN_PURSE.get())) {
            var custom = com.skycraft.inventory.ItemData.custom(stack);
            long v = custom != null ? custom.getLongOr("gold", 0L) : 0;
            return (v <= 0 ? 25 : v) * stack.getCount();
        }
        return 0;
    }

    /** Formats large currency amounts with compact suffixes (e.g. 2500 -> "2.5k", 10000 -> "10k", 1500000 -> "1.5M"). */
    public static String formatCompact(long amount) {
        if (amount < 0) return "-" + formatCompact(-amount);
        if (amount < 1_000) return String.valueOf(amount);
        if (amount < 1_000_000) {
            double v = amount / 1000.0;
            if (amount % 1000 == 0) return String.format(java.util.Locale.ROOT, "%.0fk", v);
            String formatted = String.format(java.util.Locale.ROOT, "%.1fk", v);
            return formatted.endsWith(".0k") ? formatted.substring(0, formatted.length() - 3) + "k" : formatted;
        }
        if (amount < 1_000_000_000L) {
            double v = amount / 1_000_000.0;
            if (amount % 1_000_000 == 0) return String.format(java.util.Locale.ROOT, "%.0fM", v);
            String formatted = String.format(java.util.Locale.ROOT, "%.1fM", v);
            return formatted.endsWith(".0M") ? formatted.substring(0, formatted.length() - 3) + "M" : formatted;
        }
        double v = amount / 1_000_000_000.0;
        if (amount % 1_000_000_000L == 0) return String.format(java.util.Locale.ROOT, "%.0fB", v);
        String formatted = String.format(java.util.Locale.ROOT, "%.1fB", v);
        return formatted.endsWith(".0B") ? formatted.substring(0, formatted.length() - 3) + "B" : formatted;
    }

    /** Formats currency with standard digit separators (e.g. 2500 -> "2,500"). */
    public static String formatFull(long amount) {
        return java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(amount);
    }

    /** Formats gold with compact and full forms when large (e.g. "2.5k (2,500)"). */
    public static String formatGold(long amount) {
        if (amount >= 1_000) {
            return formatCompact(amount) + "g (" + formatFull(amount) + ")";
        }
        return amount + "g";
    }
}
