package com.skycraft.aether;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Streams far-field column tiles around the camera on its own thread and rebuilds aether's clipmap there, so the
 * render thread only ever flips to finished GPU copies (aether stages each scene revision in aether_update).
 */
final class AetherFarField implements AutoCloseable {
    /** Where far-field columns come from. */
    interface ColumnSource {
        /** True when tiles carry block palette ids (AetherPalette.blocks), false for aether's preview palette. */
        boolean usesBlockPalette();

        void prepare(AetherNative.Context ctx) throws IOException;

        /**
         * Makes sure every tile of LOD `lod` covering [x0,x1) x [z0,z1) is uploaded. True when it left work for a
         * later call (tiles still to refine), so the worker comes back soon instead of idling.
         */
        boolean cover(AetherNative.Context ctx, int lod, int x0, int z0, int x1, int z1);

        default void close() {}
    }

    /** What goes on top of the ground tiles once they are resident (settlements). */
    interface Overlay {
        void cover(AetherNative.Context ctx, int lod, int x0, int z0, int x1, int z1);
    }

    /** The bundled Tamriel raster, unpacked once per mod version into the game directory. */
    static Path previewRaster() throws IOException {
        String version = AetherFarField.class.getPackage().getImplementationVersion();
        return AetherRasterCache.ensure(Minecraft.getInstance().gameDirectory.toPath(), version != null ? version : "dev");
    }

    /**
     * Seed-free source: the bundled Tamriel raster (exact macro shape, rivers, lakes, coasts, climates) with
     * aether's stand-in for the seeded detail. Works the same on multiplayer clients, which do not know the seed.
     * In single player AetherExactSource replaces it with the server's own tamriel_height.
     */
    static ColumnSource defaultSource() {
        return new ColumnSource() {
            @Override
            public boolean usesBlockPalette() {
                return false;
            }

            @Override
            public void prepare(AetherNative.Context ctx) throws IOException {
                ctx.previewLoadRaster(previewRaster());
            }

            @Override
            public boolean cover(AetherNative.Context ctx, int lod, int x0, int z0, int x1, int z1) {
                ctx.previewGenerate(lod, x0, z0, x1, z1);  // skips tiles it already has
                return false;
            }
        };
    }

    static final int LODS = 7, WINDOW = 2;

    private final AetherNative.Context ctx;
    private final ColumnSource source;
    private final java.util.function.Supplier<Overlay> overlayFactory;
    private Overlay overlay;
    private boolean overlayFailed;
    private final Thread worker;
    private volatile boolean running = true;
    private volatile double camX, camY, camZ;
    private volatile boolean haveCamera;
    /** True once the worker has staged a first update: aether renders nothing before that (and says so). */
    private volatile boolean updated;

    AetherFarField(AetherNative.Context ctx, ColumnSource source) throws IOException {
        this(ctx, source, null);
    }

    /** overlays: made on the worker thread when first needed (it may load plans); null for none. */
    AetherFarField(AetherNative.Context ctx, ColumnSource source, java.util.function.Supplier<Overlay> overlays)
            throws IOException {
        this.ctx = ctx;
        this.source = source;
        this.overlayFactory = overlays;
        source.prepare(ctx);
        worker = new Thread(this::run, "skycraft-aether-farfield");
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

    void follow(double x, double y, double z) {
        camX = x;
        camY = y;
        camZ = z;
        haveCamera = true;
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
                boolean pending = false;
                for (int lod = 0; lod < LODS; lod++) {
                    int span = 256 << lod;
                    int cx = Math.floorDiv((int) Math.floor(x), span), cz = Math.floorDiv((int) Math.floor(z), span);
                    pending |= source.cover(ctx, lod, (cx - WINDOW) * span, (cz - WINDOW) * span,
                            (cx + WINDOW + 1) * span, (cz + WINDOW + 1) * span);
                    Overlay ov = overlay();
                    if (ov != null) ov.cover(ctx, lod, (cx - WINDOW) * span, (cz - WINDOW) * span, (cx + WINDOW + 1) * span,
                            (cz + WINDOW + 1) * span);
                }
                ctx.update(x, y, z, 0);  // builds what changed and stages it for the next frame
                updated = true;
                // idle while the camera stays inside the same finest-LOD region and nothing is left to refine
                boolean moved = Double.isNaN(lastX) || Math.abs(x - lastX) > 32 || Math.abs(z - lastZ) > 32;
                lastX = x;
                lastZ = z;
                Thread.sleep(moved || pending ? 5 : 100);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                com.skycraft.Skycraft.LOGGER.warn("aether far field worker: {}", t.toString());
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
                com.skycraft.Skycraft.LOGGER.warn("aether far-field settlements off: {}", e.toString());
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
