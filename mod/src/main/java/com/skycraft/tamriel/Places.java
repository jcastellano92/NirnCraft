package com.skycraft.tamriel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Named places of Tamriel: capitals, towns, standing stones, word walls, named dungeons. Thread-safe and usable on
 * both sides; loaded once from the class path, so worldgen threads can query it freely.
 *
 * <p>Data lives in {@code skycraft/tamriel/places/}: {@code index.json} lists the files (a JSON array of names),
 * and each file is a JSON array of places:
 * {@code {"id","name","type","culture","region","x","z","radius","variant"}}. {@code name} is a lang key; a missing key falls
 * back to a literal built from the id. Later files may not reuse an earlier id.</p>
 */
public final class Places {
    private static final String ROOT = "/skycraft/tamriel/places/";

    private static final Map<String, Place> BY_ID;
    private static final List<Place> ALL;
    private static final Map<Long, List<Place>> BY_CHUNK;

    static {
        Map<String, Place> byId = new LinkedHashMap<>();
        for (String file : readIndex()) {
            JsonElement root = read(ROOT + file);
            if (root == null || !root.isJsonArray()) {
                Skycraft.LOGGER.warn("Places file {} is missing or not an array", file);
                continue;
            }
            for (JsonElement e : root.getAsJsonArray()) {
                try {
                    Place p = parse(e.getAsJsonObject());
                    if (byId.putIfAbsent(p.id(), p) != null) {
                        Skycraft.LOGGER.warn("Duplicate place id {} in {}", p.id(), file);
                    }
                } catch (RuntimeException ex) {
                    Skycraft.LOGGER.warn("Bad place entry in {}: {}", file, e, ex);
                }
            }
        }
        Map<Long, List<Place>> byChunk = new HashMap<>();
        for (Place p : byId.values()) {
            byChunk.computeIfAbsent(ChunkPos.asLong(p.x() >> 4, p.z() >> 4), k -> new ArrayList<>(1)).add(p);
        }
        byChunk.replaceAll((k, v) -> List.copyOf(v));
        BY_ID = Collections.unmodifiableMap(byId);
        ALL = List.copyOf(byId.values());
        BY_CHUNK = byChunk;
        Skycraft.LOGGER.info("Loaded {} places of Tamriel", ALL.size());
    }

    private Places() {}

    public static List<Place> all() {
        return ALL;
    }

    public static Optional<Place> byId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /** Nearest place of {@code type} (any type if null) within {@code maxDist} blocks of (x, z). */
    public static Optional<Place> nearest(PlaceType type, int x, int z, int maxDist) {
        long best = (long) maxDist * maxDist;
        Place found = null;
        for (Place p : ALL) {
            if (type != null && p.type() != type) continue;
            long d = p.distSqr(x, z);
            if (d <= best) {
                best = d;
                found = p;
            }
        }
        return Optional.ofNullable(found);
    }

    /** Places whose anchor (x, z) lies in this chunk. */
    public static List<Place> inChunk(ChunkPos pos) {
        return BY_CHUNK.getOrDefault(pos.toLong(), List.of());
    }

    /** Places whose footprint (anchor ± radius) overlaps the given block rectangle. */
    public static List<Place> overlapping(int minX, int minZ, int maxX, int maxZ) {
        List<Place> out = new ArrayList<>();
        for (Place p : ALL) {
            if (p.x() + p.radius() >= minX && p.x() - p.radius() <= maxX
                    && p.z() + p.radius() >= minZ && p.z() - p.radius() <= maxZ) {
                out.add(p);
            }
        }
        return out;
    }

    private static Place parse(JsonObject o) {
        String id = o.get("id").getAsString();
        String nameKey = o.has("name") ? o.get("name").getAsString() : "place.skycraft." + id;
        Component name = Component.translatableWithFallback(nameKey, prettify(id));
        return new Place(id, name, PlaceType.byId(o.get("type").getAsString()),
                o.has("culture") ? o.get("culture").getAsString() : "nord",
                o.has("region") ? o.get("region").getAsString() : "",
                o.get("x").getAsInt(), o.get("z").getAsInt(),
                o.has("radius") ? o.get("radius").getAsInt() : 32,
                o.has("variant") ? o.get("variant").getAsString() : "");
    }

    private static String prettify(String id) {
        StringBuilder sb = new StringBuilder();
        boolean up = true;
        for (char c : id.toCharArray()) {
            if (c == '_') {
                sb.append(' ');
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

    private static List<String> readIndex() {
        JsonElement index = read(ROOT + "index.json");
        if (index == null || !index.isJsonArray()) return List.of();
        List<String> files = new ArrayList<>();
        for (JsonElement e : (JsonArray) index) files.add(e.getAsString());
        return files;
    }

    private static JsonElement read(String path) {
        try (InputStream in = Places.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Could not read {}", path, e);
            return null;
        }
    }
}
