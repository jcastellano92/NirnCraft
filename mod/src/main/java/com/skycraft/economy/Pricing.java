package com.skycraft.economy;

import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.perk.Perks;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Skyrim barter prices. Speech and the Haggling/Allure perks narrow the gap between what merchants charge and
 * what they pay: at Speech 15 you buy at ~1.9x and sell at ~0.37x an item's value; at 100 with Haggling 5 you
 * buy at ~1.15x and sell at ~0.75x.
 */
public final class Pricing {
    private Pricing() {}

    private static double speechFraction(Player player) {
        return Math.min(100, SkyData.get(player).getSkill(Skill.SPEECH)) / 100.0;
    }

    public static double buyMult(Player player) {
        double m = 2.0 - 0.6 * speechFraction(player) - 0.05 * Perks.rank(player, "speech.haggling")
                - (Perks.has(player, "speech.allure") ? 0.05 : 0);
        return Mth.clamp(m, 1.05, 3.0);
    }

    public static double sellMult(Player player) {
        double m = 0.33 + 0.27 * speechFraction(player) + 0.03 * Perks.rank(player, "speech.haggling");
        return Mth.clamp(m, 0.1, 0.95);
    }

    /** Price of ONE item of the stack when buying from a merchant (at least 1). */
    public static int buyPrice(Player player, ItemStack stack) {
        int value = ItemValues.get(stack);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE / 128, Math.ceil(value * buyMult(player))));
    }

    /** Gold paid for ONE item of the stack when selling to a merchant (0 if worthless). */
    public static int sellPrice(Player player, ItemStack stack) {
        int value = ItemValues.get(stack);
        if (value <= 0) return 0;
        return (int) Math.max(1, Math.floor(value * sellMult(player)));
    }

    /** Price of ONE item bought from {@code npc}: a faction's quartermaster gives its members a discount. */
    public static int buyPrice(Player player, ItemStack stack, net.minecraft.world.entity.LivingEntity npc) {
        int price = buyPrice(player, stack);
        int off = com.skycraft.quest.faction.FactionStaff.discountFor(player, npc);
        if (off <= 0) return price;
        // never below what any merchant pays for it, so the discount can't be traded for gold
        int floor = (int) Math.ceil(sellPrice(player, stack) * 1.1) + 1;
        return Math.max(Math.min(price, floor), (int) Math.ceil(price * (100 - off) / 100.0));
    }

    /** Gold paid by {@code npc} for ONE item (a faction's quartermaster pays the usual price). */
    public static int sellPrice(Player player, ItemStack stack, net.minecraft.world.entity.LivingEntity npc) {
        return sellPrice(player, stack);
    }
}
