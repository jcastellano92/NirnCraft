package com.nirncraft.roads;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.TamrielMap;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Development tool behind {@code /nirncraft-roads export-inns}: walks the planned highways and proposes a roadside inn
 * every ~{@link #SPACING} blocks where the road runs that far without passing a settlement, set a little off the road on
 * dry, gentle ground. The result is a places file (villages of variant {@code inn}, built by the settlement module's
 * tavern structure) to review and ship in {@code nirncraft/tamriel/places/}; once shipped, the inns are nodes of the road
 * graph, so the highways run past their doors.
 */
final class InnSites {
    static final int SPACING = 1000;
    /** No inn within this distance of any settlement. */
    private static final int CLEAR = 700;
    /** Skyrim has its own inns (Nightgate, Old Hroldan, the town inns). */
    private static final int SKYRIM_MIN_X = -3300, SKYRIM_MAX_X = 3000, SKYRIM_MIN_Z = -2600, SKYRIM_MAX_Z = 2000;

    private InnSites() {}

    static int export(RoadsData data, File file) throws IOException {
        JsonArray out = new JsonArray();
        java.util.List<int[]> chosen = new java.util.ArrayList<>();
        MapSample s = new MapSample();
        for (Road road : data.roads.values()) {
            if (road.kind != Road.HIGHWAY || road.length() < SPACING * 1.8f) continue;
            int count = (int) (road.length() / SPACING) - 1;
            if (count < 1) continue;
            for (int k = 1; k <= count; k++) {
                float at = road.length() * k / (count + 1);
                int i = 0;
                while (i < road.size() - 1 && road.cum[i] < at) i++;
                int[] site = siteBeside(road, i, s);
                if (site == null) continue;
                if (site[0] > SKYRIM_MIN_X && site[0] < SKYRIM_MAX_X && site[1] > SKYRIM_MIN_Z && site[1] < SKYRIM_MAX_Z) continue;
                if (Places.nearest(null, site[0], site[1], CLEAR).filter(p -> p.type().isSettlement()).isPresent()) continue;
                boolean crowded = false;
                for (int[] c : chosen) {
                    long dx = c[0] - site[0], dz = c[1] - site[1];
                    if (dx * dx + dz * dz < (long) CLEAR * CLEAR) crowded = true;
                }
                if (crowded) continue;
                chosen.add(site);
                Region region = TamrielMap.region(site[0], site[1]);
                JsonObject o = new JsonObject();
                String id = "roadside_" + region.id().replace('/', '_') + "_" + chosen.size();
                o.addProperty("id", id);
                o.addProperty("type", "village");
                o.addProperty("variant", "inn");
                o.addProperty("culture", region.culture());
                o.addProperty("region", region.id());
                o.addProperty("x", site[0]);
                o.addProperty("z", site[1]);
                o.addProperty("radius", 26);
                out.add(o);
            }
        }
        Files.writeString(file.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(out), StandardCharsets.UTF_8);
        return out.size();
    }

    /** A dry, gentle spot 34 blocks to either side of road point {@code i}, or null. */
    private static int[] siteBeside(Road road, int i, MapSample s) {
        double[] r = RoadLayout.right(road, i);
        for (int side : new int[]{1, -1}) {
            for (int off : new int[]{34, 44, 56}) {
                int x = (int) Math.round(road.xs[i] + r[0] * off * side), z = (int) Math.round(road.zs[i] + r[1] * off * side);
                TamrielMap.sample(x, z, s);
                if (s.region == 0 || s.coastDistance < 40 || s.inWater() || s.riverDistance < s.riverHalfWidth + 30
                        || s.lakeDistance > -30 || s.ridge > 0.35f || s.crag > 0.4f || s.wetland > 0.6f) continue;
                return new int[]{x, z};
            }
        }
        return null;
    }
}
