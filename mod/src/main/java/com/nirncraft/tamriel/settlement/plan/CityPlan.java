package com.nirncraft.tamriel.settlement.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nirncraft.tamriel.settlement.Culture;
import com.nirncraft.tamriel.settlement.grammar.Overlays;
import com.nirncraft.tamriel.settlement.kit.Kits;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The data of one bespoke city ({@code nirncraft/tamriel/cityplans/<placeId>.json}), parsed once and immutable. A plan
 * gives a place its identity without a Java edit: layout archetype, landmarks, named buildings, streets, walls,
 * quarters (districts of another kit or colour) and the kit/overlay that styles it. {@link CityPlans} applies it to a
 * {@link Plan} in the same local frame the hand-written layouts of {@link Cities} use (local north is the palace side,
 * local south the main gate, coordinates relative to the place's anchor).
 *
 * <p>Format (all keys optional unless noted):</p>
 * <pre>
 * { "id": "aldruhn",                 // required, the place id
 *   "base": "none" | "zoning" | "java",  // none: fully authored; zoning: pins + the zoning planner's fill; java: run
 *                                       // the Cities.java layout, then add/override
 *   "sil": "shell-spine-fort",        // silhouette tag, unique across plans (validated offline)
 *   "reach": 140, "radius": 120,      // Cities.reach / Cities.layoutRadius
 *   "back": "N|E|S|W",                // world direction of local north
 *   "kit": "redoran", "overlay": "aldruhn",   // kit palette name of the culture; overlay id (styles/overlays)
 *   "names": {"inn": "key", "store": "key", "smithy": "key"}, "innKey": "key",
 *   "gates": [[0, 110]],              // road ends, main gate first
 *   "terraces": [{"rect": [x0,z0,x1,z1], "level": 7, "parapet": 3}],
 *   "landmarks": [{"id": "shell", "at": [0,-30], "size": [41,17], "h": 28, "margin": 2, "face": "S",
 *                  "flags": ["no_beard"], "raise": 0, "name": "", "kit": "", "overlay": ""}],
 *   "buildings": [{"kind": "inn", "at": [x,z], "size": [13,17], "face": "E", "name": "key", "flags": ["upper"],
 *                  "variant": 0, "raise": 0, "h": 0, "count": 1, "step": [0,12], "kit": "", "overlay": "",
 *                  "use": "sawmill", "wealth": "common"}],   // use: a building-grammar use (kind "building"); see grammar/Use
 *   "streets": [{"from": [0,100], "to": [0,-10], "width": 5, "major": true, "raise": 0, "fill": true}],
 *   "rings": [{"r": 60, "sides": 16, "width": 3, "major": false, "raise": 0, "fill": false, "at": [0,0], "offset": 0}],
 *   "lines": [{"kind": "bridge|canal|wall|street", "from": [..], "to": [..], "width": 5, "variant": 1,
 *              "lift": 2, "lift2": 2, "h": 9, "flags": [..]}],
 *   "walls": [{"r": 112, "sides": 16, "h": 10, "gates": [0, 1.57], "flags": ["major"], "towers": true, "raise": 0}],
 *   "plazas": [{"at": [0,40], "size": 17, "variant": 0, "stalls": 6, "raise": 0, "name": ""}],
 *   "quarters": [{"rect": [x0,z0,x1,z1] | "ring": [r0,r1], "culture": "dunmer", "kit": "velothi", "overlay": ""}],
 *   "queue": [{"kind": "house", "count": 30, "size": [7,9], "flags": ["upper"], "plain_every": 3, "small_every": 4,
 *              "name": "", "variant": 0}],
 *   "fill_gap": 2 }
 * </pre>
 */
public final class CityPlan {
    public enum Base { NONE, ZONING, JAVA }

    public record Terrace(int x0, int z0, int x1, int z1, int level, int parapet) {}

    public record Landmark(String id, int x, int z, int w, int d, int h, int margin, Direction face, int flags, int raise,
                           String name, String kit, String overlay) {}

    public record Building(Archetype kind, int x, int z, int w, int d, int h, int margin, Direction face, int flags,
                           int raise, int variant, String name, String culture, String kit, String overlay, int count,
                           int stepX, int stepZ, String use, String wealth) {}

    public record Line(String kind, int x1, int z1, int x2, int z2, int width, boolean major, int raise, int variant,
                       Integer lift, Integer lift2, int h, int flags, boolean fill, String tag) {}

    public record Ring(int r, int sides, int width, boolean major, int raise, boolean fill, int cx, int cz, double offset, String tag) {}

    public record Wall(int r, int sides, int h, double[] gates, int flags, boolean towers, int raise) {}

    public record Plaza(int x, int z, int size, int variant, int stalls, int raise, String name) {}

    public record Quarter(int[] rect, int[] ring, String culture, String kit, String overlay) {}

    public record Queued(Archetype kind, int count, int w, int d, int flags, int plainEvery, int smallEvery, String name,
                         int variant, int raise, String on, String use, String wealth) {}

    public final String id;
    public final Base base;
    public final String sil;
    /** 0 = not set (use the Cities.java default). */
    public final int reach, radius;
    /** Null = not set. */
    public final Direction back;
    public final String kit, overlay, innKey;
    public final Layouts.Names names;
    /** Null = not set. */
    public final int[][] gates;
    public final List<Terrace> terraces = new ArrayList<>();
    public final List<Landmark> landmarks = new ArrayList<>();
    public final List<Building> buildings = new ArrayList<>();
    public final List<Line> streets = new ArrayList<>();
    public final List<Ring> rings = new ArrayList<>();
    public final List<Line> lines = new ArrayList<>();
    public final List<Wall> walls = new ArrayList<>();
    public final List<Plaza> plazas = new ArrayList<>();
    public final List<Quarter> quarters = new ArrayList<>();
    public final List<Queued> queue = new ArrayList<>();
    public final int fillGap;
    /** Organic street networks, civic features, quality zones and the fabric (density) settings: raw JSON, read by {@link Nets}, Features and Fabric. */
    public final List<JsonObject> nets = new ArrayList<>(), zones = new ArrayList<>(), features = new ArrayList<>();
    public final JsonObject fabric;

    CityPlan(JsonObject o) {
        id = str(o, "id", "");
        if (id.isEmpty()) throw new IllegalArgumentException("plan without id");
        base = Base.valueOf(str(o, "base", "none").toUpperCase(Locale.ROOT));
        sil = str(o, "sil", "");
        reach = o.has("reach") ? o.get("reach").getAsInt() : 0;
        radius = o.has("radius") ? o.get("radius").getAsInt() : 0;
        back = o.has("back") ? dir(o.get("back").getAsString(), Direction.NORTH) : null;
        kit = str(o, "kit", "");
        overlay = str(o, "overlay", "");
        innKey = str(o, "innKey", "");
        if (o.has("names")) {
            JsonObject n = o.getAsJsonObject("names");
            names = new Layouts.Names(str(n, "inn", ""), str(n, "store", ""), str(n, "smithy", ""));
        } else {
            names = Layouts.Names.NONE;
        }
        if (o.has("gates")) {
            JsonArray g = o.getAsJsonArray("gates");
            gates = new int[g.size()][];
            for (int i = 0; i < g.size(); i++) gates[i] = ints(g.get(i).getAsJsonArray());
        } else {
            gates = null;
        }
        fillGap = o.has("fill_gap") ? o.get("fill_gap").getAsInt() : 2;
        nets.addAll(objs(o, "nets"));
        zones.addAll(objs(o, "zones"));
        features.addAll(objs(o, "features"));
        fabric = o.has("fabric") ? o.getAsJsonObject("fabric") : null;
        for (JsonObject t : objs(o, "terraces")) {
            int[] r = ints(t.getAsJsonArray("rect"));
            terraces.add(new Terrace(r[0], r[1], r[2], r[3], num(t, "level", 0), num(t, "parapet", 1)));
        }
        for (JsonObject l : objs(o, "landmarks")) {
            int[] at = pair(l, "at", 0, 0), size = pair(l, "size", 0, 0);
            landmarks.add(new Landmark(str(l, "id", ""), at[0], at[1], size[0], size[1], num(l, "h", 0), num(l, "margin", -1),
                    dir(str(l, "face", "S"), Direction.SOUTH), flags(l), num(l, "raise", 0), str(l, "name", ""),
                    str(l, "kit", ""), str(l, "overlay", "")));
        }
        for (JsonObject b : objs(o, "buildings")) {
            int[] at = pair(b, "at", 0, 0), size = pair(b, "size", 0, 0), step = pair(b, "step", 0, 0);
            buildings.add(new Building(Archetype.valueOf(str(b, "kind", "house").toUpperCase(Locale.ROOT)), at[0], at[1],
                    size[0], size[1], num(b, "h", 0), num(b, "margin", -1), dir(str(b, "face", "S"), Direction.SOUTH),
                    flags(b), num(b, "raise", 0), num(b, "variant", 0), str(b, "name", ""), str(b, "culture", ""),
                    str(b, "kit", ""), str(b, "overlay", ""), Math.max(1, num(b, "count", 1)), step[0], step[1],
                    str(b, "use", ""), str(b, "wealth", "")));
        }
        for (JsonObject s : objs(o, "streets")) streets.add(line(s, "street"));
        for (JsonObject s : objs(o, "lines")) lines.add(line(s, str(s, "kind", "street")));
        for (JsonObject r : objs(o, "rings")) {
            int[] at = pair(r, "at", 0, 0);
            rings.add(new Ring(num(r, "r", 40), Math.max(3, num(r, "sides", 16)), num(r, "width", 3), bool(r, "major", false),
                    num(r, "raise", 0), bool(r, "fill", false), at[0], at[1], r.has("offset") ? r.get("offset").getAsDouble() : 0, str(r, "tag", "")));
        }
        for (JsonObject w : objs(o, "walls")) {
            double[] gs = new double[0];
            if (w.has("gates")) {
                com.google.gson.JsonElement ge = w.get("gates");
                if (ge.isJsonArray()) {
                    JsonArray a = ge.getAsJsonArray();
                    gs = new double[a.size()];
                    for (int i = 0; i < gs.length; i++) gs[i] = a.get(i).getAsDouble();
                } else if (ge.isJsonPrimitive() && ge.getAsJsonPrimitive().isNumber()) {
                    int n = Math.max(0, ge.getAsInt());
                    gs = new double[n];
                    for (int i = 0; i < n; i++) gs[i] = (2.0 * Math.PI * i) / n;
                }
            }
            walls.add(new Wall(num(w, "r", 60), Math.max(4, num(w, "sides", 16)), num(w, "h", 9), gs, flags(w),
                    bool(w, "towers", true), num(w, "raise", 0)));
        }
        for (JsonObject p : objs(o, "plazas")) {
            int[] at = pair(p, "at", 0, 0);
            plazas.add(new Plaza(at[0], at[1], num(p, "size", 13), num(p, "variant", 0), num(p, "stalls", 0), num(p, "raise", 0),
                    str(p, "name", "")));
        }
        for (JsonObject q : objs(o, "quarters")) {
            quarters.add(new Quarter(q.has("rect") ? ints(q.getAsJsonArray("rect")) : null,
                    q.has("ring") ? ints(q.getAsJsonArray("ring")) : null, str(q, "culture", ""), str(q, "kit", ""),
                    str(q, "overlay", "")));
        }
        for (JsonObject q : objs(o, "queue")) {
            int[] size = pair(q, "size", 0, 0);
            queue.add(new Queued(Archetype.valueOf(str(q, "kind", "house").toUpperCase(Locale.ROOT)), Math.max(1, num(q, "count", 1)),
                    size[0], size[1], flags(q), num(q, "plain_every", 0), num(q, "small_every", 0), str(q, "name", ""),
                    num(q, "variant", 0), num(q, "raise", 0), str(q, "on", ""), str(q, "use", ""), str(q, "wealth", "")));
        }
    }

    // ------------------------------------------------------------------ queries

    /** The packed style int of this plan for a culture, given the default kit style {@code dflt}. */
    public int style(Culture c, int dflt) {
        int k = kit.isEmpty() ? dflt & Kits.KIT_MASK : Kits.styleOf(c, kit, dflt & Kits.KIT_MASK);
        return Kits.pack(k, overlay.isEmpty() ? 0 : Overlays.index(overlay));
    }

    /** Overlay ids this plan refers to. */
    void overlayIds(java.util.Set<String> out) {
        if (!overlay.isEmpty()) out.add(overlay);
        for (Quarter q : quarters) if (!q.overlay().isEmpty()) out.add(q.overlay());
        for (Landmark l : landmarks) if (!l.overlay().isEmpty()) out.add(l.overlay());
        for (Building b : buildings) if (!b.overlay().isEmpty()) out.add(b.overlay());
    }

    // ------------------------------------------------------------------ parsing helpers

    private static Line line(JsonObject s, String kind) {
        int[] f = pair(s, "from", 0, 0), t = pair(s, "to", 0, 0);
        boolean axis = f[0] == t[0] || f[1] == t[1];
        return new Line(kind, f[0], f[1], t[0], t[1], num(s, "width", 3), bool(s, "major", false), num(s, "raise", 0),
                num(s, "variant", 0), s.has("lift") ? s.get("lift").getAsInt() : null, s.has("lift2") ? s.get("lift2").getAsInt() : null,
                num(s, "h", 0), flags(s), bool(s, "fill", axis), str(s, "tag", ""));
    }

    static int flagOf(String f) {
        return switch (f.toLowerCase(Locale.ROOT)) {
            case "for_sale" -> Spec.FOR_SALE;
            case "upper" -> Spec.UPPER;
            case "rich" -> Spec.RICH;
            case "water" -> Spec.WATER;
            case "no_beard" -> Spec.NO_BEARD;
            case "fixed" -> Spec.FIXED;
            case "major" -> Spec.MAJOR;
            case "palisade" -> Spec.PALISADE;
            case "ruined" -> Spec.RUINED;
            case "burnt" -> Spec.BURNT;
            case "sign" -> Spec.SIGN;
            case "seat" -> Spec.SEAT;
            case "ground" -> Spec.GROUND;
            default -> throw new IllegalArgumentException("unknown flag " + f);
        };
    }

    private static int flags(JsonObject o) {
        int f = 0;
        if (o.has("flags")) for (JsonElement e : o.getAsJsonArray("flags")) f |= flagOf(e.getAsString());
        return f;
    }

    private static List<JsonObject> objs(JsonObject o, String key) {
        List<JsonObject> out = new ArrayList<>();
        if (o.has(key)) for (JsonElement e : o.getAsJsonArray(key)) out.add(e.getAsJsonObject());
        return out;
    }

    private static String str(JsonObject o, String k, String d) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : d;
    }

    private static int num(JsonObject o, String k, int d) {
        return o.has(k) ? o.get(k).getAsInt() : d;
    }

    private static boolean bool(JsonObject o, String k, boolean d) {
        return o.has(k) ? o.get(k).getAsBoolean() : d;
    }

    private static int[] ints(JsonArray a) {
        int[] r = new int[a.size()];
        for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsInt();
        return r;
    }

    private static int[] pair(JsonObject o, String k, int dx, int dz) {
        if (!o.has(k)) return new int[]{dx, dz};
        int[] v = ints(o.getAsJsonArray(k));
        return v.length >= 2 ? new int[]{v[0], v[1]} : new int[]{dx, dz};
    }

    private static Direction dir(String s, Direction d) {
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "N", "NORTH" -> Direction.NORTH;
            case "E", "EAST" -> Direction.EAST;
            case "S", "SOUTH" -> Direction.SOUTH;
            case "W", "WEST" -> Direction.WEST;
            default -> d;
        };
    }
}
