package com.nirncraft.atlas;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nirncraft.landmarks.LandmarkSite;
import com.nirncraft.landmarks.Landmarks;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The offline side of the harness's tool validation: for each request (a square around a point, like
 * /nirncraft-qa sample) the exact terrain and surface heights the offline tools believe (AtlasGround: the seeded
 * TamrielHeight + map water) and the seeded landmarks (Landmarks.inRect). tools/harness/validation.py compares them
 * with what the real world generated.
 * <pre>  $env:HARNESS_JAVA_OPTS='-Dharness.requests=requests.json'
 *   run_exact_harness.ps1 -Main HeightSampleHarness -Out out.json -Seed 1234 -Threads 4</pre>
 * requests.json: [{"id", "x", "z", "radius", "step"}]; out: {id: {"points": [{x, z, terrain, surface}],
 * "landmarks": [{kind, x, z}]}}.
 */
public final class HeightSampleHarness {
    private HeightSampleHarness() {}

    public static void main(String[] a) {
        try {
            run(a);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    static void run(String[] a) throws Exception {
        Path outPath = Path.of(a[2]);
        long seed = Long.parseLong(a[3]);
        Path requests = Path.of(System.getProperty("harness.requests", "requests.json"));
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        AtlasGround g = new AtlasGround(HarnessSeed.height(seed));
        JsonObject out = new JsonObject();
        out.addProperty("_seed", seed);
        for (var e : JsonParser.parseString(Files.readString(requests, StandardCharsets.UTF_8)).getAsJsonArray()) {
            JsonObject r = e.getAsJsonObject();
            int x = r.get("x").getAsInt(), z = r.get("z").getAsInt(), rad = r.get("radius").getAsInt(), step = r.get("step").getAsInt();
            JsonArray points = new JsonArray();
            for (int px = x - rad; px <= x + rad; px += step) {
                for (int pz = z - rad; pz <= z + rad; pz += step) {
                    JsonObject p = new JsonObject();
                    p.addProperty("x", px);
                    p.addProperty("z", pz);
                    p.addProperty("terrain", Math.round(g.terrain(px, pz) * 100) / 100.0);
                    p.addProperty("surface", Math.round(g.surface(px, pz) * 100) / 100.0);
                    points.add(p);
                }
            }
            JsonArray marks = new JsonArray();
            for (LandmarkSite s : Landmarks.inRect(seed, x - rad, z - rad, x + rad, z + rad)) {
                JsonObject m = new JsonObject();
                m.addProperty("kind", String.valueOf(s.kind()).toLowerCase(java.util.Locale.ROOT));
                m.addProperty("x", s.x());
                m.addProperty("z", s.z());
                marks.add(m);
            }
            JsonObject res = new JsonObject();
            res.add("points", points);
            res.add("landmarks", marks);
            out.add(r.get("id").getAsString(), res);
        }
        Files.writeString(outPath, new GsonBuilder().create().toJson(out), StandardCharsets.UTF_8);
        System.out.println("height samples: " + (out.size() - 1) + " request(s) -> " + outPath);
    }
}
