package com.nirncraft.client.autotest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One reading of Infinite ATLAS's own counters as flat numbers (the route's per-second record and the atlas settle):
 * the profiler line (AtlasClient.debugInfo: frame, clientGen) and the core's atlas_info JSON (voxgrid coverage, tiers,
 * genCache). Parsed from the strings the profiler already writes, so no counter is added to the renderer's files. Safe
 * to call off the render thread.
 */
final class AtlasProbe {
    private AtlasProbe() {}

    private static final Pattern FRAME = Pattern.compile(
            "frame\\{atlasMs=([\\d.]+) glRenderWallMs=([\\d.]+) frameWallMs=([\\d.]+) fps=(\\d+) unified=(\\w+) loadedChunks=(-?\\d+)");
    private static final Pattern GEN = Pattern.compile("clientGen\\{.*? chunks=(\\d+) flagged=(\\d+) ready=(\\d+) up=(\\d+)/(\\d+) sections");
    private static final Pattern FAR = Pattern.compile(
            "far (\\d+) tiles (\\d+)/(\\d+) chunks taken ([\\d.]+) ms busy (\\d+), partial (\\d+) \\(retried (\\d+)\\), from the cache (\\d+)");
    private static final Pattern CACHED = Pattern.compile("cached chunks (\\d+) \\((\\d+) sections\\)");
    private static final Pattern BUDGET = Pattern.compile(
            "budget\\{frame ([\\d.]+) ms duty ([\\d.]+) used (\\d+) waited (\\d+) \\(far (\\d+)\\) longest (\\d+) ms, (\\d+) submissions\\}");
    private static final Pattern JUMP = Pattern.compile("jump\\{firstUp=(-?[\\d.]+) windowUp=(-?[\\d.]+) features=(-?[\\d.]+)");

    static JsonObject sample() {
        long t0 = System.nanoTime();
        String info = com.nirncraft.atlas.AtlasClient.debugInfo();
        JsonObject o = new JsonObject();
        o.addProperty("infoMs", 0);
        num(o, FRAME, info, "atlasMs", "glRenderWallMs", "frameWallMs", "fps", null, "loadedChunks");
        o.addProperty("unified", info.contains("unified=true"));
        num(o, GEN, info, "genChunks", "genFlagged", "genReady", "genUp", "genUpOf");
        num(o, FAR, info, "farTiles", "farTaken", "farChunks", "farMs", "farBusy", "farPartial", "farRetried", "farCached");
        num(o, CACHED, info, "cachedChunks", "cachedSections");
        num(o, BUDGET, info, "budgetFrameMs", "budgetDuty", "budgetUsed", "budgetWaited", "budgetFar", "budgetLongestMs", "budgetSubmissions");
        num(o, JUMP, info, "jumpFirstUpMs", "jumpWindowUpMs", "jumpFeaturesMs");
        int at = info.indexOf(" core=");
        if (at >= 0) {
            try {
                JsonObject core = JsonParser.parseString(info.substring(at + 6)).getAsJsonObject();
                core(o, core);
            } catch (RuntimeException e) {
                o.addProperty("coreError", e.toString());
            }
        }
        o.addProperty("infoMs", (System.nanoTime() - t0) / 1e6);
        return o;
    }

    private static void num(JsonObject o, Pattern p, String info, String... names) {
        Matcher m = p.matcher(info);
        if (!m.find()) return;
        for (int i = 0; i < names.length; i++) if (names[i] != null && i < m.groupCount()) o.addProperty(names[i], Double.parseDouble(m.group(i + 1)));
    }

    private static void core(JsonObject o, JsonObject core) {
        JsonObject v = obj(core, "voxgrid");
        if (v == null) return;
        copy(o, v, "pending", "pending", "waiting", "gpuWaiting", "coarseWaiting", "pendingSections", "built", "batches", "lastGpuMs",
                "stageCpuMs", "stageCpuMaxMs", "framesSinceMove", "resident", "uniform", "nearestMissing", "reach");
        if (v.has("settled")) o.addProperty("gridSettled", v.get("settled").getAsBoolean());
        JsonObject cov = obj(v, "coverage");
        if (cov != null) {
            for (String k : new String[]{"holes", "partial", "columns", "slotsUsed", "slotCap"}) if (cov.has(k)) o.add(k, cov.get(k));
            if (cov.has("farReach")) o.add("farReach", cov.get("farReach"));
        }
        JsonObject tiers = obj(v, "tiers");
        if (tiers != null) {
            JsonObject warm = obj(tiers, "warm"), cold = obj(tiers, "cold");
            if (warm != null) {
                put(o, "warmHits", warm, "hits");
                put(o, "warmMisses", warm, "misses");
                put(o, "warmSections", warm, "sections");
            }
            if (cold != null) {
                put(o, "coldHits", cold, "hits");
                put(o, "coldWrites", cold, "writes");
                put(o, "coldRecords", cold, "records");
            }
        }
        JsonObject gc = obj(v, "genCache");
        if (gc != null) {
            put(o, "gcChunks", gc, "chunks");
            put(o, "gcChunkHits", gc, "chunkHits");
            put(o, "gcChunkMisses", gc, "chunkMisses");
            put(o, "gcSectionReads", gc, "sectionReads");
            put(o, "gcSectionWrites", gc, "sectionWrites");
            put(o, "gcFarTileWrites", gc, "farTileWrites");
            put(o, "gcFarTileHits", gc, "farTileHits");
            put(o, "gcQueued", gc, "queuedWrites");
            put(o, "gcHashErrors", gc, "hashErrors");
            if (gc.has("on")) o.addProperty("gcOn", gc.get("on").getAsBoolean());
        }
        JsonArray lv = v.has("levels") && v.get("levels").isJsonArray() ? v.getAsJsonArray("levels") : null;
        if (lv != null) {
            long ing = 0, pend = 0;
            for (JsonElement e : lv) {
                JsonObject l = e.getAsJsonObject();
                ing += l.has("ingested") ? l.get("ingested").getAsLong() : 0;
                pend += l.has("pending") ? l.get("pending").getAsLong() : 0;
            }
            o.addProperty("coarseIngested", ing);
            o.addProperty("coarsePending", pend);
        }
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }

    private static void put(JsonObject to, String name, JsonObject from, String key) {
        if (from.has(key)) to.add(name, from.get(key));
    }

    private static void copy(JsonObject to, JsonObject from, String ignored, String... keys) {
        for (String k : keys) if (from.has(k)) to.add(k, from.get(k));
    }

    /** Sum of an int-array field ("holes"), 0 when absent. */
    static long sum(JsonObject s, String field) {
        long n = 0;
        if (s != null && s.has(field) && s.get(field).isJsonArray()) for (JsonElement e : s.getAsJsonArray(field)) n += e.getAsLong();
        return n;
    }

    static double d(JsonObject s, String field) {
        return s != null && s.has(field) && s.get(field).isJsonPrimitive() ? s.get(field).getAsDouble() : 0;
    }
}
