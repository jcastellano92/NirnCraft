package com.nirncraft.dungeons.world.cavern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nirncraft.NirnCraft;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The cavern library: shape parameters and look profiles read once from
 * {@code /nirncraft/dungeons/caverns/index.json} (a {@code shapes} file and a list of {@code profiles} files, each an
 * array of profile objects). Adding a look is adding an object to one of those files; nothing in Java changes.
 */
public final class CavernLibrary {
    private static final String BASE = "/nirncraft/dungeons/caverns/";
    private static volatile CavernLibrary instance;

    /** Defaults of one shape. {@code long} shapes want an elongated piece; {@code tierBias} makes them likelier deeper. */
    public record ShapeDef(CavernShape shape, float weight, int minHeight, boolean elongated, float tierBias,
                           Map<String, double[]> params) {
        public double[] param(String key, double lo, double hi, @Nullable CavernProfile profile) {
            if (profile != null) {
                Map<String, double[]> o = profile.shapeParams.get(shape);
                if (o != null && o.containsKey(key)) return o.get(key);
            }
            double[] d = params.get(key);
            return d != null ? d : new double[]{lo, hi};
        }
    }

    private final List<CavernProfile> profiles;
    private final Map<String, CavernProfile> byId;
    private final Map<CavernShape, ShapeDef> shapes = new EnumMap<>(CavernShape.class);

    private CavernLibrary(List<CavernProfile> profiles, Map<CavernShape, ShapeDef> shapes) {
        this.profiles = List.copyOf(profiles);
        Map<String, CavernProfile> m = new LinkedHashMap<>();
        for (CavernProfile p : profiles) m.put(p.id, p);
        this.byId = Collections.unmodifiableMap(m);
        this.shapes.putAll(shapes);
        for (CavernShape s : CavernShape.VALUES) {
            this.shapes.computeIfAbsent(s, k -> new ShapeDef(k, 1f, 15, false, 0f, Map.of()));
        }
    }

    public static CavernLibrary get() {
        CavernLibrary l = instance;
        if (l == null) {
            synchronized (CavernLibrary.class) {
                l = instance;
                if (l == null) {
                    l = load();
                    instance = l;
                }
            }
        }
        return l;
    }

    public List<CavernProfile> profiles() {
        return profiles;
    }

    @Nullable
    public CavernProfile byId(String id) {
        return byId.get(id);
    }

    public ShapeDef shape(CavernShape s) {
        return shapes.get(s);
    }

    // ------------------------------------------------------------------ loading

    private static CavernLibrary load() {
        List<CavernProfile> out = new ArrayList<>();
        Map<CavernShape, ShapeDef> shapes = new EnumMap<>(CavernShape.class);
        JsonObject index = read("index.json");
        if (index != null) {
            if (index.has("shapes")) {
                JsonObject so = read(index.get("shapes").getAsString());
                if (so != null) {
                    for (Map.Entry<String, JsonElement> e : so.entrySet()) {
                        CavernShape s = CavernShape.byId(e.getKey());
                        if (s == null || !e.getValue().isJsonObject()) continue;
                        JsonObject o = e.getValue().getAsJsonObject();
                        Map<String, double[]> params = o.has("params") ? ranges(o.getAsJsonObject("params")) : Map.of();
                        shapes.put(s, new ShapeDef(s, o.has("weight") ? o.get("weight").getAsFloat() : 1f,
                                o.has("minHeight") ? o.get("minHeight").getAsInt() : 15,
                                o.has("long") && o.get("long").getAsBoolean(),
                                o.has("tierBias") ? o.get("tierBias").getAsFloat() : 0f, params));
                    }
                }
            }
            if (index.has("profiles")) {
                for (JsonElement f : index.getAsJsonArray("profiles")) {
                    JsonElement arr = readElement(f.getAsString());
                    if (arr == null || !arr.isJsonArray()) continue;
                    for (JsonElement pe : arr.getAsJsonArray()) {
                        try {
                            CavernProfile p = CavernProfile.parse(pe.getAsJsonObject(), f.getAsString());
                            boolean dup = false;
                            for (CavernProfile q : out) dup |= q.id.equals(p.id);
                            if (dup) NirnCraft.LOGGER.warn("NirnCraft caverns: duplicate profile id {}", p.id);
                            else out.add(p);
                        } catch (RuntimeException ex) {
                            NirnCraft.LOGGER.warn("NirnCraft caverns: bad profile in {}: {}", f.getAsString(), ex.toString());
                        }
                    }
                }
            }
        }
        if (out.isEmpty()) out.add(fallback());
        NirnCraft.LOGGER.info("NirnCraft caverns: {} profiles, {} shapes", out.size(), CavernShape.VALUES.length);
        return new CavernLibrary(out, shapes);
    }

    private static CavernProfile fallback() {
        JsonObject o = JsonParser.parseString("{\"id\":\"fallback_cavern\",\"name\":\"Cavern\",\"fluid\":\"water\","
                + "\"palette\":{\"floor\":[\"rock@4\",\"minecraft:moss_block@1\"],\"wall\":[\"rock@3\",\"minecraft:dripstone_block@1\"],"
                + "\"spike\":[\"minecraft:dripstone_block@1\",\"rock@2\"],\"platform\":\"minecraft:stone_bricks\"},"
                + "\"lights\":{\"roof\":\"minecraft:ochre_froglight\",\"density\":44}}").getAsJsonObject();
        return CavernProfile.parse(o, "fallback");
    }

    static Map<String, double[]> ranges(JsonObject o) {
        Map<String, double[]> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            JsonElement v = e.getValue();
            if (v.isJsonArray()) {
                var a = v.getAsJsonArray();
                m.put(e.getKey(), new double[]{a.get(0).getAsDouble(), a.get(a.size() > 1 ? 1 : 0).getAsDouble()});
            } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                m.put(e.getKey(), new double[]{v.getAsDouble(), v.getAsDouble()});
            }
        }
        return m;
    }

    @Nullable
    private static JsonObject read(String file) {
        JsonElement e = readElement(file);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    @Nullable
    private static JsonElement readElement(String file) {
        String path = BASE + file;
        try (InputStream in = CavernLibrary.class.getResourceAsStream(path)) {
            if (in == null) {
                NirnCraft.LOGGER.warn("NirnCraft caverns: missing {}", path);
                return null;
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            NirnCraft.LOGGER.warn("NirnCraft caverns: could not read {}", path, e);
            return null;
        }
    }
}
