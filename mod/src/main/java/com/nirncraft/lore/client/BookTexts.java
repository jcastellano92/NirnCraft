package com.nirncraft.lore.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nirncraft.NirnCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Book texts from {@code assets/nirncraft/lore/books/<id>.json}:
 * <pre>{"author": "...", "pages": ["first section...", "second section..."]}</pre>
 * Each entry of {@code pages} starts on a fresh page; long sections flow onto following pages. Within a section,
 * {@code \n} breaks lines, a line starting with {@code ^} is centered, one starting with {@code #} is a centered
 * heading and one starting with {@code ~} is a line of verse (indented, wrapped with a hanging indent). Resource
 * packs can replace or translate the texts.
 */
public final class BookTexts {
    public record Text(String author, List<String> pages) {}

    private static final Map<String, Optional<Text>> CACHE = new HashMap<>();

    private BookTexts() {}

    @Nullable
    public static Text get(String id) {
        return CACHE.computeIfAbsent(id, BookTexts::load).orElse(null);
    }

    public static void clear() {
        CACHE.clear();
    }

    private static Optional<Text> load(String id) {
        Identifier location = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "lore/books/" + id + ".json");
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            // a place readable: its text lives in the place lore data (and the language file)
            com.nirncraft.lore.place.PlaceLore.BookText t = com.nirncraft.lore.place.PlaceLore.bookText(id);
            return t == null ? Optional.empty() : Optional.of(new Text(t.author(), t.pages()));
        }
        try (Reader reader = resource.get().openAsReader()) {
            JsonObject json = GsonHelper.parse(reader);
            String author = GsonHelper.getAsString(json, "author", "");
            List<String> pages = new ArrayList<>();
            JsonArray array = GsonHelper.getAsJsonArray(json, "pages", new JsonArray());
            for (JsonElement e : array) pages.add(e.getAsString());
            return Optional.of(new Text(author, pages));
        } catch (Exception e) {
            NirnCraft.LOGGER.warn("Couldn't read book text {}: {}", location, e.toString());
            return Optional.empty();
        }
    }
}
