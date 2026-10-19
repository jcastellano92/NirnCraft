package com.nirncraft.quest.story;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where story quests send players. A quest names a place by id: the real {@link Places} entry when the world has
 * it (settlements, landmarks and dungeons are added by other workstreams), otherwise a fallback anchor from
 * {@code nirncraft/quests/places.json} (positions from the reference map, scaled like the rest of Skyrim), so every
 * quest is playable whatever places exist yet.
 */
public final class QuestPlaces {
    /** A resolved quest location. */
    public record Spot(String id, int x, int z, int radius, String dim, Component name, @Nullable Place place) {
    }

    private record Fallback(int x, int z, int radius, String name, String dim) {
    }

    private static final Map<String, Fallback> FALLBACK = new ConcurrentHashMap<>();
    private static volatile boolean loaded;

    private QuestPlaces() {}

    private static void ensureLoaded() {
        if (!loaded) loadTable();
        // questline files declare their own lore sites (StoryData registers them while loading)
        StoryData.ensureLoaded();
    }

    private static synchronized void loadTable() {
        if (loaded) return;
        loaded = true;
        JsonElement root = StoryData.read("/nirncraft/quests/places.json");
        if (root == null || !root.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            FALLBACK.put(e.getKey(), new Fallback(o.get("x").getAsInt(), o.get("z").getAsInt(),
                    o.has("r") ? o.get("r").getAsInt() : 48, StoryData.str(o, "name", e.getKey()),
                    StoryData.str(o, "dim", "minecraft:overworld")));
        }
    }

    /** A questline's own fallback anchor (its {@code places} block); the shared table wins on a clash. */
    static void declare(String id, int x, int z, int radius, String name, String dim) {
        FALLBACK.putIfAbsent(id, new Fallback(x, z, radius, name, dim));
    }

    /** Resolves a place id; empty if neither the world nor the fallback table knows it. */
    public static Optional<Spot> find(String id) {
        if (id == null || id.isEmpty()) return Optional.empty();
        Optional<Place> real = Places.byId(id);
        if (real.isPresent()) {
            Place p = real.get();
            return Optional.of(new Spot(id, p.x(), p.z(), Math.max(24, Math.min(p.radius(), 160)), "minecraft:overworld", p.name(), p));
        }
        ensureLoaded();
        Fallback f = FALLBACK.get(id);
        if (f == null) return landSpot(id);
        Component name = Component.translatableWithFallback("place.nirncraft." + id, f.name());
        if ("minecraft:overworld".equals(f.dim())) {
            // a real place built at (or very near) the reference position under another id: anchor to it
            Optional<Place> near = Places.nearest(null, f.x(), f.z(), Math.max(48, f.radius()));
            if (near.isPresent()) {
                Place p = near.get();
                return Optional.of(new Spot(id, p.x(), p.z(), Math.max(24, Math.min(Math.max(p.radius(), f.radius()), 160)),
                        f.dim(), name, p));
            }
        }
        return Optional.of(new Spot(id, f.x(), f.z(), f.radius(), f.dim(), name, null));
    }

    /** {@code land_<x>_<z>}: a spot of a foreign shore found by a {@code land:} quest variable, named after its region. */
    private static Optional<Spot> landSpot(String id) {
        if (!id.startsWith("land_")) return Optional.empty();
        String[] p = id.substring(5).split("_");
        if (p.length != 2) return Optional.empty();
        try {
            int x = Integer.parseInt(p[0]), z = Integer.parseInt(p[1]);
            return Optional.of(new Spot(id, x, z, 48, "minecraft:overworld", com.nirncraft.tamriel.TamrielMap.region(x, z).name(), null));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** The display name of a place id (the id itself if unknown). */
    public static Component name(String id) {
        return find(id).map(Spot::name).orElse(Component.literal(id));
    }

    /** Every fallback id, for commands and validation. */
    public static Iterable<String> fallbackIds() {
        ensureLoaded();
        return FALLBACK.keySet();
    }
}
