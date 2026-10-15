package com.skycraft.lore.place;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import com.skycraft.lore.LoreBookItem;
import com.skycraft.lore.LoreBooks;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The hand-written lore of Tamriel's named places: for every place a hook (map tooltip, discovery, rumours), a
 * townsperson's rumour, 1-3 readables (journals, orders, letters, treasure notes...) and optionally a mini-quest; for
 * every region its rumours, legends and folk songs. Loaded once from the class path on both sides
 * ({@code skycraft/lore/places/index.json} lists the files; see docs/LORE.md for the format).
 *
 * <p>Every text is inline English with a stable language key ({@code tools/place_lore.js} exports them into
 * {@code lang/skycraft/place_lore.json}), so translations can replace any line while the data stays the source.</p>
 *
 * <p>The readables are ordinary lore books ({@code skycraft:book}) with the id {@code place.<place>.<note>}:
 * {@link LoreBooks#get} resolves them here, the reading screen takes their text from {@link #bookText}, and reading
 * one runs {@link PlaceLoreEvents#onRead} (reveal a place on the map, start the place's mini-quest).</p>
 *
 * <p>Placement contract for other modules (dungeon and explorer builders): {@link #contentFor} gives a place's
 * content and {@link #createNote} its items. A builder that puts a place's notes itself calls
 * {@link #claimPlacement} once at setup; otherwise the default placer does it: the place's containers hand each
 * reader the notes they haven't found ({@link PlaceLoreLoot}), and notes marked {@code "at": "site"} wait on a
 * lectern near the place's centre ({@link PlaceLorePlacer}).</p>
 */
public final class PlaceLore {
    private static final String ROOT = "/skycraft/lore/places/";
    /** Book id prefix of place readables. */
    public static final String BOOK_PREFIX = "place.";

    /** A line of text with its language key; the inline English is the fallback. */
    public record Txt(String key, String text) {
        public MutableComponent c(Object... args) {
            return Component.translatableWithFallback(key, text, args);
        }

        /** The translated string (client side: the loaded language; server: usually the English fallback). */
        public String str() {
            return Language.getInstance().getOrDefault(key, text);
        }
    }

    /** Where the default placer puts a note. */
    public enum At {
        /** Any container of the place. */
        CHEST,
        /** The place's boss chest first (other containers once the reader has looted the rest). */
        BOSS,
        /** A lectern near the place's centre (shrines, stones, word walls, mounds, landmarks). */
        SITE;

        static At of(String s) {
            return switch (s) {
                case "boss" -> BOSS;
                case "site" -> SITE;
                default -> CHEST;
            };
        }
    }

    /** One readable. {@code bookId} is {@code place.<place>.<id>}. */
    public record Note(String placeId, String id, String bookId, String kind, Txt title, @Nullable Txt author, List<Txt> pages,
                       At at, String reveals, String starts) {
        public LoreBooks.Book book() {
            return PlaceLore.book(bookId);
        }
    }

    /** Everything written about one place. */
    public record Content(String placeId, Txt hook, Txt rumour, String quest, List<Note> notes) {
    }

    public record Song(Txt title, List<Txt> lines) {
    }

    /** A region's (or province's) rumours, legends and songs. */
    public record RegionLore(String id, List<Txt> rumours, List<Txt> legends, List<Song> songs) {
    }

    private static final Map<String, Content> PLACES = new LinkedHashMap<>();
    private static final Map<String, Note> NOTES = new LinkedHashMap<>();
    private static final Map<String, RegionLore> REGIONS = new LinkedHashMap<>();
    private static final Map<String, LoreBooks.Book> BOOKS = new ConcurrentHashMap<>();
    /** Places whose notes another module places itself (the default placer leaves them alone). */
    private static final Set<String> CLAIMED = ConcurrentHashMap.newKeySet();
    private static volatile boolean loaded;

    private PlaceLore() {}

    // ------------------------------------------------------------------ API

    /** The lore of a place, if any was written. */
    public static Optional<Content> contentFor(String placeId) {
        ensureLoaded();
        return Optional.ofNullable(PLACES.get(placeId));
    }

    /** Every place with lore. */
    public static Collection<Content> all() {
        ensureLoaded();
        return Collections.unmodifiableCollection(PLACES.values());
    }

    /** The place's hook (map tooltip, discovery), or null. */
    @Nullable
    public static Component hook(String placeId) {
        Content c = contentFor(placeId).orElse(null);
        return c == null ? null : c.hook().c();
    }

    /** What a townsperson says about the place, or null. */
    @Nullable
    public static Component rumour(String placeId) {
        Content c = contentFor(placeId).orElse(null);
        return c == null ? null : c.rumour().c();
    }

    /** A note by its book id ({@code place.<place>.<note>}), or null. */
    @Nullable
    public static Note note(String bookId) {
        ensureLoaded();
        return NOTES.get(bookId);
    }

    /** The lore book of a place readable (resolved by {@link LoreBooks#get}), or null if it isn't one. */
    @Nullable
    public static LoreBooks.Book book(String bookId) {
        if (bookId == null || !bookId.startsWith(BOOK_PREFIX)) return null;
        Note n = note(bookId);
        if (n == null) return null;
        return BOOKS.computeIfAbsent(bookId, id -> {
            boolean bound = n.kind().equals("book") || n.kind().equals("poem");
            LoreBooks.Category cat = n.kind().equals("letter") ? LoreBooks.Category.LETTER
                    : n.kind().equals("poem") ? LoreBooks.Category.POEM : bound ? LoreBooks.Category.HISTORY : LoreBooks.Category.NOTE;
            // weight 0: never a random shelf or chest book; these belong to their place
            return new LoreBooks.Book(id, cat, bound ? LoreBooks.BROWN : LoreBooks.SHEET, 0, bound ? 15 : 5);
        });
    }

    /** The item of a note ({@code skycraft:book} with the note's id), or empty if unknown. */
    public static ItemStack createNote(String placeId, String noteId) {
        LoreBooks.Book b = book(BOOK_PREFIX + placeId + "." + noteId);
        return b == null ? ItemStack.EMPTY : LoreBookItem.create(b);
    }

    /** The items of every note of a place (for builders that place them). */
    public static List<ItemStack> createNotes(String placeId) {
        List<ItemStack> out = new ArrayList<>();
        contentFor(placeId).ifPresent(c -> c.notes().forEach(n -> out.add(LoreBookItem.create(n.book()))));
        return out;
    }

    /** A module that places this place's notes itself calls this once (at setup); the default placer then skips it. */
    public static void claimPlacement(String placeId) {
        CLAIMED.add(placeId);
    }

    public static boolean claimed(String placeId) {
        return CLAIMED.contains(placeId);
    }

    /** Author and page texts of a place readable for the reading screen, or null. Language-resolved. */
    @Nullable
    public static BookText bookText(String bookId) {
        Note n = note(bookId);
        if (n == null) return null;
        List<String> pages = new ArrayList<>();
        for (Txt t : n.pages()) pages.add(t.str());
        return new BookText(n.author() == null ? "" : n.author().str(), pages);
    }

    public record BookText(String author, List<String> pages) {
    }

    /** The texture of a region: its own entry, else its province's, else null. */
    @Nullable
    public static RegionLore region(String regionId) {
        ensureLoaded();
        RegionLore r = REGIONS.get(regionId);
        if (r == null && regionId.indexOf('/') > 0) r = REGIONS.get(regionId.substring(0, regionId.indexOf('/')));
        return r;
    }

    /** A random regional rumour (region, else province), or null. */
    @Nullable
    public static Component regionalRumour(String regionId, RandomSource r) {
        RegionLore lore = region(regionId);
        if (lore == null || lore.rumours().isEmpty()) return null;
        return lore.rumours().get(r.nextInt(lore.rumours().size())).c();
    }

    // ------------------------------------------------------------------ loading

    public static void ensureLoaded() {
        if (!loaded) load();
    }

    private static synchronized void load() {
        if (loaded) return;
        JsonElement index = read(ROOT + "index.json");
        if (index != null && index.isJsonArray()) {
            for (JsonElement f : index.getAsJsonArray()) {
                JsonElement root = read(ROOT + f.getAsString());
                if (root == null || !root.isJsonObject()) {
                    Skycraft.LOGGER.warn("Place lore file {} is missing or not an object", f.getAsString());
                    continue;
                }
                try {
                    loadFile(root.getAsJsonObject());
                } catch (RuntimeException e) {
                    Skycraft.LOGGER.error("Bad place lore file {}", f.getAsString(), e);
                }
            }
        } else {
            Skycraft.LOGGER.warn("Place lore index is missing");
        }
        loaded = true;
        Skycraft.LOGGER.info("Loaded the lore of {} places ({} readables) and {} regions", PLACES.size(), NOTES.size(), REGIONS.size());
    }

    private static void loadFile(JsonObject root) {
        if (root.has("regions")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("regions").entrySet()) {
                String k = "place_lore.skycraft.region." + e.getKey().replace('/', '.');
                JsonObject o = e.getValue().getAsJsonObject();
                // a region written in several lore files: later files append (key indices continue)
                RegionLore prior = REGIONS.get(e.getKey());
                List<Song> songs = new ArrayList<>(prior == null ? List.of() : prior.songs());
                int i = songs.size();
                for (JsonElement s : arr(o, "songs")) {
                    String sk = k + ".song." + i++;
                    if (s.isJsonPrimitive()) {
                        String raw = s.getAsString().replaceFirst("^[~^#]\\s*", "");
                        songs.add(new Song(new Txt(sk + ".title", "Ballad of " + e.getKey()),
                                List.of(new Txt(sk + ".line.0", raw))));
                        continue;
                    }
                    if (!s.isJsonObject()) continue;
                    JsonObject so = s.getAsJsonObject();
                    // sung, not printed: no book markup at the start of a line
                    List<Txt> lines = new ArrayList<>();
                    for (Txt t : txts(arr(so, "lines"), sk + ".line.")) lines.add(new Txt(t.key(), t.text().replaceFirst("^[~^#]\\s*", "")));
                    songs.add(new Song(new Txt(sk + ".title", str(so, "title")), List.copyOf(lines)));
                }
                List<Txt> rumours = new ArrayList<>(prior == null ? List.of() : prior.rumours());
                JsonArray ra = arr(o, "rumours");
                for (int n = 0; n < ra.size(); n++) rumours.add(new Txt(k + ".rumour." + rumours.size(), ra.get(n).getAsString()));
                List<Txt> legends = new ArrayList<>(prior == null ? List.of() : prior.legends());
                JsonArray la = arr(o, "legends");
                for (int n = 0; n < la.size(); n++) legends.add(new Txt(k + ".legend." + legends.size(), la.get(n).getAsString()));
                REGIONS.put(e.getKey(), new RegionLore(e.getKey(), List.copyOf(rumours), List.copyOf(legends), List.copyOf(songs)));
            }
        }
        if (root.has("places")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("places").entrySet()) {
                String pid = e.getKey();
                JsonObject o = e.getValue().getAsJsonObject();
                String k = "place_lore.skycraft." + pid;
                List<Note> notes = new ArrayList<>();
                for (JsonElement ne : arr(o, "notes")) {
                    JsonObject n = ne.getAsJsonObject();
                    String nid = str(n, "id");
                    String bookId = BOOK_PREFIX + pid + "." + nid;
                    String nk = k + "." + nid;
                    String author = str(n, "author");
                    Note note = new Note(pid, nid, bookId, str(n, "kind"), new Txt("book.skycraft." + bookId, str(n, "title")),
                            author.isEmpty() ? null : new Txt(nk + ".author", author), txts(arr(n, "pages"), nk + ".p"),
                            At.of(str(n, "at")), str(n, "reveals"), str(n, "starts"));
                    notes.add(note);
                    NOTES.put(bookId, note);
                }
                Content c = new Content(pid, new Txt(k + ".hook", str(o, "hook")), new Txt(k + ".rumour", str(o, "rumour")),
                        str(o, "quest"), List.copyOf(notes));
                if (PLACES.put(pid, c) != null) Skycraft.LOGGER.warn("Place {} has lore in two files", pid);
            }
        }
    }

    private static List<Txt> txts(JsonArray a, String keyPrefix) {
        List<Txt> out = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) out.add(new Txt(keyPrefix + i, a.get(i).getAsString()));
        return List.copyOf(out);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static JsonArray arr(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    @Nullable
    private static JsonElement read(String path) {
        try (InputStream in = PlaceLore.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Skycraft.LOGGER.error("Couldn't read {}", path, e);
            return null;
        }
    }
}
