package com.skycraft.economy.client;

import com.skycraft.Skycraft;
import com.skycraft.core.Currency;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.economy.EconomyPackets;
import com.skycraft.economy.ItemValues;
import com.skycraft.economy.SkillBookItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Client side of the economy: packet handlers and the item value tooltip line. */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class EconomyClient {
    private EconomyClient() {}

    public static void handleBarterState(EconomyPackets.BarterState state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof BarterScreen screen && screen.entityId() == state.entityId()) {
            screen.update(state);
        } else if (state.open()) {
            mc.gui.setScreen(new BarterScreen(state));
        }
    }

    public static boolean hasReadBook(Skill skill) {
        return hasReadBook(skill, 1);
    }

    public static boolean hasReadBook(Skill skill, int volume) {
        Player player = Minecraft.getInstance().player;
        return player != null && SkillBookItem.hasRead(SkyData.get(player), skill, volume);
    }

    /** Skyrim shows every item's gold value. */
    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || Currency.valueOf(stack) > 0) return;
        if (stack.is(com.skycraft.economy.Merchants.CONTRABAND)) {
            event.getToolTip().add(Component.translatable("tooltip.skycraft.economy.contraband").withStyle(ChatFormatting.DARK_RED));
        }
        int value = ItemValues.get(stack);
        if (value <= 0) return;
        event.getToolTip().add(Component.translatable("tooltip.skycraft.economy.value", value).withStyle(ChatFormatting.GOLD));
    }
}
