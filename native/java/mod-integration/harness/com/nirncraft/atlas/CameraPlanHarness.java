package com.nirncraft.atlas;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nirncraft.qa.CameraPlanner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Camera planning for the test harness, outside Minecraft: com.nirncraft.qa.CameraPlanner (the same planner the
 * in-game /nirncraft-tour uses) on the exact terrain of a seed, for every target place, so cameras exist for every
 * location and are made again whenever world generation or the seed changes (tools/harness/cameras.py drives this).
 * <pre>  $env:HARNESS_JAVA_OPTS='-Dharness.targets=targets.json'
 *   run_exact_harness.ps1 -Main CameraPlanHarness -Out plan.json -Seed 1234 -Threads 8</pre>
 * targets.json: [{"id", "x", "z", "radius"}]. Output: {id: {"ground", "water", "aerial": [pose], "approach": [pose]}},
 * pose = {"camera": [x, y, z], "look": [x, y, z], "yaw", "pitch", "score", "visible"}, best first.
 */
public final class CameraPlanHarness {
    private CameraPlanHarness() {}

    public static void main(String[] a) {
        try {
            run(a);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    static double r1(double v) { return Math.round(v * 10) / 10.0; }

    static JsonArray poses(java.util.List<CameraPlanner.Shot> shots) {
        JsonArray out = new JsonArray();
        for (CameraPlanner.Shot s : shots) {
            JsonObject o = new JsonObject();
            JsonArray cam = new JsonArray(), look = new JsonArray();
            cam.add(r1(s.x())); cam.add(r1(s.y())); cam.add(r1(s.z()));
            look.add(r1(s.lookX())); look.add(r1(s.lookY())); look.add(r1(s.lookZ()));
            o.add("camera", cam);
            o.add("look", look);
            o.addProperty("yaw", r1(s.yaw()));
            o.addProperty("pitch", r1(s.pitch()));
            o.addProperty("score", Math.round(s.score() * 1000) / 1000.0);
            o.addProperty("visible", Math.round(s.visible() * 100) / 100.0);
            out.add(o);
        }
        return out;
    }

    static void run(String[] a) throws Exception {
        Path outPath = Path.of(a[2]);
        long seed = Long.parseLong(a[3]);
        int threads = Integer.parseInt(a[7]);
        Path targets = Path.of(System.getProperty("harness.targets", "targets.json"));
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        AtlasGround world = new AtlasGround(HarnessSeed.height(seed));
        JsonArray list = JsonParser.parseString(Files.readString(targets, StandardCharsets.UTF_8)).getAsJsonArray();
        JsonObject[] results = new JsonObject[list.size()];
        long t0 = System.nanoTime();
        new ForkJoinPool(threads).submit(() -> IntStream.range(0, list.size()).parallel().forEach(i -> {
            JsonObject t = list.get(i).getAsJsonObject();
            double x = t.get("x").getAsDouble(), z = t.get("z").getAsDouble();
            double radius = t.has("radius") ? t.get("radius").getAsDouble() : 40;
            CameraPlanner.Cached g = new CameraPlanner.Cached(world);
            JsonObject r = new JsonObject();
            r.addProperty("ground", r1(g.terrain((int) x, (int) z)));
            r.addProperty("water", g.surface((int) x, (int) z) > g.terrain((int) x, (int) z) + 0.5);
            r.add("aerial", poses(CameraPlanner.plan(g, x, z, radius, CameraPlanner.Kind.AERIAL, 4)));
            r.add("approach", poses(CameraPlanner.plan(g, x, z, radius, CameraPlanner.Kind.APPROACH, 4)));
            results[i] = r;
        })).get();
        JsonObject out = new JsonObject();
        out.addProperty("_seed", seed);
        for (int i = 0; i < results.length; i++) out.add(list.get(i).getAsJsonObject().get("id").getAsString(), results[i]);
        Files.writeString(outPath, new GsonBuilder().disableHtmlEscaping().create().toJson(out), StandardCharsets.UTF_8);
        System.out.printf("camera plan: %d places in %.1f s (seed %d) -> %s%n", results.length, (System.nanoTime() - t0) / 1e9,
                seed, outPath);
    }
}
