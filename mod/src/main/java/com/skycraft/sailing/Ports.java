package com.skycraft.sailing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The ports and sea routes of Nirn, loaded once from the class path ({@code skycraft/tamriel/ports.json}) so the
 * server and the client map share them. Thread-safe and usable on both sides.
 *
 * <pre>
 * { "ports":  [ {"id", "kind": "port"|"landing", "place", "style", "ferry", "x", "z", "land", "approach", "dock": [x, z]} ],
 *   "routes": [ {"a", "b", "ship", "expedition", "fare"} ] }
 * </pre>
 * Names are lang keys {@code sailing.skycraft.port.<id>} and {@code sailing.skycraft.ship.<ship>}.
 */
public final class Ports {
    private static final String PATH = "/skycraft/tamriel/ports.json";

    private static final Map<String, Port> BY_ID;
    private static final List<Port> ALL;
    private static final List<Route> ROUTES;

    static {
        Map<String, Port> byId = new LinkedHashMap<>();
        List<Route> routes = new ArrayList<>();
        JsonElement root = read();
        if (root != null && root.isJsonObject()) {
            JsonObject o = root.getAsJsonObject();
            for (JsonElement e : o.has("ports") ? o.getAsJsonArray("ports") : new JsonArray()) {
                try {
                    Port p = parsePort(e.getAsJsonObject());
                    if (byId.putIfAbsent(p.id(), p) != null) Skycraft.LOGGER.warn("Duplicate port id {}", p.id());
                } catch (RuntimeException ex) {
                    Skycraft.LOGGER.warn("Bad port entry: {}", e, ex);
                }
            }
            for (JsonElement e : o.has("routes") ? o.getAsJsonArray("routes") : new JsonArray()) {
                try {
                    JsonObject r = e.getAsJsonObject();
                    String a = r.get("a").getAsString(), b = r.get("b").getAsString();
                    if (!byId.containsKey(a) || !byId.containsKey(b) || a.equals(b)) {
                        Skycraft.LOGGER.warn("Sea route {} - {} names an unknown port", a, b);
                        continue;
                    }
                    Component ship = r.has("ship") ? Component.translatable("sailing.skycraft.ship." + r.get("ship").getAsString()) : null;
                    routes.add(new Route(a, b, ship, r.has("expedition") && r.get("expedition").getAsBoolean(),
                            r.has("fare") ? r.get("fare").getAsInt() : -1));
                } catch (RuntimeException ex) {
                    Skycraft.LOGGER.warn("Bad sea route entry: {}", e, ex);
                }
            }
        } else {
            Skycraft.LOGGER.warn("Ports file {} is missing", PATH);
        }
        BY_ID = Collections.unmodifiableMap(byId);
        ALL = List.copyOf(byId.values());
        ROUTES = List.copyOf(routes);
        Skycraft.LOGGER.info("Loaded {} ports and {} sea routes", ALL.size(), ROUTES.size());
    }

    private Ports() {}

    public static List<Port> all() {
        return ALL;
    }

    public static Optional<Port> byId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    public static List<Route> routes() {
        return ROUTES;
    }

    /** Routes leaving {@code port}. */
    public static List<Route> routesFrom(String port) {
        List<Route> out = new ArrayList<>();
        for (Route r : ROUTES) if (r.touches(port)) out.add(r);
        return out;
    }

    private static Port parsePort(JsonObject o) {
        String id = o.get("id").getAsString();
        int[] dock = null;
        if (o.has("dock")) {
            JsonArray d = o.getAsJsonArray("dock");
            dock = new int[]{d.get(0).getAsInt(), d.get(1).getAsInt()};
        }
        return new Port(id, Component.translatable("sailing.skycraft.port." + id),
                str(o, "kind", "port"), str(o, "place", ""), str(o, "style", "nord"),
                ShipType.byId(str(o, "ferry", "cog")),
                o.get("x").getAsInt(), o.get("z").getAsInt(),
                str(o, "land", ""), str(o, "approach", ""), dock);
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) ? o.get(key).getAsString() : fallback;
    }

    private static JsonElement read() {
        try (InputStream in = Ports.class.getResourceAsStream(PATH)) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Could not read {}", PATH, e);
            return null;
        }
    }
}
