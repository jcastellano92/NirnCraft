package com.nirncraft.lore.client;

import com.nirncraft.core.SkyData;
import com.nirncraft.lore.LoreBookItem;
import com.nirncraft.lore.LoreBooks;
import com.nirncraft.lore.LorePackets;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Client entry points used from common code through {@code DistExecutor} (only class-loaded on the client). */
public final class LoreClient {
    private LoreClient() {}

    public static void openBook(String id) {
        LoreBooks.Book book = LoreBooks.get(id);
        if (book == null) return;
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.gui.screens.Screen ret = com.nirncraft.inventory.client.ClientInventoryHandlers.consumeReturnScreen(mc.gui.screen());
        mc.gui.setScreen(new BookScreen(book, ret));
    }

    /** A story read off a lectern, a sign or a note barrel: the same reading screen, with the text from the server. */
    public static void openStory(LorePackets.OpenStory m) {
        List<String> pages = new ArrayList<>(m.pagesJson().size());
        for (String json : m.pagesJson()) pages.add(pageText(json));
        Component title = m.title().isBlank() ? Component.translatable("screen.nirncraft.lore.story.untitled") : Component.literal(m.title());
        Component label = Component.translatable("screen.nirncraft.lore.story." + m.kind());
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.gui.screens.Screen ret = com.nirncraft.inventory.client.ClientInventoryHandlers.consumeReturnScreen(mc.gui.screen());
        mc.gui.setScreen(new BookScreen(title, m.author(), pages, m.sheet(), label, ret));
    }

    /** A page's chat-component JSON as the text it shows in the reader's language (plain text passes through). */
    static String pageText(String json) {
        try {
            Component c = com.nirncraft.platform.ArcaneData.readComponent(json);
            if (c != null) return c.getString();
        } catch (RuntimeException e) {
            // not JSON: plain text
        }
        return json;
    }

    public static boolean hasRead(String id) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && LoreBookItem.hasRead(SkyData.get(mc.player), id);
    }

    @Nullable
    public static String author(String id) {
        BookTexts.Text text = BookTexts.get(id);
        return text == null ? null : text.author();
    }
}
