package com.skycraft.inventory;

import com.skycraft.crafting.arcane.alchemy.Alchemy;
import com.skycraft.crafting.arcane.alchemy.Ingredients;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Self-test of the inventory screen's Use button on an ingredient, through the real server entry point
 * ({@link InventoryActions#handle}, the same method the {@code InvAction} packet calls): the ingredient is consumed,
 * exactly one effect is learned with exactly one message, and an effect is applied. Called from
 * {@link com.skycraft.crafting.arcane.alchemy.AlchemySelfTest} with a clean player; returns the problems found.
 */
public final class IngredientUseSelfTest {
    private IngredientUseSelfTest() {}

    public static List<String> run(ServerPlayer player) {
        List<String> problems = new ArrayList<>();
        Ingredients.Ingredient ing = Ingredients.get("minecraft:torchflower");
        if (ing == null || ing.item() == null) {
            problems.add("alchemy use: torchflower is not an ingredient");
            return problems;
        }
        var inv = player.getInventory();
        inv.clearContent();
        player.removeAllEffects();
        inv.setItem(5, new ItemStack(ing.item(), 3));
        int knownBefore = Alchemy.knownMask(player, ing);
        int announcedBefore = Alchemy.announcedTotal();
        var id = BuiltInRegistries.ITEM.getKey(ing.item());
        InventoryActions.handle(player, InventoryPackets.InvAction.USE, 5, id);
        int learned = Alchemy.knownMask(player, ing) & ~knownBefore;
        if (inv.getItem(5).getCount() != 2) problems.add("alchemy use: expected 2 flowers left, " + inv.getItem(5).getCount());
        if (knownBefore == 0 && Integer.bitCount(learned) != 1) problems.add("alchemy use: learned mask " + learned + " (expected one effect)");
        if (Alchemy.announcedTotal() - announcedBefore != Integer.bitCount(learned)) {
            problems.add("alchemy use: " + (Alchemy.announcedTotal() - announcedBefore) + " messages for " + Integer.bitCount(learned) + " learned effects");
        }
        boolean applied = false;
        for (var effect : ing.effects()) if (player.hasEffect(com.skycraft.platform.Effects.holder(effect.mobEffect()))) applied = true;
        if (!applied) problems.add("alchemy use: no effect of the ingredient is active after eating");
        return problems;
    }
}
