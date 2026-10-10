package com.skycraft.survival.cooking;

import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.crafting.recipe.Cost;
import com.skycraft.survival.block.CookingPotBlock;
import com.skycraft.vitals.ActionHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

/** Server-side cooking. Dishes train the Cooking skill and carry the cook's quality ({@link com.skycraft.skills.Cookery}). */
public final class Cooking {
    /** Most dishes made with one click (shift-click). */
    public static final int MAX_BATCH = 16;

    private Cooking() {}

    public static void cook(ServerPlayer player, CookingMenu menu, String recipeId, int times) {
        CookingRecipe recipe = CookingRecipes.get(recipeId);
        if (recipe == null) return;
        int wanted = Math.max(1, Math.min(MAX_BATCH, times));
        int made = 0;
        while (made < wanted && recipe.hasMaterials(player.getInventory())) {
            for (Cost cost : recipe.costs()) {
                ItemStack shown = cost.display().get();
                cost.removeFrom(player.getInventory(), cost.count(), -1);
                // milk buckets, honey bottles... give their container back
                if (!shown.isEmpty() && shown.hasCraftingRemainingItem()) {
                    ItemStack rest = shown.getCraftingRemainingItem();
                    for (int i = 0; i < cost.count() && !rest.isEmpty(); i++) give(player, rest.copy());
                }
            }
            give(player, com.skycraft.skills.Cookery.season(player, recipe.resultStack()));
            made++;
        }
        // Cooking skill (skills module): XP per dish, and Seasoned's extra portions.
        int ingredients = 0;
        for (Cost cost : recipe.costs()) ingredients += cost.count();
        com.skycraft.skills.Cookery.onCooked(player, ingredients, made);
        for (int i = com.skycraft.skills.Cookery.bonusPortions(player, made); i > 0; i--) {
            give(player, com.skycraft.skills.Cookery.season(player, recipe.resultStack()));
        }
        if (made == 0) {
            Notifier.message(player, Component.translatable("message.skycraft.survival.missing_ingredients"));
            return;
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        boolean heated = CookingPotBlock.heated(player.level(), menu.pos);
        player.level().playSound(null, menu.pos, heated ? SoundEvents.FIRE_EXTINGUISH : SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS,
                heated ? 0.4f : 0.8f, 0.9f + player.getRandom().nextFloat() * 0.2f);
        SkyData.get(player).addStat("dishes_cooked", made);
        Notifier.message(player, Component.translatable("message.skycraft.survival.cooked", recipe.resultStack().getHoverName(), made * recipe.count()));
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!ActionHandler.addToBags(player, stack) && !stack.isEmpty()) player.drop(stack, false);
    }
}
