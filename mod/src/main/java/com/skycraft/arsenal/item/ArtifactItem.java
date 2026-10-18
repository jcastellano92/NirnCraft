package com.skycraft.arsenal.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Marker for named Skyrim artifacts (Dawnbreaker, Chillrend, ...). Their powers live in the arsenal event handlers. */
public interface ArtifactItem {
    /** Durability of artifact weapons (they are effectively unbreakable in normal play). */
    int DURABILITY = 4000;

    String artifactId();

    static boolean is(ItemStack stack, String id) {
        return stack.getItem() instanceof ArtifactItem a && a.artifactId().equals(id);
    }

    /** Adds the effect line and the italic lore line. */
    static void tooltip(String id, java.util.function.Consumer<Component> tooltip) {
        tooltip.accept(Component.translatable("tooltip.skycraft.artifact." + id).withStyle(ChatFormatting.GOLD));
        tooltip.accept(Component.translatable("lore.skycraft.artifact." + id).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }
}
