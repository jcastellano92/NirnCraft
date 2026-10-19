package com.nirncraft.roads;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nirncraft.tamriel.Place;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * {@code /nirncraft-roads export}: the planned network as JSON for {@code tools/qa/reach_lint.py --export <file>}: every
 * road with its simplified polyline, kind and end places, every settlement, and for each planned town its predicted
 * gates and whether a road ends at them. Read-only on the world data.
 */
final class RoadExport {
    private RoadExport() {}

    static int write(RoadsData data, File file) throws IOException {
        JsonObject root = new JsonObject();
        JsonArray roads = new JsonArray();
        for (Road r : data.roads.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id);
            o.addProperty("k", r.kind);
            o.addProperty("a", placeOf(data, r.a));
            o.addProperty("b", placeOf(data, r.b));
            if (r.b < 0) o.addProperty("gate", true);
            if (!r.name.isEmpty()) o.addProperty("n", r.name);
            o.addProperty("len", Math.round(r.length()));
            JsonArray pts = new JsonArray();
            for (int v : RoadsPackets.simplify(r).xz()) pts.add(v);
            o.add("p", pts);
            roads.add(o);
        }
        root.add("roads", roads);
        JsonArray settlements = new JsonArray();
        JsonArray gates = new JsonArray();
        for (Settlement s : data.settlements) {
            JsonObject o = new JsonObject();
            o.addProperty("p", s.placeId);
            o.addProperty("x", s.x);
            o.addProperty("z", s.z);
            o.addProperty("town", s.town);
            o.addProperty("roads", data.roadsAt(s.id).size());
            settlements.add(o);
            Place place = s.place().orElse(null);
            if (place == null || !s.town || !place.type().isSettlement()) continue;
            List<int[]> g = GateEnds.usable(place);
            for (int i = 0; i < g.size(); i++) {
                JsonObject go = new JsonObject();
                go.addProperty("p", s.placeId);
                go.addProperty("i", i);
                go.addProperty("x", g.get(i)[0]);
                go.addProperty("z", g.get(i)[1]);
                go.addProperty("served", WorldPlan.nearestRoadPoint(data, g.get(i)[0], g.get(i)[1], WorldPlan.GATE_SERVED,
                        new int[0], true) != -1);
                gates.add(go);
            }
        }
        root.add("settlements", settlements);
        root.add("gates", gates);
        root.addProperty("pending", data.pending.size());
        root.addProperty("failed", data.failed.size());
        Files.writeString(file.toPath(), new Gson().toJson(root), StandardCharsets.UTF_8);
        return data.roads.size();
    }

    private static String placeOf(RoadsData data, int id) {
        Settlement s = id < 0 ? null : data.settlement(id);
        return s == null ? "" : s.placeId;
    }
}
