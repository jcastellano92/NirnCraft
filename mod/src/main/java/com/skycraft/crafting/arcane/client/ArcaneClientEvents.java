package com.skycraft.crafting.arcane.client;

import com.skycraft.Skycraft;
import com.skycraft.crafting.arcane.ArcaneEvents;
import com.skycraft.crafting.arcane.alchemy.Alchemy;
import com.skycraft.crafting.arcane.alchemy.Ingredients;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/** Client tooltips: known alchemy effects on ingredients, applied weapon poisons and the poison-coating hint. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class ArcaneClientEvents {
    private ArcaneClientEvents() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        List<Component> tooltip = event.getToolTip();
        Player player = event.getEntity() != null ? event.getEntity() : Minecraft.getInstance().player;

        Ingredients.Ingredient ing = Ingredients.get(stack);
        if (ing != null && player != null) {
            tooltip.add(Component.translatable("tooltip.skycraft.alchemy.ingredient").withStyle(ChatFormatting.GOLD));
            // all four slots: discovered ones by name and power, the rest as "?" (same lines as the inventory detail panel)
            for (Component line : Alchemy.effectLines(player, ing)) tooltip.add(Component.literal("  ").append(line));
        }

        CompoundTag tag = stack.getTag();
        if (tag == null) return;
        if (tag.contains(ArcaneEvents.WEAPON_POISON, Tag.TAG_COMPOUND)) {
            CompoundTag poison = tag.getCompound(ArcaneEvents.WEAPON_POISON);
            Component name;
            try {
                name = Component.Serializer.fromJson(poison.getString("Name"));
            } catch (RuntimeException e) {
                name = null;
            }
            if (name == null) name = Component.translatable("tooltip.skycraft.alchemy.poison");
            tooltip.add(Component.translatable("tooltip.skycraft.alchemy.poisoned", name, poison.getInt("Hits")).withStyle(ChatFormatting.DARK_GREEN));
        }
        if (tag.getBoolean(Alchemy.POISON_TAG)) {
            tooltip.add(Component.translatable("tooltip.skycraft.alchemy.coat_hint").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
