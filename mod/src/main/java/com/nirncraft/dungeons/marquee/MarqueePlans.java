package com.nirncraft.dungeons.marquee;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nirncraft.NirnCraft;
import com.nirncraft.dungeons.world.Kit;
import com.nirncraft.dungeons.world.Site;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hand-authored marquee dungeon plans (data: {@code /nirncraft/dungeons/marquee/<placeId>.json}, listed in
 * {@code index.json}) and the two hooks {@link com.nirncraft.dungeons.world.DungeonStructure} calls for a named
 * place: {@link #depth} and {@link #build} (emits the pieces); painting goes through {@link MarqueeBuilder#room}.
 *
 * <p>A plan is a graph of rooms on the dungeon grid of {@link MarqueeLayout#CELL}-block cells around the entrance
 * hall. Cells are written in a local frame whose {@code +v} axis points to the entrance tunnel, so the same plan fits
 * whichever way the mouth opens. Each room names its centre cell, floor level (relative to the tunnel's bottom),
 * half sizes, height, and an ordered list of {@code ops} ({@link MarqueeBuilder}) that dress it.</p>
 */
public final class MarqueePlans {
    /** A written book on a lectern or in a chest: plain literal text, so no language keys are needed. */
    public record Book(String title, String author, List<String> pages) {}

    /** The master of the place. */
    public record Boss(String id, String name, double health) {}

    public static final class Room {
        public String id, name;
        public int i, j, y, hu, hv, h, role = 1, tier;
        /** Door height above the room's floor per local side ('n','s','e','w'); absent = floor level. */
        public final Map<Character, Integer> doors = new HashMap<>();
        public JsonArray ops = new JsonArray();
        /** How far below the floor the room's bottom reaches (flooded pits, bowls). */
        public int drop;
    }

    public static final class Link {
        public String a, b;
        /** Name of the room that holds the iron door and its lever, or null for an open doorway. */
        public String gate;
        public boolean trap;
    }

    public static final class Plan {
        public String id;
        public int hash;
        public Kit kit = Kit.NONE;
        public int entryDepth = 20;
        /** Fort cellars: the tunnel opens into the keep floor above instead of a portal. */
        public boolean openTop;
        public JsonObject palette = new JsonObject();
        public final Map<String, Book> books = new HashMap<>();
        public Boss boss;
        public final List<Room> rooms = new ArrayList<>();
        public final List<Link> links = new ArrayList<>();
        public final Map<String, Integer> byId = new HashMap<>();
        /** Lowest room bottom relative to the tunnel bottom. */
        public int lowest;
    }

    private static final Map<String, Plan> BY_ID = new LinkedHashMap<>();
    private static final Map<Integer, Plan> BY_HASH = new HashMap<>();

    static {
        JsonElement index = read("/nirncraft/dungeons/marquee/index.json");
        if (index != null && index.isJsonArray()) {
            for (JsonElement f : index.getAsJsonArray()) {
                try {
                    String file = f.getAsString();
                    if (!file.endsWith(".json")) file += ".json";
                    JsonElement e = read("/nirncraft/dungeons/marquee/" + file);
                    if (e != null && e.isJsonObject()) {
                        Plan plan = parse(e.getAsJsonObject());
                        BY_ID.put(plan.id, plan);
                        BY_HASH.put(plan.hash, plan);
                    }
                } catch (RuntimeException ex) {
                    NirnCraft.LOGGER.warn("NirnCraft marquee: bad plan {}", f, ex);
                }
            }
        }
    }

    private MarqueePlans() {}

    // ------------------------------------------------------------------ lookup

    public static boolean has(String placeId) {
        return BY_ID.containsKey(placeId);
    }

    @Nullable
    public static Plan get(String placeId) {
        return BY_ID.get(placeId);
    }

    @Nullable
    static Plan byHash(int hash) {
        return BY_HASH.get(hash);
    }

    public static Set<String> ids() {
        return Collections.unmodifiableSet(BY_ID.keySet());
    }

    // ------------------------------------------------------------------ hooks

    /** The entrance tunnel's depth for this place: at least what the plan's rooms need to lie under the ground. */
    public static int depth(String placeId, int rolled) {
        Plan p = BY_ID.get(placeId);
        return p == null ? rolled : Math.max(rolled, p.entryDepth);
    }

    /**
     * Emits the pieces of the place's plan (entrance tunnel, portal, rooms, corridors).
     *
     * @return false if the place has no plan (the caller falls back to the procedural layout)
     */
    public static boolean build(StructurePiecesBuilder builder, Site site, String placeId, int ex, int surfaceY, int ez,
                                Direction out, int depth, long seed, int minFloor) {
        Plan plan = BY_ID.get(placeId);
        if (plan == null) return false;
        MarqueeLayout.emit(builder, site, plan, ex, surfaceY, ez, out, depth, seed, minFloor);
        return true;
    }

    // ------------------------------------------------------------------ parsing

    private static Plan parse(JsonObject o) {
        Plan plan = new Plan();
        plan.id = o.get("id").getAsString();
        plan.hash = plan.id.hashCode();
        if (o.has("kit")) {
            String k = o.get("kit").getAsString();
            for (Kit kit : Kit.values()) if (kit.id.equals(k)) plan.kit = kit;
        }
        if (o.has("entry_depth")) plan.entryDepth = o.get("entry_depth").getAsInt();
        if (o.has("open_top")) plan.openTop = o.get("open_top").getAsBoolean();
        if (o.has("palette")) plan.palette = o.getAsJsonObject("palette");
        if (o.has("books")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("books").entrySet()) {
                JsonObject b = e.getValue().getAsJsonObject();
                List<String> pages = new ArrayList<>();
                for (JsonElement pg : b.getAsJsonArray("pages")) pages.add(pg.getAsString());
                plan.books.put(e.getKey(), new Book(b.get("title").getAsString(),
                        b.has("author") ? b.get("author").getAsString() : "?", pages));
            }
        }
        if (o.has("boss")) {
            JsonObject b = o.getAsJsonObject("boss");
            plan.boss = new Boss(b.get("id").getAsString(), b.has("name") ? b.get("name").getAsString() : null,
                    b.has("health") ? b.get("health").getAsDouble() : 1.0);
        }
        int lowest = 0;
        for (JsonElement re : o.getAsJsonArray("rooms")) {
            JsonObject r = re.getAsJsonObject();
            Room room = new Room();
            room.id = r.get("id").getAsString();
            room.name = r.has("name") ? r.get("name").getAsString() : room.id;
            JsonArray at = r.getAsJsonArray("at");
            room.i = at.get(0).getAsInt();
            room.j = at.get(1).getAsInt();
            room.y = r.get("y").getAsInt();
            JsonArray half = r.getAsJsonArray("half");
            room.hu = half.get(0).getAsInt();
            room.hv = half.get(1).getAsInt();
            room.h = r.get("h").getAsInt();
            String role = r.has("role") ? r.get("role").getAsString() : "normal";
            room.role = role.equals("start") ? 0 : role.equals("boss") ? 2 : 1;
            if (r.has("doors")) {
                for (Map.Entry<String, JsonElement> d : r.getAsJsonObject("doors").entrySet()) {
                    room.doors.put(d.getKey().charAt(0), d.getValue().getAsInt());
                }
            }
            if (r.has("ops")) room.ops = r.getAsJsonArray("ops");
            if (r.has("tier")) room.tier = r.get("tier").getAsInt();
            int sink = 0, bowl = 0;
            for (JsonElement opE : room.ops) {
                JsonObject op = opE.getAsJsonObject();
                String name = op.get("op").getAsString();
                if (name.equals("flood")) sink = Math.max(sink, op.has("sink") ? op.get("sink").getAsInt() : 2);
                if (name.equals("bowl")) {
                    int t = op.has("tiers") ? op.get("tiers").getAsInt() : 3;
                    int s = op.has("step") ? op.get("step").getAsInt() : 1;
                    bowl = Math.max(bowl, t * s);
                }
            }
            room.drop = sink + bowl;
            plan.byId.put(room.id, plan.rooms.size());
            plan.rooms.add(room);
            lowest = Math.min(lowest, room.y - room.drop - 1);
        }
        plan.lowest = lowest;
        for (JsonElement le : o.getAsJsonArray("links")) {
            JsonObject l = le.getAsJsonObject();
            Link link = new Link();
            link.a = l.get("a").getAsString();
            link.b = l.get("b").getAsString();
            link.gate = l.has("gate") && !l.get("gate").isJsonNull() ? l.get("gate").getAsString() : null;
            link.trap = l.has("trap") && l.get("trap").getAsBoolean();
            if (!plan.byId.containsKey(link.a) || !plan.byId.containsKey(link.b)) {
                throw new IllegalArgumentException("link to unknown room " + link.a + " / " + link.b);
            }
            plan.links.add(link);
        }
        return plan;
    }

    @Nullable
    private static JsonElement read(String path) {
        try (InputStream in = MarqueePlans.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            NirnCraft.LOGGER.warn("NirnCraft marquee: could not read {}", path, e);
            return null;
        }
    }
}
