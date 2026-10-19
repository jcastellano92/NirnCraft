package com.nirncraft.lore.place.client;

import com.nirncraft.lore.place.PlaceLore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;

/** Client helpers of the place lore (only class-loaded on the client). */
public final class PlaceLoreClient {
    private PlaceLoreClient() {}

    /** The place's hook wrapped to {@code width} pixels for a tooltip (empty if it has none). */
    public static List<Component> hookLines(String placeId, Font font, int width) {
        List<Component> out = new ArrayList<>();
        Component hook = PlaceLore.hook(placeId);
        if (hook == null) return out;
        Style style = Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(true);
        for (FormattedText line : font.getSplitter().splitLines(hook, width, style)) {
            out.add(Component.literal(line.getString()).withStyle(style));
        }
        return out;
    }
}
