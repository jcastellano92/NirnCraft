package com.nirncraft.crafting.arcane.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.crafting.arcane.ArcaneEvents;
import com.nirncraft.crafting.arcane.alchemy.Alchemy;
import com.nirncraft.crafting.arcane.alchemy.Ingredients;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.List;

/** Client tooltips: known alchemy effects on ingredients, applied weapon poisons and the poison-coating hint. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class ArcaneClientEvents {
    private ArcaneClientEvents() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        List<Component> tooltip = event.getToolTip();
        Player player = event.getEntity() != null ? event.getEntity() : Minecraft.getInstance().player;

        Ingredients.Ingredient ing = Ingredients.get(stack);
        if (ing != null && player != null) {
            tooltip.add(Component.translatable("tooltip.nirncraft.alchemy.ingredient").withStyle(ChatFormatting.GOLD));
            // all four slots: discovered ones by name and power, the rest as "?" (same lines as the inventory detail panel)
            for (Component line : Alchemy.effectLines(player, ing)) tooltip.add(Component.literal("  ").append(line));
        }

        CompoundTag tag = com.nirncraft.inventory.ItemData.custom(stack);
        if (tag == null) return;
        if (com.nirncraft.platform.NbtData.hasType(tag, ArcaneEvents.WEAPON_POISON, Tag.TAG_COMPOUND)) {
            CompoundTag poison = tag.getCompoundOrEmpty(ArcaneEvents.WEAPON_POISON);
            Component name;
            try {
                name = com.nirncraft.platform.ArcaneData.readComponent(poison.getStringOr("Name",""));
            } catch (RuntimeException e) {
                name = null;
            }
            if (name == null) name = Component.translatable("tooltip.nirncraft.alchemy.poison");
            tooltip.add(Component.translatable("tooltip.nirncraft.alchemy.poisoned", name, poison.getIntOr("Hits",0)).withStyle(ChatFormatting.DARK_GREEN));
        }
        if (tag.getBooleanOr(Alchemy.POISON_TAG, false)) {
            tooltip.add(Component.translatable("tooltip.nirncraft.alchemy.coat_hint").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
