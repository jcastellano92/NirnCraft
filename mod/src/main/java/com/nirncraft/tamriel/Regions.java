package com.nirncraft.tamriel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nirncraft.NirnCraft;
import com.nirncraft.tamriel.terrain.Beyond;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Region lookup for gameplay code. Overworld positions come from {@link TamrielMap}; other dimensions (Oblivion,
 * Sovngarde, the jail) get a region named after the dimension.
 *
 * <p>The authored regions are loaded once from {@code nirncraft/tamriel/regions.json} (written by
 * {@code tools/worldgen/tamriel_map.py}); an entry's position in that array is its region index, which is what the
 * map rasters store. Index 0 is the open sea. Generated regions of the endless lands have negative indices and are
 * resolved by {@link Beyond}. Everything here is immutable after loading and thread-safe.</p>
 */
public final class Regions {
    private static final Region WHITERUN = new Region("skyrim/whiterun", "skyrim", "hold", "nord",
            Component.translatable("region.nirncraft.skyrim.whiterun"), 1, 10, "skyrim/whiterun", "skyrim",
            "skyrim_temperate", "whiterun");

    /** Per-region terrain parameters from regions.json, used for the procedural foreign lands. */
    public record Terrain(float base, float relief, float rough, float crag, float dunes, float wet, float coast) {
        public static final Terrain DEFAULT = new Terrain(90, 10, 0.35f, 0.1f, 0, 0, 200);
    }

    private static final Region[] BY_INDEX;
    private static final Terrain[] TERRAIN;
    private static final Map<String, Integer> INDEX;
    private static final Map<String, String> REALM_BANNER;
    private static final List<Region> ALL;
    private static final Map<String, Region> DIMENSIONS = new ConcurrentHashMap<>();

    static {
        List<Region> list = new ArrayList<>();
        List<Terrain> terrain = new ArrayList<>();
        Map<String, Integer> index = new LinkedHashMap<>();
        Map<String, String> banners = new LinkedHashMap<>();
        try (InputStream in = Regions.class.getResourceAsStream("/nirncraft/tamriel/regions.json")) {
            if (in == null) throw new IllegalStateException("missing nirncraft/tamriel/regions.json");
            JsonArray arr = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                JsonArray lv = o.getAsJsonArray("levels");
                String capital = o.has("capital") && !o.get("capital").isJsonNull() ? o.get("capital").getAsString() : null;
                Region r = new Region(o.get("id").getAsString(), o.get("province").getAsString(),
                        o.get("kind").getAsString(), o.get("culture").getAsString(),
                        Component.translatable(o.get("name").getAsString()), lv.get(0).getAsInt(), lv.get(1).getAsInt(),
                        o.get("palette").getAsString(), o.get("music").getAsString(), o.get("weather").getAsString(),
                        capital);
                if (o.has("index") && o.get("index").getAsInt() != list.size()) {
                    throw new IllegalStateException("regions.json index mismatch at " + r.id());
                }
                if (o.has("realm_banner") && !o.get("realm_banner").isJsonNull()) {
                    banners.put(r.id(), o.get("realm_banner").getAsString());
                }
                index.put(r.id(), list.size());
                list.add(r);
                terrain.add(parseTerrain(o));
            }
        } catch (Exception ex) {
            NirnCraft.LOGGER.error("Could not load the Tamriel region table", ex);
            list.clear();
            terrain.clear();
            index.clear();
            banners.clear();
            list.add(new Region("sea/open_ocean", "sea", "sea", "none", Component.translatable("region.nirncraft.sea.open_ocean"),
                    40, 60, "sea/open_ocean", "sea", "maritime", null));
            terrain.add(Terrain.DEFAULT);
            index.put("sea/open_ocean", 0);
            index.put(WHITERUN.id(), 1);
            list.add(WHITERUN);
            terrain.add(Terrain.DEFAULT);
        }
        BY_INDEX = list.toArray(new Region[0]);
        TERRAIN = terrain.toArray(new Terrain[0]);
        INDEX = Collections.unmodifiableMap(index);
        REALM_BANNER = Collections.unmodifiableMap(banners);
        ALL = List.copyOf(list);
        NirnCraft.LOGGER.info("Loaded {} regions of Nirn", ALL.size());
    }

    private Regions() {}

    public static String realmBanner(Region region) {
        return region == null ? "" : realmBanner(region.id());
    }

    public static String realmBanner(String regionId) {
        return regionId == null ? "" : REALM_BANNER.getOrDefault(regionId, "");
    }

    private static Terrain parseTerrain(JsonObject o) {
        if (!o.has("terrain")) return Terrain.DEFAULT;
        JsonObject t = o.getAsJsonObject("terrain");
        return new Terrain(f(t, "base", 90), f(t, "relief", 10), f(t, "rough", 0.35f), f(t, "crag", 0.1f),
                f(t, "dunes", 0), f(t, "wet", 0), f(t, "coast", 200));
    }

    private static float f(JsonObject o, String key, float def) {
        return o.has(key) ? o.get(key).getAsFloat() : def;
    }

    public static Region at(Level level, BlockPos pos) {
        if (level.dimension() != Level.OVERWORLD) {
            String dim = level.dimension().identifier().toString();
            return DIMENSIONS.computeIfAbsent(dim, Regions::dimensionRegion);
        }
        return TamrielMap.region(pos.getX(), pos.getZ());
    }

    public static Region at(int x, int z) {
        return TamrielMap.region(x, z);
    }

    private static Region dimensionRegion(String dim) {
        String path = dim.substring(dim.indexOf(':') + 1);
        String key = "region.nirncraft.dimension." + path;
        String fallback = Character.toUpperCase(path.charAt(0)) + path.substring(1).replace('_', ' ');
        // the realms of Oblivion and the afterlife have their own level bands (com.nirncraft.realms.Realm)
        com.nirncraft.realms.Realm realm = com.nirncraft.realms.Realm.byId(dim);
        int lo = realm != null ? realm.minLevel : 30, hi = realm != null ? realm.maxLevel : 60;
        return new Region(dim, path, "dimension", "none", Component.translatableWithFallback(key, fallback),
                lo, hi, path, path, path, null);
    }

    public static Optional<Region> byId(String id) {
        Integer i = INDEX.get(id);
        if (i != null) return Optional.of(BY_INDEX[i]);
        return Beyond.generatedById(id);
    }

    public static Region get(String id) {
        if (id == null || id.isBlank()) return fallback();
        Optional<Region> exact = byId(id);
        if (exact.isPresent()) return exact.get();
        for (Region r : ALL) {
            if (r.shortId().equalsIgnoreCase(id)) return r;
        }
        return fallback();
    }

    /** Authored regions only (generated regions are created on demand). */
    public static Collection<Region> all() {
        return ALL;
    }

    /** Used while the region table is loading or for unknown indices. */
    public static Region fallback() {
        Integer i = INDEX.get(WHITERUN.id());
        return i != null ? BY_INDEX[i] : WHITERUN;
    }

    /** The region for a map region index: authored ({@code >= 0}) or generated (negative). */
    public static Region byIndex(int index) {
        if (index >= 0) return index < BY_INDEX.length ? BY_INDEX[index] : fallback();
        return Beyond.generated(index);
    }

    /** Index of an authored region id, or -1. */
    public static int indexOf(String id) {
        Integer i = INDEX.get(id);
        return i == null ? -1 : i;
    }

    /** Number of authored regions (indices 0 .. count-1). */
    public static int count() {
        return BY_INDEX.length;
    }

    /** Terrain parameters of an authored region (defaults for unknown indices). */
    public static Terrain terrain(int index) {
        return index >= 0 && index < TERRAIN.length ? TERRAIN[index] : Terrain.DEFAULT;
    }
}
