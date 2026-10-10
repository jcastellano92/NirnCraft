package com.skycraft.crafting;

import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.crafting.recipe.SmithingRecipe;
import com.skycraft.crafting.recipe.SmithingRecipes;
import com.skycraft.perk.Perks;
import com.skycraft.skills.Progression;
import com.skycraft.vitals.ActionHandler;
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
            Notifier.message(player, Component.translatable("message.skycraft.smithing.requires_perk", Tempering.perkName(recipe.perk())));
            return;
        }
        int wanted = Math.max(1, Math.min(MAX_BATCH, times));
        int made = 0;
        while (made < wanted && recipe.hasMaterials(player.getInventory())) {
            recipe.consume(player.getInventory());
            ItemStack result = recipe.resultStack();
            ActionHandler.addToBags(player, result);
            if (!result.isEmpty()) player.drop(result, false);
            made++;
        }
        if (made == 0) {
            Notifier.message(player, Component.translatable("message.skycraft.smithing.missing_materials"));
            return;
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        player.level().playSound(null, player.blockPosition(), sound(station), SoundSource.BLOCKS, 0.8f, 0.9f + player.getRandom().nextFloat() * 0.2f);
        Progression.addSkillXp(player, Skill.SMITHING, xpUse(recipe.value()) * made);
        SkyData.get(player).addStat("items_crafted", made);
        ItemStack shown = recipe.resultStack();
        Notifier.message(player, Component.translatable("message.skycraft.smithing.crafted", shown.getHoverName(), made * recipe.count()));
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
            case TANNING_RACK -> SoundEvents.ARMOR_EQUIP_LEATHER;
            default -> SoundEvents.ANVIL_USE;
        };
    }
}
