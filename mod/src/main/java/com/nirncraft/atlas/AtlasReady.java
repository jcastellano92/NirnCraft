package com.nirncraft.atlas;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;

/**
 * Whether atlas is fully ready, and if not, what is pending: the single call the gates and the player hold use.
 *
 * <p>{@link #ready()} is true only when everything is truly loaded: the renderer made and not off; its pipelines built
 * (atlas_info {@code ready.pipelinesBuilding} is 0: no kernel still compiling off the render thread); the loading phase
 * over; the client generator running with the spawn area in and the horizon (the coarse bake) loaded; and the spawn
 * settled in the grid (nothing pending or waiting in any level). {@link #pending()} says which is the first that is not
 * (empty when ready). Call from the client thread, at most a few times a second (it reads atlas_info). When atlas is
 * off for the session ({@link #offForSession()}) it is never ready: a hold must not wait on it.
 *
 * <p>The profiler line carries it as {@code ready{ok}} or {@code ready{pending: ...}}.
 */
public final class AtlasReady {
    private AtlasReady() {}

    /** True when atlas is fully loaded and settled (see the class comment). */
    public static boolean ready() {
        return pending().isEmpty();
    }

    /** The loading screen is armed or open: the player is held (a test run in the client waits, and does not close it). */
    public static boolean loadingScreenHeld() {
        return AtlasLoadingScreen.holding();
    }

    /** A string that changes while the loading phase progresses ("" when it is not held): the harness fails a step whose progress stops. */
    public static String loadingProgress() {
        if (!AtlasLoadingScreen.holding()) return "";
        var p = AtlasClientGen.progress();
        var b = AtlasCoarseBake.progress();
        var pp = AtlasClient.building();  // (the kernels compiling: minutes on a cold driver cache)
        return "|load" + (pp == null ? "" : "p" + pp.done()) + (p == null ? "" : p.near() + "/" + p.generated() + "/" + p.cached()) + (b == null ? "" : "/" + b.done() + "/" + b.servePending());
    }

    /** The generator's submissions by phase since atlas became ready (the route's own; AtlasLoadStats reset the count), or "". */
    public static String genSubs() {
        String s = AtlasClientGen.subStats(false);
        return s == null ? "" : s;
    }

    /** Atlas gave up for this session (a lost device, a crash guard): there is nothing to wait for. */
    public static boolean offForSession() {
        return AtlasClient.failedForSession();
    }

    /** The first thing still pending, or "" when atlas is ready. */
    public static String pending() {
        return pending(AtlasClient.coreInfoJson());
    }

    /** As {@link #pending()}, on atlas_info's JSON already read (null: not read). */
    static String pending(String core) {
        if (AtlasClient.failedForSession()) return "atlas is off for the session";
        if (AtlasClient.buildingContext()) {  // (the context's kernels compiling on its thread: no atlas_info yet)
            AtlasNative.PipelineProgress pp = AtlasClient.building();
            return pp == null ? "pipelines still building off the render thread"
                    : "pipelines still building off the render thread (" + pp.done() + " / " + pp.total() + ", " + pp.name() + ")";
        }
        if (!AtlasClient.started()) return "renderer not started";
        if (!AtlasClient.outputReady()) return "renderer first output not delivered";
        JsonObject info = null;
        if (core != null) {
            try {
                info = parsed(core);
            } catch (RuntimeException e) {
                return "atlas_info unreadable";
            }
        }
        if (info == null) return "atlas_info not read";
        var rd = info.has("ready") && info.get("ready").isJsonObject() ? info.getAsJsonObject("ready") : null;
        if (rd == null) return "atlas_info lacks the ready block (an older library)";
        if (rd.get("pipelinesBuilding").getAsInt() != 0) return "pipelines still building off the render thread";
        // (the loading screen itself is not a condition: under the player hold it stays up until the server releases the
        // player, which happens because this is ready; its work is the generator and grid conditions below)
        if (AtlasLoadingScreen.waiting()) return "loading phase not over";
        if (AtlasClientGen.ON) {
            var p = AtlasClientGen.progress();
            if (p == null || !p.running()) return "generator not running";
            if (!p.nearDone()) return "spawn area " + p.near() + " of " + p.nearTotal() + " chunks";
            var bk = AtlasCoarseBake.progress();  // (levels 3-6: the whole-map bake's own count, made or read; farStep makes the finer levels')
            if (bk != null && bk.phase() > 0 && !bk.loaded()) return "horizon (coarse bake) " + bk.done() + " of " + bk.total() + " tiles";
            if (p.farTotal() > 0 && p.far() < p.farTotal()) return "horizon (finer levels) " + p.far() + " of " + p.farTotal() + " tiles";
        }
        var v = info.has("voxgrid") && info.get("voxgrid").isJsonObject() ? info.getAsJsonObject("voxgrid") : null;
        if (v == null) {  // (the level-0 grid exists in the unified / client-generated modes; the plain far field has none to wait for)
            if (AtlasNearPass.unified() || AtlasClientGen.ON) return "grid not started";
            return "";
        }
        return gridPending(v);
    }

    // (the info thread hands out one string a tenth of a second: parsed once each, not at every ask of every caller)
    private static String parsedFrom;
    private static JsonObject parsedObj;

    private static synchronized JsonObject parsed(String core) {
        if (core != parsedFrom) {
            parsedObj = JsonParser.parseReader(new JsonReader(new StringReader(core))).getAsJsonObject();
            parsedFrom = core;
        }
        return parsedObj;
    }

    /** The grid's part of {@link #pending}: atlas_info's voxgrid block; "" when nothing there holds readiness. */
    static String gridPending(JsonObject v) {
        if (v.has("settled") && !v.get("settled").getAsBoolean()) return "grid not settled since its last move";
        // The grid's queues. Level 0's waiting sections (waiting: the host's, gpuWaiting: the generator's) count only when
        // level 0 would take one now: the nearest of them within its reach and its pool's cut-off. The others are past the
        // reach (where a pixel covers more than level 0's voxels: 152 blocks from a camera held at y 522, the whole ground)
        // or past a full pool's cut-off; the coarse levels draw them, and they wait for the camera to come near, never for
        // time: readiness waited on "gpuWaiting 10280" for good while the outer rings' sections came in.
        long nm = v.has("nearestMissing") ? v.get("nearestMissing").getAsLong() : -1;
        boolean l0Takes = levelZeroTakes(v);
        for (String k : new String[]{"pending", "waiting", "gpuWaiting", "coarseWaiting", "pendingSections"}) {
            long n = v.has(k) ? v.get(k).getAsLong() : 0;
            if (n <= 0 || (!l0Takes && (k.equals("waiting") || k.equals("gpuWaiting")))) continue;
            String what = "grid " + k + " " + n + (k.endsWith("aiting") && nm > 0 ? " (the nearest " + nm + " blocks away)" : "");
            if (waitOn(k, n, what)) return what;  // (released: it did not drain for 30 s; logged once why, readiness goes on)
        }
        if (v.has("levels") && v.get("levels").isJsonArray())
            for (var e : v.getAsJsonArray("levels")) {
                long n = e.getAsJsonObject().get("pending").getAsLong();
                String what = "level " + e.getAsJsonObject().get("level").getAsInt() + " pending " + n;
                if (n > 0 && waitOn("level " + e.getAsJsonObject().get("level").getAsInt(), n, what)) return what;
            }
        return "";
    }

    /**
     * Whether level 0 would take one of its waiting sections (waiting, gpuWaiting) now, from atlas_info's voxgrid block or a
     * sample with its fields (nearestMissing, cutoff): the nearest of them is within its reach (nearestMissing 0: none is)
     * and its pool's cut-off. Without nearestMissing (an older library): true, as before.
     */
    public static boolean levelZeroTakes(JsonObject v) {
        if (v == null || !v.has("nearestMissing")) return true;
        double nm = v.get("nearestMissing").getAsDouble(), cut = v.has("cutoff") ? v.get("cutoff").getAsDouble() : Double.MAX_VALUE;
        return nm > 0 && nm <= cut;
    }

    // (each grid queue's drain guard: a queue that does not fall for 30 s while readiness waits on it no longer holds it)
    private static final java.util.Map<String, AtlasWaitGuard> GUARDS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Whether readiness still waits on grid queue {@code k} at {@code n} (false once its guard released it: logged once). */
    private static boolean waitOn(String k, long n, String what) {
        int r = GUARDS.computeIfAbsent(k, x -> new AtlasWaitGuard()).blocks(n, System.nanoTime());
        if (r < 0) com.nirncraft.NirnCraft.LOGGER.warn("atlas readiness: no longer waits on {}: it did not drain for {} s; {}",
                what, AtlasWaitGuard.STALL_NS / 1_000_000_000L, gridLine());
        return r > 0;
    }

    /** The grid's residency in a line (the watchdog's and the stall guard's log): what level 0 holds, what waits and why. */
    static String gridLine() {
        String core = AtlasClient.coreInfoJson();
        if (core == null) return "grid: atlas_info not read";
        try {
            JsonObject info = JsonParser.parseReader(new JsonReader(new StringReader(core))).getAsJsonObject();
            if (!info.has("voxgrid") || !info.get("voxgrid").isJsonObject()) return "grid: none";
            JsonObject v = info.getAsJsonObject("voxgrid");
            StringBuilder b = new StringBuilder("grid:");
            for (String k : new String[]{"settled", "resident", "slotsTotal", "pending", "waiting", "gpuWaiting", "coarseWaiting", "pendingSections",
                    "nearestMissing", "cutoff", "reach", "wantedAsked", "wantedHeld", "wantedReasked", "wantedPending", "rankCut",
                    "rankWaitBest", "rankAdmits", "rankEvicts"})
                if (v.has(k)) b.append(' ').append(k).append(' ').append(v.get(k).getAsString());
            long nm = v.has("nearestMissing") ? v.get("nearestMissing").getAsLong() : -1;
            if (nm == 0) b.append(" (level 0's waiting sections are past its reach or a full pool's cut-off: the coarse levels draw them)");
            return b.toString();
        } catch (RuntimeException e) {
            return "grid: atlas_info unreadable";
        }
    }

    /** The profiler block: {@code ready{ok}} or {@code ready{pending: what}}. */
    static String line(String core) {
        String p = pending(core);
        return p.isEmpty() ? "ready{ok}" : "ready{pending: " + p.replace('}', ')') + "}";
    }
}
