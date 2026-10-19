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

        default void close() {}
    }

    /** What goes on top of the ground tiles once they are resident (settlements). */
    interface Overlay {
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
    private volatile long idleSinceMs;

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
                source.view(x, z, camYaw, camHalf);
                source.beginPass();
                for (int lod = 0; lod < lods; lod++) {
                    int span = 256 << lod;
                    int cx = Math.floorDiv((int) Math.floor(x), span), cz = Math.floorDiv((int) Math.floor(z), span);
                    pending |= source.cover(ctx, lod, (cx - WINDOW) * span, (cz - WINDOW) * span,
                            (cx + WINDOW + 1) * span, (cz + WINDOW + 1) * span);
                }
                // the ground of the new place on screen now: the overlays (buildings, trees) can take a while, and
                // the old window stayed on screen until they finished (a teleport showed stale far ground)
                ctx.update(x, y, z, 0);
                Overlay ov = overlay();
                if (ov != null) {
                    long deadline = System.nanoTime() + OVERLAY_BUDGET_MS * 1_000_000L;
                    for (int lod = 0; lod < lods; lod++) {
                        int span = 256 << lod;
                        int cx = Math.floorDiv((int) Math.floor(x), span), cz = Math.floorDiv((int) Math.floor(z), span);
                        overlaid |= ov.cover(ctx, lod, (cx - WINDOW) * span, (cz - WINDOW) * span, (cx + WINDOW + 1) * span,
                                (cz + WINDOW + 1) * span, deadline);
                    }
                }
                ctx.update(x, y, z, 0);  // builds what changed and stages it for the next frame
                updated = true;
                // idle while the camera stays inside the same finest-LOD region and nothing is left to refine
                boolean moved = Double.isNaN(lastX) || Math.abs(x - lastX) > 32 || Math.abs(z - lastZ) > 32;
                lastX = x;
                lastZ = z;
                boolean was = streaming;
                // only what the camera sees counts, and buildings / trees still going up (captures wait for them)
                streaming = moved || source.visiblePending() || overlaid;
                if (was && !streaming) idleSinceMs = System.currentTimeMillis();
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
