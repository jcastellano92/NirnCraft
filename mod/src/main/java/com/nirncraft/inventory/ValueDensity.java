package com.nirncraft.inventory;

import com.nirncraft.economy.ItemValues;
import net.minecraft.world.item.ItemStack;

/**
 * Gold per weight unit ("V/W"): how much of a load's weight is paid back in coins. The higher the ratio, the more an
 * item is worth carrying (a ring is worth far more per kilogram than an iron sword). Common code (no client classes):
 * the inventory, container and barter screens all format it the same way.
 *
 * <p>Weightless items have no ratio ({@link #NONE}, shown as {@value #DASH}): a stack of coins or a feather costs
 * nothing to carry, so "per weight" has no meaning and sorting puts them first when a list is sorted descending.</p>
 */
public final class ValueDensity {
    /** Returned for an item that weighs nothing (or is worth nothing at no weight): the column shows a dash. */
    public static final float NONE = -1f;
    public static final String DASH = "-";
    /** Below this weight an item counts as weightless (the weight table rounds to 0.1). */
    private static final float MIN_WEIGHT = 0.005f;

    private ValueDensity() {}

    /** Coins per weight unit of ONE item of {@code stack}, or {@link #NONE} when it is weightless. */
    public static float of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return NONE;
        return of(ItemValues.get(stack), ItemWeights.get(stack));
    }

    /** {@code value / weight}, or {@link #NONE} for a weightless item. */
    public static float of(int value, float weight) {
        if (weight < MIN_WEIGHT) return NONE;
        return Math.max(0f, value) / weight;
    }

    /**
     * Compact text of a ratio: {@code "-"} (weightless), {@code "0"}, one decimal below 10 ({@code "3.5"}), whole
     * numbers below 1000, then {@code "1.2k"} / {@code "12k"} / {@code "1.2m"}. At most 4 characters.
     */
    public static String format(float ratio) {
        if (ratio < 0f || Float.isNaN(ratio)) return DASH;
        if (ratio < 0.05f) return "0";
        if (ratio < 9.95f) return trim(Math.round(ratio * 10f) / 10f);
        if (ratio < 999.5f) return String.valueOf(Math.round(ratio));
        if (ratio < 9_950f) return trim(Math.round(ratio / 100f) / 10f) + "k";
        if (ratio < 999_500f) return Math.round(ratio / 1000f) + "k";
        if (ratio < 9_950_000f) return trim(Math.round(ratio / 100_000f) / 10f) + "m";
        return Math.min(999, Math.round(ratio / 1_000_000f)) + "m";
    }

    private static String trim(float v) {
        return Math.abs(v - Math.round(v)) < 0.001f ? String.valueOf(Math.round(v)) : String.valueOf(v);
    }

    /** Sort key: weightless items sort as the best (infinite density, they cost no capacity at all). */
    public static float sortKey(float ratio) {
        return ratio < 0f ? Float.MAX_VALUE : ratio;
    }
}
