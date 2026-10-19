package com.nirncraft.crafting;

import com.nirncraft.core.Notifier;
import com.nirncraft.core.SkyData;
import com.nirncraft.core.Skill;
import com.nirncraft.crafting.recipe.SmithingRecipe;
import com.nirncraft.crafting.recipe.SmithingRecipes;
import com.nirncraft.perk.Perks;
import com.nirncraft.skills.Progression;
import com.nirncraft.vitals.ActionHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

/** Server-side crafting at the forge, smelter and tanning rack. */
public final class StationCrafting {
    /** Most items made with one click (shift-click on the craft button). */
    public static final int MAX_BATCH = 16;

    private StationCrafting() {}

    public static void craft(ServerPlayer player, StationType station, String recipeId, int times) {
        SmithingRecipe recipe = SmithingRecipes.get(recipeId);
        if (recipe == null || recipe.station() != station) return;
        if (recipe.perk() != null && !Perks.has(player, recipe.perk())) {
            Notifier.message(player, Component.translatable("message.nirncraft.smithing.requires_perk", Tempering.perkName(recipe.perk())));
            return;
        }
        int wanted = Math.max(1, Math.min(MAX_BATCH, times));
        int made = 0;
        while (made < wanted && recipe.hasMaterials(player.getInventory())) {
            recipe.consume(player.getInventory());
            ItemStack result = recipe.resultStack();
            ActionHandler.addToBags(player, result);
            if (!result.isEmpty()) player.drop(result, false, net.minecraft.util.Prediction.SERVER_ONLY);
            made++;
        }
        if (made == 0) {
            Notifier.message(player, Component.translatable("message.nirncraft.smithing.missing_materials"));
            return;
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        player.level().playSound(null, player.blockPosition(), sound(station), SoundSource.BLOCKS, 0.8f, 0.9f + player.getRandom().nextFloat() * 0.2f);
        Progression.addSkillXp(player, Skill.SMITHING, xpUse(recipe.value()) * made);
        SkyData.get(player).addStat("items_crafted", made);
        ItemStack shown = recipe.resultStack();
        Notifier.message(player, Component.translatable("message.nirncraft.smithing.crafted", shown.getHoverName(), made * recipe.count()));
    }

    /**
     * Smithing "use value" of one craft worth {@code value} gold: Skyrim's sublinear 25 + 3 * value^0.65, so cheap
     * items still train a little and expensive ones don't level the skill in a few clicks.
     */
    public static float xpUse(int value) {
        return value <= 0 ? 0f : 25f + 3f * (float) Math.pow(value, 0.65);
    }

    private static SoundEvent sound(StationType station) {
        return switch (station) {
            case SMELTER -> SoundEvents.BLASTFURNACE_FIRE_CRACKLE;
            case TANNING_RACK -> SoundEvents.ARMOR_EQUIP_LEATHER.value();
            default -> SoundEvents.ANVIL_USE;
        };
    }
}
