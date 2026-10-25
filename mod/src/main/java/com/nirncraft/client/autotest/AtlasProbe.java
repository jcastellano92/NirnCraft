package com.nirncraft.client.autotest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One reading of Infinite ATLAS's own counters as flat numbers (the route's per-second record and the atlas settle):
 * the profiler line (AtlasClient.debugInfo: frame, clientGen with its pace and cache lines) and the core's atlas_info
 * JSON (voxgrid coverage, tiers). Parsed from the strings the profiler already writes, so no counter is added to the
 * renderer's files. Safe to call off the render thread.
 *
 * <p>A counter the reading lacks is never a number: {@link #REQUIRED} names the ones a gate cannot do without and
 * {@code "missing"} lists those a reading did not have (the route fails on them: "metric X absent"). The check that the
 * parser still reads the profiler's current format is AtlasProbeTest, on a captured profile line.
 */
final class AtlasProbe {
    private AtlasProbe() {}

    private static final Pattern FRAME = Pattern.compile(
            "frame\\{atlasMs=([\\d.,]+) glRenderWallMs=([\\d.,]+) frameWallMs=([\\d.,]+) fps=(\\d+) unified=(\\w+) loadedChunks=(-?\\d+)");
    private static final Pattern GEN = Pattern.compile("clientGen\\{.*? chunks=(\\d+) flagged=(\\d+) ready=(\\d+) up=(\\d+)/(\\d+) sections");
    private static final Pattern FAR = Pattern.compile(
            "far (\\d+) tiles (\\d+)/(\\d+) chunks taken ([\\d.,]+) ms busy (\\d+), partial (\\d+) \\(retried (\\d+)\\), from the cache (\\d+)");
    private static final Pattern CACHED = Pattern.compile("cached chunks (\\d+) \\((\\d+) sections\\)");
    // what the generator made against what the cache served (AtlasClientGen.state): the relaunch test's numbers
    private static final Pattern MADE = Pattern.compile("generated chunks (\\d+) \\(cache served (\\d+)\\)");
    // the pace line (AtlasGenNative.paceLine): frame period (gpu), duty, tokens used / waited (far), the longest
    // submission and the recent one with their phases, submissions
    private static final Pattern BUDGET = Pattern.compile(
            "budget\\{frame ([\\d.,]+) ms \\(gpu ([\\d.,]+)\\) duty ([\\d.,]+) used (\\d+) waited (\\d+) \\(far (\\d+)\\) longest (\\d+) ms \\(([^)]*)\\), "
                    + "lately (\\d+) ms \\(([^)]*)\\), (\\d+) submissions\\}");
    // (the older line: no gpu share, no phases)
    private static final Pattern BUDGET_OLD = Pattern.compile(
            "budget\\{frame ([\\d.,]+) ms duty ([\\d.,]+) used (\\d+) waited (\\d+) \\(far (\\d+)\\) longest (\\d+) ms, (\\d+) submissions\\}");
    // the cache line (AtlasGenNative.cacheLine); "genCache{not in this library}" when the library has no cache peek
    private static final Pattern CACHE = Pattern.compile(
            "genCache\\{(on|OFF) fp ([0-9a-f]+) on disk (\\d+) chunks (\\d+) sections ([\\d.,]+) MB; queued writes (\\d+); written (\\d+) chunks (\\d+) sections (\\d+) far tiles; "
                    + "chunk hits (\\d+) misses (\\d+) \\((\\d+) incomplete\\), section reads (\\d+), far tile hits (\\d+), hash errors (\\d+)\\}");
    // AtlasReady.line: "ready{ok}" or "ready{pending: what}"
    private static final Pattern READY = Pattern.compile("ready\\{(ok|pending: ([^}]*))\\}");
    private static final Pattern VERIFY = Pattern.compile("verify\\{chunks (\\d+) differed (\\d+), tiles (\\d+) differed (\\d+)\\}");
    private static final Pattern JUMP = Pattern.compile("jump\\{firstUp=(-?[\\d.,]+) windowUp=(-?[\\d.,]+) features=(-?[\\d.,]+)");

    /** The counters a gate cannot do without. */
    static final String[] REQUIRED = {"atlasMs", "loadedChunks", "genChunks", "farTiles", "farCached", "budgetFrameMs", "holes", "partial",
            "farReach", "stageCpuMs", "coldHits", "coldWrites", "ready"};

    /** The required counters this reading lacks. */
    static java.util.List<String> missing(JsonObject s) {
        var out = new java.util.ArrayList<String>();
        for (String k : REQUIRED) if (s == null || !s.has(k)) out.add(k);
        return out;
    }

    static JsonObject sample() {
        long t0 = System.nanoTime();
        JsonObject o = parse(com.nirncraft.atlas.AtlasClient.debugInfo());
        o.addProperty("infoMs", (System.nanoTime() - t0) / 1e6);
        return o;
    }

    /** One reading from a profiler string (AtlasClient.debugInfo, or a profile line: the log's prefix is ignored). */
    static JsonObject parse(String info) {
        JsonObject o = new JsonObject();
        num(o, FRAME, info, "atlasMs", "glRenderWallMs", "frameWallMs", "fps", null, "loadedChunks");
        o.addProperty("unified", info.contains("unified=true"));
        num(o, GEN, info, "genChunksRaw", "genFlagged", "genReady", "genUp", "genUpOf");
        num(o, FAR, info, "farTiles", "farTaken", "farChunks", "farMs", "farBusy", "farPartial", "farRetried", "farCached");
        num(o, CACHED, info, "cachedChunks", "cachedSections");
        if (BUDGET.matcher(info).find()) {
            num(o, BUDGET, info, "budgetFrameMs", "budgetGpuMs", "budgetDuty", "budgetUsed", "budgetWaited", "budgetFar", "budgetLongestMs", null,
                    "budgetLatelyMs", null, "budgetSubmissions");
            Matcher m = BUDGET.matcher(info);
            if (m.find()) {
                o.addProperty("budgetLongestPhase", m.group(8));
                o.addProperty("budgetLatelyPhase", m.group(10));
            }
        } else {
            num(o, BUDGET_OLD, info, "budgetFrameMs", "budgetDuty", "budgetUsed", "budgetWaited", "budgetFar", "budgetLongestMs", "budgetSubmissions");
        }
        num(o, MADE, info, "genChunks", "genCacheServed");
        if (!o.has("genChunks") && o.has("genChunksRaw")) o.add("genChunks", o.get("genChunksRaw"));  // (an older line: chunks=)
        if (CACHE.matcher(info).find()) {
            num(o, CACHE, info, null, null, "gcChunks", "gcSections", "gcMb", "gcQueued", "gcChunkWrites", "gcSectionWrites", "gcFarTileWrites",
                    "gcChunkHits", "gcChunkMisses", "gcChunkIncomplete", "gcSectionReads", "gcFarTileHits", "gcHashErrors");
            o.addProperty("gcOn", info.contains("genCache{on "));
        } else {
            o.addProperty("gcAbsent", true);  // (no cache peek in this library: the tiers' numbers stand)
        }
        Matcher rm = READY.matcher(info);
        if (rm.find()) {
            o.addProperty("ready", rm.group(1).equals("ok"));
            if (rm.group(2) != null) o.addProperty("readyPending", rm.group(2));
        }
        num(o, VERIFY, info, "verifyChunks", "verifyChunksDiffered", "verifyTiles", "verifyTilesDiffered");  // (the idle verifier of the caches' key)
        num(o, JUMP, info, "jumpFirstUpMs", "jumpWindowUpMs", "jumpFeaturesMs");
        int at = info.indexOf(" core=");
        if (at >= 0) {
            try {
                // (the profiler appends other blocks after the core's JSON, e.g. recorder{...}: read the one object)
                var reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(info.substring(at + 6)));
                core(o, JsonParser.parseReader(reader).getAsJsonObject());
            } catch (RuntimeException e) {
                o.addProperty("coreError", e.toString());
            }
        }
        var gone = missing(o);
        if (!gone.isEmpty()) {
            var a = new JsonArray();
            gone.forEach(a::add);
            o.add("missing", a);
        }
        return o;
    }

    private static void num(JsonObject o, Pattern p, String info, String... names) {
        Matcher m = p.matcher(info);
        if (!m.find()) return;
        for (int i = 0; i < names.length; i++)
            if (names[i] != null && i < m.groupCount()) o.addProperty(names[i], Double.parseDouble(m.group(i + 1).replace(',', '.')));
    }

    private static void core(JsonObject o, JsonObject core) {
        JsonObject v = obj(core, "voxgrid");
        if (v == null) return;
        copy(o, v, "pending", "waiting", "gpuWaiting", "coarseWaiting", "pendingSections", "built", "batches", "lastGpuMs",
                "stageCpuMs", "stageCpuMaxMs", "framesSinceMove", "resident", "uniform", "nearestMissing", "reach");
        JsonObject rd = obj(core, "ready");
        if (rd != null) put(o, "pipelinesBuilding", rd, "pipelinesBuilding");
        if (v.has("settled")) o.addProperty("gridSettled", v.get("settled").getAsBoolean());
        JsonObject cov = obj(v, "coverage");
        if (cov != null) {
            for (String k : new String[]{"holes", "partial", "columns", "slotsUsed", "slotCap", "farReach", "l0Near6", "nearHoles", "nearReach"}) if (cov.has(k)) o.add(k, cov.get(k));
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
        if (gc != null && !o.has("gcChunkHits")) {  // (the core's own report, when the Java line has none)
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
            o.remove("gcAbsent");
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

    private static void copy(JsonObject to, JsonObject from, String... keys) {
        for (String k : keys) if (from.has(k)) to.add(k, from.get(k));
    }

    /** Sum of an int-array field ("holes"), 0 when absent (callers check {@link #missing} first). */
    static long sum(JsonObject s, String field) {
        long n = 0;
        if (s != null && s.has(field) && s.get(field).isJsonArray()) for (JsonElement e : s.getAsJsonArray(field)) n += e.getAsLong();
        return n;
    }

    static double d(JsonObject s, String field) {
        return s != null && s.has(field) && s.get(field).isJsonPrimitive() ? s.get(field).getAsDouble() : 0;
    }
}
