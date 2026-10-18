package com.skycraft.tamriel.settlement.grammar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import com.skycraft.tamriel.settlement.Culture;
import com.skycraft.tamriel.settlement.kit.Palette;
import com.skycraft.tamriel.settlement.plan.CityPlans;
import net.minecraft.world.level.block.Blocks;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-place style overlays: {@code skycraft/tamriel/styles/overlays/<id>.json}. An overlay re-tints a culture kit for one
 * city (or one quarter of it) without a new {@code Kits} int: its {@code palette} object overrides palette blocks
 * ({@code wall}, {@code roof}, {@code path}, {@code plaza}, {@code cityWall}, {@code accent}...) and its {@code style}
 * object layers style-sheet keys ({@code tiers}, {@code roofs}, {@code forms}...) on the culture sheet.
 *
 * <p>An overlay is referenced by an <b>index</b> packed into a spec's style int above the kit bits
 * ({@link com.skycraft.tamriel.settlement.kit.Kits#pack}). The index is a hash of the overlay id, so it is the same in
 * every world and every session (specs are saved with it); {@link CityPlans} lists the ids in use.</p>
 */
public final class Overlays {
    private static final Map<Integer, JsonObject> SHEETS = new ConcurrentHashMap<>();
    private static final Map<String, Palette> PALETTES = new ConcurrentHashMap<>();

    private Overlays() {}

    /** Stable index (1..2^22) of an overlay id. */
    public static int index(String id) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < id.length(); i++) {
            h ^= id.charAt(i);
            h *= 0x100000001b3L;
        }
        return 1 + (int) ((h ^ (h >>> 29)) & 0x3FFFFF);
    }

    /** The overlay sheet of an index, or null when no plan uses it. */
    static JsonObject sheet(int idx) {
        JsonObject cached = SHEETS.get(idx);
        if (cached != null) return cached.has("_missing") ? null : cached;
        String id = CityPlans.overlayId(idx);
        JsonObject o = id == null ? null : read(id);
        SHEETS.put(idx, o == null ? missing() : o);
        return o;
    }

    private static JsonObject missing() {
        JsonObject o = new JsonObject();
        o.addProperty("_missing", true);
        return o;
    }

    private static JsonObject read(String id) {
        String path = "/skycraft/tamriel/styles/overlays/" + id + ".json";
        try (InputStream in = com.skycraft.dev.DevResources.open(Overlays.class, path)) {
            if (in == null) {
                Skycraft.LOGGER.warn("No style overlay {}", path);
                return null;
            }
            JsonElement e = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            Skycraft.LOGGER.error("Could not read style overlay {}", path, e);
            return null;
        }
    }

    /** The kit palette re-tinted by an overlay (cached). Falls back to the kit palette. */
    public static Palette palette(Palette kit, Culture c, int idx) {
        return PALETTES.computeIfAbsent(c.id() + "/" + kit.name + "#" + idx, k -> {
            JsonObject sheet = sheet(idx);
            if (sheet == null || !(sheet.has("palette") || sheet.has("tint"))) return kit;
            try {
                Palette out = kit;
                // "tint" (the keys that restyle every building) also tints the kit palette: landmarks, paving and walls match
                if (sheet.has("tint")) {
                    JsonObject loose = new JsonObject();
                    for (Map.Entry<String, JsonElement> e : sheet.getAsJsonObject("tint").entrySet()) {
                        if (e.getValue().isJsonPrimitive()) loose.add(e.getKey(), e.getValue());
                    }
                    if (sheet.getAsJsonObject("tint").has("all") && sheet.getAsJsonObject("tint").get("all").isJsonObject()) {
                        for (Map.Entry<String, JsonElement> e : sheet.getAsJsonObject("tint").getAsJsonObject("all").entrySet()) {
                            loose.add(e.getKey(), e.getValue());
                        }
                    }
                    out = tint(out, loose);
                }
                if (sheet.has("palette")) out = tint(out, sheet.getAsJsonObject("palette"));
                return out;
            } catch (RuntimeException e) {
                Skycraft.LOGGER.error("Bad palette overlay {}", idx, e);
                return kit;
            }
        });
    }

    static Palette tint(Palette kit, JsonObject o) {
        Palette p = kit.copy();
        // the style-sheet keys (wall, roof, wood, accent...) go through the same code the culture sheets use
        Style.Variant v = Style.variant(o);
        p = v.apply(p);
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (!e.getValue().isJsonPrimitive()) continue;
            String val = e.getValue().getAsString();
            switch (e.getKey()) {
                case "path" -> p.path = Materials.block(val, Blocks.DIRT_PATH);
                case "path2" -> p.path2 = Materials.block(val, Blocks.COARSE_DIRT);
                case "plaza" -> p.plaza = Materials.block(val, Blocks.STONE_BRICKS);
                case "plaza2" -> p.plaza2 = Materials.block(val, Blocks.ANDESITE);
                case "cityWall" -> {
                    Materials.Family f = Materials.family(val);
                    p.cityWall = f.full();
                    p.cityWallStairs = f.stairs();
                    p.cityWallSlab = f.slab();
                    p.cityWallWall = f.wall();
                }
                case "cityWall2" -> p.cityWall2 = Materials.block(val, Blocks.CRACKED_STONE_BRICKS);
                case "glass" -> p.glass = Materials.block(val, Blocks.GLASS);
                case "stilts" -> p.stilts = Boolean.parseBoolean(val);
                case "thatch" -> p.thatch = Boolean.parseBoolean(val);
                case "horns" -> p.horns = Boolean.parseBoolean(val);
                default -> {
                }
            }
        }
        return p;
    }

    /** Drops the caches (dev reload). */
    public static void reload() {
        SHEETS.clear();
        PALETTES.clear();
    }
}
