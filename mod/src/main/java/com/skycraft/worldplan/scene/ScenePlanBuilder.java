package com.skycraft.worldplan.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.worldplan.RoadPlan;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Builds the {@link ScenePlan} (bake name {@value #BAKE_NAME}) from the road plan, the places, the lore web's clues and
 * the scatter and landmark kind tables. Only inputs that do not depend on the world seed are read, every choice is a
 * hash of stable ids, and the output order is fixed, so the bytes are the same on every run.
 *
 * <p>Scenes, in priority order (an earlier scene keeps its ground, a later one gives way):</p>
 * <ol>
 *     <li>lore-web clues: each clue of a lore-web place near it, with a sign carrying the text and its note;</li>
 *     <li>places: shrines, battle sites (forts, Oblivion gates), ruin edges, camp sites, dungeon mouths;</li>
 *     <li>settlement edges and shores, farm yards;</li>
 *     <li>waysides along every planned road: one every 1.5-3 km (rarer on trails), at the best anchor near the drawn
 *     spot: a crossroads, a ford, a bridge end, else the sharpest bend.</li>
 * </ol>
 */
public final class ScenePlanBuilder {
    public static final String BAKE_NAME = "sceneplan";
    /** Spacing grid of accepted scenes (blocks). */
    private static final int GRID = 32;
    private static final int[][] DIRS = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}}; // 2D data values: south, west, north, east

    private ScenePlanBuilder() {}

    public static byte[] computeLive() throws IOException {
        StringBuilder stats = new StringBuilder();
        byte[] out = build(stats).write();
        Skycraft.LOGGER.info("[bake] sceneplan: {}", stats);
        return out;
    }

    static ScenePlan build(StringBuilder stats) {
        return new Run().run(stats);
    }

    // ------------------------------------------------------------------ hashing (stable across runs and JVMs)

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static long hash(String s, long salt) {
        long h = 0x9E3779B97F4A7C15L ^ salt;
        for (int i = 0; i < s.length(); i++) h = mix(h + s.charAt(i));
        return mix(h);
    }

    static double unit(long h, int k) {
        return (mix(h + k * 0x9E3779B97F4A7C15L) >>> 11) * 0x1.0p-53;
    }

    // ------------------------------------------------------------------ input tables

    /** A scatter kind's planning data. */
    record KindInfo(String id, boolean human, int radius, String purpose, int cmin, int cmax, int cradius, List<String> members) {
    }

    /** A landmark vignette's planning data. */
    record LmKind(String id, String builder, int radius, String purpose) {
    }

    private static JsonObject read(String path) {
        try (InputStream in = ScenePlanBuilder.class.getResourceAsStream(path)) {
            if (in == null) return new JsonObject();
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            Skycraft.LOGGER.warn("[sceneplan] could not read {}", path, e);
            return new JsonObject();
        }
    }

    private static int intOf(JsonObject o, String k, int d) {
        return o.has(k) ? o.get(k).getAsInt() : d;
    }

    private static String strOf(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    /** A weighted table of kind ids (insertion order kept). */
    static final class Table {
        final List<String> ids = new ArrayList<>();
        final List<Double> w = new ArrayList<>();
        double total;

        String pick(double u) {
            double roll = u * total;
            for (int i = 0; i < ids.size(); i++) {
                roll -= w.get(i);
                if (roll < 0) return ids.get(i);
            }
            return ids.isEmpty() ? null : ids.get(ids.size() - 1);
        }
    }

    // ------------------------------------------------------------------ one scene while planning

    static final class SceneB {
        String kind;
        int x, z, facing, radius;
        float h;
        byte anchor;
        String ref, reason, clue = "", note = "";
        int pathX = ScenePlan.NO_PATH, pathZ;
        final List<String> props = new ArrayList<>();
        final IntArrayList dx = new IntArrayList(), dz = new IntArrayList();
    }

    /** A scene layout relative to its centre (before it has a place). */
    static final class Layout {
        final List<String> kinds = new ArrayList<>();
        final IntArrayList dx = new IntArrayList(), dz = new IntArrayList(), r = new IntArrayList();
        int radius;
        String purpose = "";

        void add(String k, int x, int z, int rad) {
            kinds.add(k);
            dx.add(x);
            dz.add(z);
            r.add(rad);
            radius = Math.max(radius, Math.max(Math.abs(x), Math.abs(z)) + rad);
        }
    }

    // ------------------------------------------------------------------ the run

    static final class Run {
        final RoadPlan rp = RoadPlan.get();
        final RoadPlan.Hit hit = new RoadPlan.Hit();
        final MapSample ms = new MapSample(), probe = new MapSample();
        final Map<String, KindInfo> kinds = new LinkedHashMap<>();
        final Map<String, List<LmKind>> lmByBuilder = new LinkedHashMap<>();
        final Map<String, Table> anchors = new LinkedHashMap<>();
        final Map<String, String> signs = new LinkedHashMap<>();
        final List<Object[]> clueWords = new ArrayList<>();
        final Map<String, Double> share = new LinkedHashMap<>();
        final Map<String, Integer> perSettlement = new LinkedHashMap<>();
        double shoreShare = 0.6;
        final int[][] gap = {{1500, 3000}, {1500, 3000}, {2000, 3500}, {3500, 6000}};
        int offset = 7, settlementClear = 150;
        JsonObject clues = new JsonObject();

        final List<SceneB> scenes = new ArrayList<>();
        final Long2ObjectOpenHashMap<IntArrayList> grid = new Long2ObjectOpenHashMap<>();
        final int[] tried = new int[ScenePlan.ANCHOR_NAMES.length], refused = new int[ScenePlan.ANCHOR_NAMES.length];

        void load() {
            JsonObject k = read("/skycraft/scatter/kinds.json");
            for (Map.Entry<String, JsonElement> e : k.entrySet()) {
                if (e.getKey().startsWith("_")) continue;
                JsonObject o = e.getValue().getAsJsonObject();
                boolean human = false;
                if (o.has("tags")) for (JsonElement t : o.getAsJsonArray("tags")) human |= "human".equals(t.getAsString());
                int cmin = 0, cmax = 0, crad = 0;
                List<String> members = new ArrayList<>();
                if (o.has("cluster")) {
                    JsonObject c = o.getAsJsonObject("cluster");
                    cmin = intOf(c, "min", 3);
                    cmax = intOf(c, "max", 6);
                    crad = Math.min(6, intOf(c, "radius", 6));
                    for (JsonElement m : c.getAsJsonArray("members")) members.add(m.getAsString());
                }
                kinds.put(e.getKey(), new KindInfo(e.getKey(), human, Math.min(5, intOf(o, "radius", 3)), strOf(o, "purpose"),
                        cmin, cmax, crad, List.copyOf(members)));
            }
            JsonObject lm = read("/skycraft/landmarks/kinds.json");
            if (lm.has("kinds")) {
                for (JsonElement e : lm.getAsJsonArray("kinds")) {
                    JsonObject o = e.getAsJsonObject();
                    String tier = strOf(o, "tier");
                    if (!tier.equals("S") && !tier.equals("M")) continue;
                    String b = strOf(o, "builder");
                    if (!SceneKinds.LANDMARK_BUILDERS.contains(b)) continue;
                    lmByBuilder.computeIfAbsent(b, x -> new ArrayList<>())
                            .add(new LmKind(strOf(o, "id"), b, intOf(o, "radius", 4), strOf(o, "purpose")));
                }
            }
            JsonObject sc = read("/skycraft/scatter/rules.json");
            sc = sc.has("scenes") ? sc.getAsJsonObject("scenes") : new JsonObject();
            if (sc.has("wayside")) {
                JsonObject w = sc.getAsJsonObject("wayside");
                if (w.has("gap")) {
                    JsonObject g = w.getAsJsonObject("gap");
                    for (int c = 0; c < RoadPlan.CLASS_NAMES.length; c++) {
                        if (!g.has(RoadPlan.CLASS_NAMES[c])) continue;
                        JsonArray a = g.getAsJsonArray(RoadPlan.CLASS_NAMES[c]);
                        gap[c] = new int[]{a.get(0).getAsInt(), a.get(1).getAsInt()};
                    }
                }
                offset = intOf(w, "offset", offset);
                settlementClear = intOf(w, "settlementClear", settlementClear);
            }
            if (sc.has("share")) for (var e : sc.getAsJsonObject("share").entrySet()) share.put(e.getKey(), e.getValue().getAsDouble());
            if (sc.has("settlement")) {
                for (var e : sc.getAsJsonObject("settlement").entrySet()) {
                    if (e.getKey().equals("shore")) shoreShare = e.getValue().getAsDouble();
                    else perSettlement.put(e.getKey(), e.getValue().getAsInt());
                }
            }
            if (sc.has("anchors")) {
                for (var e : sc.getAsJsonObject("anchors").entrySet()) {
                    Table t = new Table();
                    for (var ke : e.getValue().getAsJsonObject().entrySet()) {
                        String id = ke.getKey();
                        boolean known = id.startsWith(ScenePlan.LANDMARK_PREFIX)
                                ? lmByBuilder.containsKey(id.substring(ScenePlan.LANDMARK_PREFIX.length()))
                                : kinds.containsKey(id) && kinds.get(id).human();
                        if (!known) {
                            Skycraft.LOGGER.warn("[sceneplan] anchor {} names unknown or non-human kind {}", e.getKey(), id);
                            continue;
                        }
                        t.ids.add(id);
                        t.w.add(ke.getValue().getAsDouble());
                        t.total += ke.getValue().getAsDouble();
                    }
                    anchors.put(e.getKey(), t);
                }
            }
            if (sc.has("signs")) for (var e : sc.getAsJsonObject("signs").entrySet()) signs.put(e.getKey(), e.getValue().getAsString());
            if (sc.has("clueWords")) {
                for (JsonElement e : sc.getAsJsonArray("clueWords")) {
                    JsonArray a = e.getAsJsonArray();
                    clueWords.add(new Object[]{Pattern.compile("\\b(" + a.get(0).getAsString() + ")", Pattern.CASE_INSENSITIVE),
                            a.get(1).getAsString()});
                }
            }
            JsonObject c = read("/skycraft/scatter/loreweb_clues.json");
            if (c.has("places")) clues = c.getAsJsonObject("places");
        }

        ScenePlan run(StringBuilder stats) {
            long t0 = System.nanoTime();
            load();
            List<Place> all = Places.all();
            loreClues();
            for (Place p : all) placeScene(p);
            for (Place p : all) settlementScenes(p);
            waysides();
            borderAndCorridorFill();
            wildernessAndIslandFill();
            ScenePlan plan = pack();
            if (stats != null) {
                int[] perAnchor = new int[ScenePlan.ANCHOR_NAMES.length];
                for (SceneB s : scenes) perAnchor[s.anchor]++;
                stats.append(String.format(Locale.ROOT, "%d scenes, %d props, %.0f km² land, %.0f km road (%d ms); ", plan.scenes(), plan.props(),
                        plan.landKm2, plan.roadKm[0] + plan.roadKm[1] + plan.roadKm[2] + plan.roadKm[3], (System.nanoTime() - t0) / 1_000_000));
                for (int a = 0; a < perAnchor.length; a++) {
                    stats.append(ScenePlan.ANCHOR_NAMES[a]).append('=').append(perAnchor[a]).append(" (").append(refused[a])
                            .append(" refused) ");
                }
            }
            return plan;
        }

        // -------------------------------------------------------------- kinds and layouts

        /** Lays out kind {@code id} around (0, 0): a cluster spreads its members, a vignette is one prop. Null if unknown. */
        Layout layout(String id, long h) {
            Layout l = new Layout();
            if (id.startsWith(ScenePlan.LANDMARK_PREFIX)) {
                List<LmKind> fam = lmByBuilder.get(id.substring(ScenePlan.LANDMARK_PREFIX.length()));
                if (fam == null || fam.isEmpty()) return null;
                LmKind k = fam.get((int) (unit(h, 31) * fam.size()));
                l.add(ScenePlan.LANDMARK_PREFIX + k.id(), 0, 0, k.radius());
                l.purpose = k.purpose();
                return l;
            }
            KindInfo k = kinds.get(id);
            if (k == null) return null;
            l.purpose = k.purpose();
            if (k.members().isEmpty()) {
                l.add(id, 0, 0, k.radius());
                return l;
            }
            int n = k.cmin() + (int) (unit(h, 32) * Math.max(1, k.cmax() - k.cmin() + 1));
            for (int i = 0; i < n; i++) {
                KindInfo m = kinds.get(k.members().get((int) (unit(h, 40 + i) * k.members().size())));
                if (m == null || !m.members().isEmpty()) continue;
                int mr = Math.min(3, m.radius());
                for (int attempt = 0; attempt < 12; attempt++) {
                    int px, pz;
                    if (l.kinds.isEmpty()) {
                        px = 0;
                        pz = 0;
                    } else {
                        double a = unit(h, 100 + i * 16 + attempt) * Math.PI * 2;
                        double d = 3 + unit(h, 300 + i * 16 + attempt) * (k.cradius() - 2);
                        px = (int) Math.round(Math.cos(a) * d);
                        pz = (int) Math.round(Math.sin(a) * d);
                    }
                    boolean clear = true;
                    for (int j = 0; j < l.kinds.size() && clear; j++) {
                        int ddx = l.dx.getInt(j) - px, ddz = l.dz.getInt(j) - pz, need = Math.max(mr, l.r.getInt(j));
                        if (ddx * ddx + ddz * ddz < need * need) clear = false;
                    }
                    if (clear) {
                        l.add(m.id(), px, pz, mr);
                        break;
                    }
                }
            }
            return l.kinds.isEmpty() ? null : l;
        }

        /** The kind of a lore-web clue: the first keyword table entry its words match, else a marker cairn. */
        String clueKind(String text) {
            for (Object[] cw : clueWords) {
                if (((Pattern) cw[0]).matcher(text).find() && kinds.containsKey((String) cw[1])) return (String) cw[1];
            }
            return "cairn";
        }

        // -------------------------------------------------------------- validity

        /**
         * True when a scene of footprint {@code r} may stand at (x, z): dry map ground of gentle slope, clear of every
         * named place (beyond its radius), every planned road bed and every scene planned so far.
         */
        boolean ok(int x, int z, int r, boolean wet) {
            TamrielMap.sample(x, z, ms);
            if (!wet && (ms.inWater() || ms.coastDistance < 2f)) return false;
            if (ms.coastDistance < -1f) return false;
            if (ms.slope > 0.9f) return false;
            for (int[] d : DIRS) {
                TamrielMap.sample(x + d[0] * r, z + d[1] * r, probe);
                if (!wet && (probe.inWater() || probe.coastDistance < 1f)) return false;
            }
            if (rp.at(x, z, hit) && hit.distance < hit.halfWidth + r + 1.5f) return false;
            for (Place p : Places.overlapping(x - r - 8, z - r - 8, x + r + 8, z + r + 8)) {
                double need = p.radius() + r + (p.type().isSettlement() ? 6 : 4);
                if (p.distSqr(x, z) < need * need) return false;
            }
            int c0x = Math.floorDiv(x - r - 16, GRID), c1x = Math.floorDiv(x + r + 16, GRID);
            int c0z = Math.floorDiv(z - r - 16, GRID), c1z = Math.floorDiv(z + r + 16, GRID);
            for (int cx = c0x; cx <= c1x; cx++) {
                for (int cz = c0z; cz <= c1z; cz++) {
                    IntArrayList l = grid.get(((long) cx << 32) ^ (cz & 0xFFFFFFFFL));
                    if (l == null) continue;
                    for (int i = 0; i < l.size(); i++) {
                        SceneB q = scenes.get(l.getInt(i));
                        long dx = q.x - x, dz = q.z - z, need = q.radius + r + 6;
                        if (dx * dx + dz * dz < need * need) return false;
                    }
                }
            }
            return true;
        }

        /** Every prop of the layout at (x, z) keeps off every road bed. */
        boolean propsOffRoads(Layout l, int x, int z) {
            for (int i = 0; i < l.kinds.size(); i++) {
                int px = x + l.dx.getInt(i), pz = z + l.dz.getInt(i);
                if (rp.at(px, pz, hit) && hit.distance < hit.halfWidth + 2f + l.r.getInt(i)) return false;
            }
            return true;
        }

        /** Blocks to the nearest water of the map sample (coast, lake shore, river bank). */
        static float waterDistance(MapSample s) {
            float d = s.coastDistance >= 0 && s.coastDistance < 9999f ? s.coastDistance : 9999f;
            if (!Float.isNaN(s.waterLevel)) {
                float lake = s.lakeDistance > -9000f ? -s.lakeDistance : 9999f;
                float river = Math.max(0f, s.riverDistance - s.riverHalfWidth);
                d = Math.min(d, Math.min(lake, river));
            }
            return d;
        }

        static int facingTowards(int fromX, int fromZ, double toX, double toZ) {
            double dx = toX - fromX, dz = toZ - fromZ;
            if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? 3 : 1;
            return dz > 0 ? 0 : 2;
        }

        // -------------------------------------------------------------- accepting

        SceneB accept(String kind, Layout l, int x, int z, byte anchor, String ref, String reason, int facing) {
            SceneB s = new SceneB();
            s.kind = kind;
            s.x = x;
            s.z = z;
            s.facing = facing;
            s.anchor = anchor;
            s.ref = ref;
            s.reason = reason;
            TamrielMap.sample(x, z, ms);
            s.h = ms.elevation;
            for (int i = 0; i < l.kinds.size(); i++) {
                s.props.add(l.kinds.get(i));
                s.dx.add(l.dx.getInt(i));
                s.dz.add(l.dz.getInt(i));
            }
            s.radius = l.radius;
            String sign = signs.get(kind);
            if (sign != null) s.clue = sign;
            int id = scenes.size();
            scenes.add(s);
            int c0x = Math.floorDiv(x, GRID), c0z = Math.floorDiv(z, GRID);
            grid.computeIfAbsent(((long) c0x << 32) ^ (c0z & 0xFFFFFFFFL), k -> new IntArrayList(2)).add(id);
            return s;
        }

        /**
         * Adds the clue sign (and the note barrel beside it) on the anchor side of the scene's footprint, so it is read
         * from the road or the place. The sign stands just outside the props, never on a road.
         */
        void addSign(SceneB s, boolean withNote) {
            int[] d = DIRS[s.facing];
            int dist = Math.min(7, s.radius + 1);
            for (int tries = 0; tries < 4; tries++, dist = Math.max(2, dist - 1)) {
                int sx = d[0] * dist, sz = d[1] * dist;
                // beside: perpendicular step for the barrel
                int bx = sx + d[1], bz = sz - d[0];
                boolean clash = false;
                for (int i = 0; i < s.props.size() && !clash; i++) {
                    int ex = s.dx.getInt(i) - sx, ez = s.dz.getInt(i) - sz;
                    if (ex * ex + ez * ez < 9) clash = true;
                }
                if (clash) continue;
                if (rp.at(s.x + sx, s.z + sz, hit) && hit.distance < hit.halfWidth + 1.5f) continue;
                s.props.add(ScenePlan.SIGN);
                s.dx.add(sx);
                s.dz.add(sz);
                if (withNote) {
                    s.props.add(ScenePlan.NOTE);
                    s.dx.add(bx);
                    s.dz.add(bz);
                }
                s.radius = Math.max(s.radius, Math.max(Math.abs(sx), Math.abs(sz)) + 2);
                return;
            }
        }

        /**
         * Searches a ring around a place for a spot for the layout: {@code dists} distances from the place's edge,
         * 16 angles from a hashed start. {@code high} picks the highest valid spot (a cemetery on the rise), else the
         * first. {@code waterBand} (when > 0) also requires the map's water within that many blocks.
         */
        int[] ring(Place p, Layout l, long h, int[] dists, boolean high, float waterBand, boolean wet) {
            int best = -1;
            float bestH = -1e9f;
            int[] out = null;
            double start = unit(h, 7) * Math.PI * 2;
            for (int di = 0; di < dists.length; di++) {
                int d = p.radius() + l.radius + dists[di];
                for (int a = 0; a < 16; a++) {
                    double ang = start + a * Math.PI / 8;
                    int x = p.x() + (int) Math.round(Math.cos(ang) * d), z = p.z() + (int) Math.round(Math.sin(ang) * d);
                    if (!ok(x, z, l.radius, wet) || !propsOffRoads(l, x, z)) continue;
                    if (waterBand > 0) {
                        float wd = waterDistance(ms);
                        if (wd < 3 || wd > waterBand) continue;
                    }
                    if (!high) return new int[]{x, z};
                    TamrielMap.sample(x, z, ms);
                    if (ms.elevation > bestH) {
                        bestH = ms.elevation;
                        out = new int[]{x, z};
                        best = a;
                    }
                }
                if (best >= 0) return out;
            }
            return out;
        }

        String pickKind(String anchorKey, long h) {
            Table t = anchors.get(anchorKey);
            return t == null || t.total <= 0 ? null : t.pick(unit(h, 3));
        }

        static String purposeOf(Layout l) {
            return l.purpose == null || l.purpose.isEmpty() ? "" : ": " + l.purpose;
        }

        // -------------------------------------------------------------- lore-web clues

        void loreClues() {
            for (Map.Entry<String, JsonElement> e : clues.entrySet()) {
                String pid = e.getKey();
                Optional<Place> op = Places.byId(pid);
                if (op.isEmpty()) continue;
                Place p = op.get();
                JsonObject o = e.getValue().getAsJsonObject();
                List<String> texts = new ArrayList<>();
                List<String> notes = new ArrayList<>();
                JsonArray cs = o.has("clues") ? o.getAsJsonArray("clues") : new JsonArray();
                for (int i = 0; i < cs.size(); i++) {
                    texts.add(cs.get(i).getAsString());
                    notes.add(i == 0 ? strOf(o, "note") : "");
                }
                if (!strOf(o, "thread_clue").isEmpty()) {
                    texts.add(strOf(o, "thread_clue"));
                    notes.add(strOf(o, "thread_note"));
                }
                String arc = strOf(o, "arc");
                for (int i = 0; i < texts.size(); i++) {
                    String text = texts.get(i);
                    long h = hash(pid + "#" + i, 0x10E3EBL);
                    String kind = clueKind(text);
                    tried[ScenePlan.LOREWEB_CLUE]++;
                    Layout l = layout(kind, h);
                    if (l == null) continue;
                    l.radius += 2; // room for the sign
                    int[] at = ring(p, l, h, new int[]{10 + 6 * i, 18 + 6 * i, 30 + 6 * i, 48}, false, 0, false);
                    if (at == null) {
                        refused[ScenePlan.LOREWEB_CLUE]++;
                        continue;
                    }
                    String why = "lore-web clue " + (i + 1) + " of " + pid + (arc.isEmpty() ? "" : " (arc " + arc + ")") + purposeOf(l);
                    SceneB s = accept(kind, l, at[0], at[1], ScenePlan.LOREWEB_CLUE, pid + "#" + i, why,
                            facingTowards(at[0], at[1], p.x(), p.z()));
                    s.clue = text;
                    s.note = notes.get(i);
                    addSign(s, !s.note.isEmpty());
                }
            }
        }

        // -------------------------------------------------------------- places (shrines, battles, ruins, camps, dungeons)

        void placeScene(Place p) {
            byte anchor;
            String key;
            PlaceType t = p.type();
            String v = p.variant() == null ? "" : p.variant();
            if (t == PlaceType.DAEDRIC_SHRINE || t == PlaceType.STANDING_STONE || t == PlaceType.WORD_WALL) {
                anchor = ScenePlan.SHRINE_SITE;
            } else if (t == PlaceType.FORT || (t == PlaceType.LANDMARK && v.equals("oblivion_gate"))) {
                anchor = ScenePlan.BATTLE_SITE;
            } else if (t == PlaceType.LANDMARK && SceneKinds.RUIN_VARIANTS.contains(v)) {
                anchor = ScenePlan.RUIN_EDGE;
            } else if (t == PlaceType.LANDMARK && v.equals("campsite")) {
                anchor = ScenePlan.CAMP_SITE;
            } else if (t == PlaceType.DUNGEON_FIXED) {
                anchor = ScenePlan.DUNGEON_MOUTH;
            } else {
                return;
            }
            key = ScenePlan.ANCHOR_NAMES[anchor];
            long h = hash(p.id(), 0x5CE7E0L + anchor);
            if (unit(h, 1) >= share.getOrDefault(key, 0.5)) return;
            tried[anchor]++;
            String kind = pickKind(key, h);
            Layout l = kind == null ? null : layout(kind, h);
            if (l == null) return;
            int[] at = ring(p, l, h, new int[]{6, 12, 20, 32}, false, 0, false);
            if (at == null) {
                refused[anchor]++;
                return;
            }
            String what = switch (anchor) {
                case ScenePlan.SHRINE_SITE -> "pilgrims at the shrine " + p.id();
                case ScenePlan.BATTLE_SITE -> "the fighting at " + p.id();
                case ScenePlan.RUIN_EDGE -> "the edge of the ruin " + p.id();
                case ScenePlan.CAMP_SITE -> "the camp site " + p.id();
                default -> "the mouth of " + p.id();
            };
            SceneB s = accept(kind, l, at[0], at[1], anchor, p.id(), what + purposeOf(l), facingTowards(at[0], at[1], p.x(), p.z()));
            if (!s.clue.isEmpty()) addSign(s, false);
        }

        // -------------------------------------------------------------- settlements and farms

        void settlementScenes(Place p) {
            PlaceType t = p.type();
            if (!t.isSettlement()) return;
            if (t == PlaceType.FARM) {
                if (unit(hash(p.id(), 0xFA4L), 1) >= share.getOrDefault("FARM", 1.0)) return;
                long h = hash(p.id(), 0xFA4D);
                tried[ScenePlan.FARM]++;
                String kind = pickKind("FARM", h);
                Layout l = kind == null ? null : layout(kind, h);
                if (l == null) return;
                int[] at = ring(p, l, h, new int[]{4, 10, 18, 28}, false, 0, false);
                if (at == null) {
                    refused[ScenePlan.FARM]++;
                    return;
                }
                accept(kind, l, at[0], at[1], ScenePlan.FARM, p.id(), "the working yard of the farm " + p.id() + purposeOf(l),
                        facingTowards(at[0], at[1], p.x(), p.z()));
                return;
            }
            int n = perSettlement.getOrDefault(t.id(), 1);
            for (int i = 0; i < n; i++) {
                long h = hash(p.id() + "#" + i, 0xED6EL);
                tried[ScenePlan.SETTLEMENT_EDGE]++;
                String kind = pickKind("SETTLEMENT_EDGE", h);
                Layout l = kind == null ? null : layout(kind, h);
                if (l == null) continue;
                boolean high = kind.equals("cemetery");
                int[] at = ring(p, l, h, new int[]{8, 16, 26, 40}, high, 0, false);
                if (at == null) {
                    refused[ScenePlan.SETTLEMENT_EDGE]++;
                    continue;
                }
                SceneB s = accept(kind, l, at[0], at[1], ScenePlan.SETTLEMENT_EDGE, p.id(), "the edge of " + p.id() + purposeOf(l),
                        facingTowards(at[0], at[1], p.x(), p.z()));
                if (!s.clue.isEmpty()) addSign(s, false);
            }
            long h = hash(p.id(), 0x5408EL);
            if (unit(h, 1) < shoreShare) {
                String kind = pickKind("SETTLEMENT_SHORE", h);
                Layout l = kind == null ? null : layout(kind, h);
                if (l == null) return;
                boolean wet = kind.equals("boat_wreck") || kind.startsWith(ScenePlan.LANDMARK_PREFIX);
                int[] at = ring(p, l, h, new int[]{6, 14, 24, 36}, false, 24f, wet);
                if (at == null) return; // most settlements are not on the water: not a refusal
                tried[ScenePlan.SETTLEMENT_EDGE]++;
                accept(kind, l, at[0], at[1], ScenePlan.SETTLEMENT_EDGE, p.id() + "@shore",
                        "the fishers' shore of " + p.id() + purposeOf(l), facingTowards(at[0], at[1], p.x(), p.z()));
            }
        }

        // -------------------------------------------------------------- waysides along the roads

        /** Global vertex of a segment where another segment's end joins it -> the joining segment. */
        final Int2IntOpenHashMap junction = new Int2IntOpenHashMap();

        void junctions() {
            junction.defaultReturnValue(-1);
            for (int s = 0; s < rp.segments(); s++) {
                for (int end = 0; end < 2; end++) {
                    int t = end == 0 ? rp.segJoinA[s] : rp.segJoinB[s];
                    if (t < 0 || t >= rp.segments()) continue;
                    int v = end == 0 ? rp.segStart[s] : rp.segEnd[s] - 1;
                    int best = -1;
                    double bestD = 1e18;
                    for (int u = rp.segStart[t]; u < rp.segEnd[t]; u++) {
                        double dx = rp.vx[u] - rp.vx[v], dz = rp.vz[u] - rp.vz[v], d = dx * dx + dz * dz;
                        if (d < bestD) {
                            bestD = d;
                            best = u;
                        }
                    }
                    if (best >= 0 && bestD < 24 * 24 && !junction.containsKey(best)) junction.put(best, s);
                }
            }
        }

        void waysides() {
            junctions();
            List<IntArrayList> byLink = new ArrayList<>();
            for (int l = 0; l < rp.links(); l++) byLink.add(new IntArrayList());
            for (int s = 0; s < rp.segments(); s++) if (rp.segLink[s] >= 0 && rp.segLink[s] < byLink.size()) byLink.get(rp.segLink[s]).add(s);
            for (int link = 0; link < rp.links(); link++) {
                IntArrayList segs = byLink.get(link);
                if (segs.isEmpty()) continue;
                int cls = Math.max(0, Math.min(3, rp.linkKind[link]));
                long h = hash(rp.linkA[link] + "|" + rp.linkB[link] + "|" + link, 0x3A75L);
                int draws = 0;
                // a uniform phase: a link of length L gets L / gap waysides on average, however short it is
                double next = draw(cls, h, draws++) * unit(h, 2);
                double acc = 0;
                for (int si = 0; si < segs.size(); si++) {
                    int s = segs.getInt(si);
                    int a = rp.segStart[s], b = rp.segEnd[s];
                    double len = rp.varc[b - 1];
                    while (next <= acc + len) {
                        double local = next - acc;
                        int v = a;
                        while (v < b - 1 && rp.varc[v] < local) v++;
                        double at = wayside(link, s, v, hash(link + ":" + draws, h));
                        if (at >= 0) next = acc + at + draw(cls, h, draws++);
                        else next += 250;
                    }
                    acc += len;
                }
            }
        }

        double draw(int cls, long h, int k) {
            int[] g = gap[cls];
            return g[0] + unit(h, 50 + k) * (g[1] - g[0]);
        }

        /** Turn angle (degrees) of the road at vertex u over 16 blocks each way, within the segment. */
        double turn(int a, int b, int u) {
            int p = Math.max(a, u - 4), q = Math.min(b - 1, u + 4);
            if (u - p < 2 || q - u < 2) return 0;
            double ax = rp.vx[u] - rp.vx[p], az = rp.vz[u] - rp.vz[p], bx = rp.vx[q] - rp.vx[u], bz = rp.vz[q] - rp.vz[u];
            double la = Math.hypot(ax, az), lb = Math.hypot(bx, bz);
            if (la < 1e-3 || lb < 1e-3) return 0;
            double c = Math.max(-1, Math.min(1, (ax * bx + az * bz) / (la * lb)));
            return Math.toDegrees(Math.acos(c));
        }

        /**
         * Tries to place one wayside of segment s near vertex v: the best anchor within ±60 vertices (about 240 blocks).
         * Returns the arc distance (in the segment) of the scene's anchor, or -1 when nothing fits there.
         */
        double wayside(int link, int s, int v, long h) {
            int a = rp.segStart[s], b = rp.segEnd[s];
            int lo = Math.max(a + 2, v - 60), hi = Math.min(b - 3, v + 60);
            if (hi < lo) return -1;
            int best = -1;
            byte bestAnchor = ScenePlan.ROAD_BEND;
            double bestScore = -1e9;
            int bestJoin = -1;
            for (int u = lo; u <= hi; u++) {
                // the edge of a settlement: its own scenes cover it
                if (nearSettlement(rp.vx[u], rp.vz[u])) continue;
                double score;
                byte anchor;
                int join = junction.get(u);
                if (u - a <= 3 && rp.segJoinA[s] >= 0) join = rp.segJoinA[s];
                if (b - 1 - u <= 3 && rp.segJoinB[s] >= 0) join = rp.segJoinB[s];
                byte span = rp.vspan[u];
                if (join >= 0) {
                    score = 4;
                    anchor = ScenePlan.CROSSROADS;
                } else if (span == RoadPlan.FORD && u > a && rp.vspan[u - 1] != RoadPlan.FORD) {
                    score = 3;
                    anchor = ScenePlan.FORD;
                } else if (span == RoadPlan.BRIDGE && u > a && rp.vspan[u - 1] != RoadPlan.BRIDGE) {
                    score = 2.5;
                    anchor = ScenePlan.BRIDGE_END;
                } else {
                    // the sharpest bend wins; a near-straight stretch only when nothing else is in reach
                    double t = turn(a, b, u);
                    score = t < 10 ? t / 100.0 - 1 : Math.min(2.4, t / 30.0);
                    anchor = ScenePlan.ROAD_BEND;
                }
                // closer to the drawn spot wins ties
                score -= Math.abs(u - v) * 0.002;
                if (score > bestScore) {
                    bestScore = score;
                    best = u;
                    bestAnchor = anchor;
                    bestJoin = join;
                }
            }
            if (best < 0) return -1;
            tried[bestAnchor]++;
            String key = ScenePlan.ANCHOR_NAMES[bestAnchor];
            String kind = pickKind(key, h);
            Layout l = kind == null ? null : layout(kind, h);
            if (l == null) return -1;
            // ford and bridge scenes stand on the bank before the crossing
            int u0 = bestAnchor == ScenePlan.FORD || bestAnchor == ScenePlan.BRIDGE_END ? Math.max(a, best - 2) : best;
            float hw = rp.segHalfWidth[s];
            boolean firstSide = unit(h, 5) < 0.5;
            for (int shift = 0; shift < 5; shift++) {
                int u = Math.max(a, Math.min(b - 2, u0 + (shift % 2 == 0 ? shift / 2 : -(shift + 1) / 2) * 2));
                int n1 = Math.min(b - 1, u + 1), n0 = Math.max(a, u - 1);
                double dx = rp.vx[n1] - rp.vx[n0], dz = rp.vz[n1] - rp.vz[n0], len = Math.hypot(dx, dz);
                if (len < 1e-3) continue;
                dx /= len;
                dz /= len;
                for (int side = 0; side < 2; side++) {
                    int sgn = (side == 0) == firstSide ? 1 : -1;
                    double nx = -dz * sgn, nz = dx * sgn;
                    double d = hw + offset + l.radius;
                    int x = (int) Math.round(rp.vx[u] + nx * d), z = (int) Math.round(rp.vz[u] + nz * d);
                    if (!ok(x, z, l.radius, false) || !propsOffRoads(l, x, z)) continue;
                    String road = rp.linkA[link] + "-" + rp.linkB[link];
                    String what = switch (bestAnchor) {
                        case ScenePlan.CROSSROADS -> "a crossroads on the " + RoadPlan.CLASS_NAMES[rp.segKind[s]] + " " + road;
                        case ScenePlan.FORD -> "the ford on the " + RoadPlan.CLASS_NAMES[rp.segKind[s]] + " " + road;
                        case ScenePlan.BRIDGE_END -> "the bridge end on the " + RoadPlan.CLASS_NAMES[rp.segKind[s]] + " " + road;
                        default -> "a bend of the " + RoadPlan.CLASS_NAMES[rp.segKind[s]] + " " + road;
                    };
                    SceneB sc = accept(kind, l, x, z, bestAnchor, "seg:" + s + ":v" + (best - a), "wayside at " + what + purposeOf(l),
                            facingTowards(x, z, rp.vx[u], rp.vz[u]));
                    sc.h = rp.vg[u];
                    // a worn path from the scene to the road's edge
                    sc.pathX = (int) Math.round(rp.vx[u] + nx * (hw + 0.5));
                    sc.pathZ = (int) Math.round(rp.vz[u] + nz * (hw + 0.5));
                    if (bestAnchor == ScenePlan.CROSSROADS) {
                        String names = roadNames(link, bestJoin);
                        if (!names.isEmpty()) sc.clue = "@roads:" + names;
                    }
                    if (!sc.clue.isEmpty()) addSign(sc, false);
                    return rp.varc[best];
                }
            }
            refused[bestAnchor]++;
            return -1;
        }

        /** True within {@link #settlementClear} blocks of a settlement's edge (its edge scenes own that ground). */
        boolean nearSettlement(float x, float z) {
            int c = settlementClear;
            for (Place p : Places.overlapping((int) x - c, (int) z - c, (int) x + c, (int) z + c)) {
                if (!p.type().isSettlement()) continue;
                double edge = Math.sqrt(p.distSqr((int) x, (int) z)) - p.radius();
                if (edge < c) return true;
            }
            return false;
        }

        /** The named places the two roads of a crossroads lead to (place ids, comma separated, at most three). */
        String roadNames(int link, int joinSeg) {
            List<String> out = new ArrayList<>();
            addEnd(out, rp.linkA[link]);
            addEnd(out, rp.linkB[link]);
            if (joinSeg >= 0) {
                int jl = rp.segLink[joinSeg];
                if (jl >= 0 && jl < rp.links()) {
                    addEnd(out, rp.linkA[jl]);
                    addEnd(out, rp.linkB[jl]);
                }
            }
            return String.join(",", out.subList(0, Math.min(3, out.size())));
        }

        static void addEnd(List<String> out, String id) {
            if (id == null) return;
            int bar = id.indexOf('|');
            String pid = bar >= 0 ? id.substring(0, bar) : id;
            if (pid.isEmpty() || pid.startsWith("@") || out.contains(pid) || Places.byId(pid).isEmpty()) return;
            out.add(pid);
        }

        /** True if any named place or already-accepted scene lies within {@code r} blocks of (x, z). */
        boolean hasContentNear(int x, int z, int r) {
            long r2 = (long) r * r;
            for (Place p : Places.overlapping(x - r, z - r, x + r, z + r)) {
                if (p.distSqr(x, z) <= r2) return true;
            }
            int c0x = Math.floorDiv(x - r, GRID), c1x = Math.floorDiv(x + r, GRID);
            int c0z = Math.floorDiv(z - r, GRID), c1z = Math.floorDiv(z + r, GRID);
            for (int cx = c0x; cx <= c1x; cx++) {
                for (int cz = c0z; cz <= c1z; cz++) {
                    IntArrayList l = grid.get(((long) cx << 32) ^ (cz & 0xFFFFFFFFL));
                    if (l == null) continue;
                    for (int i = 0; i < l.size(); i++) {
                        SceneB q = scenes.get(l.getInt(i));
                        long dx = q.x - x, dz = q.z - z;
                        if (dx * dx + dz * dz <= r2) return true;
                    }
                }
            }
            return false;
        }

        /** Places a roadside scene beside vertex u of segment s with the given anchor, ref, and reason prefix. */
        boolean placeRoadsideScene(int s, int u, byte anchor, String ref, String what, long h) {
            int a = rp.segStart[s], b = rp.segEnd[s];
            String key = ScenePlan.ANCHOR_NAMES[anchor];
            String kind = pickKind(key, h);
            Layout l = kind == null ? null : layout(kind, h);
            if (l == null) return false;
            float hw = rp.segHalfWidth[s];
            boolean firstSide = unit(h, 5) < 0.5;
            for (int shift = 0; shift < 5; shift++) {
                int u1 = Math.max(a, Math.min(b - 2, u + (shift % 2 == 0 ? shift / 2 : -(shift + 1) / 2) * 2));
                int n1 = Math.min(b - 1, u1 + 1), n0 = Math.max(a, u1 - 1);
                double dx = rp.vx[n1] - rp.vx[n0], dz = rp.vz[n1] - rp.vz[n0], len = Math.hypot(dx, dz);
                if (len < 1e-3) continue;
                dx /= len;
                dz /= len;
                for (int side = 0; side < 2; side++) {
                    int sgn = (side == 0) == firstSide ? 1 : -1;
                    double nx = -dz * sgn, nz = dx * sgn;
                    double d = hw + offset + l.radius;
                    int x = (int) Math.round(rp.vx[u1] + nx * d), z = (int) Math.round(rp.vz[u1] + nz * d);
                    if (!ok(x, z, l.radius, false) || !propsOffRoads(l, x, z)) continue;
                    tried[anchor]++;
                    SceneB sc = accept(kind, l, x, z, anchor, ref, what + purposeOf(l),
                            facingTowards(x, z, rp.vx[u1], rp.vz[u1]));
                    sc.h = rp.vg[u1];
                    sc.pathX = (int) Math.round(rp.vx[u1] + nx * (hw + 0.5));
                    sc.pathZ = (int) Math.round(rp.vz[u1] + nz * (hw + 0.5));
                    if (!sc.clue.isEmpty()) addSign(sc, false);
                    return true;
                }
            }
            return false;
        }

        /**
         * Blends regional road crossings and fills any long empty road stretches (> 380 blocks from places/scenes)
         * using non-seg refs so wayside density stays within target bands.
         */
        void borderAndCorridorFill() {
            int maxScenes = (int) (landKm2() * 14.3f);
            byte[] borderAnchors = {ScenePlan.SHRINE_SITE, ScenePlan.CAMP_SITE, ScenePlan.BATTLE_SITE};
            byte[] corridorAnchors = {ScenePlan.CAMP_SITE, ScenePlan.SHRINE_SITE, ScenePlan.RUIN_EDGE};
            // 1. regional border crossings along highway, road, and lane segments
            for (int s = 0; s < rp.segments() && scenes.size() < maxScenes; s++) {
                if (rp.segKind[s] > 2) continue;
                int a = rp.segStart[s], b = rp.segEnd[s];
                int prevReg = -1;
                for (int u = a + 2; u < b - 2 && scenes.size() < maxScenes; u += 4) {
                    int x = Math.round(rp.vx[u]), z = Math.round(rp.vz[u]);
                    TamrielMap.sample(x, z, ms);
                    int reg = ms.region;
                    if (reg > 0 && prevReg > 0 && reg != prevReg && !nearSettlement(rp.vx[u], rp.vz[u]) && !hasContentNear(x, z, 160)) {
                        long h = hash("border:" + Math.min(reg, prevReg) + ":" + Math.max(reg, prevReg) + ":" + s + ":" + u, 0xB04DE4L);
                        byte anchor = borderAnchors[(int) (unit(h, 1) * borderAnchors.length)];
                        int link = rp.segLink[s];
                        String road = (link >= 0 && link < rp.links()) ? (rp.linkA[link] + "-" + rp.linkB[link]) : ("seg-" + s);
                        placeRoadsideScene(s, u, anchor, "border:" + prevReg + "-" + reg + ":s" + s,
                                "regional border crossing on " + road, h);
                    }
                    if (reg > 0) prevReg = reg;
                }
            }
            // 2. fill long empty stretches (> 380m from any place or scene) along highways, roads, and lanes
            for (int s = 0; s < rp.segments() && scenes.size() < maxScenes; s++) {
                if (rp.segKind[s] > 2) continue;
                int a = rp.segStart[s], b = rp.segEnd[s];
                for (int u = a + 4; u < b - 4 && scenes.size() < maxScenes; u += 12) {
                    int x = Math.round(rp.vx[u]), z = Math.round(rp.vz[u]);
                    if (nearSettlement(rp.vx[u], rp.vz[u]) || hasContentNear(x, z, 380)) continue;
                    long h = hash("corridor:" + s + ":" + u, 0xC0441DL);
                    byte anchor = corridorAnchors[(int) (unit(h, 1) * corridorAnchors.length)];
                    int link = rp.segLink[s];
                    String road = (link >= 0 && link < rp.links()) ? (rp.linkA[link] + "-" + rp.linkB[link]) : ("seg-" + s);
                    placeRoadsideScene(s, u, anchor, "corridor:s" + s + ":v" + (u - a),
                            "roadside camp along " + road, h);
                }
            }
        }

        /** Fills deep-forest/wilderness gaps and islands so no 400m land zone is empty of scenes. */
        void wildernessAndIslandFill() {
            int maxScenes = (int) (landKm2() * 14.4f);
            byte[] wildAnchors = {ScenePlan.CAMP_SITE, ScenePlan.SHRINE_SITE, ScenePlan.RUIN_EDGE, ScenePlan.BATTLE_SITE};
            int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (Place p : Places.all()) {
                x0 = Math.min(x0, p.x());
                z0 = Math.min(z0, p.z());
                x1 = Math.max(x1, p.x());
                z1 = Math.max(z1, p.z());
            }
            if (x0 > x1) return;
            for (int gx = x0; gx <= x1 && scenes.size() < maxScenes; gx += 420) {
                for (int gz = z0; gz <= z1 && scenes.size() < maxScenes; gz += 420) {
                    long h = hash("wild:" + gx + ":" + gz, 0x711D5CL);
                    int x = gx + (int) Math.round((unit(h, 1) - 0.5) * 160);
                    int z = gz + (int) Math.round((unit(h, 2) - 0.5) * 160);
                    TamrielMap.sample(x, z, ms);
                    if (ms.region <= 0 || ms.coastDistance < 4f || ms.inWater() || ms.slope > 0.7f) continue;
                    if (hasContentNear(x, z, 380)) continue;
                    byte anchor = wildAnchors[(int) (unit(h, 4) * wildAnchors.length)];
                    String key = ScenePlan.ANCHOR_NAMES[anchor];
                    String kind = pickKind(key, h);
                    Layout l = kind == null ? null : layout(kind, h);
                    if (l == null || !ok(x, z, l.radius, false) || !propsOffRoads(l, x, z)) continue;
                    tried[anchor]++;
                    SceneB sc = accept(kind, l, x, z, anchor, "wild:" + gx + ":" + gz,
                            "wilderness site in region " + ms.region + purposeOf(l), (int) (unit(h, 6) * 4));
                    if (!sc.clue.isEmpty()) addSign(sc, false);
                }
            }
        }

        // -------------------------------------------------------------- packing

        ScenePlan pack() {
            Map<String, Integer> kindIdx = new LinkedHashMap<>();
            int n = scenes.size();
            int[] kind = new int[n], x = new int[n], z = new int[n], px = new int[n], pz = new int[n], off = new int[n + 1];
            float[] h = new float[n];
            byte[] facing = new byte[n], anchor = new byte[n];
            String[] ref = new String[n], reason = new String[n], clue = new String[n], note = new String[n];
            short[] radius = new short[n];
            IntArrayList pk = new IntArrayList(), pdx = new IntArrayList(), pdz = new IntArrayList();
            for (int i = 0; i < n; i++) {
                SceneB s = scenes.get(i);
                kind[i] = kindIdx.computeIfAbsent(s.kind, k -> kindIdx.size());
                x[i] = s.x;
                z[i] = s.z;
                h[i] = Math.round(s.h * 16f) / 16f;
                facing[i] = (byte) s.facing;
                anchor[i] = s.anchor;
                ref[i] = s.ref;
                reason[i] = s.reason;
                clue[i] = s.clue;
                note[i] = s.note;
                px[i] = s.pathX;
                pz[i] = s.pathZ;
                radius[i] = (short) s.radius;
                off[i] = pk.size();
                for (int p = 0; p < s.props.size(); p++) {
                    pk.add(kindIdx.computeIfAbsent(s.props.get(p), k -> kindIdx.size()));
                    pdx.add(s.dx.getInt(p));
                    pdz.add(s.dz.getInt(p));
                }
            }
            off[n] = pk.size();
            short[] propKind = new short[pk.size()];
            byte[] propDx = new byte[pk.size()], propDz = new byte[pk.size()];
            for (int p = 0; p < propKind.length; p++) {
                propKind[p] = (short) pk.getInt(p);
                propDx[p] = (byte) pdx.getInt(p);
                propDz[p] = (byte) pdz.getInt(p);
            }
            float[] roadKm = new float[RoadPlan.CLASS_NAMES.length];
            for (int c = 0; c < roadKm.length; c++) roadKm[c] = Math.round(rp.length(c)) / 1000f;
            return new ScenePlan(landKm2(), roadKm, kindIdx.keySet().toArray(new String[0]), kind, x, z, h, facing, anchor, ref,
                    reason, clue, note, px, pz, radius, off, propKind, propDx, propDz);
        }

        /** Land (km²) of the authored map inside the box of every place: a 128-block lattice of dry map samples. */
        float landKm2() {
            int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (Place p : Places.all()) {
                x0 = Math.min(x0, p.x());
                z0 = Math.min(z0, p.z());
                x1 = Math.max(x1, p.x());
                z1 = Math.max(z1, p.z());
            }
            if (x0 > x1) return 0f;
            long land = 0;
            for (int x = x0 - 1024; x <= x1 + 1024; x += 128) {
                for (int z = z0 - 1024; z <= z1 + 1024; z += 128) {
                    TamrielMap.sample(x, z, ms);
                    if (ms.coastDistance >= 0f && !ms.inWater()) land++;
                }
            }
            return Math.round(land * 128.0 * 128.0 / 1e6);
        }
    }
}
