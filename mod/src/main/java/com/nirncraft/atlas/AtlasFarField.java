package com.nirncraft.atlas;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Streams far-field column tiles around the camera on its own thread and rebuilds atlas's clipmap there, so the
 * render thread only ever flips to finished GPU copies (atlas stages each scene revision in atlas_update).
 */
final class AtlasFarField implements AutoCloseable {
    /** Where far-field columns come from. */
    interface ColumnSource {
        /** The camera's view (position, yaw in Minecraft degrees, horizontal half-angle) for visiblePending(). */
        default void view(double x, double z, double yawDeg, double halfDeg) {
        }

        /** Start of a worker pass over every LOD (visiblePending() then covers that pass). */
        default void beginPass() {
        }

        /** Tiles in the camera's view cone (or right around it) still waiting for exact geometry this pass. */
        default boolean visiblePending() {
            return false;
        }

        /** True when tiles carry block palette ids (AtlasPalette.blocks), false for atlas's preview palette. */
        boolean usesBlockPalette();

        void prepare(AtlasNative.Context ctx) throws IOException;

        /**
         * Makes sure every tile of LOD `lod` covering [x0,x1) x [z0,z1) is uploaded. True when it left work for a
         * later call (tiles still to refine), so the worker comes back soon instead of idling.
         */
        boolean cover(AtlasNative.Context ctx, int lod, int x0, int z0, int x1, int z1);

        /**
         * The instant look at a place the far field has nothing of (a join, a teleport): LOD `lod`'s window from what
         * is ready made and stand-ins, no exact work; cover() refines it afterwards.
         */
        default void coverQuick(AtlasNative.Context ctx, int lod, int x0, int z0, int x1, int z1) {}

        default void close() {}

        /** Puts up again what the server's change feed changed (docs/ATLAS-DELTAS.md). Far-field worker. */
        default void applyEdits(AtlasNative.Context ctx, java.util.List<Long> changedTiles) {}
    }

    /** What goes on top of the ground tiles once they are resident (settlements). */
    interface Overlay {
        /** Tiles covering these LOD-0 tiles are built again on the next pass (the server's real columns arrived). */
        default void invalidate(java.util.List<Long> changedTiles) {}

        /** Puts the server's real sections up until `deadline`; how many chunks. */
        default int applySections(AtlasNative.Context ctx, long deadline) {
            return 0;
        }

        /**
         * Builds what the window needs, nearest tiles first, until `deadline` (System.nanoTime); true when it put
         * anything up or left work for the next pass (the far field is not idle yet).
         */
        boolean cover(AtlasNative.Context ctx, int lod, int x0, int z0, int x1, int z1, long deadline);
    }

    /** The bundled Tamriel raster, unpacked once per mod version into the game directory. */
    static Path previewRaster() throws IOException {
        String version = AtlasFarField.class.getPackage().getImplementationVersion();
        return AtlasRasterCache.ensure(Minecraft.getInstance().gameDirectory.toPath(), version != null ? version : "dev");
    }

    /**
     * Seed-free source: the bundled Tamriel raster (exact macro shape, rivers, lakes, coasts, climates) with
     * atlas's stand-in for the seeded detail. Works the same on multiplayer clients, which do not know the seed.
     * In single player AtlasExactSource replaces it with the server's own tamriel_height.
     */
    static ColumnSource defaultSource() {
        return new ColumnSource() {
            @Override
            public boolean usesBlockPalette() {
                return false;
            }

            @Override
            public void prepare(AtlasNative.Context ctx) throws IOException {
                ctx.previewLoadRaster(previewRaster());
            }

            @Override
            public boolean cover(AtlasNative.Context ctx, int lod, int x0, int z0, int x1, int z1) {
                ctx.previewGenerate(lod, x0, z0, x1, z1);  // skips tiles it already has
                return false;
            }
        };
    }

    static final int LODS = 7, WINDOW = 2;
    /** Time the overlays (buildings, trees) may take per pass before the far field shows what it has. */
    static final long OVERLAY_BUDGET_MS = 120;
    /** Extra rest between passes (ms) while this machine hosts a busy server (adaptive quality's host-load policy). */
    static volatile int restMs;

    private final AtlasNative.Context ctx;
    private final ColumnSource source;
    /** Clipmap levels streamed (7, or 6 where memory is short: AtlasMemory; the context keeps the same count). */
    private final int lods;
    /** The worker's last error, for the frame to report (null while it works). */
    private volatile Throwable workerError;
    private final java.util.function.Supplier<Overlay> overlayFactory;
    private Overlay overlay;
    private boolean overlayFailed;
    private final Thread worker;
    private volatile boolean running = true;
    private volatile double camX, camY, camZ, camYaw, camHalf = 80;
    private volatile boolean haveCamera;
    /** True once the worker has staged a first update: atlas renders nothing before that (and says so). */
    private volatile boolean updated;
    /** The worker's last pass: still refining tiles or the camera just crossed into a new region (see idle()). */
    private volatile boolean streaming = true;
    private volatile String streamingWhy = "starting";  // what kept the last pass busy (harness settle diagnostics)
    private volatile long idleSinceMs;
    /**
     * The loading timeline (the harness's far-timeline verb): one JSON entry a pass since the last mark, what the pass
     * spent where (ms) and what it left, so a join or a teleport can be read as when the far field first showed,
     * what each pass cost and when it settled.
     */
    private final java.util.ArrayDeque<String> timeline = new java.util.ArrayDeque<>();
    private volatile long timelineT0 = System.nanoTime();

    AtlasFarField(AtlasNative.Context ctx, ColumnSource source) throws IOException {
        this(ctx, source, null);
    }

    /** overlays: made on the worker thread when first needed (it may load plans); null for none. */
    AtlasFarField(AtlasNative.Context ctx, ColumnSource source, java.util.function.Supplier<Overlay> overlays)
            throws IOException {
        this(ctx, source, overlays, LODS);
    }

    AtlasFarField(AtlasNative.Context ctx, ColumnSource source, java.util.function.Supplier<Overlay> overlays, int lods)
            throws IOException {
        this.ctx = ctx;
        this.source = source;
        this.overlayFactory = overlays;
        this.lods = Math.max(1, Math.min(LODS, lods));
        source.prepare(ctx);
        worker = new Thread(this::run, "nirncraft-atlas-farfield");
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
    }

    ColumnSource source() {
        return source;
    }

    boolean updated() {
        return updated;
    }

    /** A worker failure the native side reported as a lost device or exhausted memory (null otherwise). */
    Throwable workerError() {
        return workerError;
    }

    void follow(double x, double y, double z) {
        camX = x;
        camY = y;
        camZ = z;
        haveCamera = true;
    }

    /** Where the camera looks (Minecraft yaw, horizontal half-angle): idle() then only waits for what it can see. */
    void look(double yawDeg, double halfDeg) {
        camYaw = yawDeg;
        camHalf = halfDeg;
    }

    /** Nothing left to refine around the current camera and its tiles are built (a harness render-settle signal). */
    public boolean idle() {
        return haveCamera && updated && !streaming;
    }

    /** Why the far field is not idle: moved, tiles (visible ones pending), edits N, sections N, overlays; "" idle. */
    public String streamingWhy() {
        return streaming ? streamingWhy : "";
    }

    /** Starts the loading timeline again (the next pass is its first entry). */
    public void timelineMark() {
        synchronized (timeline) {
            timeline.clear();
        }
        timelineT0 = System.nanoTime();
    }

    /** The loading timeline since the last mark, a JSON array. */
    public String timelineJson() {
        synchronized (timeline) {
            return "[" + String.join(",", timeline) + "]";
        }
    }

    private static double ms(long from, long to) {
        return Math.round((to - from) / 1e4) / 100.0;
    }

    /** Milliseconds the far field has been idle (0 while streaming). */
    public long idleMs() {
        return streaming ? 0 : System.currentTimeMillis() - idleSinceMs;
    }

    private void run() {
        double lastX = Double.NaN, lastZ = Double.NaN;
        while (running) {
            try {
                if (!haveCamera) {
                    Thread.sleep(50);
                    continue;
                }
                double x = camX, y = camY, z = camZ;
                boolean pending = false, overlaid = false;
                long tPass = System.nanoTime();
                source.view(x, z, camYaw, camHalf);
                // arrived where nothing is resident (the finest tile under the camera is missing): an instant pass first,
                // every level from ready-made tiles and stand-ins, coarsest first, built and shown before any exact tile
                // (the first pass after a teleport took 3.6 s, all of it before anything new was drawn)
                int c0x = Math.floorDiv((int) Math.floor(x), AtlasNative.TILE), c0z = Math.floorDiv((int) Math.floor(z), AtlasNative.TILE);
                boolean quick = !ctx.hasTile(0, c0x, c0z);
                if (quick) {
                    for (int lod = lods - 1; lod >= 0; lod--) {
                        int span = 256 << lod;
                        int cx = Math.floorDiv((int) Math.floor(x), span), cz = Math.floorDiv((int) Math.floor(z), span);
                        source.coverQuick(ctx, lod, (cx - WINDOW) * span, (cz - WINDOW) * span, (cx + WINDOW + 1) * span,
                                (cz + WINDOW + 1) * span);
                    }
                    ctx.update(x, y, z, 0);
                    updated = true;
                    long tq = System.nanoTime();
                    synchronized (timeline) {
                        timeline.addLast(String.format(java.util.Locale.ROOT, "{\"t\":%.1f,\"quick\":%.2f,\"pass\":%.2f,\"streaming\":\"quick\"}",
                                (tq - timelineT0) / 1e6, ms(tPass, tq), ms(tPass, tq)));
                    }
                }
                source.beginPass();
                for (int lod = 0; lod < lods; lod++) {
                    int span = 256 << lod;
                    int cx = Math.floorDiv((int) Math.floor(x), span), cz = Math.floorDiv((int) Math.floor(z), span);
                    pending |= source.cover(ctx, lod, (cx - WINDOW) * span, (cz - WINDOW) * span,
                            (cx + WINDOW + 1) * span, (cz + WINDOW + 1) * span);
                }
                long tCover = System.nanoTime();
                // the server's changes (players building, explosions, water, lights): put the tiles they touch up again
                java.util.List<Long> edited = AtlasEditsClient.takeDirty();
                int sectionsApplied = 0;
                boolean overlayBusy = false;
                if (!edited.isEmpty()) {
                    source.applyEdits(ctx, edited);
                    Overlay ovE = overlay();
                    if (ovE != null) ovE.invalidate(edited);
                }
                for (long ck : AtlasEditsClient.takeLightsDirty())  // a re-light: lights only, no tile, no rebuild
                    AtlasLights.upload(ctx, AtlasEditsClient.lightGroup(ck), AtlasEditsClient.lights(ck));
                long tEdits = System.nanoTime();
                // the server's real sections (the finest levels' real blocks), within a share of the pass
                Overlay ovS = overlay();
                if (ovS != null) {
                    sectionsApplied = ovS.applySections(ctx, System.nanoTime() + 40_000_000L);
                    overlaid |= sectionsApplied > 0;
                }
                // the ground of the new place on screen now: the overlays (buildings, trees) can take a while, and
                // the old window stayed on screen until they finished (a teleport showed stale far ground)
                long tSections = System.nanoTime();
                ctx.update(x, y, z, 0);
                long tUpdate1 = System.nanoTime();
                Overlay ov = overlay();
                if (ov != null) {
                    long deadline = System.nanoTime() + OVERLAY_BUDGET_MS * 1_000_000L;
                    for (int lod = 0; lod < lods; lod++) {
                        int span = 256 << lod;
                        int cx = Math.floorDiv((int) Math.floor(x), span), cz = Math.floorDiv((int) Math.floor(z), span);
                        boolean b = ov.cover(ctx, lod, (cx - WINDOW) * span, (cz - WINDOW) * span, (cx + WINDOW + 1) * span,
                                (cz + WINDOW + 1) * span, deadline);
                        overlayBusy |= b;
                        overlaid |= b;
                    }
                }
                long tOverlays = System.nanoTime();
                ctx.update(x, y, z, 0);  // builds what changed and stages it for the next frame
                long tUpdate2 = System.nanoTime();
                boolean first = !updated;
                updated = true;
                // idle while the camera stays inside the same finest-LOD region and nothing is left to refine
                boolean moved = Double.isNaN(lastX) || Math.abs(x - lastX) > 32 || Math.abs(z - lastZ) > 32;
                lastX = x;
                lastZ = z;
                boolean was = streaming;
                // only what the camera sees counts, and buildings / trees still going up (captures wait for them)
                boolean tilesPending = source.visiblePending();
                streaming = moved || tilesPending || overlaid;
                if (streaming) {
                    StringBuilder why = new StringBuilder();
                    if (moved) why.append("moved ");
                    if (tilesPending) why.append("tiles ");
                    if (!edited.isEmpty()) why.append("edits ").append(edited.size()).append(' ');
                    if (sectionsApplied > 0) why.append("sections ").append(sectionsApplied).append(' ');
                    if (overlayBusy) why.append("overlays ");
                    streamingWhy = why.toString().trim();
                }
                if (was && !streaming) idleSinceMs = System.currentTimeMillis();
                AtlasNative.Stats st = ctx.stats();
                String entry = String.format(java.util.Locale.ROOT,
                        "{\"t\":%.1f,\"pass\":%.2f,\"cover\":%.2f,\"edits\":%.2f,\"editedTiles\":%d,\"sections\":%.2f,"
                                + "\"sectionsApplied\":%d,\"update1\":%.2f,\"overlays\":%.2f,\"update2\":%.2f,\"regions\":%d,"
                                + "\"pending\":%b,\"first\":%b,\"streaming\":\"%s\"}",
                        (tUpdate2 - timelineT0) / 1e6, ms(tPass, tUpdate2), ms(tPass, tCover), ms(tCover, tEdits), edited.size(),
                        ms(tEdits, tSections), sectionsApplied, ms(tSections, tUpdate1), ms(tUpdate1, tOverlays),
                        ms(tOverlays, tUpdate2), st.regions(), pending, first, streaming ? streamingWhy : "");
                synchronized (timeline) {
                    timeline.addLast(entry);
                    while (timeline.size() > 4000) timeline.removeFirst();
                }
                Thread.sleep((moved || pending || overlaid ? 5 : 100) + restMs);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                if (t instanceof AtlasNative.AtlasException a && (a.deviceLost() || a.outOfMemory())) {
                    workerError = t;  // the render thread turns the far field off (and retries) with one line
                    return;
                }
                com.nirncraft.NirnCraft.LOGGER.warn("atlas far field worker: {}", t.toString());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private Overlay overlay() {
        if (overlay == null && overlayFactory != null && !overlayFailed) {
            try {
                overlay = overlayFactory.get();
            } catch (RuntimeException e) {
                overlayFailed = true;  // the far field goes on without them
                com.nirncraft.NirnCraft.LOGGER.warn("atlas far-field settlements off: {}", e.toString());
            }
        }
        return overlay;
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
        try {
            worker.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        source.close();
    }
}
