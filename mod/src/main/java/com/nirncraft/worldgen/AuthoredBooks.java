package com.nirncraft.worldgen;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

/** Written book components retain translated pages and NirnCraft's separate story metadata. */
public final class AuthoredBooks {
    private AuthoredBooks() {}
    public static ItemStack create(String title, String author, List<Component> pages) {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        String boundedTitle = title.length() > 32 ? title.substring(0, 32) : title;
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(boundedTitle),
                author, 0, pages.stream().map(Filterable::passThrough).toList(), true));
        com.nirncraft.inventory.ItemData.updateCustom(stack,
                tag -> tag.putBoolean(com.nirncraft.lore.place.StoryReader.STORY_TAG, true));
        return stack;
    }
}
