package com.nirncraft.tamriel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Daggerfall-scale political fiefs around the Iliac Bay (`nirncraft/tamriel/fiefs.json`).
 *
 * <p>A fief is a named feudal sub-area inside a NirnCraft realm (Region). It carries political metadata
 * (seat, lord title, ruling house, temple, knightly order, witch coven, vampire bloodline, banner, and
 * Crown/Forebear alignment) and a simplified polygon for `Fiefs.at(x, z)` lookups on the map screen,
 * road boundary signs, and court dialogue.
 */
public final class Fiefs {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PATH = "/nirncraft/tamriel/fiefs.json";

    private static volatile List<Fief> ALL;
    private static volatile Map<String, Fief> BY_ID;
    private static volatile Map<String, List<Fief>> BY_REALM;

    private Fiefs() {}

    public record Fief(
            String id,
            String realm,
            String name,
            String seat,
            String lord,
            String rulingHouse,
            String temple,
            String order,
            String coven,
            String bloodline,
            String banner,
            String faction,
            int seatX,
            int seatZ,
            double areaKm2,
            int minX,
            int minZ,
            int maxX,
            int maxZ,
            int[][][] polys
    ) {
        public String province() {
            int slash = realm.indexOf('/');
            return slash > 0 ? realm.substring(0, slash) : realm;
        }

        public int[][] poly() {
            return polys.length > 0 ? polys[0] : new int[0][0];
        }

        public boolean contains(int x, int z) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) return false;
            for (int[][] ring : polys) {
                if (pointInPoly(x, z, ring)) return true;
            }
            return false;
        }
    }

    public static List<Fief> all() {
        ensureLoaded();
        return ALL;
    }

    @Nullable
    public static Fief get(String id) {
        return byId(id);
    }

    @Nullable
    public static Fief byId(String id) {
        if (id == null) return null;
        ensureLoaded();
        return BY_ID.get(id);
    }

    public static List<Fief> inRealm(String realmId) {
        if (realmId == null) return List.of();
        ensureLoaded();
        return BY_REALM.getOrDefault(realmId, List.of());
    }

    @Nullable
    public static Fief at(BlockPos pos) {
        return at(pos.getX(), pos.getZ());
    }

    @Nullable
    public static Fief at(int blockX, int blockZ) {
        ensureLoaded();
        for (Fief f : ALL) {
            if (f.contains(blockX, blockZ)) {
                return f;
            }
        }
        return null;
    }

    private static boolean pointInPoly(int x, int z, int[][] poly) {
        int n = poly.length;
        if (n < 3) return false;
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            int xi = poly[i][0], zi = poly[i][1];
            int xj = poly[j][0], zj = poly[j][1];
            if (((zi > z) != (zj > z)) && (x < (long) (xj - xi) * (z - zi) / (double) (zj - zi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static void ensureLoaded() {
        if (ALL != null) return;
        synchronized (Fiefs.class) {
            if (ALL != null) return;
            load();
        }
    }

    private static void load() {
        List<Fief> list = new ArrayList<>();
        Map<String, Fief> byId = new HashMap<>();
        Map<String, List<Fief>> byRealm = new HashMap<>();
        try (InputStream in = Fiefs.class.getResourceAsStream(PATH)) {
            if (in != null) {
                JsonElement rootEl = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                JsonArray arr = rootEl.isJsonObject() && rootEl.getAsJsonObject().has("fiefs")
                        ? rootEl.getAsJsonObject().getAsJsonArray("fiefs")
                        : rootEl.getAsJsonArray();
                for (JsonElement el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    String id = str(o, "id", "");
                    String realm = str(o, "realm", "");
                    String name = str(o, "name", id);
                    String seat = str(o, "seat", "");
                    String lord = str(o, "lord", "");
                    String rulingHouse = str(o, "ruling_house", "");
                    String temple = str(o, "temple", "");
                    String order = str(o, "order", "");
                    String coven = str(o, "coven", "");
                    String bloodline = str(o, "bloodline", "");
                    String banner = str(o, "banner", "");
                    String faction = str(o, "faction", "");
                    int seatX = 0, seatZ = 0;
                    if (o.has("seat_mc") && o.get("seat_mc").isJsonArray()) {
                        JsonArray smc = o.getAsJsonArray("seat_mc");
                        if (smc.size() >= 2 && !smc.get(0).isJsonNull()) {
                            seatX = smc.get(0).getAsInt();
                            seatZ = smc.get(1).getAsInt();
                        }
                    }
                    double areaKm2 = o.has("area_km2") ? o.get("area_km2").getAsDouble() : 0.0;
                    int[][][] polys = parsePolys(o);
                    int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                    int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                    for (int[][] ring : polys) {
                        for (int[] pt : ring) {
                            if (pt[0] < minX) minX = pt[0];
                            if (pt[0] > maxX) maxX = pt[0];
                            if (pt[1] < minZ) minZ = pt[1];
                            if (pt[1] > maxZ) maxZ = pt[1];
                        }
                    }
                    if (polys.length == 0) {
                        minX = minZ = maxX = maxZ = 0;
                    }
                    Fief f = new Fief(id, realm, name, seat, lord, rulingHouse, temple, order, coven,
                            bloodline, banner, faction, seatX, seatZ, areaKm2, minX, minZ, maxX, maxZ, polys);
                    list.add(f);
                    byId.put(id, f);
                    int slash = id.indexOf('/');
                    if (slash > 0) {
                        byId.putIfAbsent(id.substring(slash + 1), f);
                    }
                    byRealm.computeIfAbsent(realm, k -> new ArrayList<>()).add(f);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load {}", PATH, e);
        }
        ALL = Collections.unmodifiableList(list);
        BY_ID = Collections.unmodifiableMap(byId);
        Map<String, List<Fief>> immByRealm = new HashMap<>();
        for (Map.Entry<String, List<Fief>> entry : byRealm.entrySet()) {
            immByRealm.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        BY_REALM = Collections.unmodifiableMap(immByRealm);
        LOGGER.info("Loaded {} Tamriel fiefs", ALL.size());
    }

    private static int[][][] parsePolys(JsonObject o) {
        if (o.has("polys") && o.get("polys").isJsonArray()) {
            JsonArray rings = o.getAsJsonArray("polys");
            int[][][] out = new int[rings.size()][][];
            for (int i = 0; i < rings.size(); i++) {
                out[i] = parseRing(rings.get(i).getAsJsonArray());
            }
            return out;
        }
        if (o.has("poly") && o.get("poly").isJsonArray()) {
            return new int[][][]{parseRing(o.getAsJsonArray("poly"))};
        }
        return new int[0][][];
    }

    private static int[][] parseRing(JsonArray arr) {
        int[][] ring = new int[arr.size()][2];
        for (int i = 0; i < arr.size(); i++) {
            JsonArray pt = arr.get(i).getAsJsonArray();
            ring[i][0] = pt.get(0).getAsInt();
            ring[i][1] = pt.get(1).getAsInt();
        }
        return ring;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }
}
