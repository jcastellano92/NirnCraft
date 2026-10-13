package com.skycraft.lore.client;

import com.skycraft.core.SkyData;
import com.skycraft.lore.LoreBookItem;
import com.skycraft.lore.LoreBooks;
import com.skycraft.lore.LorePackets;
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
        Minecraft.getInstance().setScreen(new BookScreen(book));
    }

    /** A story read off a lectern, a sign or a note barrel: the same reading screen, with the text from the server. */
    public static void openStory(LorePackets.OpenStory m) {
        List<String> pages = new ArrayList<>(m.pagesJson().size());
        for (String json : m.pagesJson()) pages.add(pageText(json));
        Component title = m.title().isBlank() ? Component.translatable("screen.skycraft.lore.story.untitled") : Component.literal(m.title());
        Component label = Component.translatable("screen.skycraft.lore.story." + m.kind());
        Minecraft.getInstance().setScreen(new BookScreen(title, m.author(), pages, m.sheet(), label));
    }

    /** A page's chat-component JSON as the text it shows in the reader's language (plain text passes through). */
    static String pageText(String json) {
        try {
            Component c = Component.Serializer.fromJson(json);
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
