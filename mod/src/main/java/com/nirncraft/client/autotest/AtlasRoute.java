package com.nirncraft.client.autotest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nirncraft.NirnCraft;
import net.minecraft.client.Minecraft;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The scripted route and the atlas settle (harness: tools/harness/atlas_tools.py).
 *
 * <p>{@code route SPEC.json}: the player walks, sprints, flies and turns along the spec's phases while the client's own
 * frame intervals are kept (render-frame event to render-frame event, uncapped), and Infinite ATLAS's counters are read
 * once per route second on a sampler thread (so the reading itself never lengthens a frame). Ten (or as many as the spec
 * asks) screenshots at fixed route ticks. The report goes to build/autotest-route/&lt;tag&gt;.json. Movement is driven
 * per tick from here; the player is the test spectator, so nothing collides.
 *
 * <p>{@code settle-atlas MAX_TICKS [MIN_TICKS]}: waits on atlas's own state (nothing pending in any level, the generator
 * and the grid's counters still for 4 s), not on Sodium's built chunks, which do not exist when atlas draws everything.
 */
final class AtlasRoute {
    private AtlasRoute() {}

    private static final ExecutorService SAMPLER = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "atlas-route-sampler");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean SAMPLING = new AtomicBoolean();
    private static final Path OUT = Path.of("build", "autotest-route");

    // ---- route state
    private static JsonObject spec;
    private static String tag;
    private static boolean running, settling;
    private static int routeTick, phaseIndex, phaseTick, captureNext;
    private static int[] phaseStart;
    private static double x, y, z, yaw, pitch, groundSmooth, flyY;
    private static double heading;
    private static List<JsonObject> phases = new ArrayList<>();
    private static int[] captureTicks = new int[0];
    private static final ConcurrentHashMap<Integer, JsonObject> samples = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, JsonObject> where = new ConcurrentHashMap<>();
    private static JsonArray captures = new JsonArray();
    private static long began;
    // display restore
    private static boolean savedVsync;
    private static int savedLimit, savedW, savedH;
    private static boolean displayChanged;
    private static JsonObject display = new JsonObject();
    // frames
    private static long lastFrameNs, frameStartNs;
    private static volatile int curSec;
    private static int excludeFrames, startDelay;
    private static final ArrayList<ArrayList<Float>> frames = new ArrayList<>();
    private static final ArrayList<ArrayList<Float>> cpu = new ArrayList<>();
    private static final ArrayList<float[]> worst = new ArrayList<>();  // {ms, routeTick, second}
    private static int excludedTotal;

    // ---- settle state
    private static int settleTicks, settleMax, settleMin, quiet, seenSeq;
    private static volatile int sampleSeq;
    private static volatile JsonObject lastSample;
    private static String prevSig = "";
    private static long settleBegan;
    private static long firstNoHolesMs = -1;
    private static JsonObject settleStart;

    static boolean active() { return running || settling; }

    static String progress() { return running ? "route" + routeTick : settling ? "settle" + settleTicks + "q" + quiet : ""; }

    static void cancel() {
        if (running) restoreDisplay(Minecraft.getInstance());
        running = settling = false;
        spec = null;
    }

    // ---------------------------------------------------------------------------------------------------- settle
    static void beginSettle(Minecraft mc, String args) {
        String[] p = args.trim().split("\\s+");
        settleMax = Math.max(40, Integer.parseInt(p[0]));
        settleMin = p.length > 1 ? Integer.parseInt(p[1]) : 40;
        settling = true;
        settleTicks = quiet = seenSeq = 0;
        sampleSeq = 0;
        lastSample = null;
        prevSig = "";
        firstNoHolesMs = -1;
        settleBegan = System.currentTimeMillis();
        settleStart = null;
    }

    private static void settleTick(Minecraft mc) {
        settleTicks++;
        if (settleTicks % 10 == 0 && SAMPLING.compareAndSet(false, true)) {
            SAMPLER.execute(() -> {
                try {
                    lastSample = AtlasProbe.sample();
                    sampleSeq++;
                } catch (RuntimeException e) {
                    // (a reading that failed is skipped)
                } finally {
                    SAMPLING.set(false);
                }
            });
        }
        if (sampleSeq != seenSeq && lastSample != null) {
            seenSeq = sampleSeq;
            JsonObject s = lastSample;
            if (settleStart == null) settleStart = s;
            boolean busy = AtlasProbe.d(s, "pending") > 0 || AtlasProbe.d(s, "waiting") > 0 || AtlasProbe.d(s, "gpuWaiting") > 0
                    || AtlasProbe.d(s, "coarseWaiting") > 0 || AtlasProbe.d(s, "pendingSections") > 0 || AtlasProbe.d(s, "coarsePending") > 0;
            String sig = (long) AtlasProbe.d(s, "genChunks") + "/" + (long) AtlasProbe.d(s, "farTiles") + "/" + (long) AtlasProbe.d(s, "cachedChunks")
                    + "/" + (long) AtlasProbe.d(s, "built") + "/" + AtlasProbe.sum(s, "holes") + "/" + AtlasProbe.sum(s, "partial")
                    + "/" + AtlasProbe.sum(s, "slotsUsed") + "/" + (long) AtlasProbe.d(s, "coarseIngested") + "/" + (long) AtlasProbe.d(s, "loadedChunks");
            quiet = !busy && sig.equals(prevSig) ? quiet + 1 : 0;
            prevSig = sig;
            if (firstNoHolesMs < 0 && AtlasProbe.sum(s, "holes") == 0 && AtlasProbe.d(s, "columns") >= 0 && s.has("holes"))
                firstNoHolesMs = System.currentTimeMillis() - settleBegan;
            if (quiet >= 8 && settleTicks >= settleMin) {
                finishSettle(true, s, "");
                return;
            }
        }
        if (settleTicks >= settleMax) {
            JsonObject s = lastSample;
            finishSettle(false, s, "pending " + (s == null ? "?" : (long) AtlasProbe.d(s, "pending") + "+" + (long) AtlasProbe.d(s, "waiting")
                    + "+" + (long) AtlasProbe.d(s, "gpuWaiting") + "+" + (long) AtlasProbe.d(s, "coarseWaiting")) + ", holes "
                    + (s == null ? "?" : String.valueOf(s.get("holes"))) + ", gen " + prevSig);
        }
    }

    private static void finishSettle(boolean ok, JsonObject s, String why) {
        settling = false;
        var gone = AtlasProbe.missing(s);
        if (!gone.isEmpty()) {  // (a counter the reading lacks is never a number: the settle says so and is not "settled")
            ok = false;
            why = "metric " + String.join(", ", gone) + " absent; " + why;
        }
        long ms = System.currentTimeMillis() - settleBegan;
        var o = new JsonObject();
        o.addProperty("settled", ok);
        if (!gone.isEmpty()) {
            var a = new JsonArray();
            gone.forEach(a::add);
            o.add("missing", a);
        }
        o.addProperty("ticks", settleTicks);
        o.addProperty("ms", ms);
        o.addProperty("timeToNoHolesMs", firstNoHolesMs);
        if (s != null) o.add("sample", s);
        if (settleStart != null) o.add("firstSample", settleStart);
        try {
            Files.createDirectories(OUT);
            Files.writeString(OUT.resolve("settle.json"), o.toString());
        } catch (java.io.IOException e) {
            NirnCraft.LOGGER.warn("atlas settle receipt failed", e);
        }
        if (ok) NirnCraft.LOGGER.info("Autotest live: atlas settled after {} ticks ({} ms; holes {}, far reach {})", settleTicks, ms,
                s == null ? "?" : s.get("holes"), s == null ? "?" : s.get("farReach"));
        else NirnCraft.LOGGER.warn("Autotest live: atlas settle deadline passed, continuing (partial): {}", why);
    }

    // ---------------------------------------------------------------------------------------------------- route
    static void begin(Minecraft mc, String path) {
        try {
            spec = JsonParser.parseString(Files.readString(Path.of(path.trim()))).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("route spec unreadable: " + e);
        }
        tag = spec.get("tag").getAsString();
        phases.clear();
        for (var e : spec.getAsJsonArray("phases")) phases.add(e.getAsJsonObject());
        phaseStart = new int[phases.size() + 1];
        for (int i = 0; i < phases.size(); i++) phaseStart[i + 1] = phaseStart[i] + phases.get(i).get("ticks").getAsInt();
        var caps = spec.getAsJsonArray("captures");
        captureTicks = new int[caps.size()];
        for (int i = 0; i < captureTicks.length; i++) captureTicks[i] = caps.get(i).getAsInt();
        Arrays.sort(captureTicks);
        x = mc.player.getX();
        y = mc.player.getY();
        z = mc.player.getZ();
        yaw = spec.has("heading") ? spec.get("heading").getAsDouble() : mc.player.getYRot();
        heading = yaw;
        pitch = 0;
        groundSmooth = y;
        flyY = y;
        routeTick = phaseIndex = phaseTick = captureNext = 0;
        samples.clear();
        where.clear();
        captures = new JsonArray();
        frames.clear();
        cpu.clear();
        worst.clear();
        excludedTotal = 0;
        excludeFrames = 0;
        lastFrameNs = 0;
        curSec = 0;
        display = new JsonObject();
        if (spec.has("uncap") && spec.get("uncap").getAsBoolean()) uncap(mc);
        if (spec.has("window")) resize(mc, spec.getAsJsonArray("window").get(0).getAsInt(), spec.getAsJsonArray("window").get(1).getAsInt());
        startDelay = display.has("resizedFrom") ? 80 : 0;  // (the window takes its new size on a later event poll)
        if (startDelay == 0) measureDisplay(mc);
        began = System.currentTimeMillis();
        running = true;
        NirnCraft.LOGGER.info("Autotest live: route {} started ({} ticks, {} captures)", tag, phaseStart[phases.size()], captureTicks.length);
    }

    private static void measureDisplay(Minecraft mc) {
        display.addProperty("framebufferW", mc.getWindow().getWidth());
        display.addProperty("framebufferH", mc.getWindow().getHeight());
        display.addProperty("vsync", mc.options.enableVsync().get());
        display.addProperty("framerateLimit", mc.options.framerateLimit().get());
        display.addProperty("renderDistance", mc.options.renderDistance().get());
    }

    private static void uncap(Minecraft mc) {
        savedVsync = mc.options.enableVsync().get();
        savedLimit = mc.options.framerateLimit().get();
        mc.options.enableVsync().set(false);
        mc.options.framerateLimit().set(260);
        displayChanged = true;
    }

    private static void resize(Minecraft mc, int w, int h) {
        try {
            savedW = mc.getWindow().getWidth();
            savedH = mc.getWindow().getHeight();
            org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize(mc.getWindow().handle(), w, h);
            displayChanged = true;
            display.addProperty("resizedFrom", savedW + "x" + savedH);
        } catch (Throwable t) {
            display.addProperty("resizeError", t.toString());
        }
    }

    private static void restoreDisplay(Minecraft mc) {
        if (!displayChanged) return;
        displayChanged = false;
        try {
            mc.options.enableVsync().set(savedVsync);
            mc.options.framerateLimit().set(savedLimit);
            if (savedW > 0) org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize(mc.getWindow().handle(), savedW, savedH);
        } catch (Throwable t) {
            NirnCraft.LOGGER.warn("route: display not restored", t);
        }
        savedW = 0;
    }

    static void tick(Minecraft mc) {
        if (settling) {
            settleTick(mc);
            return;
        }
        if (!running) return;
        if (startDelay > 0) {
            if (--startDelay == 0) measureDisplay(mc);
            return;
        }
        int total = phaseStart[phases.size()];
        if (routeTick >= total) {
            finish(mc);
            return;
        }
        while (phaseIndex < phases.size() - 1 && routeTick >= phaseStart[phaseIndex + 1]) phaseIndex++;
        JsonObject ph = phases.get(phaseIndex);
        phaseTick = routeTick - phaseStart[phaseIndex];
        int len = ph.get("ticks").getAsInt();
        String mode = ph.get("mode").getAsString();
        double step = ph.get("speed").getAsDouble() / 20.0;
        double dir = Math.toRadians(heading);
        double dx = -Math.sin(dir) * step, dz = Math.cos(dir) * step;
        x += dx;
        z += dz;
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        boolean loaded = mc.level.getChunkSource().hasChunk(bx >> 4, bz >> 4);
        double ground = loaded ? mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, bx, bz) : groundSmooth;
        if (mode.equals("ground")) {
            groundSmooth += Math.max(-1.0, Math.min(0.6, ground - groundSmooth));
            y = groundSmooth;
            flyY = y;
        } else if (mode.equals("air")) {
            double climb = ph.has("climb") ? ph.get("climb").getAsDouble() : 0;
            int up = ph.has("climbTicks") ? ph.get("climbTicks").getAsInt() : 40;
            double target = flyY + climb;
            if (phaseTick == 0) ph.addProperty("_from", y);
            double from = ph.get("_from").getAsDouble();
            y = phaseTick >= up ? target : from + (target - from) * phaseTick / up;
            if (loaded) y = Math.max(y, ground + 12);
            groundSmooth = y;
        }  // "hold": stays
        double wantPitch = ph.has("pitch") ? ph.get("pitch").getAsDouble() : 0;
        pitch += (wantPitch - pitch) * 0.1;
        if (ph.has("yawTurn")) yaw = heading + ph.get("yawTurn").getAsDouble() * (phaseTick + 1.0) / len;
        else yaw = heading;
        var p = mc.player;
        p.setPos(x, y, z);
        p.setYRot((float) yaw);
        p.setXRot((float) pitch);
        p.setYHeadRot((float) yaw);
        p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        curSec = routeTick / 20;
        if (routeTick % 20 == 0) {
            int sec = routeTick / 20;
            var w = new JsonObject();
            w.addProperty("phase", ph.get("name").getAsString());
            w.addProperty("x", x);
            w.addProperty("y", y);
            w.addProperty("z", z);
            w.addProperty("yaw", yaw);
            w.addProperty("groundY", loaded ? ground : -999);
            w.addProperty("loadedAround", loaded);
            where.put(sec, w);
            if (SAMPLING.compareAndSet(false, true)) {
                SAMPLER.execute(() -> {
                    try {
                        samples.put(sec, AtlasProbe.sample());
                    } catch (RuntimeException e) {
                        // (skipped)
                    } finally {
                        SAMPLING.set(false);
                    }
                });
            }
        }
        if (captureNext < captureTicks.length && routeTick >= captureTicks[captureNext]) {
            String name = tag + "_c" + String.format(java.util.Locale.ROOT, "%02d", captureNext + 1);
            var c = new JsonObject();
            c.addProperty("tag", name);
            c.addProperty("tick", routeTick);
            c.addProperty("phase", ph.get("name").getAsString());
            c.addProperty("x", x);
            c.addProperty("y", y);
            c.addProperty("z", z);
            c.addProperty("yaw", yaw);
            c.addProperty("pitch", pitch);
            captures.add(c);
            captureNext++;
            excludeFrames = 10;
            ClientAutotest.travelCapture(name);
        }
        routeTick++;
    }

    private static void finish(Minecraft mc) {
        running = false;
        restoreDisplay(mc);
        try {
            SAMPLER.submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);  // (the last reading in)
        } catch (Exception ignored) {
        }
        var out = new JsonObject();
        out.add("spec", spec);
        out.add("display", display);
        out.addProperty("wallMs", System.currentTimeMillis() - began);
        out.addProperty("ticks", routeTick);
        out.addProperty("excludedFrames", excludedTotal);
        var secs = new JsonArray();
        ArrayList<Float> all = new ArrayList<>();
        for (int s = 0; s < frames.size(); s++) {
            var row = new JsonObject();
            row.addProperty("sec", s);
            var f = frames.get(s);
            all.addAll(f);
            stats(row, "frame", f);
            stats(row, "cpu", s < cpu.size() ? cpu.get(s) : new ArrayList<>());
            var w = where.get(s);
            if (w != null) for (var e : w.entrySet()) row.add(e.getKey(), e.getValue());
            var a = samples.get(s);
            if (a != null) row.add("atlas", a);
            secs.add(row);
        }
        out.add("seconds", secs);
        var absent = new JsonArray();  // the required counters no reading of the whole route had
        for (String k : AtlasProbe.REQUIRED) {
            boolean any = false;
            for (var a : samples.values()) any |= a.has(k);
            if (!any) absent.add(k);
        }
        out.add("absentMetrics", absent);
        var total = new JsonObject();
        stats(total, "frame", all);
        out.add("total", total);
        var byPhase = new JsonObject();
        for (int i = 0; i < phases.size(); i++) {
            ArrayList<Float> pf = new ArrayList<>();
            for (int s = phaseStart[i] / 20; s < Math.min(frames.size(), (phaseStart[i + 1] + 19) / 20); s++) pf.addAll(frames.get(s));
            var row = new JsonObject();
            stats(row, "frame", pf);
            byPhase.add(phases.get(i).get("name").getAsString(), row);
        }
        out.add("phases", byPhase);
        worst.sort((a, b) -> Float.compare(b[0], a[0]));
        var ws = new JsonArray();
        for (int i = 0; i < Math.min(25, worst.size()); i++) {
            var r = new JsonObject();
            r.addProperty("ms", worst.get(i)[0]);
            r.addProperty("tick", (int) worst.get(i)[1]);
            r.addProperty("sec", (int) worst.get(i)[2]);
            ws.add(r);
        }
        out.add("worstFrames", ws);
        out.add("captures", captures);
        try {
            Files.createDirectories(OUT);
            Files.writeString(OUT.resolve(tag + ".json"), out.toString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("route report failed", e);
        }
        NirnCraft.LOGGER.info("Autotest live: route {} done: {}", tag, total.get("frame"));
        spec = null;
    }

    private static void stats(JsonObject to, String name, List<Float> f) {
        var o = new JsonObject();
        o.addProperty("n", f.size());
        if (!f.isEmpty()) {
            float[] a = new float[f.size()];
            double sum = 0;
            int over = 0;
            for (int i = 0; i < a.length; i++) {
                a[i] = f.get(i);
                sum += a[i];
                if (a[i] > 50) over++;
            }
            Arrays.sort(a);
            o.addProperty("mean", sum / a.length);
            o.addProperty("p50", a[(a.length - 1) / 2]);
            o.addProperty("p95", a[(int) Math.ceil(a.length * .95) - 1]);
            o.addProperty("p99", a[(int) Math.ceil(a.length * .99) - 1]);
            o.addProperty("max", a[a.length - 1]);
            o.addProperty("over50", over);
        }
        to.add(name, o);
    }

    // ---------------------------------------------------------------------------------------------------- frames
    static void frameStart() {
        if (running) frameStartNs = System.nanoTime();
    }

    static void frameEnd(Minecraft mc) {
        if (!running || startDelay > 0) {
            lastFrameNs = 0;
            return;
        }
        long now = System.nanoTime();
        if (lastFrameNs != 0) {
            float dt = (now - lastFrameNs) * 1e-6f;
            if (excludeFrames > 0) {
                excludeFrames--;
                excludedTotal++;
            } else {
                int s = Math.max(0, curSec);
                while (frames.size() <= s) {
                    frames.add(new ArrayList<>());
                    cpu.add(new ArrayList<>());
                }
                frames.get(s).add(dt);
                cpu.get(s).add((now - frameStartNs) * 1e-6f);
                worst.add(new float[]{dt, routeTick, s});
                if (worst.size() > 4000) {
                    worst.sort((a, b) -> Float.compare(b[0], a[0]));
                    worst.subList(200, worst.size()).clear();
                }
            }
        }
        lastFrameNs = now;
    }
}
