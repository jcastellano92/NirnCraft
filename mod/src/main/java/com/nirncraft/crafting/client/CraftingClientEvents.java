package com.nirncraft.crafting.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.crafting.Tempering;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.List;

/** Forge-bus client hooks: the tempering quality line under the item name ("Fine", "Legendary"...) and Silver's note. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class CraftingClientEvents {
    private CraftingClientEvents() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        var item = event.getItemStack().getItem();
        com.nirncraft.crafting.SmithingTier tier = item instanceof com.nirncraft.crafting.item.SkyMeleeItem m ? m.smithingTier
                : item instanceof com.nirncraft.crafting.item.SkyAxeItem a ? a.smithingTier : null;
        if (tier == com.nirncraft.crafting.SmithingTier.SILVER) {
            event.getToolTip().add(Component.translatable("tooltip.nirncraft.silver_weapon").withStyle(ChatFormatting.GRAY));
        }
        int quality = Tempering.quality(event.getItemStack());
        if (quality <= 0) return;
        List<Component> tooltip = event.getToolTip();
        Component line = Tempering.qualityName(quality).copy().withStyle(ChatFormatting.GOLD);
        tooltip.add(Math.min(1, tooltip.size()), line);
    }
}
