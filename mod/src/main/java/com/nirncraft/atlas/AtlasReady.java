package com.nirncraft.atlas;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import net.minecraft.client.Minecraft;
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
        JsonObject info = null;
        if (core != null) {
            try {
                info = JsonParser.parseReader(new JsonReader(new StringReader(core))).getAsJsonObject();
            } catch (RuntimeException e) {
                return "atlas_info unreadable";
            }
        }
        if (info == null) return "atlas_info not read";
        var rd = info.has("ready") && info.get("ready").isJsonObject() ? info.getAsJsonObject("ready") : null;
        if (rd == null) return "atlas_info lacks the ready block (an older library)";
        if (rd.get("pipelinesBuilding").getAsInt() != 0) return "pipelines still building off the render thread";
        var mc = Minecraft.getInstance();
        if (AtlasLoadingScreen.waiting() || mc.gui.screen() instanceof AtlasLoadingScreen) return "loading phase not over";
        if (AtlasClientGen.ON) {
            var p = AtlasClientGen.progress();
            if (p == null || !p.running()) return "generator not running";
            if (!p.nearDone()) return "spawn area " + p.near() + " of " + p.nearTotal() + " chunks";
            if (p.farTotal() > 0 && p.far() < p.farTotal()) return "horizon (coarse bake) " + p.far() + " of " + p.farTotal() + " tiles";
        }
        var v = info.has("voxgrid") && info.get("voxgrid").isJsonObject() ? info.getAsJsonObject("voxgrid") : null;
        if (v == null) return "grid not started";
        if (v.has("settled") && !v.get("settled").getAsBoolean()) return "grid not settled since its last move";
        for (String k : new String[]{"pending", "waiting", "gpuWaiting", "coarseWaiting", "pendingSections"})
            if (v.has(k) && v.get(k).getAsLong() > 0) return "grid " + k + " " + v.get(k).getAsLong();
        if (v.has("levels") && v.get("levels").isJsonArray())
            for (var e : v.getAsJsonArray("levels"))
                if (e.getAsJsonObject().get("pending").getAsLong() > 0)
                    return "level " + e.getAsJsonObject().get("level").getAsInt() + " pending " + e.getAsJsonObject().get("pending").getAsLong();
        return "";
    }

    /** The profiler block: {@code ready{ok}} or {@code ready{pending: what}}. */
    static String line(String core) {
        String p = pending(core);
        return p.isEmpty() ? "ready{ok}" : "ready{pending: " + p.replace('}', ')') + "}";
    }
}
