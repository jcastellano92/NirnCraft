package com.nirncraft.atlas;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * What a launch's loading cost, measured from the join to {@link AtlasReady#ready()} (the loading gate's numbers, ATLAS-PLAN
 * section 0.9): when each stage finished (renderer, generator, spawn area, horizon, ready), what was pending in between,
 * the near and bake counters every 2.5 s (the near window must progress beside the bake), the client's own frame
 * intervals while it loaded, and the generator's submissions by phase (the longest, how many over 4 / 8 / 16 ms).
 * One INFO line and {@code atlas-load.json} in the game folder, written once when atlas is ready (the harness reads it);
 * the generator's submission counts start again at that moment, so the route that follows is counted apart.
 */
final class AtlasLoadStats {
    private AtlasLoadStats() {}

    private static long joinNs, firstFrameNs, lastFrameNs, rendererNs, generatorNs, spawnNs, horizonNs, readyNs, seriesNs;
    private static boolean done;
    private static int ticks;
    private static String lastKey = "";
    private static final ArrayList<Float> frames = new ArrayList<>();
    private static final JsonArray timeline = new JsonArray(), series = new JsonArray();

    /** (client thread) The player joined a world: the clock starts. */
    static synchronized void joined() {
        joinNs = System.nanoTime();
        firstFrameNs = lastFrameNs = rendererNs = generatorNs = spawnNs = horizonNs = readyNs = seriesNs = 0;
        done = false;
        ticks = 0;
        lastKey = "";
        frames.clear();
        while (timeline.size() > 0) timeline.remove(0);
        while (series.size() > 0) series.remove(0);
    }

    /** (render thread) One atlas frame finished: the interval since the last is one of the loading frames. */
    static synchronized void frame(long ns) {
        if (joinNs == 0 || done) return;
        if (lastFrameNs != 0) frames.add((ns - lastFrameNs) / 1e6f);
        else firstFrameNs = ns;
        lastFrameNs = ns;
    }

    private static double s(long t) {
        return t == 0 ? -1 : (t - joinNs) / 1e9;
    }

    /** (client tick) Twice a second: the stages reached, what is pending, the counters; ready closes the measurement. */
    static void tick(Minecraft mc) {
        if (joinNs == 0 || done || ++ticks % 10 != 0) return;
        long now = System.nanoTime();
        synchronized (AtlasLoadStats.class) {
            if (rendererNs == 0 && AtlasClient.started()) rendererNs = now;
            AtlasClientGen.Progress p = AtlasClientGen.progress();
            if (generatorNs == 0 && p != null && p.running()) generatorNs = now;
            if (spawnNs == 0 && p != null && p.nearDone()) spawnNs = now;
            AtlasCoarseBake.Progress b = AtlasCoarseBake.progress();
            if (horizonNs == 0 && b != null && b.loaded()) horizonNs = now;
            if (now - seriesNs >= 2_500_000_000L && p != null) {
                seriesNs = now;
                var row = new JsonArray();
                row.add(Math.round(s(now) * 10) / 10.0);
                row.add(p.near());
                row.add(p.nearTotal());
                row.add(p.generated());
                row.add(p.cached());
                row.add(b == null ? -1 : b.done());
                row.add(b == null ? -1 : b.total());
                series.add(row);
            }
        }
        String pending = AtlasClient.failedForSession() ? "atlas is off for the session" : AtlasReady.pending();
        String key = pending.replaceAll("[0-9]+", "#");
        if (!key.equals(lastKey)) {
            lastKey = key;
            var e = new JsonArray();
            e.add(Math.round(s(now) * 10) / 10.0);
            e.add(pending);
            synchronized (AtlasLoadStats.class) {
                timeline.add(e);
            }
        }
        if (pending.isEmpty() || AtlasClient.failedForSession()) finish(mc, now, pending.isEmpty());
    }

    private static synchronized void finish(Minecraft mc, long now, boolean ok) {
        done = true;
        readyNs = now;
        float[] f = new float[frames.size()];
        for (int i = 0; i < f.length; i++) f[i] = frames.get(i);
        Arrays.sort(f);
        int over50 = 0, over100 = 0;
        for (float v : f) {
            over50 += v > 50f ? 1 : 0;
            over100 += v > 100f ? 1 : 0;
        }
        var fr = new JsonObject();
        fr.addProperty("n", f.length);
        fr.addProperty("p50", f.length == 0 ? 0 : f[f.length / 2]);
        fr.addProperty("p99", f.length == 0 ? 0 : f[Math.min(f.length - 1, (int) (f.length * 0.99))]);
        fr.addProperty("max", f.length == 0 ? 0 : f[f.length - 1]);
        fr.addProperty("over50", over50);
        fr.addProperty("over100", over100);
        var o = new JsonObject();
        o.addProperty("ready", ok);
        o.addProperty("toReadyS", s(readyNs));
        o.addProperty("toFirstFrameS", s(firstFrameNs));
        o.addProperty("toRendererS", s(rendererNs));
        o.addProperty("toGeneratorS", s(generatorNs));
        o.addProperty("toSpawnAreaS", s(spawnNs));
        o.addProperty("toHorizonS", s(horizonNs));
        o.add("frames", fr);
        o.add("timeline", timeline);
        o.add("series", series);
        String subs = AtlasClientGen.subStats(true);
        o.addProperty("subs", subs == null ? "" : subs);
        String bake = AtlasCoarseBake.line();
        o.addProperty("bake", bake == null ? "" : bake);
        AtlasClientGen.Progress p = AtlasClientGen.progress();
        if (p != null) o.addProperty("gen", p.describe());
        com.nirncraft.NirnCraft.LOGGER.info(String.format(Locale.ROOT,
                "atlas load: %s after %.1f s from the join (renderer %.1f, generator %.1f, spawn area %.1f, horizon %.1f); frames %d p50 %.1f p99 %.1f max %.1f ms, over 50 ms %d; %s; %s",
                ok ? "ready" : "gave up", s(readyNs), s(rendererNs), s(generatorNs), s(spawnNs), s(horizonNs), f.length,
                fr.get("p50").getAsDouble(), fr.get("p99").getAsDouble(), fr.get("max").getAsDouble(), over50, subs, p == null ? "no generator" : p.describe()));
        try {
            Files.writeString(mc.gameDirectory.toPath().resolve("atlas-load.json"), o.toString(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (java.io.IOException e) {
            com.nirncraft.NirnCraft.LOGGER.warn("atlas load receipt failed", e);
        }
    }

    /** Whether the measurement has closed (atlas ready, or off). */
    static boolean finished() {
        return done;
    }
}
